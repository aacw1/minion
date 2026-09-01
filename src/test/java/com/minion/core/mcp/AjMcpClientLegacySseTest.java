package com.minion.core.mcp;

import com.ajaxjs.mcp.client.transport.HttpMcpTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 旧版 HTTP+SSE 标准流程：endpoint 事件取 POST 地址 → POST /messages → 响应经 SSE 流回 */
public class AjMcpClientLegacySseTest {

    private FakeSseMcpServer server;

    @Before
    public void setUp() throws Exception {
        server = new FakeSseMcpServer();
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    public void legacySse_endpointEventThenMessagePost() throws Exception {
        HttpMcpTransport transport = HttpMcpTransport.builder().sseUrl(server.sseUrl()).build();
        AjMcpClient client = new AjMcpClient(transport);
        try {
            client.connect();
            List<McpToolInfo> tools = client.listTools();
            assertEquals(5, tools.size());   // 复用 FakeMcpServer 的应答（含分页）
            assertTrue(tools.stream().anyMatch(t -> "fake_tool".equals(t.name)));
            assertEquals("hello \nworld", client.callTool("fake_tool", new com.google.gson.JsonObject()));
        } finally {
            client.close();
        }
        assertTrue("POST 应打到 /messages", server.lastMessagePath.startsWith("/messages"));
    }
}
