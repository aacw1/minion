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
 * 故在执行前先用白名单首词 + 多语句检测 + 危险短语检测 + 括号语句位检测拦一道。
 * check 返回 null 表示放行，否则返回可直接展示给模型的中文拒绝原因。
 *
 * ============================ 一条不变式 ============================
 * 三种驱动背后那批库（MySQL / MariaDB / PostgreSQL / Oracle）对同一句 SQL 的文法并不一致，
 * 不一致的地方正好落在三条**互相独立**的轴上，每条轴两种读法：
 *   1) 行内注释轴：`#` 只 MySQL/MariaDB 认，`--` 只有后接 ASCII 空白/行尾时三库都认
 *      —— 另一种读法里它们是普通字符（PG 的 `#>` 运算符、MySQL 的 `1--1` 取负）；
 *   2) 可执行注释轴：「斜杠星 + 感叹号 + … + 星斜杠」（含「斜杠星 + M + 感叹号」、任意 ASCII
 *      字母前缀形态）只有 MySQL/MariaDB 真执行其内容，PG/Oracle 只当它是普通块注释；
 *   3) 块注释配对轴（round 4 补上）：PG/MariaDB 的块注释**可嵌套**，Oracle 文档明确是
 *      「斜杠星起手、第一个星斜杠收尾」、MySQL 同样不认嵌套 —— 于是
 *      「斜杠星a斜杠星b星斜杠 载荷 星斜杠」这种写法，认嵌套的读法把载荷当注释，
 *      不认嵌套的读法把载荷当代码。
 * 三条轴两两组合 = **八份视图**（见下面的 AXIS_* 常量，视图下标就是三个读法的位标记），
 * 归一化对同一句 SQL 各产出一份。不变式：**凡某库可能真执行的文本，必须被每一项检查看到**；
 * 而「命中」既可能要求文本更多（分号、写动词、短语的两个半边），也可能要求文本更少
 * （注释被剥掉后关键字才拼得拢），两边都不单调，所以只能每条轴的两种取值各跑一遍 ——
 * 单项检查不得只认一份文本，也不得只认一种配对。
 * 历轮漏洞都出在「少了一份读法」：
 *   - round 1：分号判定看保守文本、短语判定只看剥注释文本
 *     → 「SELECT 1--1 INTO OUTFILE 引号路径」（MySQL 里 --1 是取负、不是注释）被漏判；
 *   - round 2：内容级检查没有对「行内存疑区」那份读法跑 → 同类盲区；
 *   - round 3：首词只认 stripped 一份，而 stripped 会把可执行注释的内容内联
 *     → 「感叹号 + SELECT + 星斜杠 + DROP TABLE t」的首词是**从注释内容里借来的 SELECT**，
 *       可执行注释一失效（PG/Oracle）真被执行的首词就是 DROP；同一处内联还会把短语撑开：
 *       「FOR + 可执行注释 + UPDATE」在 PG 里就是 FOR UPDATE（锁行）；
 *   - round 4：三条轴只覆盖了两条 —— 块注释一律按「可嵌套」配对，于是 MySQL/Oracle
 *     在第一个星斜杠就结束注释、其后被真执行的文本，我们三份视图全都看不见
 *     （「SELECT 1 斜杠星a斜杠星感叹号星斜杠 INTO OUTFILE 引号路径 星斜杠」在三份视图里
 *     只是「一句 SELECT 加一堆注释」）。补齐第 3 条轴的同时，另修三处同类盲区：
 *     行注释/存疑区的行尾只认换行、不认回车（MySQL/PG 都把 CR 当行尾，
 *     「SELECT 1-- x回车; DROP TABLE t」因此被整行当注释剥掉）；
 *     「写动词出现在括号处的语句位」没人看（PG 的改写型 CTE、WITH 后接 DML 都是先写数据
 *     再返回结果集，第二层 executeQuery 也挡不住）；
 *     危险短语只拦 PG 的「FOR UPDATE」一档，PG 另有 FOR NO KEY UPDATE / FOR SHARE /
 *     FOR KEY SHARE、MySQL 8 也认 FOR SHARE，那三档等于门户大开。
 * 首词看哪几份：**行内注释按注释处理**的四份（否则「井号 + 换行 + SELECT 1」里行首井号在
 * 保守读法里是普通词，会打死合法写法），四份都要落进白名单；plain/flat 读法整份为空
 * = 该读法下只剩注释、没有任何语句可执行，跳过不判（不给「整句都是版本门控注释」添堵）。
 * 内容级检查（多语句、危险短语、EXPLAIN 目标、括号语句位）在八份视图上各跑一遍，
 * 并**递归跑在每个可执行注释片段自己的八份视图上**（片段的第 3 条轴读法不出现在根视图里，
 * 「外层被真执行、内层只是注释」这类混合读法只能这样看到）。
 * ===================================================================
 *
 * 归一化细则：
 *  - 注释替换成「一个空格」而不是删空：被注释切开的关键字压平后仍能拼成短语命中
 *    （FOR + 块注释 + UPDATE → "FOR UPDATE"）。块注释的收尾按第 3 条轴取两种配对。
 *  - 可执行注释（斜杠星紧跟可选 ASCII 字母/数字前缀再接感叹号）里的内容会被 MySQL/MariaDB 真执行，
 *    故在带 AXIS_INLINE 标记的四份视图里不当注释删：去掉前缀与版本号后递归归一化、按父视图同一
 *    读法内联进来，片段自身还要过一遍「首词不得是写动词」+「八份视图的内容级检查」（见 checkExecs）。
 *    不带该标记的四份（含 round 3 的 plain）则整段剥掉、内容绝不内联。
 *    前缀不设长度上限 —— round 1 只认三个字母，「斜杠星 + MARIADB + 感叹号」这类形态因此
 *    整类绕过（评审 round 2 建议 1）。
 *  - 字符串字面量与引号标识符（单引号、双引号、反引号）一律掩码成占位空串：修「字面量里的
 *    分号被当多语句、字面量里的 for update 被当锁语句」。只认「引号加倍」转义、不认反斜杠转义——
 *    PG 标准模式下反斜杠不是转义符，认了反而会把真实分号藏进假字符串里；MySQL 侧最坏是误拒。
 *  - 顶层的字面量/块注释不闭合 → 判不了就拒，不猜。但**行内存疑区**（`#` 与不接空白的 `--`
 *    之后到行尾）里的未闭合引号/块注释不算未闭合：那一行在任何库都执行不过去，
 *    按普通字符原样留着即可（宁误拒不漏判，也不给合法语句添堵）。
 *    行内存疑区一律**以行尾为界**（换行或回车都算行尾，round 4），绝不让「注释里一个孤零零的
 *    单引号」把后面几行的真实分号当成字符串吞掉（`SELECT 1--don't` 换行 `; DROP TABLE t` 必须照旧拒）。
 *  - 不认嵌套的那份配对（AXIS_FLAT）里，提前收尾暴露出来的注释尾巴（多出的星斜杠两个字符）
 *    会让那份读法必然语法错误；这类视图只用来**多看见一些危险文本**，不因为「取不到首词」判死
 *    （否则「斜杠星 外 斜杠星 内 星斜杠 中文尾巴 星斜杠 SELECT 1」这种 PG 合法写法会被误拒）。
 *
 * 已知取舍（全是误拒方向）：
 *  - 方言存疑注释里出现危险短语/写动词会被拒：`SELECT 1 # 说明 for update 会锁行`。
 *    判不出方言，与「这类注释里出现分号也拒」保持同一口径。
 *  - 以可执行注释起手、剥掉它之后首词就不在白名单的写法会被拒（如「感叹号 + SELECT + 星斜杠 + 1」
 *    这种只在 MySQL 才成立的语句）：判不出目标方言，就不能让首词从注释内容里借。
 *  - **可嵌套块注释的尾巴**会被「不认嵌套」那份读法当代码看见：若那段尾巴里正好有分号、
 *    危险短语或括号语句位写动词，则整句被拒（PG 写法「SELECT 1 斜杠星 a 斜杠星 b 星斜杠 ; c 星斜杠」
 *    会被误拒）。
 *    代价方向与其余各轴一致：这种写法在 MySQL/Oracle 里本来就是两条语句或语法错误。
 *  - 第 7 项「括号语句位」把「DML 词后紧跟左括号」一律视为函数调用放行（REPLACE(str,a,b)、
 *    MySQL 的 INSERT(str,pos,len,new) 等，线上实证修正）：语句位上的 DML 后面只跟表名/INTO，
 *    不会直接跟括号，所以该例外不覆盖任何真写通道（「左括号 REPLACE INTO」照旧拒）。
 *  - 子查询的表/列别名如果恰好取名成 insert/update/delete/merge/replace（PG 里 replace 一类
 *    非保留字可以），仍会被第 7 项误拒（别名不跟括号的形态）。
 *  - PG 美元引用（`$$…$$`）、Oracle q 引号不识别，其内部分号按字面量外处理。
 *
 * 残余缺口（由第二层 setReadOnly + executeQuery 和数据源侧只读账号兜底；登记备查）：
 *  - 三条**已识别**的方言轴（行内注释、可执行注释、块注释配对）已两两组合出八份视图，
 *    这一层不再有「少一份读法」的空格；下面的缺口都在这三条轴之外，多加视图解决不了。
 *  - PG 的 "SELECT … INTO 表名"（建表写入）与 MySQL 无副作用的 "SELECT … INTO @变量"
 *    无法在不引入误拒的前提下区分，不进危险短语表；Oracle `EXPLAIN PLAN FOR SELECT …` 仍放行
 *    （它写 plan_table）；`LOAD_FILE()` 一类外读面要拦须先扩危险短语表。
 *  - MySQL 优化器提示（「斜杠星加号 + SET_VAR(…) + 星斜杠」）可以改会话变量（general_log 一类），不拦：
 *    按名字拦会打死所有正常提示，且这类改动需要权限，交给只读账号。
 *  - PG 美元引用（`$$…$$`）、Oracle q 引号不识别（见上面的已知取舍）。
 */
public final class SqlGuard {

    /** 拒绝原因统一前缀（DbTool 原样透传给模型） */
    private static final String REJECT = "只读工具拒绝执行：";

    /** 放行的首关键词（Oracle 上 DESC/DESCRIBE 是 SQL*Plus 命令、JDBC 会报错，保留无害） */
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN"));

    private static final String ALLOWED_TEXT = "SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN";

    /**
     * 拒绝原因里的读法说明：模型看到的「首词」跟它写的不一样，不点破会被当成误拒。
     * PLAIN_MARK = 可执行注释不生效那批库的读法；FLAT_MARK = 块注释不认嵌套那批库的读法。
     */
    private static final String PLAIN_MARK = "把可执行注释当普通注释看时，";
    private static final String FLAT_MARK = "把块注释按「不认嵌套」的方言（Oracle/MySQL）看时，";

    /** EXPLAIN 系首词（MySQL 的 EXPLAIN DML 与 PG 的 EXPLAIN ANALYZE 会真执行目标语句） */
    private static final String EXPLAIN = "EXPLAIN";

    /**
     * 出现即拒的短语（整词边界匹配、忽略大小写），文案与 FORBIDDEN_PATTERNS 一一对应。
     * 四条锁语句把 PG 的四种锁强度配齐（round 4）：FOR UPDATE / FOR NO KEY UPDATE /
     * FOR KEY SHARE / FOR SHARE，外加 MySQL 的 LOCK IN SHARE MODE（MySQL 8 也认 FOR SHARE）。
     */
    private static final String[] FORBIDDEN = {
            "INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "FOR NO KEY UPDATE",
            "FOR KEY SHARE", "FOR SHARE", "LOCK IN SHARE MODE"};

    /**
     * 写动词。两份用途：可执行注释片段不许以它们开头；EXPLAIN 之后不许出现它们。
     * 整词匹配（下划线算词字符），列名 update_time 之类不受影响。
     */
    private static final Set<String> WRITE_HEADS = new HashSet<String>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "REPLACE",
            "MERGE", "UPSERT", "GRANT", "REVOKE", "SET", "CALL", "RENAME", "LOCK", "UNLOCK",
            "LOAD", "HANDLER", "KILL", "COMMIT", "ROLLBACK", "SAVEPOINT", "PREPARE", "EXECUTE",
            "DEALLOCATE", "SHUTDOWN", "START", "BEGIN", "TABLE", "COPY", "VACUUM", "RESET"));

    /**
     * 能出现在「括号处的语句位」上的写动词（round 4 第 7 项检查专用）。
     * 只收 DML 六个：PG 的改写型 CTE 正文（WITH x AS (DELETE … RETURNING *)）与
     * WITH 子句结束后接的主语句（WITH x AS (…) DELETE/UPDATE/INSERT/REPLACE/MERGE）
     * 都必须以它们开头；把 SET/TABLE/BEGIN/START 那批非保留词放进来会误伤
     * PG 里合法的列名与别名（SELECT begin, end FROM t）。
     */
    private static final Set<String> DML_HEADS = new HashSet<String>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE"));

    /** 字面量掩码：两侧留空格，避免掩码把相邻关键字粘成一个词 */
    private static final String LITERAL_MASK = " '' ";

    /** 可执行注释递归的最大层数，超出直接拒（防超长嵌套把真实内容藏进「注释」里） */
    private static final int MAX_EXEC_DEPTH = 4;

    // ==== 三条方言轴的位标记，一份视图 = 一个三位组合（见类注释的不变式） ====

    /** 轴 3：块注释按**首个**星斜杠收尾配对（Oracle/MySQL）；不带该位则按层内嵌套配对（PG/MariaDB） */
    private static final int AXIS_FLAT = 1;

    /** 轴 2：可执行注释的内容按代码内联（MySQL/MariaDB）；不带该位则整段当普通注释剥掉 */
    private static final int AXIS_INLINE = 2;

    /** 轴 1：`#` 与不接空白的 `--` 之后按代码保留（非 MySQL 读法）；不带该位则当注释剥掉 */
    private static final int AXIS_KEEP = 4;

    /** 派生标记：正处于行内存疑区内（注释标记按普通字符、本行内不闭合的构造不报错） */
    private static final int AXIS_TOLERANT = 8;

    /** 视图总数 = 2×2×2 */
    private static final int VIEWS = 8;

    /** 普通读法下标（不含 AXIS_TOLERANT），用于从存疑区递归回来时定位父视图 */
    private static final int VIEW_BITS = AXIS_FLAT | AXIS_INLINE | AXIS_KEEP;

    /**
     * 首词要看的四份视图：只看在「行内注释按注释处理」的读法（AXIS_KEEP 未置位）下非空的那些。
     * 顺序决定拒绝文案：stripped → plain → 不认嵌套 + 内联 → 不认嵌套 + 剥掉。
     */
    private static final int[] HEAD_VIEWS = {AXIS_INLINE, 0, AXIS_INLINE | AXIS_FLAT, AXIS_FLAT};

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
     * 首关键词：四份「行内注释按注释处理」的读法上都要落进白名单 —— 首词既不许从可执行注释的
     * 内容里「借」（round 3），也不许从不认嵌套配对暴露出来的注释尾巴里借（round 4）。
     * 某份视图整份为空 = 该读法下只剩注释、没有任何语句可执行，跳过这一项（不给合法写法添堵）。
     */
    private static String checkHead(Scan scan) {
        String stripped = scan.view(AXIS_INLINE).trim();
        if (stripped.isEmpty()) return "SQL 不能为空";
        for (int k = 0; k < HEAD_VIEWS.length; k++) {
            int v = HEAD_VIEWS[k];
            String text = scan.view(v).trim();
            if (text.isEmpty()) continue;
            String head = firstKeyword(text);
            if (head.isEmpty()) {
                // 按首个星斜杠收尾的读法多看见的注释尾巴起手未必是词，那份读法执行不过去，不判死
                if ((v & AXIS_FLAT) == 0) return REJECT + mark(v) + "无法识别语句首关键词";
                continue;
            }
            if (!ALLOWED.contains(head)) {
                return REJECT + mark(v) + "语句以 " + head + " 开头（仅允许 " + ALLOWED_TEXT + "）";
            }
        }
        return null;
    }

    /** 视图下标 → 读法说明前缀（哪个假设塌了就直接讲哪个，别把三轴混成一句含糊话） */
    private static String mark(int view) {
        if (view == AXIS_INLINE) return "";
        if (view == 0) return PLAIN_MARK;
        if (view == (AXIS_INLINE | AXIS_FLAT)) return FLAT_MARK;
        return FLAT_MARK + PLAIN_MARK;
    }

    /**
     * 内容级检查（多语句、危险短语、EXPLAIN 目标、括号语句位）：八份视图各跑一遍，任一命中即拒。
     * 可执行注释片段自己的八份视图同样要跑（片段在第二条、第三条轴上的读法不出现在根视图里）。
     */
    private static String checkViews(Scan scan) {
        for (int v = 0; v < VIEWS; v++) {
            String why = checkSingleStatement(scan.view(v));
            if (why != null) return why;
        }
        for (int v = 0; v < VIEWS; v++) {
            String why = checkPhrases(scan.view(v));
            if (why != null) return why;
        }
        for (int v = 0; v < VIEWS; v++) {
            // 先看「这份读法下首词真是 EXPLAIN」的视图：EXPLAIN 会连带执行目标语句
            if (!EXPLAIN.equals(firstKeyword(scan.view(v).trim()))) continue;
            String why = checkExplainTarget(scan.view(v));
            if (why != null) return why;
        }
        for (int v = 0; v < VIEWS; v++) {
            String why = checkDmlPositions(scan.view(v));
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
     * 括号处的语句位（round 4 第 7 项）：紧跟在左括号之后、或紧跟在右括号之后的第一个词，
     * 是 DML 动词就拒。这两处正好盖住 WITH 起手的两条写通道 ——
     * CTE 正文（WITH x AS 左括号 DELETE … 右括号）与 CTE 列表结束后真正的主语句
     * （WITH x AS (…) DELETE …），两者都会**先执行写操作**再返回结果集，
     * executeQuery 拿得到 ResultSet 也就挡不住。EXPLAIN 后面套 WITH 同理（第 6 项只看到 WITH 就停了）。
     *
     * 例外（线上实证修正）：词后紧跟左括号 = 函数调用（REPLACE(str,from,to)、MySQL 的
     * INSERT(str,pos,len,newstr) 等），不是语句位，放行 —— 语句位上的 DML 动词后面只跟
     * 表名或 INTO，不会直接跟括号（REPLACE INTO 的 REPLACE 后面是 INTO，照旧拒）。
     * 旧实现只看词本身，把 `max(replace(name,'x','y'))`、`COALESCE(replace(...),'')`、
     * `(replace(...))` 这类嵌套函数调用全判成写语句，误拒面远大于收益。
     */
    private static String checkDmlPositions(String text) {
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c != '(' && c != ')') continue;
            int start = skipSpaces(text, i + 1);
            int end = wordEnd(text, start);
            if (end < 0) continue;
            String word = text.substring(start, end).toUpperCase();
            if (!DML_HEADS.contains(word)) continue;
            if (isCall(text, end)) continue; // 词后紧跟 ( → 函数调用，非语句位
            return REJECT + "括号后的语句位出现写动词 " + word
                    + "（PG 的改写型 CTE、WITH 子句后接 DML 会真写数据）";
        }
        return null;
    }

    /** 从 from 起跳过空白，返回第一个非空白字符下标（到串尾返回 text.length()） */
    private static int skipSpaces(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** 从 from 起读一个整词（字母/数字/下划线），返回词尾下标；起手不是词字符返回 -1 */
    private static int wordEnd(String text, int from) {
        int i = from;
        while (i < text.length() && isWordChar(text.charAt(i))) i++;
        return i > from ? i : -1;
    }

    /** end（词尾）跳过空白后是否紧跟左括号：是则为函数调用形态（REPLACE(…)、INSERT(…)） */
    private static boolean isCall(String text, int end) {
        int i = end;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i < text.length() && text.charAt(i) == '(';
    }

    /**
     * 可执行注释内容会被 MySQL/MariaDB 真执行：片段首词（四份「首词视图」上）不得是写动词，
     * 片段自己的八份视图也要过内容级检查（「外层被真执行、内层只是普通注释」这种混合读法只有
     * 在这里才看得到），嵌套片段同样递归检查。
     */
    private static String checkExecs(Scan scan) {
        for (int i = 0; i < scan.execs.size(); i++) {
            Scan exec = scan.execs.get(i);
            String why = null;
            for (int k = 0; k < HEAD_VIEWS.length && why == null; k++) {
                why = execHeadMustNotBeWrite(exec.view(HEAD_VIEWS[k]));
            }
            if (why == null) why = checkViews(exec);
            if (why == null) why = checkExecs(exec);
            if (why != null) return why;
        }
        return null;
    }

    /** 片段首词是写动词即拒：可执行注释在 MySQL 侧就是代码，首词是写动词就等于注入了写语句 */
    private static String execHeadMustNotBeWrite(String view) {
        String head = firstKeyword(view.trim());
        if (head.isEmpty() || !WRITE_HEADS.contains(head)) return null;
        return REJECT + "可执行注释内含 " + head + " 语句";
    }

    /** 一次归一化：同一段文本按三条轴的八种组合各扫一遍，产出八份视图 + 内联出来的可执行注释片段 */
    private static Scan build(String sql, int from, int to, int depth) throws ParseFail {
        List<Scan> execs = new ArrayList<Scan>();
        String[] views = new String[VIEWS];
        // 每遍扫描各带一个「下一个行尾下标」缓存（扫描位置单调前进，共用就够用）：
        // 少了它会退化成每个行注释都扫到串尾，几万个注释就是 O(n^2)
        for (int v = 0; v < VIEWS; v++) {
            List<Scan> found = new ArrayList<Scan>();
            String text;
            try {
                text = normalize(sql, from, to, depth, v, found, new int[]{-2});
            } catch (ParseFail e) {
                // 词法配不上对（未闭合的注释/引号）：根级判不了就拒；片段级只说明
                // 「按这条轴的取值，这段文本在任何库里都是一句语法错误的残料」，
                // 该份视图留空（= 该读法下没有语句可执行），不牵连同段其余七份读法
                if (depth == 0 || !e.lexical) throw e;
                text = "";
            }
            views[v] = text;
            execs.addAll(found);
        }
        return new Scan(views, execs);
    }

    /**
     * 词法归一化。
     *
     * @param mode 三条轴的位组合（AXIS_FLAT / AXIS_INLINE / AXIS_KEEP）；AXIS_TOLERANT 只在
     *             行内存疑区的递归里出现：注释标记按普通字符留着，且本行内不闭合的
     *             字面量/块注释不报错（任何库都执行不到它）
     */
    private static String normalize(String sql, int from, int to, int depth, int mode,
                                    List<Scan> execs, int[] nl) throws ParseFail {
        StringBuilder out = new StringBuilder(to - from + 1);
        boolean keep = (mode & AXIS_KEEP) != 0;
        int i = from;
        while (i < to) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < to && sql.charAt(i + 1) == '-') {
                int end = lineCommentEnd(sql, i, to, nl);
                // 双连字符后紧跟空白/行尾才是三库通用注释，否则 MySQL 按两次取负解析。
                // 只认 ASCII 空白：Character.isWhitespace 连 U+2000 之类都算空白，
                // 而 MySQL 不认，宽松判定等于替 MySQL 藏住整行内容。
                boolean universal = i + 2 >= to || isAsciiSpace(sql.charAt(i + 2));
                if (!keep || universal) {
                    out.append(' ');
                    i = end;
                } else if ((mode & AXIS_TOLERANT) != 0) {
                    out.append("--");
                    i += 2;
                } else {
                    out.append(normalize(sql, i, end, depth, inDoubt(mode), execs, nl));
                    i = end;
                }
            } else if (c == '#') {
                // # 只 MySQL 认，PG 里是运算符（jsonb 的 #>、整数的异或）：不能当注释剥
                int end = lineCommentEnd(sql, i, to, nl);
                if (!keep) {
                    out.append(' ');
                    i = end;
                } else if ((mode & AXIS_TOLERANT) != 0) {
                    out.append('#');
                    i++;
                } else {
                    out.append(normalize(sql, i, end, depth, inDoubt(mode), execs, nl));
                    i = end;
                }
            } else if (c == '/' && i + 1 < to && sql.charAt(i + 1) == '*') {
                i = blockComment(sql, i, to, depth, mode, out, execs);
            } else if (c == '\'' || c == '"' || c == '`') {
                int end = quotedEnd(sql, i, to, c);
                if (end < 0) {
                    // 行内存疑区里没闭合的引号：本行就结束，按普通字符留着即可
                    if ((mode & AXIS_TOLERANT) != 0) {
                        out.append(c);
                        i++;
                        continue;
                    }
                    throw new ParseFail("引号未闭合，无法安全解析", true);
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

    /** 行内存疑区的子扫描：继承父视图的两条轴（配对、可执行注释），行内注释按代码保留 */
    private static int inDoubt(int mode) {
        return (mode & (AXIS_FLAT | AXIS_INLINE)) | AXIS_KEEP | AXIS_TOLERANT;
    }

    /**
     * 块注释。
     *  - 可执行注释（内容会被 MySQL/MariaDB 真执行）在带 AXIS_INLINE 的视图里不当注释删：
     *    递归归一化后**按父视图同一读法**内联进来（片段自己的八份视图都在，取哪份由轴组合决定），
     *    并把片段登记给 checkExecs。登记只由「同一条配对轴上、行内注释读法唯一」的那一遍负责
     *    （见 registers），否则同一片段会被扫四遍。
     *  - 不带 AXIS_INLINE 的四份视图里，可执行注释跟普通注释一样整段剥掉 —— 「它到底生不生效」
     *    判不出方言，所以两种读法都得有（round 3）。
     *  - 普通块注释三库都不执行 → 替换成一个空格（不是删空，保证被切开的关键字仍能拼成短语）。
     *  - 收尾星斜杠按第 3 条轴取配对方式：认嵌套的按层内计数，不认嵌套的按**首个**星斜杠
     *    （round 4）。两种配对的差就是「被藏住的载荷」，所以两份都得扫。
     *  - 行内存疑区里到行尾都配不上的星斜杠不叫未闭合（那一行在任何库都执行不过去），
     *    按普通字符留给后面的文本。
     *
     * @return 注释结束位置（收尾星斜杠之后）
     */
    private static int blockComment(String sql, int from, int to, int depth, int mode,
                                    StringBuilder out, List<Scan> execs) throws ParseFail {
        int end = blockCommentEnd(sql, from, to, (mode & AXIS_FLAT) == 0);
        if (end < 0) {
            if ((mode & AXIS_TOLERANT) != 0) {
                out.append('/');
                return from + 1;
            }
            throw new ParseFail("注释未闭合，无法安全解析", true);
        }
        String inner = sql.substring(from + 2, end - 2);
        if (!isExecutableComment(inner)) {
            out.append(' ');
            return end;
        }
        if ((mode & AXIS_INLINE) == 0) {
            out.append(' ');
            return end;
        }
        // 判定能力本身失败（不是某条轴的读法不匹配），一律牵到最外层拒掉
        if (depth >= MAX_EXEC_DEPTH) throw new ParseFail("可执行注释嵌套过深，无法安全解析", false);
        String body = stripCommentMarker(inner);
        Scan exec = build(body, 0, body.length(), depth + 1);
        out.append(' ').append(exec.view(mode & VIEW_BITS)).append(' ');
        if (registers(mode)) execs.add(exec);
        return end;
    }

    /**
     * 这一遍是否负责登记可执行注释片段：AXIS_INLINE 的四遍里，「非存疑区」由 stripped
     * 与它的 flat 孪生各登记一次（两条配对轴找到的片段范围不同），「存疑区」由带 AXIS_TOLERANT
     * 的那两遍各登记一次；其余带 AXIS_INLINE 但落在注释里的扫描不重复登记。
     */
    private static boolean registers(int mode) {
        if ((mode & AXIS_INLINE) == 0) return false;
        return ((mode & AXIS_KEEP) != 0) == ((mode & AXIS_TOLERANT) != 0);
    }

    /**
     * 行注释结束位置：行尾符本身（不吞掉，交给上层当空白），没有行尾则为区间尾。
     * round 4：换行与回车**都**算行尾（MySQL/PG/Oracle 的 `--`、`#` 注释都在 CR 处结束），
     * 只认换行等于让「SELECT 1-- x回车; DROP TABLE t」里第二条语句整行隐身。
     * nl[0] 是「下一个行尾的下标」缓存（-2 表示还没查过，-1 表示后面再没有行尾），
     * 扫描位置单调前进，所以整趟扫描只会把串扫一遍。
     */
    private static int lineCommentEnd(String sql, int from, int to, int[] nl) {
        if (nl[0] == -2 || (nl[0] >= 0 && nl[0] < from)) nl[0] = nextEol(sql, from);
        int k = nl[0];
        return k < 0 || k > to ? to : k;
    }

    private static int nextEol(String sql, int from) {
        for (int i = from; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\n' || c == '\r') return i;
        }
        return -1;
    }

    /**
     * 块注释结束位置（收尾星斜杠之后）。
     *
     * @param nest true = 按层内嵌套计数（PG/MariaDB 读法）；false = 按**首个**星斜杠收尾
     *             （Oracle/MySQL 读法，round 4）
     * @return 结束位置；在 to 之前配不上返回 -1
     */
    private static int blockCommentEnd(String sql, int from, int to, boolean nest) {
        if (!nest) {
            int k = sql.indexOf("*/", from + 2);
            return k < 0 || k + 2 > to ? -1 : k + 2;
        }
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

    private static boolean isWordChar(char c) {
        return isAsciiWord(c) || c == '_';
    }

    /** 只认 ASCII 空白（MySQL 的双连字符注释规则就是按 ASCII 判的） */
    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /** 首关键词：取开头的字母/数字/下划线连续段并转大写（引号、括号、空白等一律视为分隔） */
    static String firstKeyword(String text) {
        int i = 0;
        while (i < text.length() && isWordChar(text.charAt(i))) i++;
        return text.substring(0, i).toUpperCase();
    }

    /** 一次归一化的产物：三条方言轴组合出的八份读法视图 + 内联出来的可执行注释片段 */
    private static final class Scan {
        /** 下标 = AXIS_FLAT / AXIS_INLINE / AXIS_KEEP 的位组合，见类注释的不变式说明 */
        private final String[] views;
        private final List<Scan> execs;

        Scan(String[] views, List<Scan> execs) {
            this.views = views;
            this.execs = execs;
        }

        String view(int axes) {
            return views[axes & VIEW_BITS];
        }
    }

    /** 词法解析失败：无法安全判定的语句一律拒绝 */
    private static final class ParseFail extends Exception {
        private static final long serialVersionUID = 1L;

        /**
         * true = 只是词法配不上对（未闭合的注释/引号）：这种失败在「派生片段 + 某一条轴取值
         * 与之不匹配」的读法里是预期内的，那份视图留空即可（见 build）；
         * false = 判定能力本身失败（可执行注释嵌套过深），任何读法都必须拒。
         */
        final boolean lexical;

        ParseFail(String message, boolean lexical) {
            super(message);
            this.lexical = lexical;
        }
    }
}
