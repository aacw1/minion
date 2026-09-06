package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 插件管理器：装配顺序、gate 判定、启停落盘、监听通知、与 ToolRegistry 的联动 */
public class ToolPluginManagerTest {

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("plugin-manager-test");
        d.toFile().deleteOnExit();
        return d;
    }

    @Test
    public void pluginsInFixedOrder() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        List<String> ids = new ArrayList<String>();
        for (ToolPlugin p : m.plugins()) ids.add(p.id());
        assertEquals("[browser, mysql, postgresql, oracle]", ids.toString());
    }

    @Test
    public void allDisabledByDefault() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        for (ToolPlugin p : m.plugins()) assertFalse(p.id() + " 默认应不启用", p.enabled());
    }

    @Test
    public void gateRejectsDisabledAndAcceptsEnabled() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        assertFalse(m.enabled("browser"));
        m.setEnabled("browser", true);
        assertTrue(m.enabled("browser"));
    }

    @Test
    public void gateConservativeForUnknownAndNull() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        assertTrue("内置工具（pluginId=null）恒放行", m.enabled(null));
        assertTrue("未登记的插件 id 保守放行，避免拼写错误导致工具全灭", m.enabled("sqlserver"));
    }

    @Test
    public void setEnabledPersistsToToolsJson() throws Exception {
        Path d = dir();
        ToolStore store = ToolStore.load(d);
        ToolPluginManager m = new ToolPluginManager(store);
        m.setEnabled("mysql", true);
        m.dbPlugin("mysql").addDataSource(
                new com.minion.core.tools.db.DataSourceConfig("prod", "jdbc:mysql://h:3306/db", "u", "p"));

        ToolStore reloaded = ToolStore.load(d);
        assertTrue(reloaded.dbConfig("mysql").enabled);
        assertEquals("prod", reloaded.dbConfig("mysql").current);
        assertFalse("未动过的插件保持默认不启用", reloaded.dbConfig("oracle").enabled);
    }

    @Test
    public void setEnabledUnknownIdIsNoop() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        m.setEnabled("sqlserver", true);   // 不抛异常
        m.setEnabled(null, true);
        assertNull(m.plugin("sqlserver"));
        assertNull(m.dbPlugin("browser"));
        assertNotNull(m.dbPlugin("postgresql"));
        assertNotNull(m.browserPlugin());
        assertNotNull(m.browserManager());
        assertNotNull(m.store());
    }

    @Test
    public void listenerNotifiedOnEnableAndDataSourceChange() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        final int[] hits = {0};
        m.addListener(new Runnable() { @Override public void run() { hits[0]++; } });
        m.setEnabled("browser", true);
        assertEquals(1, hits[0]);
        m.dbPlugin("oracle").addDataSource(
                new com.minion.core.tools.db.DataSourceConfig("orcl", "jdbc:oracle:thin:@h:1521:ORCL", "u", "p"));
        assertEquals("数据源变更同样要通知 GUI 刷新", 2, hits[0]);
    }

    @Test
    public void registryGateHidesDisabledPluginToolsEndToEnd() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        ToolRegistry reg = new ToolRegistry();
        ToolContext ctx = new ToolContext(new Workspace("."), "./skills", null, null);
        for (ToolPlugin p : m.plugins()) {
            for (Tool t : p.createTools(ctx)) reg.register(p.id(), t);
        }
        reg.setGate(m);
        // 默认全不启用：7 个插件工具一个都不该出现
        assertEquals(0, reg.schemas().size());
        assertEquals(0, reg.all().size());
        assertNull(reg.get("Browser"));
        assertNull(reg.get("DbMysql"));

        m.setEnabled("browser", true);
        assertEquals(4, reg.schemas().size());
        assertNotNull(reg.get("Browser"));
        assertNotNull(reg.get("BrowserScreenshot"));
        assertNull("数据库插件仍未启用", reg.get("DbMysql"));

        m.setEnabled("mysql", true);
        assertEquals(5, reg.schemas().size());
        assertNotNull(reg.get("DbMysql"));

        m.setEnabled("browser", false);
        assertEquals(1, reg.schemas().size());
        assertNull(reg.get("Browser"));
        assertNotNull(reg.get("DbMysql"));
    }

    @Test
    public void shutdownDoesNotThrow() throws Exception {
        ToolPluginManager m = new ToolPluginManager(ToolStore.load(dir()));
        m.shutdown();     // 未启动 Chrome 时是空操作
        m.shutdown();     // 幂等
    }
}
