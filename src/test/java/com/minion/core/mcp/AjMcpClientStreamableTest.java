package com.minion.core.mcp;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Streamable HTTP 标准报文验证：Mcp-Session-Id 保持、MCP-Protocol-Version 头、notifications/initialized */
public class AjMcpClientStreamableTest {

    private MockWebServer server;
    private final CopyOnWriteArrayList<RecordedRequest> requests = new CopyOnWriteArrayList<RecordedRequest>();
    /** 请求 body 文本（RecordedRequest.getBody() 是同一 Buffer，dispatcher 读过即空，须另存） */
    private final CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<String>();

    private static String idOf(String body) {
        int i = body.indexOf("\"id\":");
        if (i < 0) return "0";
        int j = body.indexOf(',', i);
        return body.substring(i + 5, j < 0 ? body.length() : j).trim();
    }

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                requests.add(request);
                bodies.add(body);
                if (body.contains("\"initialize\"")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setHeader("Mcp-Session-Id", "sess-1")
                            .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body) + ",\"result\":{\"protocolVersion\":\"2025-03-26\","
                                    + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}}");
                }
                if (body.contains("\"notifications/initialized\"")) {
                    return new MockResponse().setResponseCode(202);
                }
                if (body.contains("\"tools/list\"")) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body) + ",\"result\":{\"tools\":["
                                    + "{\"name\":\"fake_tool\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}");
                }
                return new MockResponse().setResponseCode(400);
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void streamable_negotiatesAndKeepsSessionAndVersionHeaders() throws Exception {
        com.ajaxjs.mcp.client.transport.StreamableHttpTransport transport =
                com.ajaxjs.mcp.client.transport.StreamableHttpTransport.builder()
                        .endpointUrl(server.url("/mcp").toString())
                        .openEventStream(false)
                        .timeout(java.time.Duration.ofSeconds(30))
                        .requestHeaders(java.util.Collections.singletonMap("Authorization", "Bearer tok"))
                        .build();
        AjMcpClient client = new AjMcpClient(transport);
        try {
            client.connect();
            assertEquals(1, client.listTools().size());
        } finally {
            client.close();
        }
        // 断言四个请求及其头（initialize / initialized / tools/list）
        assertTrue(requests.size() >= 3);
        RecordedRequest init = requests.get(0);
        assertTrue(bodies.get(0).contains("\"protocolVersion\""));
        assertTrue(bodies.get(0).contains("\"clientInfo\":{\"name\":\"minion\""));
        RecordedRequest notif = requests.get(1);
        assertTrue(bodies.get(1).contains("\"notifications/initialized\""));
        RecordedRequest list = requests.get(2);
        assertEquals("sess-1", list.getHeader("Mcp-Session-Id"));
        assertEquals("2025-03-26", list.getHeader("MCP-Protocol-Version"));
        assertEquals("Bearer tok", list.getHeader("Authorization"));
        assertEquals("application/json, text/event-stream", list.getHeader("Accept"));
    }
}
