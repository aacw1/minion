package com.minion.core.mcp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 用 FakeMcpServer（stdio）验证状态机：惰性连接、去重、失败、工具表、shutdown */
public class McpManagerTest {

    private McpManager manager;
    private McpServer server;

    private static McpServer stdioServer(String name, boolean enabled) {
        McpServer s = new McpServer();
        s.name = name;
        s.transport = "stdio";
        s.command = System.getProperty("java.home") + "/bin/java";
        s.args = new ArrayList<String>();
        s.args.add("-cp");
        s.args.add(System.getProperty("java.class.path"));
        s.args.add(FakeMcpServer.class.getName());
        s.enabled = enabled;
        return s;
    }

    @Before
    public void setUp() throws Exception {
        Path d = Files.createTempDirectory("mcp-mgr-test");
        McpStore store = McpStore.load(d);
        server = stdioServer("fake", true);
        store.list().add(server);
        manager = new McpManager(store);
    }

    @After
    public void tearDown() {
        manager.shutdown();
    }

    private static McpServer waitForState(McpManager m, String name, McpServer.State target) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        m.addListener(s -> { if (s.name.equals(name) && s.state == target) latch.countDown(); });
        m.ensureConnectedAsync(name);
        // 已连接的情形也兜底
        if (m.servers().get(0).state != target) {
            assertTrue("等待 " + target + " 超时", latch.await(15, TimeUnit.SECONDS));
        }
        for (McpServer s : m.servers()) if (s.name.equals(name)) return s;
        throw new AssertionError("server not found");
    }

    @Test
    public void ensureConnected_connectsAndFillsTools() throws Exception {
        McpServer s = waitForState(manager, "fake", McpServer.State.CONNECTED);
        assertEquals(1, s.tools.size());
        assertEquals("fake_tool", s.tools.get(0).name);
    }

    @Test
    public void connect_failure_marksFailedWithReason() throws Exception {
        McpServer bad = stdioServer("bad", true);
        bad.command = "definitely-not-a-command-xyz";
        manager.servers().add(bad);
        McpServer s = waitForState(manager, "bad", McpServer.State.FAILED);
        assertNotNull(s.failReason);
        assertTrue(s.failReason.length() > 0);
    }

    @Test
    public void call_routesToConnectedServer() throws Exception {
        waitForState(manager, "fake", McpServer.State.CONNECTED);
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("q", "x");
        assertEquals("hello \nworld", manager.call("fake", "fake_tool", args));
    }

    @Test
    public void disconnect_clearsToolsAndState() throws Exception {
        waitForState(manager, "fake", McpServer.State.CONNECTED);
        manager.disconnect("fake");
        McpServer s = manager.servers().get(0);
        assertEquals(McpServer.State.DISCONNECTED, s.state);
        assertTrue(s.tools.isEmpty());
    }

    @Test
    public void ensureConnected_whileConnecting_noDuplicateProcess() throws Exception {
        // 并发两次 ensureConnectedAsync：连接锁去重（状态机一致性：最终 CONNECTED 且无异常）
        manager.ensureConnectedAsync("fake");
        manager.ensureConnectedAsync("fake");
        waitForState(manager, "fake", McpServer.State.CONNECTED);
    }

    @Test
    public void disabledServer_notConnected() throws Exception {
        server.enabled = false;
        manager.ensureConnectedAsync("fake");
        Thread.sleep(500); // 等可能的错误连接发生
        assertEquals(McpServer.State.DISCONNECTED, server.state);
    }
}
