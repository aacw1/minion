package com.minion.core.tools.db;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SqlGuard 的**多方言差分回归工件**（round 4 沉淀）：不再靠人肉想绕过用例。
 *
 * 做法：用一套「参考读取器」独立算出每种真实方言（MySQL / MariaDB / PG / Oracle，
 * 各自在三条轴上的取值组合）面对同一句 SQL **真正会执行的文本**，再用一份与 SqlGuard
 * 互不共享代码的「危险定义」判那份文本危不危险；只要任一读法的文本危险，SqlGuard 就必须拒。
 * 于是每条轴新出现的盲区都会自动现形（round 4 的块注释配对轴就是这么找出来的，
 * 之前三版人肉评审各自漏了一条轴）。
 *
 * 覆盖面靠结构枚举撑：注释标记 / 行尾 / 分号 / 括号 / 引号这些「结构原子」按长度组合，
 * 再夹上危险载荷（写语句首词、四条危险短语、WITH 后接 DML、改写型 CTE），
 * 而不是手写若干条字符串。跑一次约十万级用例，秒级完成，可直接进 mvn test。
 *
 * 另附「真实只读语料」反向检查：模型实际会写的只读 SQL 不许被误拒（防修法过猛）。
 */
public class SqlGuardDialectTest {

    // ==================== 参考侧：方言读法 ====================

    /** 一种方言读法：三条轴各取一种取值，外加两处各家不同的细节 */
    private static final class Dialect {
        final String name;
        /** 轴 3：块注释是否可嵌套（false = 按**首个**星斜杠收尾，Oracle/MySQL 的文档口径） */
        final boolean nest;
        /** 轴 2：斜杠星紧跟感叹号（或字母前缀 + 感叹号）的内容是否会被真执行 */
        final boolean execComment;
        /** 可执行注释是否也认「斜杠星 + 字母前缀 + 感叹号」形态（MariaDB） */
        final boolean letterPrefix;
        /** 轴 1：# 是否算行注释（只 MySQL/MariaDB 认） */
        final boolean hashComment;
        /** -- 之后是否必须接空白才算注释（只 MySQL/MariaDB 有这个要求） */
        final boolean dashNeedsSpace;
        /** 字符串里反斜杠是否转义（PG 标准模式不转义） */
        final boolean backslashEscape;

        Dialect(String name, boolean nest, boolean execComment, boolean letterPrefix,
                boolean hashComment, boolean dashNeedsSpace, boolean backslashEscape) {
            this.name = name;
            this.nest = nest;
            this.execComment = execComment;
            this.letterPrefix = letterPrefix;
            this.hashComment = hashComment;
            this.dashNeedsSpace = dashNeedsSpace;
            this.backslashEscape = backslashEscape;
        }
    }

    private static final Dialect[] DIALECTS = {
            new Dialect("mysql", true, true, false, true, true, true),
            new Dialect("mysql-flat", false, true, false, true, true, true),
            new Dialect("mariadb", true, true, true, true, true, true),
            new Dialect("mariadb-flat", false, true, true, true, true, true),
            new Dialect("postgresql", true, false, false, false, false, false),
            new Dialect("oracle-nest", true, false, false, false, false, false),
            new Dialect("oracle-flat", false, false, false, false, false, false),
    };
    /**
     * 该方言真会执行的文本：注释按它的规则剥、可执行注释按它的规则展开、字面量掩成空串。
     *
     * @return null = 这种读法连词法都过不去（未闭合的注释/引号），语句执行不了，无需判定
     */
    private static String executed(String sql, Dialect d, int depth) {
        if (depth > 8) return null;
        StringBuilder out = new StringBuilder();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int body = i + 2;
                boolean exec = false;
                if (d.execComment) {
                    int k = body;
                    while (k < n && (d.letterPrefix ? isWord(sql.charAt(k)) : isDigit(sql.charAt(k)))) k++;
                    if (k < n && sql.charAt(k) == '!') {
                        body = k + 1;
                        exec = true;
                    }
                }
                int end = closeAt(sql, i, n, d.nest);
                if (end < 0) return null;
                if (exec) {
                    String inner = executed(sql.substring(body, end - 2), d, depth + 1);
                    out.append(inner == null ? "" : inner).append(' ');
                } else {
                    out.append(' ');
                }
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (!d.dashNeedsSpace || atEolOrSpace(sql, i + 2, n))) {
                i = eol(sql, i, n);
                out.append(' ');
            } else if (c == '#' && d.hashComment) {
                i = eol(sql, i, n);
                out.append(' ');
            } else if (c == '\'' || c == '"' || c == '`') {
                int e = quoteEnd(sql, i, n, c, d.backslashEscape);
                if (e < 0) return null;
                out.append(" '' ");
                i = e;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 块注释收尾：认嵌套的按层内计数，不认嵌套的按首个星斜杠 */
    private static int closeAt(String s, int from, int n, boolean nest) {
        if (!nest) {
            int k = s.indexOf("*/", from + 2);
            return k < 0 || k + 2 > n ? -1 : k + 2;
        }
        int i = from + 2, level = 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '*' && i + 1 < n && s.charAt(i + 1) == '/') {
                if (--level == 0) return i + 2;
                i += 2;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                level++;
                i += 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** 行尾：换行与回车都算（MySQL/PG/Oracle 的 -- 与 # 注释都在 CR 处结束） */
    private static int eol(String s, int from, int n) {
        for (int i = from; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') return i;
        }
        return n;
    }

    private static boolean atEolOrSpace(String s, int k, int n) {
        return k >= n || Character.isWhitespace(s.charAt(k));
    }

    private static int quoteEnd(String s, int from, int n, char q, boolean backslash) {
        int i = from + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (backslash && c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) {
                if (i + 1 < n && s.charAt(i + 1) == q) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    // ==================== 参考侧：危险定义 ====================

    private static final Set<String> WRITE = new HashSet<String>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "REPLACE",
            "MERGE", "UPSERT", "GRANT", "REVOKE", "SET", "CALL", "RENAME", "LOCK", "UNLOCK",
            "LOAD", "HANDLER", "KILL", "COMMIT", "ROLLBACK", "SAVEPOINT", "PREPARE", "EXECUTE",
            "DEALLOCATE", "SHUTDOWN", "START", "BEGIN", "TABLE", "COPY", "VACUUM", "RESET"));

    private static final Set<String> DML = new HashSet<String>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE"));

    private static final Set<String> EXPLAIN_OPTIONS = new HashSet<String>(Arrays.asList(
            "ANALYZE", "ANALYSE", "VERBOSE", "COSTS", "SETTINGS", "GENERIC_PLAN", "BUFFERS",
            "WAL", "TIMING", "SUMMARY", "MEMORY", "FORMAT", "EXTENDED", "PARTITIONS", "PLAN",
            "FOR", "JSON", "TEXT", "XML", "YAML", "TRUE", "FALSE", "ON", "OFF", "IS"));

    private static final String[] PHRASES = {
            "INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "FOR NO KEY UPDATE",
            "FOR KEY SHARE", "FOR SHARE", "LOCK IN SHARE MODE"};

    private static final Pattern[] PHRASE_PATTERNS = new Pattern[PHRASES.length];
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern WORDS = Pattern.compile("[A-Za-z0-9_]+");

    static {
        for (int i = 0; i < PHRASES.length; i++) {
            PHRASE_PATTERNS[i] = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(PHRASES[i])
                    + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);
        }
    }

    /** 这份真执行文本危险在哪（返回 null = 不危险）：四项判据与 SqlGuard 一一对应 */
    private static String risk(String real) {
        String t = SPACES.matcher(real).replaceAll(" ").trim();
        if (t.isEmpty()) return null;
        List<String> segs = new ArrayList<String>();
        segs.add(t);
        String head = t;
        if (head.endsWith(";")) head = head.substring(0, head.length() - 1);
        int sc = head.indexOf(';');
        while (sc >= 0) {
            segs.add(head.substring(sc + 1));
            head = head.substring(0, sc);
            sc = head.indexOf(';');
        }
        for (int i = 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            String h = firstWord(seg);
            if (h.isEmpty()) continue;
            if (WRITE.contains(h)) return "第 " + (i + 1) + " 条语句以写动词 " + h + " 开头";
            if ("EXPLAIN".equals(h)) {
                Matcher m = WORDS.matcher(seg);
                while (m.find()) {
                    String tok = m.group().toUpperCase();
                    if ("EXPLAIN".equals(tok) || EXPLAIN_OPTIONS.contains(tok)) continue;
                    if (WRITE.contains(tok)) return "EXPLAIN 的目标是写动词 " + tok;
                    break;
                }
            }
            for (int j = 0; j < PHRASE_PATTERNS.length; j++) {
                if (PHRASE_PATTERNS[j].matcher(seg).find()) return "含危险短语 " + PHRASES[j];
            }
            // 括号语句位只在「该段有 WITH 子句」时提要求：改写型 CTE 与 WITH 后接 DML 才是
            // 真会执行的写通道，`SELECT 1 FOR ( UPDATE` 那种纯语法错误不该算载荷
            if (hasWord(seg, "WITH")) {
                for (int j = 0; j < seg.length(); j++) {
                    char c = seg.charAt(j);
                    if (c != '(' && c != ')') continue;
                    String w = wordAfter(seg, j + 1);
                    if (w != null && DML.contains(w)) return "WITH 语句里括号处是写动词 " + w;
                }
            }
        }
        return null;
    }

    private static String firstWord(String text) {
        String s = text.trim();
        int i = 0;
        while (i < s.length() && isWord(s.charAt(i))) i++;
        return s.substring(0, i).toUpperCase();
    }

    /** 整词存在性（忽略大小写）：只用来限定「这段文本里确实有 WITH 子句」 */
    private static boolean hasWord(String text, String word) {
        Matcher m = WORDS.matcher(text);
        while (m.find()) {
            if (word.equalsIgnoreCase(m.group())) return true;
        }
        return false;
    }

    private static String wordAfter(String text, int from) {        int i = from, n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        int s = i;
        while (i < n && isWord(text.charAt(i))) i++;
        return i > s ? text.substring(s, i).toUpperCase() : null;
    }

    private static boolean isWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ==================== 差分枚举 ====================

    /** 结构原子：注释标记、行尾、分号、括号、引号 —— 盲区都长在它们的组合上 */
    private static final String[] ATOM = {"/*", "*/", "/*!", "/*M!1", "#", "--", "-- ", " ",
            "\n", "\r", ";", "'", "(", ")", "x"};

    /** 危险载荷：四条真执行通道各一条 */
    private static final String[] PAYLOADS = {"DROP TABLE t", "UPDATE t SET a=1",
            "INTO OUTFILE 'f'", "FOR UPDATE", "FOR SHARE", "LOCK IN SHARE MODE",
            "DELETE FROM t RETURNING *", "SET GLOBAL x=1"};

    /** 深枚举用的少量载荷 + 更长前缀：三种执行通道各挑一条，把注释结构的组合推到 3 层 */
    private static final String[] DEEP_PAYLOADS = {"DROP TABLE t", "INTO OUTFILE 'f'", "FOR SHARE"};

    private static int checked;

    /**
     * 主差分：**任一**方言读法真执行的文本若危险，SqlGuard 必须拒。
     * 三类模板（载荷藏进注释结构 / 首词位置 / WITH 第二条通道）+ 纯结构枚举。
     */
    @Test
    public void noDialectReadingMayHideADangerousPayload() {
        holes = new ArrayList<String>();
        checked = 0;

        // 1) 前缀（结构原子的 0~2 组合）+ 载荷 + 尾巴：把危险文本藏进注释结构的每种夹法
        List<String> prefixes = combos(2);
        for (int p = 0; p < PAYLOADS.length; p++) {
            String pay = PAYLOADS[p];
            for (int i = 0; i < prefixes.size(); i++) {
                String pre = prefixes.get(i);
                diff("SELECT 1 " + pre + " " + pay);
                diff(pre + " " + pay);
                diff("EXPLAIN " + pre + " " + pay);
                diff("SELECT 1 " + pre + " " + pay + " */");
                diff("SELECT 1 " + pre + " " + pay + " /*z*/");
                diff("SELECT * FROM t FOR " + pre + " UPDATE");
            }
        }
        // 2) 首词位置：结构前缀 + 白名单词 + 星斜杠 + 写动词（round 3 那条轴的复发面）
        String[] words = {"SELECT", "WITH", "EXPLAIN", "SHOW"};
        for (int w = 0; w < words.length; w++) {
            for (int i = 0; i < prefixes.size(); i++) {
                String pre = prefixes.get(i);
                diff(pre + "/*!" + words[w] + "*/DROP TABLE t");
                diff(pre + "/*!" + words[w] + "*/SELECT 1 INTO OUTFILE 'f'");
                diff(pre + "/*a/*b*/" + words[w] + "*/DROP TABLE t */");
            }
        }
        // 3) WITH 的第二条写通道（round 4 必修 A）：括号位置换各种藏法
        String[] verbs = {"DELETE", "UPDATE", "INSERT", "REPLACE", "MERGE"};
        for (int v = 0; v < verbs.length; v++) {
            for (int i = 0; i < prefixes.size(); i++) {
                String pre = prefixes.get(i);
                diff("WITH x AS (SELECT 1) " + pre + " " + verbs[v] + " FROM t WHERE id=1");
                diff("WITH x AS (" + pre + " " + verbs[v] + " FROM t RETURNING *) SELECT * FROM x");
                diff("EXPLAIN WITH x AS (SELECT 1) " + pre + " " + verbs[v] + " FROM t");
            }
        }
        // 4) 深一层：前缀长度推到 3（组合数 ×15），载荷换着来，专治「要三层结构才现形」的盲区
        List<String> deep = combos(3);
        for (int p = 0; p < DEEP_PAYLOADS.length; p++) {
            String pay = DEEP_PAYLOADS[p];
            for (int i = 0; i < deep.size(); i++) {
                String pre = deep.get(i);
                diff("SELECT 1 " + pre + " " + pay + " */");
                diff(pre + "/*!SELECT*/ " + pay);
            }
        }
        // 5) 纯结构枚举（长度 ≤3）：不给载荷，看守卫会不会把「只剩注释」的东西判放行
        for (int len = 1; len <= 3; len++) {
            List<String> combo = combos(len);
            for (int i = 0; i < combo.size(); i++) {
                diff(combo.get(i));
                diff("SELECT 1 " + combo.get(i) + " 'a'");
            }
        }

        assertTrue("枚举用例太少，工件已失效: checked=" + checked, checked > 30000);
        // 空串 = 没有漏判；一旦非空，JUnit 会把「漏判[...] <<< 语句」整段打进报告
        assertEquals("SqlGuard 放行、但某方言读法真执行的文本危险", "", join(holes));
    }

    /** 真实只读语料反向检查：不许误拒（防修法过猛把模型日常写法打死） */
    @Test
    public void ordinaryReadOnlyQueriesMustNotBeRejected() {
        String[] ok = {
                "SELECT * FROM users WHERE id = 1",
                "SELECT COUNT(*) FROM orders o JOIN users u ON u.id = o.uid WHERE o.created_at > '2026-01-01' LIMIT 100",
                "WITH recent AS (SELECT * FROM orders LIMIT 10) SELECT * FROM recent",
                "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t) SELECT n FROM t",
                "SELECT /* 提示 */ id FROM t",
                "SELECT /*+ INDEX(t idx) */ id FROM t",
                "SELECT -- 行注释\n id FROM t",
                "SELECT id FROM t -- 尾注释",
                "SELECT id FROM t # mysql 尾注释",
                "SELECT /* 说明; 带分号 */ id FROM t",
                "SELECT 'it''s; ok' AS s",
                "SELECT `col;umn` FROM t",
                "SELECT a FROM t WHERE b = 'FOR UPDATE'",
                "SELECT for_update_at, for_share, lock_in_share_mode_flag FROM t",
                "SELECT information FROM t",
                "SELECT * FROM information_schema.tables WHERE table_schema = 'app'",
                "SELECT JSON_EXTRACT(meta, '$.a;b') FROM t",
                "SELECT a #> '{\"k\":\"v;v\"}' AS j FROM t",
                "SELECT id FROM t LIMIT 10;",
                "SELECT id FROM t LIMIT 10 ;  ",
                "SELECT/*!32340 1*/FROM t",
                "SELECT id FROM t /*!WHERE id=1*/",
                "SELECT id FROM t /*M!100000 WHERE id=1*/",
                "/*!40101 SELECT 1*/",
                "SHOW TABLES",
                "SHOW FULL COLUMNS FROM t",
                "DESC t",
                "EXPLAIN SELECT * FROM t WHERE a = 1",
                "EXPLAIN FORMAT=JSON SELECT * FROM t",
                "EXPLAIN (ANALYZE, BUFFERS) SELECT 1",
                "SELECT count(*) FROM (SELECT id FROM t) x",
                "SELECT * FROM (VALUES (1),(2)) AS t(id)",
                "SELECT begin, end, start FROM t",
                "SELECT 1 /* 外 /* 内 */ 还在外 */",
                "SELECT * FROM t WHERE name LIKE '%\\_%' ESCAPE '\\'",
                "SELECT a, b FROM t ORDER BY a DESC",
                "SELECT 1 UNION SELECT 2",
                "# mysqldump 风格\n/*!40101 SELECT 1*/",
                "SELECT 1;\r\n",
        };
        for (int i = 0; i < ok.length; i++) {
            assertNull("合法只读被误拒: " + esc(ok[i]), SqlGuard.check(ok[i]));
        }
    }

    // ==================== 工具 ====================

    /** 跑一条用例：守卫放行、但某方言真执行的文本危险 → 记为漏判 */
    private static void diff(String sql) {
        checked++;
        if (SqlGuard.check(sql) != null) return;
        for (int d = 0; d < DIALECTS.length; d++) {
            String real = executed(sql, DIALECTS[d], 0);
            if (real == null) continue;
            String why = risk(real);
            if (why != null) {
                holes.add("漏判[" + DIALECTS[d].name + "] " + why + "  <<< " + esc(sql));
                return;
            }
        }
    }

    private static List<String> holes = new ArrayList<String>();

    private static List<String> combos(int max) {
        List<String> out = new ArrayList<String>();
        out.add("");
        List<String> cur = new ArrayList<String>();
        cur.add("");
        for (int len = 1; len <= max; len++) {
            List<String> next = new ArrayList<String>();
            for (int i = 0; i < cur.size(); i++) {
                for (int j = 0; j < ATOM.length; j++) next.add(cur.get(i) + ATOM[j]);
            }
            out.addAll(next);
            cur = next;
        }
        return out;
    }

    /** 把漏判按「危险种类」归并打印：一类一条样例 + 条数，报告才看得出是哪条轴塌了 */
    private static String join(List<String> list) {
        java.util.LinkedHashMap<String, String> sample = new java.util.LinkedHashMap<String, String>();
        java.util.LinkedHashMap<String, Integer> count = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < list.size(); i++) {
            String line = list.get(i);
            int k = line.indexOf("  <<< ");
            String kind = k < 0 ? line : line.substring(0, k);
            Integer c = count.get(kind);
            count.put(kind, c == null ? 1 : c + 1);
            if (!sample.containsKey(kind)) sample.put(kind, k < 0 ? "" : line.substring(k + 6));
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (java.util.Map.Entry<String, String> e : sample.entrySet()) {
            if (n++ == 25) {
                b.append("…共 ").append(sample.size()).append(" 类\n");
                break;
            }
            b.append(e.getKey()).append(" ×").append(count.get(e.getKey()))
                    .append("  例: ").append(e.getValue()).append('\n');
        }
        return b.toString();
    }

    private static String esc(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
