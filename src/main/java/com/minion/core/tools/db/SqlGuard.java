package com.minion.core.tools.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 只读 SQL 校验（纯函数，第一层防护）。
 *
 * 三大驱动的 executeQuery 都是「先执行再判断有没有 ResultSet」，单靠它挡不住写操作，
 * 故在执行前先用白名单首词 + 多语句检测 + 危险短语检测拦一道。
 * check 返回 null 表示放行，否则返回可直接展示给模型的中文拒绝原因。
 *
 * 流程：先做一次词法归一化扫描（一次遍历产出两份文本），再跑四项检查。归一化规则：
 *  - 行注释与块注释替换成「一个空格」（不是删空：被注释切开的关键字压平后仍能拼成短语命中）。
 *    双连字符要后接空白/行尾才算三库通用注释，# 只 MySQL 认（见下）。块注释按层内嵌套配对；
 *    注释或引号未闭合、可执行注释嵌套超过 MAX_EXEC_DEPTH 层 → 判不了就拒，不猜。
 *  - MySQL/MariaDB 可执行注释（斜杠星紧跟感叹号）里的内容会被驱动真执行，故不当注释删：
 *    去掉感叹号与版本号后，内容递归归一化并内联进代码文本，原文也留在分号判定用的文本里。
 *  - 字符串字面量与引号标识符（单引号、双引号、反引号）一律掩码成占位空串：修「字面量里的分号被
 *    当多语句、字面量里的 for update 被当锁语句」。只认「引号加倍」转义、不认反斜杠转义——
 *    PG 标准模式下反斜杠不是转义符，认了反而会把真实分号藏进假字符串里；MySQL 侧最坏是误拒。
 *
 * 两份文本各有分工，这是防绕过的关键：
 *  - code（注释剥空、字面量掩码、可执行注释内联）用于首关键词与危险短语判定；
 *  - loose（字面量掩码，通用注释与可执行注释之外的原文全留着）用于分号判定：
 *    # 在 PG 里是运算符（如 jsonb 的 #>），当成 MySQL 注释剥掉就等于替 PG 藏住后面的分号与写语句；
 *    不接空白的双连字符同理（MySQL/PG 当两次取负）。故 SELECT 1; -- 说明 放行、
 *    SELECT x #> '{a}'; DROP TABLE t 拒绝。
 *
 * 已知取舍：
 *  - 字面量已掩码，字符串里出现 FOR UPDATE 不再误拒；# 注释、不接空白的双连字符里出现分号仍会误拒
 *    （判不出方言，安全优先）。
 *  - PG 美元引用（两个美元符号包裹）、Oracle q 引号不识别，其内部分号按字面量外处理（误拒方向）。
 *  - 改写型 CTE（WITH x AS (DELETE FROM t RETURNING *)）与 Oracle EXPLAIN PLAN FOR 不拦，
 *    由第二层 setReadOnly + executeQuery 和数据源侧只读账号兜底；最终兜底应是只读账号。
 */
public final class SqlGuard {

    /** 拒绝原因统一前缀（DbTool 原样透传给模型） */
    private static final String REJECT = "只读工具拒绝执行：";

    /** 放行的首关键词（Oracle 上 DESC/DESCRIBE 是 SQL*Plus 命令、JDBC 会报错，保留无害） */
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN"));

    private static final String ALLOWED_TEXT = "SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN";

    /** 出现即拒的子串（整词边界匹配、忽略大小写），文案与 FORBIDDEN_PATTERNS 一一对应 */
    private static final String[] FORBIDDEN = {
            "INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "LOCK IN SHARE MODE"};

    /**
     * 可执行注释片段不许以这些写动词开头。片段拼在语句中间多半是语法错误，出现写动词就是
     * 「借注释注入语句」的强信号；白名单首词（SELECT/SHOW 等）不在其中，不会误伤合法提示。
     */
    private static final Set<String> WRITE_HEADS = new HashSet<String>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "REPLACE",
            "MERGE", "UPSERT", "GRANT", "REVOKE", "SET", "CALL", "RENAME", "LOCK", "UNLOCK",
            "LOAD", "HANDLER", "KILL", "COMMIT", "ROLLBACK", "SAVEPOINT", "PREPARE", "EXECUTE",
            "DEALLOCATE", "SHUTDOWN", "START", "BEGIN", "TABLE", "COPY", "VACUUM", "RESET"));

    /** 字面量掩码：两侧留空格，避免掩码把相邻关键字粘成一个词 */
    private static final String LITERAL_MASK = " '' ";

    /** 可执行注释递归的最大层数，超出直接拒（防超长嵌套把真实内容藏进「注释」里） */
    private static final int MAX_EXEC_DEPTH = 4;

    /** 任意连续空白：危险短语匹配前压成单个空格 */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 整词匹配：前后不能是字母/数字/下划线（防 information 里的 for、for_update_at 列名误伤） */
    private static final Pattern[] FORBIDDEN_PATTERNS = new Pattern[FORBIDDEN.length];

    static {
        for (int i = 0; i < FORBIDDEN.length; i++) {
            FORBIDDEN_PATTERNS[i] = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(FORBIDDEN[i]) + "(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE);
        }
    }

    private SqlGuard() { }

    /**
     * @param sql 模型提交的语句
     * @return null 表示放行；非 null 为拒绝原因（可直接作为 ToolResult 错误文案）
     */
    public static String check(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "SQL 不能为空";
        Scan scan;
        try {
            scan = scan(sql, 0);
        } catch (ParseFail e) {
            return REJECT + e.getMessage();
        }

        String code = scan.code.toString().trim();
        if (code.isEmpty()) return "SQL 不能为空";

        String head = firstKeyword(code);
        if (head.isEmpty()) return REJECT + "无法识别语句首关键词";
        if (!ALLOWED.contains(head)) {
            return REJECT + "语句以 " + head + " 开头（仅允许 " + ALLOWED_TEXT + "）";
        }

        // 分号判 loose：code 里被剥掉的注释内容不能替 PG 藏住分号
        String why = checkSingleStatement(scan.loose.toString());
        if (why != null) return why;

        // 危险短语在空白压平后的 code 上匹配：关键字间允许任意空白/换行/注释，否则漏判
        String flat = SPACES.matcher(code).replaceAll(" ");
        for (int i = 0; i < FORBIDDEN_PATTERNS.length; i++) {
            if (FORBIDDEN_PATTERNS[i].matcher(flat).find()) {
                return REJECT + "语句含 " + FORBIDDEN[i];
            }
        }
        return checkExecComments(scan);
    }

    /**
     * 多语句检测：最多允许末尾一个终止分号（"SELECT 1;;" 这种尾部空语句也算多语句）。
     * 字面量已掩码、通用注释已剥除，故 "SELECT 'a;b'" 与「说明性注释里带分号」都放行；
     * 而 "SELECT ';' ; DROP TABLE t" 与 PG 的 "#> 运算数; DROP TABLE t" 拒绝。
     */
    private static String checkSingleStatement(String loose) {
        String s = loose.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        if (s.indexOf(';') >= 0) return REJECT + "不允许多条语句";
        return null;
    }

    /** 可执行注释内容会被真执行：片段首词是写动词即拒；嵌套片段同样递归检查 */
    private static String checkExecComments(Scan scan) {
        for (int i = 0; i < scan.execs.size(); i++) {
            Scan exec = scan.execs.get(i);
            String head = firstKeyword(exec.code.toString().trim());
            if (!head.isEmpty() && WRITE_HEADS.contains(head)) {
                return REJECT + "可执行注释内含 " + head + " 语句";
            }
            String why = checkExecComments(exec);
            if (why != null) return why;
        }
        return null;
    }

    /**
     * 词法归一化：一次遍历同时产出 code（首词与短语判定用）与 loose（分号判定用）。
     * 两者的差别只有方言相关的部分：# 行注释、不接空白的双连字符在 loose 里按代码留着原文。
     */
    private static Scan scan(String sql, int depth) throws ParseFail {
        Scan out = new Scan();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = lineCommentEnd(sql, i);
                // 双连字符后紧跟空白/行尾才是通用注释，否则 MySQL/PG 按两次取负运算符解析
                boolean universal = i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2));
                out.code.append(' ');
                if (!universal) out.loose.append(sql, i, end);
                i = end;
            } else if (c == '#') {
                // # 只 MySQL 认，PG 里是运算符：分号判定不能把它当注释剥掉
                int end = lineCommentEnd(sql, i);
                out.code.append(' ');
                out.loose.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = blockCommentEnd(sql, i);
                if (end < 0) throw new ParseFail("注释未闭合，无法安全解析");
                String inner = sql.substring(i + 2, end - 2);
                if (isExecutableComment(inner)) {
                    if (depth >= MAX_EXEC_DEPTH) throw new ParseFail("可执行注释嵌套过深，无法安全解析");
                    Scan exec = scan(stripCommentMarker(inner), depth + 1);
                    out.code.append(' ').append(exec.code).append(' ');
                    out.loose.append(sql, i, end);
                    out.execs.add(exec);
                } else {
                    // 三库都不执行普通块注释：两份文本都只留一个空格当分隔
                    out.code.append(' ');
                }
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = quotedEnd(sql, i, c);
                if (end < 0) throw new ParseFail("引号未闭合，无法安全解析");
                out.code.append(LITERAL_MASK);
                out.loose.append(LITERAL_MASK);
                i = end;
            } else {
                out.code.append(c);
                out.loose.append(c);
                i++;
            }
        }
        return out;
    }

    /** 行注释结束位置：换行符本身（留给主循环当空白），没有换行则为串尾 */
    private static int lineCommentEnd(String sql, int from) {
        int k = sql.indexOf('\n', from);
        return k < 0 ? sql.length() : k;
    }

    /** 块注释结束位置（收尾星斜杠之后），按层内嵌套计数；未闭合返回 -1 */
    private static int blockCommentEnd(String sql, int from) {
        int i = from + 2, n = sql.length(), level = 1;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                level--;
                i += 2;
                if (level == 0) return i;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                level++;
                i += 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * 引号包裹内容（字符串或标识符）的结束位置（收尾引号之后），未闭合返回 -1。
     * 只按「引号加倍」解转义：MySQL 里用反斜杠转义的引号会被判成字符串提前结束，
     * 后果是把真实分号当分号（误拒方向），反过来才不会替任何数据库藏住代码。
     */
    private static int quotedEnd(String sql, int from, char quote) {
        int i = from + 1, n = sql.length();
        while (i < n) {
            if (sql.charAt(i) != quote) { i++; continue; }
            if (i + 1 < n && sql.charAt(i + 1) == quote) { i += 2; continue; }
            return i + 1;
        }
        return -1;
    }

    /** 是否 MySQL/MariaDB 可执行注释：感叹号紧跟斜杠星（前面最多两个字母，如 M!） */
    private static boolean isExecutableComment(String inner) {
        return markerEnd(inner) >= 0;
    }

    /** 去掉可执行注释开头的标记与版本号（如 M!100000、!50000），返回待展开的 SQL 片段 */
    private static String stripCommentMarker(String inner) {
        int k = markerEnd(inner);
        if (k < 0) return inner;
        while (k < inner.length() && Character.isDigit(inner.charAt(k))) k++;
        return inner.substring(k);
    }

    /** 定位感叹号之后的位置；不是可执行注释返回 -1 */
    private static int markerEnd(String inner) {
        int k = 0;
        while (k < inner.length() && k < 3 && Character.isLetter(inner.charAt(k))) k++;
        return k < inner.length() && inner.charAt(k) == '!' ? k + 1 : -1;
    }

    /** 首关键词：取开头的字母/数字/下划线连续段并转大写（引号、括号、空白等一律视为分隔） */
    static String firstKeyword(String code) {
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            boolean word = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!word) break;
            i++;
        }
        return code.substring(0, i).toUpperCase();
    }

    /** 一次归一化的产物：两份文本 + 内联出来的可执行注释片段 */
    private static final class Scan {
        final StringBuilder code = new StringBuilder();
        final StringBuilder loose = new StringBuilder();
        final List<Scan> execs = new ArrayList<Scan>();
    }

    /** 词法解析失败：无法安全判定的语句一律拒绝 */
    private static final class ParseFail extends Exception {
        private static final long serialVersionUID = 1L;
        ParseFail(String message) { super(message); }
    }
}
