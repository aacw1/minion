package com.minion.core.tools.db;

import org.junit.Test;

import static org.junit.Assert.*;

/** 只读 SQL 校验：白名单首词 / 注释剥离 / 多语句 / 危险子串 */
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
    }

    @Test
    public void stripLeadingCommentsBeforeJudging() {
        assertNull(SqlGuard.check("-- 注释\nSELECT 1"));
        assertNull(SqlGuard.check("# mysql 行注释\nSELECT 1"));
        assertNull(SqlGuard.check("/* 块注释 */ SELECT 1"));
        assertNull(SqlGuard.check("  /*a*//*b*/  -- c\n SELECT 1"));
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

    @Test
    public void allowOptimizerHintCommentAfterKeyword() {
        // 词中块注释（如优化器提示）不应被当成语句首词的一部分
        assertNull(SqlGuard.check("SELECT/*+ INDEX(t) */ id FROM t"));
        assertNull(SqlGuard.check("SELECT/**/1"));
    }

    @Test
    public void rejectUnparsableHeadKeyword() {
        // 无法识别首词时给明确文案，不输出空首词
        assertEquals("只读工具拒绝执行：无法识别语句首关键词", SqlGuard.check("/*unclosed"));
        assertEquals("只读工具拒绝执行：无法识别语句首关键词", SqlGuard.check("(SELECT 1)"));
    }

    @Test
    public void substringMatchIsWordBounded() {
        // "information" 含 "for" 但不是独立词；列名 for_update_at 不应误伤
        assertNull(SqlGuard.check("SELECT information FROM t"));
        assertNull(SqlGuard.check("SELECT for_update_at FROM t"));
        assertNull(SqlGuard.check("SELECT outfile FROM t"));
    }
}
