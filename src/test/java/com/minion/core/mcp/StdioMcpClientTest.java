package com.minion.core.mcp;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 用 FakeMcpServer（java -cp 启动）验证 stdio 全流程 */
public class StdioMcpClientTest {

    private StdioMcpClient client;

    @Before
    public void setUp() throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FakeMcpServer.class.getName());
        client = new StdioMcpClient(cmd, new java.util.HashMap<String, String>());
    }

    @After
    public void tearDown() {
        client.close();
    }

    @Test
    public void connectThenListTools() throws Exception {
        client.connect();
        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("fake_tool", tools.get(0).name);
        assertEquals("fake tool desc", tools.get(0).description);
        assertEquals("object", tools.get(0).schema.get("type").getAsString());
    }

    @Test
    public void callTool_concatenatesTextContent() throws Exception {
        client.connect();
        JsonObject args = new JsonObject();
        args.addProperty("q", "hi");
        String out = client.callTool("fake_tool", args);
        assertEquals("hello \nworld", out); // content 数组按段拼接（段间换行）
    }

    @Test(expected = McpException.class)
    public void callTool_unknownMethod_throws() throws Exception {
        client.connect();
        client.callTool("nope", new JsonObject());
    }
}
