package com.minion.core.tools.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 校验（纯函数，第一层防护）。
 *
 * 三大驱动的 executeQuery 都是「先执行再判断有没有 ResultSet」，单靠它挡不住写操作，
 * 故在执行前先用白名单首词 + 多语句检测 + 危险短语检测拦一道。
 * check 返回 null 表示放行，否则返回可直接展示给模型的中文拒绝原因。
 *
 * ============================ 一条不变式 ============================
 * 三种方言对「什么算注释」并不一致（# 只 MySQL 认；-- 只有后接空白/行尾时三库都认；
 * 斜杠星紧跟感叹号的可执行注释只有 MySQL/MariaDB 会真执行，PG/Oracle 只当它是普通块注释），
 * 所以归一化产出**三份文本、三种读法**：
 *   - stripped：所有注释（含方言存疑的）一律剥成一个空格，但可执行注释的**内容内联进来**
 *     —— 可执行注释「确实生效」那批库（MySQL/MariaDB）的读法；
 *   - conservative：只有「三库都当注释」的片段才剥掉，# 与不接空白的 -- 按代码原样保留
 *     —— 方言存疑注释的读法；
 *   - plain（第三视图）：连可执行注释也当普通注释**整段剥掉、内容绝不内联**
 *     —— 可执行注释「其实不生效」那批库（PG/Oracle，以及 MySQL 见到斜杠星 + 字母前缀的
 *     MariaDB 形态时）的读法，也就是「任何一库都至少会执行」的那份最小文本。
 * 不变式：**凡某库可能真执行的文本，必须被每一项检查看到**。因此首关键词与内容级检查
 * （多语句、危险短语、EXPLAIN 目标）都得在对应的几份读法上各跑一遍，任一命中即拒 ——
 * 单项检查不得只认一份文本。
 * round 1 的洞正出在这里：分号判定看 conservative（留原文），短语判定只看 stripped，
 * 于是 "SELECT 1--1 INTO OUTFILE '/tmp/x'"（MySQL 里 --1 是取负、不是注释）被漏判成只读。
 * round 3 的洞出在首词只认 stripped 一份 —— 而 stripped 会把可执行注释的内容内联：
 *   「感叹号 + SELECT + 星斜杠 + DROP TABLE t」这句的首词是**从注释内容里借来的 SELECT**，
 *   但它在 PG/Oracle 里整段只是注释，真被执行的首词是 DROP，白名单首词反倒替写语句打了掩护。
 *   同一处内联还会把短语撑开：「FOR + 可执行注释 + UPDATE」在 PG 里就是 FOR UPDATE（锁行）。
 * 所以首词现在要在 stripped 与 plain 上双双过白名单（plain 整份为空 = 该读法下只剩注释、
 * 没有任何语句可执行，此时跳过，不给「整句都是版本门控注释」这种合法写法添堵）；
 * 内容级检查也补跑 plain，并且**递归跑在每个可执行注释片段自己的三份视图上**
 * （片段的 plain 视图不在根视图里，「外层被真执行、内层只是注释」这种混合读法只能这样看到）。
 * plain 只会比 stripped 少文本，不会凭空多出分号，多出来的命中全是「跨注释拼回原形」的短语。
 * 首词仍不看 conservative：以 # 或不接空白的 -- 起手的语句，在非 MySQL 库里必然是语法错误
 * （运算符/取负没有左操作数），而那份文本里行首注释是普通词，用它取首词会打死 "# 注释\nSELECT 1"。
 * ===================================================================
 *
 * 归一化细则：
 *  - 注释替换成「一个空格」而不是删空：被注释切开的关键字压平后仍能拼成短语命中
 *    （FOR + 块注释 + UPDATE → "FOR UPDATE"）。块注释按层内嵌套配对。
 *  - 可执行注释（斜杠星紧跟可选 ASCII 字母/数字前缀再接感叹号）里的内容会被 MySQL 真执行，
 *    故在 stripped/conservative 两份视图里不当注释删：去掉前缀与版本号后递归归一化、内联进
 *    对应视图，片段自身还要过一遍「首词不得是写动词」+「三份视图的内容级检查」（见 checkExecs）。
 *    但 plain 视图里它跟普通注释一样整段剥掉 —— 并非每个库都会执行它，见上面的不变式。
 *    前缀不设长度上限 —— round 1 只认三个字母，「斜杠星 + MARIADB + 感叹号」这类形态因此
 *    整类绕过（评审 round 2 建议 1）。
 *  - 字符串字面量与引号标识符（单引号、双引号、反引号）一律掩码成占位空串：修「字面量里的
 *    分号被当多语句、字面量里的 for update 被当锁语句」。只认「引号加倍」转义、不认反斜杠转义——
 *    PG 标准模式下反斜杠不是转义符，认了反而会把真实分号藏进假字符串里；MySQL 侧最坏是误拒。
 *  - 顶层的字面量/块注释不闭合 → 判不了就拒，不猜。但**行内存疑区**（# 与不接空白的 --
 *    之后到行尾）里的未闭合引号/块注释不算未闭合：那一行在任何库都执行不过去，
 *    按普通字符原样留着即可（宁误拒不漏判，也不给合法语句添堵）。
 *    行内存疑区一律**以行尾为界**，绝不让「注释里一个孤零零的单引号」把后面几行的真实分号
 *    当成字符串吞掉（`SELECT 1--don't` 换行 `; DROP TABLE t` 必须照旧拒）。
 *
 * 已知取舍（全是误拒方向）：
 *  - 方言存疑注释里出现危险短语会被拒：SELECT 1 # 说明 for update 会锁行。判不出方言，
 *    与「这类注释里出现分号也拒」保持同一口径。
 *  - 以可执行注释起手、剥掉它之后首词就不在白名单的写法会被拒（如「感叹号 + SELECT + 星斜杠 + 1」
 *    这种只在 MySQL 才成立的语句）：判不出目标方言，就不能让首词从注释内容里借。
 *  - PG 美元引用（$$…$$）、Oracle q 引号不识别，其内部分号按字面量外处理。
 *  - 改写型 CTE（WITH x AS (DELETE FROM t RETURNING *)）不拦；PG 的 "SELECT … INTO t"（建表写入）
 *    与 MySQL 无副作用的 "SELECT … INTO @var" 无法在不引入误拒的前提下区分，也不进危险短语表；
 *    Oracle EXPLAIN PLAN FOR 后接 SELECT 仍放行（它写 plan_table）。这三条连同 LOAD_FILE() 一类
 *    外读面，由第二层 setReadOnly + executeQuery 和数据源侧只读账号兜底；最终兜底应是只读账号。
 *
 * 残余缺口（同上由第二层兜底；本轮未动，登记备查）：
 *  - **块注释配对只按 PG 的「可嵌套」实现**：Oracle（以及按文档不认嵌套的 MySQL）在第一个
 *    星斜杠处就结束了注释，其后文本我们三份视图都看不见，例
 *    「SELECT 1 斜杠星a斜杠星感叹号星斜杠 INTO OUTFILE 引号路径 斜杠星b星斜杠 减号减号 c 星斜杠」
 *    在三份视图里都只是「一句 SELECT 加一堆注释」。堵法是给 plain 再换一种配对（按首个星斜杠收尾），
 *    属另一条方言轴（注释配对而非可执行注释），不改本轮成果，留待下一轮评审定夺。
 */
public final class SqlGuard {

    /** 拒绝原因统一前缀（DbTool 原样透传给模型） */
    private static final String REJECT = "只读工具拒绝执行：";

    /** 放行的首关键词（Oracle 上 DESC/DESCRIBE 是 SQL*Plus 命令、JDBC 会报错，保留无害） */
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN"));

    private static final String ALLOWED_TEXT = "SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN";

    /**
     * 首词在 plain 读法（把可执行注释当普通注释）上被拒时加在原因里的说明：
     * 模型看到的「首词」跟它写的不一样，不点破会被当成误拒。
     */
    private static final String PLAIN_MARK = "把可执行注释当普通注释看时，";

    /** EXPLAIN 系首词（MySQL 的 EXPLAIN DML 与 PG 的 EXPLAIN ANALYZE 会真执行目标语句） */
    private static final String EXPLAIN = "EXPLAIN";

    /** 出现即拒的短语（整词边界匹配、忽略大小写），文案与 FORBIDDEN_PATTERNS 一一对应 */
    private static final String[] FORBIDDEN = {
            "INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "LOCK IN SHARE MODE"};

    /**
     * 写动词。两份用途：可执行注释片段不许以它们开头；EXPLAIN 之后不许出现它们。
     * 整词匹配（下划线算词字符），列名 update_time 之类不受影响。
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

    /** 归一化模式：剥掉一切注释、可执行注释内容内联（MySQL/MariaDB 读法，取首词 + 拼回短语用） */
    private static final int MODE_STRIPPED = 0;

    /** 归一化模式：只剥三库通用注释，方言存疑注释按代码保留（内容级检查用） */
    private static final int MODE_CONSERVATIVE = 1;

    /** 行内存疑区（存疑注释之后到行尾），注释标记按普通字符、未闭合构造不报错 */
    private static final int MODE_IN_DOUBT = 2;

    /**
     * 归一化模式：第三视图 plain —— 连可执行注释也当普通注释整段剥掉（内容绝不内联）。
     * 它是「可执行注释不生效」那批库（PG/Oracle）的读法，也是任何库都至少会执行的最小文本，
     * 专治「首词从可执行注释内容里借」与「内联内容把危险短语撑开」两类漏判。
     */
    private static final int MODE_PLAIN = 3;

    /** EXPLAIN 的解释选项词：判「EXPLAIN 后面是不是写语句」时先跳过它们 */
    private static final Set<String> EXPLAIN_OPTIONS = new HashSet<String>(Arrays.asList(
            "ANALYZE", "ANALYSE", "VERBOSE", "COSTS", "SETTINGS", "GENERIC_PLAN", "BUFFERS",
            "WAL", "TIMING", "SUMMARY", "MEMORY", "FORMAT", "EXTENDED", "PARTITIONS", "PLAN",
            "FOR", "JSON", "TEXT", "XML", "YAML", "TRUE", "FALSE", "ON", "OFF", "IS"));

    /** 任意连续空白：危险短语匹配前压成单个空格 */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 整词切分：非「字母/数字/下划线」即为分隔（引号已被掩码成占位空串） */
    private static final Pattern WORDS = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * 整词匹配：前后不能是字母/数字/下划线（防 information 里的 for、for_update_at 列名误伤）。
     * 每份读法的视图各跑一次，见类注释的不变式说明。
     */
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
        Scan root;
        try {
            root = build(sql, 0, sql.length(), 0);
        } catch (ParseFail e) {
            return REJECT + e.getMessage();
        }
        String why = checkHead(root);
        if (why == null) why = checkViews(root);
        if (why == null) why = checkExecs(root);
        return why;
    }

    /**
     * 首关键词：必须在 stripped（可执行注释内容内联）与 plain（可执行注释当普通注释）
     * 两份读法上双双落进白名单 —— 首词不许从注释内容里「借」，详见类注释的不变式说明。
     * plain 整份为空 = 该读法下只剩注释、没有任何语句可执行，跳过这一项（不给合法写法添堵）。
     */
    private static String checkHead(Scan scan) {
        String stripped = scan.stripped.toString().trim();
        if (stripped.isEmpty()) return "SQL 不能为空";
        String why = headMustBeAllowed(stripped, "");
        if (why == null) {
            String plain = scan.plain.toString().trim();
            if (!plain.isEmpty()) why = headMustBeAllowed(plain, PLAIN_MARK);
        }
        return why;
    }

    private static String headMustBeAllowed(String text, String mark) {
        String head = firstKeyword(text);
        if (head.isEmpty()) return REJECT + mark + "无法识别语句首关键词";
        if (!ALLOWED.contains(head)) {
            return REJECT + mark + "语句以 " + head + " 开头（仅允许 " + ALLOWED_TEXT + "）";
        }
        return null;
    }

    /**
     * 内容级检查（多语句、危险短语、EXPLAIN 目标）：这份文本每份读法各跑一遍，任一命中即拒。
     * 可执行注释片段自己的三份视图同样要跑（片段的 plain 视图不出现在根视图里）。
     */
    private static String checkViews(Scan scan) {
        String[] views = {scan.stripped.toString(), scan.conservative.toString(), scan.plain.toString()};
        for (int v = 0; v < views.length; v++) {
            String why = checkSingleStatement(views[v]);
            if (why != null) return why;
        }
        for (int v = 0; v < views.length; v++) {
            String why = checkPhrases(views[v]);
            if (why != null) return why;
        }
        for (int v = 0; v < views.length; v++) {
            // 只看「这份读法下首词真是 EXPLAIN」的视图：EXPLAIN 会连带执行目标语句
            if (!EXPLAIN.equals(firstKeyword(views[v].trim()))) continue;
            String why = checkExplainTarget(views[v]);
            if (why != null) return why;
        }
        return null;
    }

    /**
     * 多语句检测：最多允许末尾一个终止分号（"SELECT 1;;" 这种尾部空语句也算多语句）。
     * 字面量已掩码，故 "SELECT 'a;b'" 放行；而 "SELECT ';' ; DROP TABLE t"、
     * PG 的 "#> '{a}'; DROP TABLE t"（# 只 MySQL 认）与 MySQL 的 "--1; DROP TABLE t" 都拒绝。
     */
    private static String checkSingleStatement(String text) {
        String s = text.trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        if (s.indexOf(';') >= 0) return REJECT + "不允许多条语句";
        return null;
    }

    /** 危险短语：关键字间允许任意空白/换行/注释，否则漏判（匹配前把连续空白压成单个空格） */
    private static String checkPhrases(String text) {
        String flat = SPACES.matcher(text).replaceAll(" ");
        for (int i = 0; i < FORBIDDEN_PATTERNS.length; i++) {
            if (FORBIDDEN_PATTERNS[i].matcher(flat).find()) {
                return REJECT + "语句含 " + FORBIDDEN[i];
            }
        }
        return null;
    }

    /**
     * EXPLAIN 是白名单首词里唯一「会连带执行目标语句」的：MySQL 的 EXPLAIN DML、
     * PG 的 EXPLAIN ANALYZE DML 都会真写数据。取 EXPLAIN 之后（跳过解释选项词）的第一个实词，
     * 是写动词就拒；不是则不往后扫（免得把列名、表名当写动词误伤）。
     */
    private static String checkExplainTarget(String text) {
        Matcher m = WORDS.matcher(text);
        while (m.find()) {
            String token = m.group().toUpperCase();
            if (EXPLAIN.equals(token) || EXPLAIN_OPTIONS.contains(token)) continue;
            if (WRITE_HEADS.contains(token)) {
                return REJECT + "EXPLAIN 的目标含写动词 " + token
                        + "（MySQL 的 EXPLAIN DML、PG 的 EXPLAIN ANALYZE 会连带执行语句）";
            }
            return null;
        }
        return null;
    }

    /**
     * 可执行注释内容会被 MySQL/MariaDB 真执行：片段首词（内联读法与剥掉读法各判一次）不得是写动词，
     * 片段自己的三份视图也要过内容级检查（「外层被真执行、内层只是普通注释」这种混合读法只有在这里才看得到），
     * 嵌套片段同样递归检查。
     */
    private static String checkExecs(Scan scan) {
        for (int i = 0; i < scan.execs.size(); i++) {
            Scan exec = scan.execs.get(i);
            String why = execHeadMustNotBeWrite(exec.stripped);
            if (why == null) why = execHeadMustNotBeWrite(exec.plain);
            if (why == null) why = checkViews(exec);
            if (why == null) why = checkExecs(exec);
            if (why != null) return why;
        }
        return null;
    }

    /** 片段首词是写动词即拒：可执行注释在 MySQL 侧就是代码，首词是写动词就等于注入了写语句 */
    private static String execHeadMustNotBeWrite(CharSequence view) {
        String head = firstKeyword(view.toString().trim());
        if (head.isEmpty() || !WRITE_HEADS.contains(head)) return null;
        return REJECT + "可执行注释内含 " + head + " 语句";
    }

    /** 一次归一化：同一段文本按三种读法各扫一遍，产出三份文本 + 内联出来的可执行注释片段 */
    private static Scan build(String sql, int from, int to, int depth) throws ParseFail {
        List<Scan> execs = new ArrayList<Scan>();
        // 每遍扫描各带一个「下一个换行下标」缓存（扫描位置单调前进，共用就够用）：
        // 少了它会退化成每个行注释都 indexOf 到串尾，几万个注释就是 O(n^2)
        String stripped = normalize(sql, from, to, depth, MODE_STRIPPED, execs, new int[]{-2});
        String plain = normalize(sql, from, to, depth, MODE_PLAIN, execs, new int[]{-2});
        String conservative = normalize(sql, from, to, depth, MODE_CONSERVATIVE, execs, new int[]{-2});
        return new Scan(new StringBuilder(stripped), new StringBuilder(conservative),
                new StringBuilder(plain), execs);
    }

    /**
     * 词法归一化。
     *
     * @param mode MODE_STRIPPED 剥掉一切注释（可执行注释内容内联）；MODE_PLAIN 剥掉一切注释
     *             （可执行注释也当普通注释整段剥掉，见类注释）；MODE_CONSERVATIVE 只剥三库通用注释；
     *             MODE_IN_DOUBT 行内存疑区（存疑注释之后到行尾），注释标记按普通字符留着，
     *             且本行内不闭合的字面量/块注释不报错（任何库都执行不到它）
     */
    private static String normalize(String sql, int from, int to, int depth, int mode,
                                    List<Scan> execs, int[] nl) throws ParseFail {
        StringBuilder out = new StringBuilder(to - from + 1);
        // stripped 与 plain 的区别只在可执行注释（见 blockComment），其余处理完全一致
        boolean stripAll = mode == MODE_STRIPPED || mode == MODE_PLAIN;
        int i = from;
        while (i < to) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < to && sql.charAt(i + 1) == '-') {
                int end = lineCommentEnd(sql, i, to, nl);
                // 双连字符后紧跟空白/行尾才是三库通用注释，否则 MySQL 按两次取负解析。
                // 只认 ASCII 空白：Character.isWhitespace 连 U+2000 之类都算空白，
                // 而 MySQL 不认，宽松判定等于替 MySQL 藏住整行内容。
                boolean universal = i + 2 >= to || isAsciiSpace(sql.charAt(i + 2));
                if (stripAll) {
                    out.append(' ');
                    i = end;
                } else if (universal) {
                    out.append(' ');
                    i = end;
                } else if (mode == MODE_CONSERVATIVE) {
                    out.append(normalize(sql, i, end, depth, MODE_IN_DOUBT, execs, nl));
                    i = end;
                } else {
                    out.append("--");
                    i += 2;
                }
            } else if (c == '#') {
                // # 只 MySQL 认，PG 里是运算符（jsonb 的 #>、整数的异或）：不能当注释剥
                int end = lineCommentEnd(sql, i, to, nl);
                if (stripAll) {
                    out.append(' ');
                    i = end;
                } else if (mode == MODE_CONSERVATIVE) {
                    out.append(normalize(sql, i, end, depth, MODE_IN_DOUBT, execs, nl));
                    i = end;
                } else {
                    out.append('#');
                    i++;
                }
            } else if (c == '/' && i + 1 < to && sql.charAt(i + 1) == '*') {
                i = blockComment(sql, i, to, depth, mode, out, execs);
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = quotedEnd(sql, i, to, c);
                if (end < 0) {
                    // 行内存疑区里没闭合的引号：本行就结束，按普通字符留着即可
                    if (mode == MODE_IN_DOUBT) {
                        out.append(c);
                        i++;
                        continue;
                    }
                    throw new ParseFail("引号未闭合，无法安全解析");
                }
                out.append(LITERAL_MASK);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 块注释。
     *  - 可执行注释（内容会被 MySQL/MariaDB 真执行）在 stripped/conservative 两份视图里不当注释删：
     *    递归归一化后按当前视图内联进来，并把片段登记给 checkExecs（只登记一次：stripped 视图与
     *    行内存疑区各自负责一部分，普通代码区的片段由 stripped 这一遍登记，藏在 # / 不接空白 --
     *    里的由存疑区这一遍登记；plain 这一遍既不外泄内容也不登记）。
     *  - 可执行注释在 plain 视图里当普通注释整段剥掉 —— 「它到底生不生效」判不出方言，
     *    所以两种读法都得有，见类注释的不变式。
     *  - 普通块注释三库都不执行 → 替换成一个空格（不是删空，保证被切开的关键字仍能拼成短语）。
     *  - 行内存疑区里到行尾都配不上的星斜杠不叫未闭合（那一行在任何库都执行不过去），
     *    按普通字符留给后面的文本。
     *
     * @return 注释结束位置（收尾星斜杠之后）
     */
    private static int blockComment(String sql, int from, int to, int depth, int mode,
                                    StringBuilder out, List<Scan> execs) throws ParseFail {
        int end = blockCommentEnd(sql, from, to);
        if (end < 0) {
            if (mode == MODE_IN_DOUBT) {
                out.append('/');
                return from + 1;
            }
            throw new ParseFail("注释未闭合，无法安全解析");
        }
        String inner = sql.substring(from + 2, end - 2);
        if (!isExecutableComment(inner)) {
            out.append(' ');
            return end;
        }
        // 第三视图 plain：可执行注释跟普通注释一样整段剥掉，内容绝不内联 ——
        // 「只有 MySQL/MariaDB 执行它」，PG/Oracle 看到的就只是一段注释，
        // 首词与短语都必须在这份「最小执行文本」上再判一次（见类注释的不变式）
        if (mode == MODE_PLAIN) {
            out.append(' ');
            return end;
        }
        if (depth >= MAX_EXEC_DEPTH) throw new ParseFail("可执行注释嵌套过深，无法安全解析");
        String body = stripCommentMarker(inner);
        Scan exec = build(body, 0, body.length(), depth + 1);
        out.append(' ').append(mode == MODE_STRIPPED ? exec.stripped : exec.conservative).append(' ');
        if (mode == MODE_STRIPPED || mode == MODE_IN_DOUBT) execs.add(exec);
        return end;
    }

    /**
     * 行注释结束位置：换行符本身（不吞掉，交给上层当空白），没有换行则为区间尾。
     * nl[0] 是「下一个换行的下标」缓存（-2 表示还没查过，-1 表示后面再没有换行），
     * 扫描位置单调前进，所以整趟扫描只会把串扫一遍。
     */
    private static int lineCommentEnd(String sql, int from, int to, int[] nl) {
        if (nl[0] == -2 || (nl[0] >= 0 && nl[0] < from)) nl[0] = sql.indexOf('\n', from);
        int k = nl[0];
        return k < 0 || k > to ? to : k;
    }

    /** 块注释结束位置（收尾星斜杠之后），按层内嵌套计数；在 to 之前配不上返回 -1 */
    private static int blockCommentEnd(String sql, int from, int to) {
        int i = from + 2, level = 1;
        while (i < to) {
            char c = sql.charAt(i);
            if (c == '*' && i + 1 < to && sql.charAt(i + 1) == '/') {
                level--;
                i += 2;
                if (level == 0) return i;
            } else if (c == '/' && i + 1 < to && sql.charAt(i + 1) == '*') {
                level++;
                i += 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * 引号包裹内容（字符串或标识符）的结束位置（收尾引号之后），到 limit 配不上返回 -1。
     * 只按「引号加倍」解转义：MySQL 里用反斜杠转义的引号会被判成字符串提前结束，
     * 后果是把真实分号当分号（误拒方向），反过来才不会替任何数据库藏住代码。
     */
    private static int quotedEnd(String sql, int from, int limit, char quote) {
        int i = from + 1;
        while (i < limit) {
            if (sql.charAt(i) != quote) { i++; continue; }
            if (i + 1 < limit && sql.charAt(i + 1) == quote) { i += 2; continue; }
            return i + 1;
        }
        return -1;
    }

    /**
     * 是否 MySQL/MariaDB 可执行注释：感叹号紧跟斜杠星，或紧跟在 ASCII 字母/数字前缀之后
     * （「斜杠星 + 感叹号」、「斜杠星 + M + 感叹号 + 版本号」以及更长的前缀形态）。
     * 前缀只认 ASCII 字母与数字，免得「斜杠星 + 中文说明 + 感叹号」这种普通注释被误伤。
     */
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

    /** 定位感叹号之后的位置；不是可执行注释返回 -1（前缀字母/数字不设长度上限，宁误拒不漏判） */
    private static int markerEnd(String inner) {
        int k = 0;
        while (k < inner.length() && isAsciiWord(inner.charAt(k))) k++;
        return k < inner.length() && inner.charAt(k) == '!' ? k + 1 : -1;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 只认 ASCII 空白（MySQL 的双连字符注释规则就是按 ASCII 判的） */
    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /** 首关键词：取开头的字母/数字/下划线连续段并转大写（引号、括号、空白等一律视为分隔） */
    static String firstKeyword(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean word = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!word) break;
            i++;
        }
        return text.substring(0, i).toUpperCase();
    }

    /** 一次归一化的产物：三份读法的视图 + 内联出来的可执行注释片段 */
    private static final class Scan {
        /** 一切注释剥成空格、可执行注释内容内联（MySQL/MariaDB 读法） */
        final StringBuilder stripped;
        /** 只剥三库通用注释，方言存疑注释按代码保留 */
        final StringBuilder conservative;
        /** 第三视图：连可执行注释也当普通注释整段剥掉（PG/Oracle 读法，任何库的最小执行文本） */
        final StringBuilder plain;
        final List<Scan> execs;

        Scan(StringBuilder stripped, StringBuilder conservative, StringBuilder plain, List<Scan> execs) {
            this.stripped = stripped;
            this.conservative = conservative;
            this.plain = plain;
            this.execs = execs;
        }
    }

    /** 词法解析失败：无法安全判定的语句一律拒绝 */
    private static final class ParseFail extends Exception {
        private static final long serialVersionUID = 1L;
        ParseFail(String message) { super(message); }
    }
}
