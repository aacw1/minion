package com.minion.core.mcp;

import com.google.gson.JsonObject;
import com.ajaxjs.mcp.client.transport.StdioTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** AjMcpClient：库握手 + 原始 tools/list（分页/schema 保真）+ 原始 tools/call（text/image/isError/断连） */
public class AjMcpClientTest {

    private AjMcpClient client;

    private static StdioTransport stdioTransport() {
        List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FakeMcpServer.class.getName());
        return StdioTransport.builder().command(cmd).logEvents(false).build();
    }

    @Before
    public void setUp() throws Exception {
        client = new AjMcpClient(stdioTransport());
        client.connect();
    }

    @After
    public void tearDown() {
        client.close();
    }

    @Test
    public void connect_thenListTools_mergesPages() throws Exception {
        List<McpToolInfo> tools = client.listTools();
        assertEquals(5, tools.size());   // 第一页 4 + 第二页 1（畸形缺 name 条目被跳过，不 NPE）
        assertTrue(tools.stream().anyMatch(t -> "paged_tool".equals(t.name)));
        assertTrue(tools.stream().anyMatch(t -> "fake_tool".equals(t.name)));
    }

    @Test
    public void listTools_skipsMalformedEntryWithoutNpe() throws Exception {
        List<McpToolInfo> tools = client.listTools();
        // 服务端混入缺 name 的工具条目：不抛 NPE，且畸形条目不进入结果
        assertFalse(tools.stream().anyMatch(t -> t.name == null || t.name.isEmpty()));
    }

    @Test
    public void callTool_contentMissingType_noNpe() throws Exception {
        // content 项缺 type / text 为 null：不抛 NPE；缺 type 项按原样 JSON 兜底，正常 text 照常拼接
        String out = client.callTool("tool_malformed", new JsonObject());
        assertTrue(out.contains("\"text\":\"orphan\""));
        assertTrue(out.endsWith("ok"));
    }

    @Test
    public void listTools_inputSchemaPassthrough() throws Exception {
        List<McpToolInfo> tools = client.listTools();
        McpToolInfo rich = tools.stream().filter(t -> "tool_schema".equals(t.name)).findFirst().orElse(null);
        assertNotNull(rich);
        assertEquals("a", rich.schema.getAsJsonObject("properties").getAsJsonObject("q").getAsJsonArray("enum").get(0).getAsString());
        assertTrue(rich.schema.getAsJsonObject("properties").getAsJsonObject("nested").getAsJsonObject("properties").has("k"));
        assertTrue(rich.schema.getAsJsonObject("properties").getAsJsonObject("list").has("items"));
    }

    @Test
    public void callTool_textConcatenated() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("q", "hi");
        assertEquals("hello \nworld", client.callTool("fake_tool", args));
    }

    @Test
    public void callTool_image_serializedAsJson() throws Exception {
        String out = client.callTool("tool_image", new JsonObject());
        assertTrue(out.contains("\"type\":\"image\""));
        assertTrue(out.contains("aGVsbG8="));
    }

    @Test(expected = McpException.class)
    public void callTool_isError_throws() throws Exception {
        client.callTool("tool_error", new JsonObject());
    }

    @Test(expected = McpConnectionException.class)
    public void callTool_afterProcessExit_connectionException() throws Exception {
        client.callTool("tool_die", new JsonObject());   // 服务端退出 → 读线程 EOF
        client.callTool("fake_tool", new JsonObject());  // 进程已死 → 连接层异常
    }
}
