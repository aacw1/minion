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

    @Test
    public void labelOf_friendlyNames() {
        assertEquals("stdio", McpFormPolicy.labelOf("stdio"));
        assertEquals("SSE", McpFormPolicy.labelOf("sse"));
        assertEquals("Streamable", McpFormPolicy.labelOf("streamable"));
    }
}
