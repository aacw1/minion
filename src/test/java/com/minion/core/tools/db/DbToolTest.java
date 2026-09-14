package com.minion.core.tools.db;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.plugin.DbConfig;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * DbTool 参数分派与文案（不触达 JDBC）：
 * query 分支只测校验拦截；PG 的 schema/describe 必须在建连接之前返回固定提示。
 */
public class DbToolTest {

    private static DbConfig emptyConfig() { return new DbConfig(); }

    private static DbConfig withUnreachable() {
        DbConfig c = new DbConfig();
        // 127.0.0.1:1 不可达：若代码真的去建连接，本测试会卡到 loginTimeout（10s）而暴露问题
        c.dataSources.add(new DataSourceConfig("bad", "jdbc:postgresql://127.0.0.1:1/db", "u", "p"));
        c.current = "bad";
        return c;
    }

    private static JsonObject json(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    @Test
    public void toolNamesFollowDbType() {
        assertEquals("DbMysql", new DbTool(DbType.MYSQL, emptyConfig(), (java.nio.file.Path) null).name());
        assertEquals("DbPostgres", new DbTool(DbType.POSTGRES, emptyConfig(), (java.nio.file.Path) null).name());
        assertEquals("DbOracle", new DbTool(DbType.ORACLE, emptyConfig(), (java.nio.file.Path) null).name());
    }

    @Test
    public void queryIsNeverHighRisk() {
        DbTool t = new DbTool(DbType.MYSQL, emptyConfig(), (java.nio.file.Path) null);
        assertFalse(t.isHighRisk(json("action", "query", "sql", "SELECT 1")));
    }

    @Test
    public void descriptionShowsCurrentDataSource() {
        DbConfig c = new DbConfig();
        c.dataSources.add(new DataSourceConfig("prod", "jdbc:mysql://h:3306/shop", "u", "s3cr3t!"));
        c.current = "prod";
        String d = new DbTool(DbType.MYSQL, c, (java.nio.file.Path) null).description();
        assertTrue(d, d.contains("当前数据源: prod"));
        assertTrue(d, d.contains("jdbc:mysql://h:3306/shop"));
        assertFalse("密码不得出现在描述里", d.contains("s3cr3t!"));
        assertTrue(d, d.contains("action=schema 列出表与视图"));
        assertTrue(d, d.contains("action=describe 查看表字段"));
        assertTrue(d, d.contains("仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN"));
    }

    /** description 引导拆分查询（大结果闸门配套：避免整表拉大字段） */
    @Test
    public void descriptionAdvisesSplittingLargeResults() {
        DbConfig c = new DbConfig();
        c.dataSources.add(new DataSourceConfig("prod", "jdbc:mysql://h:3306/shop", "u", "p"));
        c.current = "prod";
        String d = new DbTool(DbType.MYSQL, c, (java.nio.file.Path) null).description();
        assertTrue(d, d.contains("结果超过 30000 字符会落盘并提示拆分"));
        assertTrue(d, d.contains("按需 WHERE/LIMIT 拆分查询"));
    }

    @Test
    public void descriptionWhenNoDataSourceSelected() {
        String d = new DbTool(DbType.ORACLE, emptyConfig(), (java.nio.file.Path) null).description();
        assertTrue(d, d.contains("当前未选择数据源，调用会返回提示，请在 设置 → 工具 中选择数据源"));
    }

    @Test
    public void postgresDescriptionDeclaresQueryOnly() {
        String d = new DbTool(DbType.POSTGRES, emptyConfig(), (java.nio.file.Path) null).description();
        assertTrue(d, d.contains("仅支持 action=query"));
        assertTrue(d, d.contains("SELECT * FROM 表名 LIMIT 1 自行推断"));
        assertFalse("不应宣称支持 schema 动作", d.contains("action=schema 列出表与视图"));
    }

    @Test
    public void missingActionRejected() {
        ToolResult r = new DbTool(DbType.MYSQL, emptyConfig(), (java.nio.file.Path) null)
                .execute(new JsonObject());
        assertFalse(r.ok);
        assertEquals("缺少 action 参数", r.output);
    }

    @Test
    public void unknownActionListsSupportedOnes() {
        ToolResult r = new DbTool(DbType.MYSQL, emptyConfig(), (java.nio.file.Path) null)
                .execute(json("action", "fly"));
        assertFalse(r.ok);
        assertEquals("未知 action: fly（支持 query/schema/describe）", r.output);

        ToolResult p = new DbTool(DbType.POSTGRES, emptyConfig(), (java.nio.file.Path) null)
                .execute(json("action", "fly"));
        assertEquals("未知 action: fly（支持 query）", p.output);
    }

    @Test
    public void noDataSourceSelectedMessage() {
        ToolResult r = new DbTool(DbType.MYSQL, emptyConfig(), (java.nio.file.Path) null)
                .execute(json("action", "query", "sql", "SELECT 1"));
        assertFalse(r.ok);
        assertEquals("mysql 插件未选择数据源，请在 设置 → 工具 → mysql 中选择", r.output);
    }

    @Test
    public void queryWithoutSqlRejected() {
        DbConfig c = new DbConfig();
        c.dataSources.add(new DataSourceConfig("bad", "jdbc:mysql://127.0.0.1:1/db", "u", "p"));
        c.current = "bad";
        ToolResult r = new DbTool(DbType.MYSQL, c, (java.nio.file.Path) null).execute(json("action", "query"));
        assertFalse(r.ok);
        assertEquals("query 需要 sql 参数", r.output);
    }

    @Test
    public void sqlGuardRejectionReturnedVerbatim() {
        DbConfig c = new DbConfig();
        c.dataSources.add(new DataSourceConfig("bad", "jdbc:mysql://127.0.0.1:1/db", "u", "p"));
        c.current = "bad";
        ToolResult r = new DbTool(DbType.MYSQL, c, (java.nio.file.Path) null)
                .execute(json("action", "query", "sql", "DELETE FROM t"));
        assertFalse(r.ok);
        assertTrue(r.output, r.output.startsWith("只读工具拒绝执行：语句以 DELETE 开头"));
    }

    @Test
    public void describeWithoutTableRejected() {
        DbConfig c = new DbConfig();
        c.dataSources.add(new DataSourceConfig("bad", "jdbc:mysql://127.0.0.1:1/db", "u", "p"));
        c.current = "bad";
        ToolResult r = new DbTool(DbType.MYSQL, c, (java.nio.file.Path) null).execute(json("action", "describe"));
        assertFalse(r.ok);
        assertEquals("describe 需要 table 参数", r.output);
    }

    @Test
    public void postgresSchemaAndDescribeReturnHintWithoutConnecting() {
        long t0 = System.currentTimeMillis();
        DbTool t = new DbTool(DbType.POSTGRES, withUnreachable(), (java.nio.file.Path) null);
        ToolResult schema = t.execute(json("action", "schema"));
        ToolResult describe = t.execute(json("action", "describe", "table", "t"));
        long cost = System.currentTimeMillis() - t0;
        assertFalse(schema.ok);
        assertEquals(DbTool.PG_SCHEMA_HINT, schema.output);
        assertFalse(describe.ok);
        assertEquals(DbTool.PG_DESCRIBE_HINT, describe.output);
        assertTrue("提示必须在建连接之前返回，实际耗时 " + cost + "ms", cost < 2000);
    }

    @Test
    public void dbTypeOfIdAndActions() {
        assertSame(DbType.MYSQL, DbType.ofId("mysql"));
        assertSame(DbType.POSTGRES, DbType.ofId("postgresql"));
        assertSame(DbType.ORACLE, DbType.ofId("oracle"));
        assertNull(DbType.ofId("sqlserver"));
        assertNull(DbType.ofId(null));
        assertEquals("query/schema/describe", DbType.MYSQL.actionsText());
        assertEquals("query", DbType.POSTGRES.actionsText());
        assertTrue(DbType.ORACLE.supports("describe"));
        assertFalse(DbType.POSTGRES.supports("describe"));
        assertEquals("com.mysql.cj.jdbc.Driver", DbType.MYSQL.driverClass());
        assertEquals("org.postgresql.Driver", DbType.POSTGRES.driverClass());
        assertEquals("oracle.jdbc.OracleDriver", DbType.ORACLE.driverClass());
        assertTrue(DbType.MYSQL.urlTemplate().startsWith("jdbc:mysql://"));
        assertTrue(DbType.POSTGRES.urlTemplate().startsWith("jdbc:postgresql://"));
        assertTrue(DbType.ORACLE.urlTemplate().startsWith("jdbc:oracle:thin:@"));
        assertEquals("postgreSQL", DbType.POSTGRES.displayName());
    }

    @Test
    public void timeoutDetectionBySqlState() {
        assertTrue(DbExecutor.isTimeout(new java.sql.SQLException("cancelled", "57014")));
        assertTrue(DbExecutor.isTimeout(new java.sql.SQLException("ORA-01013", "01013")));
        assertTrue(DbExecutor.isTimeout(new java.sql.SQLException("Statement cancelled due to timeout", "70100")));
        assertTrue(DbExecutor.isTimeout(new java.sql.SQLException("Query exceeded timeout", (String) null)));
        assertFalse(DbExecutor.isTimeout(new java.sql.SQLException("table not found", "42S02")));
    }

    @Test
    public void schemaDeclaresFullParameter() {
        JsonObject s = new DbTool(DbType.MYSQL, emptyConfig(), (java.nio.file.Path) null).schema();
        assertTrue(s.getAsJsonObject("properties").has("full"));
    }

    @Test
    public void fullOfParsesTolerantly() {
        assertFalse(DbTool.fullOf(null));
        assertFalse(DbTool.fullOf(new JsonObject()));
        assertFalse(DbTool.fullOf(json("action", "query")));
        assertFalse(DbTool.fullOf(json("full", "false")));
        assertFalse(DbTool.fullOf(json("full", "")));
        assertFalse(DbTool.fullOf(json("full", "yes")));
        assertTrue(DbTool.fullOf(json("full", "true")));
        assertTrue(DbTool.fullOf(json("full", "True")));     // 模型大写容忍
        assertTrue(DbTool.fullOf(json("full", " true ")));   // 首尾空白容忍
        JsonObject boolTrue = new JsonObject();
        boolTrue.addProperty("full", true);                  // 原生布尔值容忍
        assertTrue(DbTool.fullOf(boolTrue));
        JsonObject boolFalse = new JsonObject();
        boolFalse.addProperty("full", false);
        assertFalse(DbTool.fullOf(boolFalse));
    }

    @Test
    public void firstLineTruncatesLongMessages() {
        assertEquals("首行", DbExecutor.firstLine("首行\n第二行"));
        assertEquals("", DbExecutor.firstLine(null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('e');
        String out = DbExecutor.firstLine(sb.toString());
        assertEquals(201, out.length());
        assertTrue(out.endsWith("…"));
    }
}
