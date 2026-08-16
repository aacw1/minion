package com.minion.core.mcp;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class McpStoreTest {

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("mcp-store-test");
        d.toFile().deleteOnExit();
        return d;
    }

    @Test
    public void load_missingFile_createsEmptyServers() throws Exception {
        Path d = dir();
        McpStore s = McpStore.load(d);
        assertTrue(s.list().isEmpty());
        assertTrue(Files.exists(d.resolve("mcp.json"))); // 缺省文件已生成
    }

    @Test
    public void save_roundtrip_preservesFields() throws Exception {
        Path d = dir();
        McpStore s = McpStore.load(d);
        McpServer server = new McpServer();
        server.name = "playwright";
        server.transport = "stdio";
        server.command = "npx";
        server.args = new java.util.ArrayList<String>();
        server.args.add("@playwright/mcp");
        server.env = new java.util.HashMap<String, String>();
        server.env.put("K", "V");
        server.enabled = true;
        s.list().add(server);
        s.save();

        McpStore s2 = McpStore.load(d);
        assertEquals(1, s2.list().size());
        McpServer got = s2.list().get(0);
        assertEquals("playwright", got.name);
        assertEquals("stdio", got.transport);
        assertEquals("npx", got.command);
        assertEquals("@playwright/mcp", got.args.get(0));
        assertEquals("V", got.env.get("K"));
        assertTrue(got.enabled);
        assertEquals(McpServer.State.DISCONNECTED, got.state); // 运行时字段默认态
    }

    @Test
    public void load_corruptFile_backsUpAndRebuilds() throws Exception {
        Path d = dir();
        Files.write(d.resolve("mcp.json"), "not json{{{".getBytes("UTF-8"));
        McpStore s = McpStore.load(d);
        assertTrue(s.list().isEmpty());
        assertTrue(Files.exists(d.resolve("mcp.json.bak")));
    }
}
