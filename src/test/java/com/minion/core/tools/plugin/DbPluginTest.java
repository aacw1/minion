package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.db.DataSourceConfig;
import com.minion.core.tools.db.DbType;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 数据库插件：状态文案、数据源 CRUD 落盘、当前数据源切换、测试连接的失败分支 */
public class DbPluginTest {

    private static class CountingSaver implements Runnable {
        int count;
        @Override public void run() { count++; }
    }

    private static DbPlugin plugin(DbType type, DbConfig c, CountingSaver saver) {
        return new DbPlugin(type, c, saver);
    }

    @Test
    public void idAndDisplayNameFollowDbType() {
        assertEquals("mysql", plugin(DbType.MYSQL, new DbConfig(), null).id());
        assertEquals("mysql", plugin(DbType.MYSQL, new DbConfig(), null).displayName());
        assertEquals("postgresql", plugin(DbType.POSTGRES, new DbConfig(), null).id());
        assertEquals("postgreSQL", plugin(DbType.POSTGRES, new DbConfig(), null).displayName());
        assertEquals("oracle", plugin(DbType.ORACLE, new DbConfig(), null).id());
        assertEquals("oracle", plugin(DbType.ORACLE, new DbConfig(), null).displayName());
    }

    /** 状态列对数据库行恒空：当前选中名/（无数据源）均由行内下拉框表达，不占状态列 */
    @Test
    public void statusTextAlwaysEmpty() {
        DbConfig c = new DbConfig();
        DbPlugin p = plugin(DbType.MYSQL, c, null);
        assertEquals("", p.statusText());          // 无数据源：下拉 prompt 已提示
        c.dataSources.add(new DataSourceConfig("prod", "jdbc:mysql://h:3306/db", "u", "p"));
        assertEquals("", p.statusText());          // 有列表未选中：下拉无选中可见
        c.current = "prod";
        assertEquals("", p.statusText());          // 已选中：下拉显示当前名
        c.current = "ghost";                       // 选中项被删（回退前瞬间）同样不占列
        assertEquals("", p.statusText());
    }

    /** 无数据源时不能点启用（工具页勾选框禁用，防模型拿到工具却只能报错）；配好后放行 */
    @Test
    public void canEnableRequiresDataSources() {
        DbConfig c = new DbConfig();
        DbPlugin p = plugin(DbType.MYSQL, c, null);
        assertFalse(p.canEnable());
        p.addDataSource(new DataSourceConfig("prod", "jdbc:mysql://h:3306/db", "u", "p"));
        assertTrue(p.canEnable());
    }

    @Test
    public void addDataSourceSetsFirstAsCurrentAndSaves() {
        DbConfig c = new DbConfig();
        CountingSaver saver = new CountingSaver();
        DbPlugin p = plugin(DbType.MYSQL, c, saver);
        assertTrue(p.addDataSource(new DataSourceConfig("prod", "jdbc:mysql://h:3306/db", "u", "p")));
        assertEquals("prod", c.current);
        assertEquals(1, saver.count);
        assertEquals("已选中时状态列留空（当前名由下拉框显示）", "", p.statusText());
        // 重复标识名（DbConfig.add 拒绝）
        assertFalse(p.addDataSource(new DataSourceConfig("PROD", "jdbc:mysql://h2/db", "", "")));
    }

    @Test
    public void updateDataSourceRenamesAndSyncsCurrent() {
        DbConfig c = new DbConfig();
        DbPlugin p = plugin(DbType.ORACLE, c, new CountingSaver());
        p.addDataSource(new DataSourceConfig("old", "jdbc:oracle:thin:@h:1521:ORCL", "u", "p"));
        assertTrue(p.updateDataSource("old", new DataSourceConfig("new", "jdbc:oracle:thin:@h2:1521:ORCL", "u2", "p2")));
        assertEquals("new", c.current);
        assertEquals("jdbc:oracle:thin:@h2:1521:ORCL", c.currentDataSource().url);
        assertEquals("u2", c.currentDataSource().user);
        assertFalse(p.updateDataSource("ghost", new DataSourceConfig("x", "jdbc:x", "", "")));
    }

    @Test
    public void removeDataSourceFallsBackAndSaves() {
        DbConfig c = new DbConfig();
        CountingSaver saver = new CountingSaver();
        DbPlugin p = plugin(DbType.POSTGRES, c, saver);
        p.addDataSource(new DataSourceConfig("a", "jdbc:postgresql://h:5432/a", "", ""));
        p.addDataSource(new DataSourceConfig("b", "jdbc:postgresql://h:5432/b", "", ""));
        int before = saver.count;
        assertTrue(p.removeDataSource("a"));
        assertEquals("b", c.current);
        assertEquals(before + 1, saver.count);
        assertTrue(p.removeDataSource("b"));
        assertEquals("", c.current);
        assertEquals("删光后状态列留空（下拉 prompt 已提示）", "", p.statusText());
        assertFalse(p.removeDataSource("b"));
    }

    @Test
    public void setCurrentIgnoresUnknownName() {
        DbConfig c = new DbConfig();
        DbPlugin p = plugin(DbType.MYSQL, c, new CountingSaver());
        p.addDataSource(new DataSourceConfig("a", "jdbc:mysql://h/db", "", ""));
        p.addDataSource(new DataSourceConfig("b", "jdbc:mysql://h/db2", "", ""));
        p.setCurrent("b");
        assertEquals("b", c.current);
        p.setCurrent("ghost");
        assertEquals("b", c.current);
    }

    @Test
    public void createsSingleToolNamedByType() {
        DbConfig c = new DbConfig();
        ToolContext ctx = new ToolContext(new Workspace("."), "./skills", null, null);
        List<Tool> tools = plugin(DbType.MYSQL, c, null).createTools(ctx);
        assertEquals(1, tools.size());
        assertEquals("DbMysql", tools.get(0).name());
        assertEquals(1, plugin(DbType.POSTGRES, c, null).createTools(ctx).size());
        assertEquals("DbPostgres", plugin(DbType.POSTGRES, c, null).createTools(ctx).get(0).name());
        assertEquals("DbOracle", plugin(DbType.ORACLE, c, null).createTools(ctx).get(0).name());
    }

    @Test
    public void setEnabledPersistsViaSaver() {
        DbConfig c = new DbConfig();
        CountingSaver saver = new CountingSaver();
        DbPlugin p = plugin(DbType.MYSQL, c, saver);
        p.setEnabled(true);
        assertTrue(c.enabled);
        assertEquals(1, saver.count);
    }

    @Test
    public void testConnectionUnknownDataSourceFails() {
        DbPlugin p = plugin(DbType.MYSQL, new DbConfig(), null);
        DbPlugin.TestResult r = p.testConnection("ghost");
        assertFalse(r.ok);
        assertEquals("数据源不存在: ghost", r.message);
    }

    @Test
    public void testConnectionUnreachableHostFailsFast() {
        DbConfig c = new DbConfig();
        DbPlugin p = plugin(DbType.MYSQL, c, null);
        // 127.0.0.1:1 立即 connection refused，不会等满 loginTimeout
        p.addDataSource(new DataSourceConfig("bad", "jdbc:mysql://127.0.0.1:1/db", "u", "p"));
        long t0 = System.currentTimeMillis();
        DbPlugin.TestResult r = p.testConnection("bad");
        long cost = System.currentTimeMillis() - t0;
        assertFalse(r.ok);
        assertNotNull(r.message);
        assertFalse(r.message.isEmpty());
        assertTrue("应快速失败，实际 " + cost + "ms", cost < 12000);
    }
}
