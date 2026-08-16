package com.minion.core.mcp;

import com.google.gson.JsonObject;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** MockWebServer 模拟 SSE 端点：GET /sse 保持事件流，POST 响应体即 JSON-RPC 响应（Dispatcher 按方法路由） */
public class SseMcpClientTest {

    private MockWebServer server;
    private SseMcpClient client;

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    /** 请求体 JSON 里取 id，响应原样回显（通知也占自增 id，不可硬编码） */
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static String idOf(String body) {
        java.util.regex.Matcher m = ID_PATTERN.matcher(body);
        return m.find() ? m.group(1) : "0";
    }

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if ("GET".equals(request.getMethod())) {
                    // SSE 事件流：保持打开，不发事件（POST 响应体即结果，无需推送）
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setSocketPolicy(SocketPolicy.KEEP_OPEN);
                }
                String body = request.getBody().readUtf8();
                String id = idOf(body);
                if (body.contains("\"initialize\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\","
                            + "\"capabilities\":{},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}}");
                }
                if (body.contains("\"tools/list\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                            + "{\"name\":\"sse_tool\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}");
                }
                if (body.contains("\"tools/call\"")) {
                    return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":["
                            + "{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}");
                }
                return json("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}");
            }
        });
        client = new SseMcpClient(server.url("/mcp").toString(),
                new java.util.HashMap<String, String>());
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    @Test
    public void connectListAndCall() throws Exception {
        client.connect();
        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("sse_tool", tools.get(0).name);
        assertEquals("ok", client.callTool("sse_tool", new JsonObject()));
    }

    @Test
    public void close_idempotent() {
        client.close();
        client.close();
    }
}
