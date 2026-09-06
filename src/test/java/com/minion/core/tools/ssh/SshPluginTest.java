package com.minion.core.tools.ssh;

import com.minion.core.tools.Tool;
import com.minion.core.tools.plugin.ToolStore;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/** 插件：CRUD 落盘 / canEnable 依赖连接数 / 测试连接失败路径（不触网） */
public class SshPluginTest {

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("ssh-plugin-test");
        d.toFile().deleteOnExit();
        return d;
    }

    private static SshConnection conn(String name) {
        return new SshConnection(name, "127.0.0.1", 1, "u", "p", "", "");
    }

    @Test
    public void disabledAndCannotEnableWithoutConnections() throws Exception {
        ToolStore store = ToolStore.load(dir());
        SshPlugin p = new SshPlugin(store.sshConfig(), new Runnable() {
            @Override public void run() { store.save(); }
        });
        assertEquals("ssh", p.id());
        assertFalse(p.enabled());
        assertFalse("无连接不可启用", p.canEnable());
        assertTrue("添加连接后可启用", p.addConnection(conn("a")));
        assertTrue(p.canEnable());
        p.setEnabled(true);
        assertTrue(store.sshConfig().enabled);
    }

    @Test
    public void crudPersistsAndRejectsDuplicate() throws Exception {
        Path d = dir();
        ToolStore store = ToolStore.load(d);
        SshPlugin p = new SshPlugin(store.sshConfig(), new Runnable() {
            @Override public void run() { store.save(); }
        });
        assertTrue(p.addConnection(conn("prod")));
        assertFalse("重名拒绝", p.addConnection(conn("PROD")));
        p.addConnection(conn("dev"));
        p.setCurrent("prod");
        p.updateConnection("prod", conn("prod2"));
        assertEquals("prod2", store.sshConfig().current);
        p.removeConnection("dev");
        ToolStore s2 = ToolStore.load(d);
        assertEquals(1, s2.sshConfig().connections.size());
        assertEquals("prod2", s2.sshConfig().current);
    }

    @Test
    public void testConnectionUnknownNameReturnsError() throws Exception {
        ToolStore store = ToolStore.load(dir());
        SshPlugin p = new SshPlugin(store.sshConfig(), null);
        SshExecutor.TestResult r = p.testConnection("ghost");
        assertFalse(r.ok);
        assertTrue(r.message.contains("ghost"));
    }

    @Test
    public void testConnectionRefusedFailsFast() throws Exception {
        ToolStore store = ToolStore.load(dir());
        SshPlugin p = new SshPlugin(store.sshConfig(), null);
        p.addConnection(conn("bad"));
        long t0 = System.currentTimeMillis();
        SshExecutor.TestResult r = p.testConnection("bad");
        assertFalse(r.ok);
        assertTrue(System.currentTimeMillis() - t0 < 3000);
    }

    @Test
    public void createToolsProducesSevenTools() throws Exception {
        ToolStore store = ToolStore.load(dir());
        SshPlugin p = new SshPlugin(store.sshConfig(), null);
        List<Tool> tools = p.createTools(null);
        assertEquals(7, tools.size());
    }
}
