package com.minion.core.tools.db;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 只读 SQL 校验：白名单首词 / 注释剥离与注释绕过 / 字面量掩码与多语句 / 危险子串。
 *
 * 用例按评审轮次分组，盯的都是同一类问题——「某库真会执行的文本被某项检查看漏」：
 *  - round 1：危险短语被注释切开（FOR + 块注释 + UPDATE）、可执行注释注入语句、字面量里的分号/关键字；
 *  - round 2：一项检查只认一份视图（`#` 与不接空白的 `--` 之后的内容对短语判定失明）；
 *  - round 3：首词只认 stripped 一份，而 stripped 会把可执行注释的内容内联 →
 *    首词能从注释里借（「斜杠星感叹号 SELECT 星斜杠 DROP TABLE t」在 PG/Oracle 里真执行 DROP），
 *    补第三视图 plain（可执行注释按普通注释整段剥掉）后逐视图复检；
 *  - round 4：三条方言轴只覆盖了两条 —— 补第 3 条「块注释配对」轴（Oracle/MySQL 不认嵌套，
 *    在**第一个**星斜杠就结束注释，其后文本被真执行而我们看不见），另修两处同类盲区：
 *    行尾只认换行不认回车，以及「写动词出现在括号处的语句位」没人看
 *    （PG 的改写型 CTE 与 WITH 子句后接 DML，都是先写数据再返回结果集）。
 *    每条洞的对照组一起钉住，避免修法把合法只读查询打死。
 */
public class SqlGuardTest {

    @Test
    public void allowWhitelistFirstKeywords() {
        assertNull(SqlGuard.check("SELECT * FROM t"));
        assertNull(SqlGuard.check("select id from t"));
        assertNull(SqlGuard.check("WITH a AS (SELECT 1) SELECT * FROM a"));
        assertNull(SqlGuard.check("SHOW TABLES"));
        assertNull(SqlGuard.check("DESC t"));
        assertNull(SqlGuard.check("DESCRIBE t"));
        assertNull(SqlGuard.check("EXPLAIN SELECT 1"));
    }

    @Test
    public void allowTrailingSemicolonAndWhitespace() {
        assertNull(SqlGuard.check("  SELECT 1 ;  "));
        assertNull(SqlGuard.check("SELECT 1;\n"));
        assertNull(SqlGuard.check("SELECT 1;\n\n"));
    }

    @Test
    public void rejectWriteStatements() {
        for (String sql : new String[]{"UPDATE t SET a=1", "INSERT INTO t VALUES(1)",
                "DELETE FROM t", "DROP TABLE t", "TRUNCATE TABLE t", "ALTER TABLE t ADD c INT",
                "CREATE TABLE t(a INT)", "CALL p()", "SET autocommit=0", "GRANT ALL ON t TO u"}) {
            String head = sql.split(" ")[0].toUpperCase();
            String why = SqlGuard.check(sql);
            assertNotNull("应拒绝: " + sql, why);
            assertTrue("原因应含首词: " + why, why.contains(head));
        }
    }

    @Test
    public void rejectEmptyAndNull() {
        assertEquals("SQL 不能为空", SqlGuard.check(null));
        assertEquals("SQL 不能为空", SqlGuard.check("   "));
        assertEquals("SQL 不能为空", SqlGuard.check("-- 只有一句注释"));
        assertEquals("SQL 不能为空", SqlGuard.check("/* 也只有注释 */"));
    }

    @Test
    public void stripLeadingCommentsBeforeJudging() {
        assertNull(SqlGuard.check("-- 注释\nSELECT 1"));
        assertNull(SqlGuard.check("# mysql 行注释\nSELECT 1"));
        assertNull(SqlGuard.check("/* 块注释 */ SELECT 1"));
        assertNull(SqlGuard.check("  /*a*//*b*/  -- c\n SELECT 1"));
        assertNull(SqlGuard.check("# a\n-- b\n/* c */\nSELECT 1"));
        // 注释后藏写操作：剥完注释首词是 DROP → 拒
        String why = SqlGuard.check("/*c*/DROP TABLE t");
        assertNotNull(why);
        assertTrue(why.contains("DROP"));
        assertNotNull(SqlGuard.check("--x\nDELETE FROM t"));
    }

    @Test
    public void rejectMultipleStatements() {
        String why = SqlGuard.check("SELECT 1; DROP TABLE t");
        assertNotNull(why);
        assertEquals("只读工具拒绝执行：不允许多条语句", why);
        assertNotNull(SqlGuard.check("SELECT 1;;"));
        assertNotNull(SqlGuard.check("SELECT 1\n;\nDROP TABLE t"));
        assertNotNull(SqlGuard.check("SELECT 1 /*!a*/; SELECT 2"));
    }

    @Test
    public void rejectDangerousSubstrings() {
        assertEquals("只读工具拒绝执行：语句含 INTO OUTFILE",
                SqlGuard.check("SELECT * FROM t INTO OUTFILE '/tmp/x'"));
        assertEquals("只读工具拒绝执行：语句含 INTO DUMPFILE",
                SqlGuard.check("SELECT 0x31 INTO DUMPFILE '/tmp/x'"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT * FROM t WHERE id=1 for update"));
        assertEquals("只读工具拒绝执行：语句含 LOCK IN SHARE MODE",
                SqlGuard.check("SELECT * FROM t LOCK IN SHARE MODE"));
    }

    @Test
    public void rejectKeywordPhraseSplitByNewlineOrWideGap() {
        // MySQL 关键字之间允许任意空白（含换行），漏判即漏防
        assertNotNull(SqlGuard.check("SELECT * FROM t INTO   OUTFILE '/tmp/x'"));
        assertNotNull(SqlGuard.check("SELECT * FROM t\nFOR\nUPDATE"));
        assertNotNull(SqlGuard.check("SELECT * FROM t LOCK\n IN  SHARE\tMODE"));
    }

    // ===================== round 1 修正 A：注释绕过 =====================

    @Test
    public void rejectDangerousPhraseSplitByComment() {
        String[] sqls = {
                "SELECT * FROM t FOR/**/UPDATE",
                "SELECT * FROM t FOR /* c */ UPDATE",
                "SELECT * FROM t INTO/*x*/OUTFILE '/tmp/x'",
                "SELECT * FROM t LOCK/*a*/IN/*b*/SHARE/*c*/MODE",
                "SELECT * FROM t FOR--c\nUPDATE",
                "SELECT * FROM t FOR# c\nUPDATE",
                "SELECT * FROM t INTO/*c*/OUTFILE'/tmp/x'"};
        for (String sql : sqls) {
            assertNotNull("注释切开危险短语应拒: " + sql, SqlGuard.check(sql));
        }
    }

    @Test
    public void rejectWriteHeadBrokenByComment() {
        // 词中被注释切开只会更严：SEL / DR 都不是白名单首词
        assertNotNull(SqlGuard.check("SEL/**/ECT * FROM t"));
        assertNotNull(SqlGuard.check("DR/**/OP TABLE t"));
    }

    @Test
    public void rejectStatementInjectedViaExecutableComment() {
        String[] sqls = {
                "SELECT 1 /*!DROP TABLE t*/",
                "SELECT 1 /*!;DROP TABLE t*/",
                "SELECT 1 /*!50000 DELETE FROM t*/",
                "SELECT 1 /*M!100000 DROP TABLE t*/",
                "SELECT * FROM t /*!FOR UPDATE*/",
                "SHOW TABLES /*!DROP TABLE t*/",
                "SELECT 1 /*!SET GLOBAL read_only=0*/",
                "SELECT 1 /*!;*/ FROM t",
                "SELECT 1 /*!/*!/*!/*!/*!DROP TABLE t*/*/*/*/*/"};
        for (String sql : sqls) {
            assertNotNull("可执行注释注入应拒: " + sql, SqlGuard.check(sql));
        }
    }

    @Test
    public void allowVersionGatedExecutableCommentFragment() {
        // 版本号门控的注释片段是合法写法（内容按代码展开后首词不是写动词）
        assertNull(SqlGuard.check("SELECT/*!32340 1*/FROM t"));
        assertNull(SqlGuard.check("SELECT id FROM t /*!WHERE id=1*/"));
        assertNull(SqlGuard.check("SELECT id FROM t /*M!100000 WHERE id=1*/"));
    }

    @Test
    public void allowOptimizerHintCommentAfterKeyword() {
        // 词中块注释（如优化器提示）不应被当成语句首词的一部分
        assertNull(SqlGuard.check("SELECT/*+ INDEX(t) */ id FROM t"));
        assertNull(SqlGuard.check("SELECT/**/1"));
    }

    @Test
    public void rejectUnparsableSql() {
        // 解析不了就不可判定：未闭合注释/引号、注释嵌套过深一律拒，不猜
        assertEquals("只读工具拒绝执行：注释未闭合，无法安全解析", SqlGuard.check("/*unclosed"));
        assertEquals("只读工具拒绝执行：注释未闭合，无法安全解析", SqlGuard.check("/*"));
        assertEquals("只读工具拒绝执行：引号未闭合，无法安全解析", SqlGuard.check("SELECT 'abc"));
        assertEquals("只读工具拒绝执行：无法识别语句首关键词", SqlGuard.check("(SELECT 1)"));
    }

    // ===================== round 1 修正 B：字面量里的分号与关键字 =====================

    @Test
    public void allowSemicolonAndKeywordsInsideLiterals() {
        String[] ok = {
                "SELECT ';' AS a",
                "SELECT 'a;b' AS a",
                "SELECT 'for update' AS a",
                "SELECT \"a;b\" AS a",
                "SELECT `a;b` FROM t",
                "SELECT '' AS a",
                "SELECT 'it''s; fine' AS a",
                "SELECT 'DROP TABLE t;' AS a",
                "SELECT 'a; b' ; -- 尾注释",
                "SELECT'x' FROM t",
                "SELECT a FROM t WHERE q = 'x; y' AND b = 'FOR UPDATE'"};
        for (String sql : ok) {
            assertNull("字面量内部不该拦: " + sql, SqlGuard.check(sql));
        }
    }

    @Test
    public void rejectSemicolonOutsideLiterals() {
        assertNotNull(SqlGuard.check("SELECT ';' ; DROP TABLE t"));
        assertNotNull(SqlGuard.check("SELECT 'a' ; SELECT 'b'"));
        assertNotNull(SqlGuard.check("SELECT 'a; DROP TABLE t"));
        // 反斜杠不按转义解析：MySQL 里 '\' 之后的分号是真实分隔符，必须拒
        assertNotNull(SqlGuard.check("SELECT '\\' ; DROP TABLE t"));
    }

    @Test
    public void allowSemicolonInsideTrailingComment() {
        assertNull(SqlGuard.check("SELECT 1 ; -- 说明; 带分号"));
        assertNull(SqlGuard.check("SELECT 1; /* 说明 */"));
        assertNull(SqlGuard.check("SELECT 1 /* 说明 */ ; -- 又一句"));
        assertNull(SqlGuard.check("SELECT 1 /* 内部有; 分号 */"));
        assertNull(SqlGuard.check("SELECT a /* 说明; 更多 */ FROM t"));
        assertNull(SqlGuard.check("SELECT a -- 说明; 更多\nFROM t"));
        assertNull(SqlGuard.check("SELECT 1 # mysql 注释，无分号"));
    }

    @Test
    public void commentMustNotHideSemicolonFromOtherDialects() {
        // # 只是 MySQL 注释，PG 里是运算符（jsonb 的 #>）：剥了它就等于替 PG 藏住后面的分号
        assertNotNull(SqlGuard.check("SELECT x #> '{a}'; DROP TABLE t"));
        assertNotNull(SqlGuard.check("SELECT x #> '{a}' ; SELECT 1"));
        // 双连字符在 MySQL/PG 要求后接空白，不接空白时不算注释，同样不能藏分号
        assertNotNull(SqlGuard.check("SELECT 1--2; DROP TABLE t"));
        // 代价：这两类注释里写分号会被误拒（判不出方言，宁可误拒）
        assertNotNull(SqlGuard.check("SELECT 1 # 注释里带; 也算多条"));
    }

    @Test
    public void allowOtherDialectOperatorBeforeSemicolon() {
        // 运算符本身不受影响：没有第二条语句就照常放行
        assertNull(SqlGuard.check("SELECT a #> '{\"k\":1}' AS x FROM t"));
        assertNull(SqlGuard.check("SELECT a #> '{\"k\":1}' AS x FROM t;"));
        assertNull(SqlGuard.check("SELECT 1--2"));
    }

    @Test
    public void substringMatchIsWordBounded() {
        // "information" 含 "for" 但不是独立词；列名 for_update_at 不应误伤
        assertNull(SqlGuard.check("SELECT information FROM t"));
        assertNull(SqlGuard.check("SELECT for_update_at FROM t"));
        assertNull(SqlGuard.check("SELECT outfile FROM t"));
    }

    // ============ round 2 必修：--/# 注释不变式（内容级检查在每份视图上各跑一遍） ============

    @Test
    public void commentMarkedOnlyBySomeDialectMustNotHideDangerousPhrase() {
        // # 只 MySQL 认、不接空白的 -- 只 PG/Oracle 认：这些片段对另一些库就是代码，
        // 短语检查不能只在「剥掉它」的那份视图上做，否则等于替真执行的一方藏住了锁/写文件。
        assertEquals("只读工具拒绝执行：语句含 INTO OUTFILE",
                SqlGuard.check("SELECT 1--1 INTO OUTFILE '/tmp/poc.txt'"));
        assertEquals("只读工具拒绝执行：语句含 INTO DUMPFILE",
                SqlGuard.check("SELECT 1--1 INTO DUMPFILE '/tmp/poc.txt'"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT * FROM t WHERE id=1--1 FOR UPDATE"));
        assertEquals("只读工具拒绝执行：语句含 LOCK IN SHARE MODE",
                SqlGuard.check("SELECT * FROM t WHERE id=1--1 LOCK IN SHARE MODE"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT id FROM t WHERE id#1=1 FOR UPDATE"));
        assertEquals("只读工具拒绝执行：语句含 INTO OUTFILE",
                SqlGuard.check("SELECT # 'x' a, 2 INTO OUTFILE '/tmp/poc.txt' FROM t"));
        // 对照组：三库都认的注释（-- 后接空白、块注释）里出现同样文本仍然放行
        assertNull(SqlGuard.check("SELECT 1 -- 1 INTO OUTFILE '/tmp/poc.txt'"));
        assertNull(SqlGuard.check("SELECT 1 /* 1 INTO OUTFILE '/tmp/poc.txt' */"));
        // 「后接空白」只认 ASCII 空白：U+2000 被 Character.isWhitespace 当成空白但 MySQL 不认，宽松判定等于替 MySQL 藏住整行
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT 1--" + (char) 0x2000 + "FOR UPDATE"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT 1--" + (char) 0x00a0 + "FOR UPDATE"));
    }

    @Test
    public void bothViewsMustStillCatchPhraseSplitByComment() {
        // 反向：注释把短语切开时，靠「剥注释」那份视图命中——几份视图缺一不可
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT * FROM t FOR--c\nUPDATE"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT * FROM t FOR# c\nUPDATE"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT * FROM t FOR/**/UPDATE"));
    }

    @Test
    public void maskLiteralsInsideDialectCommentForSemicolonView() {
        // 存疑注释按代码看时，其内部字面量同样要掩码：PG 的 #> 运算符后面是分号判定重灾区
        assertNull(SqlGuard.check("SELECT a #> '{\"k\":\"x;y\"}' AS v FROM t"));
        assertNull(SqlGuard.check("SELECT a #>> '{k;}' AS v FROM t"));
        assertNotNull(SqlGuard.check("SELECT a #> '{k}' ; SELECT b"));
        // 存疑注释里的未闭合引号不按「无法解析」处理（那份文本只是更保守的副本）
        assertNull(SqlGuard.check("SELECT 1--2 说明 don't"));
    }

    @Test
    public void keepLeadingCommentLinesStrippedInHeadView() {
        // 首关键词判定只能跑在剥注释的视图上：行首 # 注释在 PG 里必然成语法错误，剥掉不藏任何东西
        assertNull(SqlGuard.check("# mysqldump 风格注释\nSELECT 1"));
        assertNull(SqlGuard.check("# a\n# b\n-- c\n/* d */\nSELECT 1"));
        assertNotNull(SqlGuard.check("# x\nDROP TABLE t"));
    }

    @Test
    public void rejectUnclosedConstructsButNotThoseDeadAtLineEnd() {
        // 存疑注释（# / 不接空白的 --）里的未闭合引号与块注释：行尾就结束，任何一库都执行不到，不该报「无法解析」
        assertNull(SqlGuard.check("SELECT 1--2 /* x"));
        assertNull(SqlGuard.check("SELECT 1 # 说明 don't 这样写"));
        // 但字面量外的真未闭合块注释/引号仍然一律拒（判不了就拒）
        assertEquals("只读工具拒绝执行：注释未闭合，无法安全解析", SqlGuard.check("SELECT 1 /* x"));
        assertEquals("只读工具拒绝执行：引号未闭合，无法安全解析", SqlGuard.check("SELECT 'abc"));
    }

    // ================== round 2 建议 1：可执行注释前缀不设字母数上限 ==================

    @Test
    public void rejectExecutableCommentWithLongLetterPrefix() {
        // /*MARIADB!…*/ 一类长前缀版本注释不能被「最多三个字母」放过
        assertEquals("只读工具拒绝执行：可执行注释内含 DROP 语句",
                SqlGuard.check("SELECT 1 /*MARIADB!DROP TABLE t*/"));
        assertEquals("只读工具拒绝执行：可执行注释内含 UPDATE 语句",
                SqlGuard.check("SELECT 1 /*MYSQLV8!UPDATE t SET a=1*/"));
        // 普通块注释里带感叹号不受影响
        assertNull(SqlGuard.check("SELECT 1 /* 注意! 这里只是说明 */ FROM t"));
    }

    // ============ round 2 建议 2：EXPLAIN 白名单首词的第二执行通道（PG EXPLAIN ANALYZE 真执行） ============

    @Test
    public void rejectWriteVerbAfterExplain() {
        for (String sql : new String[]{"EXPLAIN ANALYZE DELETE FROM t",
                "EXPLAIN ANALYZE INSERT INTO t VALUES(1)",
                "EXPLAIN (ANALYZE, COSTS FALSE) DELETE FROM t",
                "EXPLAIN PLAN FOR DELETE FROM t",
                "EXPLAIN UPDATE t SET a=1"}) {
            String why = SqlGuard.check(sql);
            assertNotNull("EXPLAIN 后面跟写动词应拒: " + sql, why);
            assertTrue("原因应说明 EXPLAIN: " + why, why.startsWith("只读工具拒绝执行：EXPLAIN"));
        }
        assertNull(SqlGuard.check("EXPLAIN ANALYZE SELECT 1"));
        assertNull(SqlGuard.check("EXPLAIN SELECT * FROM t WHERE a=1"));
        assertNull(SqlGuard.check("EXPLAIN WITH a AS (SELECT 1) SELECT * FROM a"));
        assertNull(SqlGuard.check("EXPLAIN FORMAT=JSON SELECT 1"));
    }

    // ============ round 2 建议 3：SHOW/DESC 等同理——写动词出现在任何视图的可执行位置都要命中 ============

    @Test
    public void invariantNoCheckReadsOnlyOneView() {
        // 同一句载荷换个「方言存疑注释」写法，结果必须一致（都拒）：内容级检查每份视图都跑
        String payload = " , 2 INTO OUTFILE '/tmp/poc.txt' FROM t";
        for (String hidden : new String[]{"--1", "#1", "# 1", "/*1*/", "/* 1 */"}) {
            String sql = "SELECT 1" + hidden + payload;
            assertNotNull("藏进 " + hidden + " 也应拒: " + sql, SqlGuard.check(sql));
        }
        // 只有「三库都当注释」的写法才可以把载荷剥掉（-- 后接空白：没有一库会执行它）
        assertNull(SqlGuard.check("SELECT 1" + "-- 1" + payload));
    }

    // ===== round 3 必修：第三视图 plain —— 首词不许从可执行注释的内容里「借」 =====

    @Test
    public void headMustNotBeBorrowedFromExecutableCommentBody() {
        // /*!…*/ 只有 MySQL/MariaDB 执行其内容，在 PG/Oracle 里就是普通块注释：
        // 首词一旦是从注释内容里借来的（SELECT），注释一失效，真被执行的首词就是它后面的写动词。
        String[] sqls = {"/*!SELECT*/DROP TABLE t", "/*!SELECT*/DELETE FROM t",
                "/*M!1SELECT*/DROP TABLE t", "/*MARIADB!SELECT*/DROP TABLE t",
                "/*!SELECT*/SET GLOBAL read_only=0", "/*!SHOW*/TRUNCATE TABLE t",
                "/*!EXPLAIN*/DROP TABLE t", "/*!SELECT*/DROP TABLE t;",
                "/*!SELECT*/CALL p()", "/*!WITH*/ALTER TABLE t ADD c INT"};
        for (String sql : sqls) {
            String why = SqlGuard.check(sql);
            assertNotNull("首词不许从可执行注释里借: " + sql, why);
            assertTrue("原因要点出「剥掉可执行注释后」的真首词: " + why, why.contains("可执行注释"));
        }
        // 对照组 1：整句都是可执行注释 —— plain 读法下没有任何语句，不该因此打死
        assertNull(SqlGuard.check("/*!40000 SELECT * FROM t*/"));
        // 拒绝文案要点破「首词不是你写的那个」，否则模型会当成误拒
        assertEquals("只读工具拒绝执行：把可执行注释当普通注释看时，语句以 DROP 开头"
                        + "（仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN）",
                SqlGuard.check("/*!SELECT*/DROP TABLE t"));
        // 对照组 2：可执行注释在句中/词后，剥掉它仍得到白名单首词
        assertNull(SqlGuard.check("SELECT /*!80000 SHOW CREATE TABLE t*/ FROM dual"));
        assertNull(SqlGuard.check("SELECT/*!32340 1*/FROM t"));
        assertNull(SqlGuard.check("# mysqldump 风格\n/*!40101 SELECT 1*/"));
    }

    @Test
    public void executableCommentMustNotSplitDangerousPhrase() {
        // 内联进来的注释内容会把短语撑开：PG/Oracle 里 /*!x*/ 就是普通注释，这句的真身是 FOR UPDATE（锁行）
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT * FROM t FOR/*!x*/UPDATE"));
        assertEquals("只读工具拒绝执行：语句含 INTO OUTFILE",
                SqlGuard.check("SELECT * FROM t INTO/*!x*/OUTFILE '/tmp/poc.txt'"));
        assertEquals("只读工具拒绝执行：语句含 LOCK IN SHARE MODE",
                SqlGuard.check("SELECT * FROM t LOCK/*!x*/IN SHARE MODE"));
        // 空内容的可执行注释本来就命中（回归保护）
        assertNotNull(SqlGuard.check("SELECT * FROM t FOR/*!*/UPDATE"));
        // 首词从注释里借来的恰好也是白名单词（SELECT）时，靠 plain 喂给短语检查兜住：
        // 这句在 PG/Oracle 里就是 SELECT 1 INTO OUTFILE …
        assertEquals("只读工具拒绝执行：语句含 INTO OUTFILE",
                SqlGuard.check("/*!SELECT*/SELECT 1 INTO OUTFILE '/tmp/poc.txt'"));
    }

    @Test
    public void execSegmentMustPassEveryReadingToo() {
        // 片段自身也有「内层 /*!…*/ 其实不生效」的读法：内联让首词变成 x，把内层当注释剥掉才露出 DROP
        assertEquals("只读工具拒绝执行：可执行注释内含 DROP 语句",
                SqlGuard.check("SELECT 1 /*!/*M!1x*/DROP TABLE t*/"));
        // 片段自己的视图也要过内容级检查：MySQL 执行外层、内层当普通注释 → FOR UPDATE
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT 1 /*!FOR/*M!1c*/UPDATE*/"));
    }

    @Test
    public void explainTargetMustPassEveryReading() {
        // EXPLAIN 之后的第一个实词在 plain 读法里才是真身：/*!x*/ 一失效就是 EXPLAIN ANALYZE DELETE
        String why = SqlGuard.check("EXPLAIN /*!x*/ANALYZE DELETE FROM t");
        assertNotNull("EXPLAIN 的目标在 plain 读法里是写动词应拒", why);
        assertTrue("原因应说明 EXPLAIN: " + why, why.startsWith("只读工具拒绝执行：EXPLAIN"));
        assertNotNull(SqlGuard.check("EXPLAIN /*!SELECT*/DELETE FROM t"));
        assertNull(SqlGuard.check("EXPLAIN /*!80000 ANALYZE*/ SELECT 1"));
    }

    // ===== round 4 必修 A：WITH 起手的第二条写通道（改写型 CTE 与 WITH 后接 DML） =====

    @Test
    public void rejectDmlAfterWithClause() {
        // WITH 在白名单里，但 PG/MySQL 8 允许 WITH 子句后面直接跟 DML，先写数据再返回结果集，
        // executeQuery 拿得到 ResultSet 也就挡不住 —— 只能靠「括号后的语句位」这项检查拦下
        String[] sqls = {
                "WITH a AS (SELECT 1) DELETE FROM t WHERE id=1",
                "WITH a AS (SELECT 1) UPDATE t SET a=1",
                "WITH a AS (SELECT 1) INSERT INTO t SELECT * FROM a",
                "WITH RECURSIVE a(n) AS (SELECT 1) DELETE FROM t",
                "WITH a AS (SELECT 1), b AS (SELECT 2) REPLACE INTO t SELECT * FROM a",
                "WITH a AS (SELECT 1) MERGE INTO t USING a ON (1=1) WHEN MATCHED THEN UPDATE SET c=1",
                // 藏在可执行注释 / 存疑注释 / 回车行尾后面的同一条载荷
                "SELECT 1 /*!WITH x AS (SELECT 1) DELETE FROM t*/",
                "SELECT 1--x\rWITH x AS (DELETE FROM t) SELECT * FROM x",
                "WITH x AS (SELECT 1) DELETE /*!y*/ FROM t"};
        for (String sql : sqls) {
            String why = SqlGuard.check(sql);
            assertNotNull("WITH 后接 DML 应拒: " + esc(sql), why);
            assertTrue("原因要点出写动词: " + why, why.contains("写动词"));
        }
        assertEquals("只读工具拒绝执行：括号后的语句位出现写动词 DELETE"
                        + "（PG 的改写型 CTE、WITH 子句后接 DML 会真写数据）",
                SqlGuard.check("WITH a AS (SELECT 1) DELETE FROM t WHERE id=1"));
    }

    @Test
    public void rejectDataModifyingCteBody() {
        // PG 的改写型 CTE：正文在左括号之后，执行发生在返回结果集之前
        String[] sqls = {
                "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x",
                "WITH x AS (UPDATE t SET a=1 RETURNING id) SELECT * FROM x",
                "WITH x AS (INSERT INTO t VALUES (1) RETURNING id) SELECT * FROM x",
                "WITH x AS (WITH y AS (DELETE FROM t RETURNING id) SELECT * FROM y) SELECT * FROM x",
                // 藏在「不认嵌套」的块注释配对后面（round 4 必修 C 与本项的交叉）
                "WITH x AS (SELECT 1) /*a/*!*/ DELETE FROM t */"};
        for (String sql : sqls) {
            String why = SqlGuard.check(sql);
            assertNotNull("改写型 CTE 应拒: " + esc(sql), why);
            assertTrue("原因要点出写动词: " + why, why.contains("DELETE") || why.contains("UPDATE")
                    || why.contains("INSERT"));
        }
    }

    @Test
    public void explainBeforeWithDmlIsAlsoRejected() {
        // EXPLAIN 那条检查只看到 WITH 就停了（WITH 不是写动词），第二条通道由括号语句位补上
        assertNotNull(SqlGuard.check("EXPLAIN WITH a AS (SELECT 1) DELETE FROM t"));
        assertNotNull(SqlGuard.check("EXPLAIN (ANALYZE) WITH a AS (SELECT 1) UPDATE t SET a=1"));
        assertNull("EXPLAIN + 只读 CTE 仍放行", SqlGuard.check("EXPLAIN WITH a AS (SELECT 1) SELECT * FROM a"));
    }

    @Test
    public void allowReadOnlyWithChainAndParenForms() {
        // 对照组：合法写法绝不能被第 7 项检查打死（括号处出现 SELECT/别名/数字/掩码字面量）
        String[] ok = {
                "WITH a AS (SELECT 1) SELECT * FROM a",
                "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM t) SELECT n FROM t",
                "WITH t(n) AS (VALUES (1),(2)) SELECT n FROM t",
                "SELECT * FROM (VALUES (1),(2)) AS t(id)",
                "SELECT count(*) FROM (SELECT id FROM t) x WHERE x.id > (SELECT min(id) FROM t)",
                "SELECT f(1) AS a, g(2) AS b FROM t",
                "SELECT * FROM TABLE(my_list)",
                "SELECT begin, end, start FROM t",
                "EXPLAIN (ANALYZE, COSTS FALSE) SELECT 1",
                "WITH a AS NOT MATERIALIZED (SELECT 1) SELECT * FROM a"};
        for (String sql : ok) {
            assertNull("合法只读被误拒: " + esc(sql), SqlGuard.check(sql));
        }
    }

    // ===== 线上实证回归：DML 词做函数名（REPLACE/INSERT）不得被误拒 =====

    @Test
    public void allowFunctionCallsNamedLikeDml() {
        // 线上实证（PostgreSQL）：SELECT max(replace(name,'x','y'))、COALESCE(replace(...),'') 等
        // 嵌套函数调用被第 7 项「括号语句位」判成写语句误拒。词后紧跟左括号 = 函数调用，非语句位。
        String[] ok = {
                "SELECT replace(name, 'a', 'b') FROM t",
                "SELECT REPLACE(name, 'a', '') FROM t",
                "SELECT max(replace(name, 'x', 'y')) FROM t",
                "SELECT COALESCE(replace(name, 'a', ''), 'x') FROM t",
                "SELECT (replace(name, 'a', 'b')) FROM t",
                "SELECT to_char(replace(name, 'a', 'b')) FROM t",
                "SELECT replace (name, 'a', 'b') FROM t",
                "SELECT insert(name, 1, 0, 'x') FROM t",
                "SELECT * FROM t WHERE replace(a, 'x', 'y') = replace(b, 'x', 'y')",
                "WITH x AS (SELECT replace(a, '1', '2') FROM t) SELECT * FROM x"};
        for (String sql : ok) {
            assertNull("函数调用被误判为写语句: " + esc(sql), SqlGuard.check(sql));
        }
    }

    @Test
    public void rejectDmlVerbNotFollowedByParen() {
        // 例外只认「词后紧跟左括号」：语句位上的 DML 后接 INTO/表名，照旧拒
        String why = SqlGuard.check("WITH a AS (SELECT 1) REPLACE INTO t SELECT * FROM a");
        assertNotNull("WITH 后接 REPLACE INTO 应拒", why);
        assertTrue("原因要点出写动词 REPLACE: " + why, why.contains("写动词 REPLACE"));
        assertNotNull(SqlGuard.check("REPLACE INTO t VALUES (1)"));
        assertNull("对照组：同位置换成函数调用放行",
                SqlGuard.check("WITH a AS (SELECT 1) SELECT replace(x, 'a', 'b') FROM a"));
    }

    // ===== round 4 必修 B：锁短语要按各家方言的锁强度配齐 =====

    @Test
    public void rejectEveryLockStrengthPhrase() {
        // PG 四种锁强度 + MySQL 8 的 FOR SHARE；只拦 FOR UPDATE 等于漏掉另外三种
        assertEquals("只读工具拒绝执行：语句含 FOR SHARE",
                SqlGuard.check("SELECT * FROM t FOR SHARE"));
        assertEquals("只读工具拒绝执行：语句含 FOR NO KEY UPDATE",
                SqlGuard.check("SELECT * FROM t FOR NO KEY UPDATE"));
        assertEquals("只读工具拒绝执行：语句含 FOR KEY SHARE",
                SqlGuard.check("SELECT * FROM t FOR KEY SHARE"));
        assertEquals("只读工具拒绝执行：语句含 FOR SHARE",
                SqlGuard.check("SELECT * FROM t FOR SHARE OF t NOWAIT"));
        assertEquals("只读工具拒绝执行：语句含 FOR NO KEY UPDATE",
                SqlGuard.check("SELECT * FROM t FOR/*a/*b*/NO KEY UPDATE */"));
        assertEquals("只读工具拒绝执行：语句含 FOR SHARE",
                SqlGuard.check("SELECT * FROM t FOR/*!x*/SHARE"));
        assertEquals("只读工具拒绝执行：语句含 FOR SHARE",
                SqlGuard.check("WITH x AS (SELECT 1) SELECT * FROM x FOR SHARE"));
    }

    @Test
    public void allowLookAlikesOfLockPhrases() {
        // 整词边界 + 字面量掩码：含 for/share/key 的列名与字符串不受影响
        String[] ok = {
                "SELECT for_share, key_share, no_key_update FROM t",
                "SELECT share FROM t",
                "SELECT * FROM t WHERE note = 'for share'",
                "SELECT * FROM t WHERE note = 'for no key update'",
                "SELECT * FROM t /* 说明 FOR SHARE 是锁 */",
                "SELECT * FROM t -- 说明 FOR SHARE 是锁\n",
                "SELECT a.key, a.share FROM t a"};
        for (String sql : ok) {
            assertNull("像锁短语但不是的写法被误拒: " + esc(sql), SqlGuard.check(sql));
        }
    }

    // ===== round 4 必修 C：块注释配对轴（Oracle/MySQL 不认嵌套，按首个星斜杠收尾） =====

    @Test
    public void blockCommentPairingMustNotHidePayload() {
        // 认嵌套的读法把整段当注释，不认嵌套的读法在**第一个**星斜杠就结束注释、其后是真执行的代码
        String[] sqls = {
                "SELECT 1 /*a/*!*/ INTO OUTFILE '/tmp/x' */",
                "SELECT 1 /*a/*b*/ INTO OUTFILE '/tmp/x' */",
                "SELECT * FROM t FOR/*a/*!*/UPDATE */",
                "SELECT * FROM t LOCK/*a/*!*/IN SHARE MODE /*b*/ -- c */",
                "SELECT 1 /*a/*!*/ ; DROP TABLE t /*b*/ -- c */",
                "EXPLAIN /*a/*!*/ DROP TABLE t */",
                "SELECT * FROM t /*!FOR*/ /*a /*b*/ UPDATE */ # x\n",
                "/*a /*!b*/ DROP TABLE t */ SELECT 1"};
        for (String sql : sqls) {
            assertNotNull("块注释不认嵌套的读法被漏掉: " + esc(sql), SqlGuard.check(sql));
        }
        // 载荷在「剥掉可执行注释」那份读法里才露形：文案要点破首词不是模型写的那个
        assertEquals("只读工具拒绝执行：把块注释按「不认嵌套」的方言（Oracle/MySQL）看时，"
                        + "语句以 DROP 开头（仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN）",
                SqlGuard.check("/*a /*!b*/ DROP TABLE t */ SELECT 1"));
    }

    @Test
    public void allowNestedBlockCommentWithoutPayload() {
        // 对照组：可嵌套注释本身是 PG 的合法写法，尾巴里没有危险内容时不能打死
        String[] ok = {
                "SELECT 1 /* 外 /* 内 */ 还在外 */",
                "SELECT /* 外 /* 内 */ 还在外 */ 1 FROM t",
                "SELECT 1 FROM t /* a /* b */ c */",
                "SELECT 1 /* 说明 /* 引号 ' 分号 ; for update 都在注释里 */ 结束 */"};
        for (String sql : ok) {
            assertNull("可嵌套注释的合法写法被误拒: " + esc(sql), SqlGuard.check(sql));
        }
        // 已知代价：尾巴里的分号在 MySQL/Oracle 侧确实是第二条语句，判不出方言就宁误拒
        assertNotNull(SqlGuard.check("SELECT 1 /* a /* b */ ; c */"));
    }

    @Test
    public void unpairableCommentInFragmentOnlyKillsThatReading() {
        // 「按首个星斜杠收尾」暴露出来的片段正文可能自带配不上的斜杠星：那只是**那一份读法**执行不过去，
        // 不能因此把整句判成「无法安全解析」——另一条配对轴的视图仍要照常复检
        assertEquals("只读工具拒绝执行：可执行注释内含 DROP 语句",
                SqlGuard.check("SELECT 1 /*!/*M!1x*/DROP TABLE t*/"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT 1 /*!FOR/*M!1c*/UPDATE*/"));
        // 根级未闭合仍然一律拒（判不了就拒，这条不许被上面的规则带跑）
        assertEquals("只读工具拒绝执行：注释未闭合，无法安全解析", SqlGuard.check("SELECT 1 /* x"));
    }

    // ===== round 4 必修 D：行尾不止换行，回车也算 =====

    @Test
    public void lineCommentEndsAtCarriageReturn() {
        // MySQL/PG/Oracle 都在 CR 处结束 --/# 注释：只认 \n 等于让「回车后面那条语句」整行隐身
        assertNotNull(SqlGuard.check("SELECT 1-- x\r; DROP TABLE t"));
        assertNotNull(SqlGuard.check("SELECT 1 -- x\r; DROP TABLE t"));
        assertEquals("只读工具拒绝执行：语句含 FOR UPDATE",
                SqlGuard.check("SELECT 1 -- x\rFOR UPDATE"));
        // CRLF 与纯 LF 的合法写法不受影响
        assertNull(SqlGuard.check("SELECT id FROM t LIMIT 10;\r\n"));
        assertNull(SqlGuard.check("SELECT 1 -- 说明\r\nFROM t"));
        assertNull(SqlGuard.check("SELECT 1 /* 说明 */\r\nFROM t"));
    }

    // ===== round 4 不变式：三条轴任意组合后的同一载荷，判定结果必须一致 =====

    @Test
    public void invariantEveryAxisCombinationSeesTheSamePayload() {
        // 同一条载荷：藏进行内存疑注释 / 可执行注释 / 嵌套块注释 / 三者交叉，都得拒
        String payload = " INTO OUTFILE '/tmp/poc.txt'";
        String[] hidings = {"--1", "#1", "/*!x*/", "/*a/*b*/", "/*a/*!*/", "/*!/*M!1c*/"};
        for (String hidden : hidings) {
            String sql = "SELECT 1 " + hidden + payload + " */";
            assertNotNull("载荷藏进 " + hidden + " 也应拒: " + esc(sql), SqlGuard.check(sql));
        }
        // 只有「三库都当注释、且两种配对下都是注释」的写法才可以把它剥掉
        assertNull(SqlGuard.check("SELECT 1 -- 1" + payload));
        assertNull(SqlGuard.check("SELECT 1 /* 1" + payload + " */"));
    }

    private static String esc(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r");
    }
}
