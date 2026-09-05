package com.minion.core.tools.db;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 只读 SQL 校验：白名单首词 / 注释剥离与注释绕过 / 字面量掩码与多语句 / 危险子串。
 *
 * 两组用例专门盯 round 1 评审指出的两类问题：
 *  - 注释绕过：危险短语被注释切开（FOR + 块注释 + UPDATE）、可执行注释注入语句；
 *  - 字面量分号：字符串里的分号被当多语句、字符串里的 for update 被当锁语句。
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

    // ============ round 2 必修：--/# 注释不变式（内容级检查在两份视图上各跑一遍） ============

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
        // 反向：注释把短语切开时，靠「剥注释」那份视图命中——两份视图缺一不可
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
        // 同一句载荷换个「方言存疑注释」写法，结果必须一致（都拒）：内容级检查两份视图都跑
        String payload = " , 2 INTO OUTFILE '/tmp/poc.txt' FROM t";
        for (String hidden : new String[]{"--1", "#1", "# 1", "/*1*/", "/* 1 */"}) {
            String sql = "SELECT 1" + hidden + payload;
            assertNotNull("藏进 " + hidden + " 也应拒: " + sql, SqlGuard.check(sql));
        }
        // 只有「三库都当注释」的写法才可以把载荷剥掉（-- 后接空白：没有一库会执行它）
        assertNull(SqlGuard.check("SELECT 1" + "-- 1" + payload));
    }
}
