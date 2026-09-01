package com.minion.gui.dialog;

import com.minion.core.mcp.McpServer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.Assert.*;

/** MCP 表单字段联动口径：三行矩阵 + 保存裁剪 */
public class McpFormPolicyTest {

    @Test
    public void fieldsOf_stdio_onlyCommandGroup() {
        assertEquals(McpFormPolicy.fieldsOf("stdio"),
                new java.util.HashSet<McpFormPolicy.Field>(Arrays.asList(
                        McpFormPolicy.Field.COMMAND, McpFormPolicy.Field.ARGS, McpFormPolicy.Field.ENV)));
    }

    @Test
    public void fieldsOf_sse_onlyUrl() {
        assertEquals(McpFormPolicy.fieldsOf("sse"),
                new java.util.HashSet<McpFormPolicy.Field>(Arrays.asList(McpFormPolicy.Field.URL)));
    }

    @Test
    public void fieldsOf_streamable_urlAndHeaders() {
        assertEquals(McpFormPolicy.fieldsOf("streamable"),
                new java.util.HashSet<McpFormPolicy.Field>(Arrays.asList(
                        McpFormPolicy.Field.URL, McpFormPolicy.Field.HEADERS)));
    }

    @Test
    public void fieldsOf_unknown_fallsBackStdio() {
        assertEquals(McpFormPolicy.fieldsOf("nonsense"),
                McpFormPolicy.fieldsOf("stdio"));
    }

    @Test
    public void trim_keepsOnlyTransportFields() {
        McpServer s = new McpServer();
        s.transport = "sse";
        s.command = "npx";
        s.args = new ArrayList<String>(Arrays.asList("@playwright/mcp"));
        s.env = new HashMap<String, String>();
        s.env.put("K", "V");
        s.url = "http://h/sse";
        s.headers = new HashMap<String, String>();
        s.headers.put("A", "b");
        McpFormPolicy.trim(s);
        assertEquals("", s.command);
        assertTrue(s.args.isEmpty());
        assertTrue(s.env.isEmpty());
        assertEquals("http://h/sse", s.url);
        assertTrue(s.headers.isEmpty());
    }

    /** stdio：保留命令组，清空 URL/请求头 */
    @Test
    public void trim_stdio_keepsCommandGroupClearsRemoteFields() {
        McpServer s = new McpServer();
        s.transport = "stdio";
        s.command = "npx";
        s.args = new ArrayList<String>(Arrays.asList("@playwright/mcp"));
        s.env = new HashMap<String, String>();
        s.env.put("K", "V");
        s.url = "http://h/mcp";
        s.headers = new HashMap<String, String>();
        s.headers.put("A", "b");
        McpFormPolicy.trim(s);
        assertEquals("npx", s.command);
        assertEquals(1, s.args.size());
        assertEquals(1, s.env.size());
        assertEquals("", s.url);
        assertTrue(s.headers.isEmpty());
    }

    /** streamable：保留 URL+请求头，清空命令组 */
    @Test
    public void trim_streamable_keepsUrlHeadersClearsCommandGroup() {
        McpServer s = new McpServer();
        s.transport = "streamable";
        s.command = "npx";
        s.args = new ArrayList<String>(Arrays.asList("@playwright/mcp"));
        s.env = new HashMap<String, String>();
        s.env.put("K", "V");
        s.url = "http://h/mcp";
        s.headers = new HashMap<String, String>();
        s.headers.put("A", "b");
        McpFormPolicy.trim(s);
        assertEquals("", s.command);
        assertTrue(s.args.isEmpty());
        assertTrue(s.env.isEmpty());
        assertEquals("http://h/mcp", s.url);
        assertEquals(1, s.headers.size());
    }

    /** 防回归：trim 不改变用户已选的传输值（仅归一化），表单保存时传输选择必须落盘 */
    @Test
    public void trim_keepsChosenTransportNormalized() {
        McpServer s = new McpServer();
        s.transport = "STREAMABLE";
        s.url = "http://h/mcp";
        McpFormPolicy.trim(s);
        assertEquals("streamable", s.transport);
        assertEquals("http://h/mcp", s.url);
    }

    /** 新建对象（transport 未赋值）trim 后归一到 stdio，不产生脏传输值 */
    @Test
    public void trim_nullTransportFallsBackStdio() {
        McpServer s = new McpServer();
        s.command = "npx";
        McpFormPolicy.trim(s);
        assertEquals("stdio", s.transport);
        assertEquals("npx", s.command);
        assertEquals("", s.url);
    }

    @Test
    public void labelOf_friendlyNames() {
        assertEquals("stdio", McpFormPolicy.labelOf("stdio"));
        assertEquals("SSE", McpFormPolicy.labelOf("sse"));
        assertEquals("Streamable", McpFormPolicy.labelOf("streamable"));
    }
}
