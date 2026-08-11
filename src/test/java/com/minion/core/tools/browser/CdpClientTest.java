package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/** CDP 客户端:命令/响应 id 匹配、事件缓冲、超时与断线 */
public class CdpClientTest {

    private MockWebServer server;
    private WebSocket serverWs;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) { serverWs = ws; }
            @Override
            public void onMessage(WebSocket ws, String text) {
                // 自动应答:收到命令回同 id 的 result(网络事件除外,事件不发 id)
                JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                if (!msg.has("id")) return;
                int id = msg.get("id").getAsInt();
                JsonObject resp = new JsonObject();
                resp.addProperty("id", id);
                if ("Runtime.evaluate".equals(msg.get("method").getAsString())) {
                    JsonObject value = new JsonObject();
                    value.addProperty("value", "42");
                    JsonObject result = new JsonObject();
                    result.add("result", value);
                    resp.add("result", result);
                } else {
                    resp.add("result", new JsonObject());
                }
                ws.send(resp.toString());
            }
        }));
        server.start();
    }

    @After
    public void tearDown() throws Exception { server.shutdown(); }

    private String wsUrl() {
        return "ws://" + server.getHostName() + ":" + server.getPort() + "/devtools/page/1";
    }

    private void waitServerWs() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (serverWs == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertNotNull("服务端 WebSocket 未建立", serverWs);
    }

    @Test
    public void commandRoundTrip() throws Exception {
        CdpClient client = new CdpClient(5000, 5000);
        client.connect(wsUrl());
        waitServerWs();
        JsonObject params = new JsonObject();
        params.addProperty("expression", "1+1");
        JsonObject result = client.command("Runtime.evaluate", params);
        assertEquals("42", result.getAsJsonObject("result").get("value").getAsString());
    }

    @Test
    public void eventsBufferedByPrefix() throws Exception {
        CdpClient client = new CdpClient(5000, 5000);
        client.connect(wsUrl());
        waitServerWs();
        serverWs.send("{\"method\":\"Runtime.consoleAPICalled\",\"params\":{\"type\":\"log\",\"args\":[]}}");
        serverWs.send("{\"method\":\"Network.requestWillBeSent\",\"params\":{}}");
        // 等待事件到达(异步推送)
        long deadline = System.currentTimeMillis() + 5000;
        while (client.events("Runtime").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, client.events("Runtime.consoleAPICalled").size());
        assertEquals(1, client.events("Network").size());
        assertEquals(0, client.events("Page").size());
    }

    @Test
    public void commandTimeout() throws Exception {
        // 不回应的服务端
        MockWebServer silent = new MockWebServer();
        silent.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) { }
        }));
        silent.start();
        try {
            CdpClient client = new CdpClient(5000, 500);
            client.connect("ws://" + silent.getHostName() + ":" + silent.getPort() + "/devtools/page/1");
            try {
                client.command("Page.navigate", new JsonObject());
                fail("应抛超时 IOException");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("超时"));
            }
        } finally {
            silent.shutdown();
        }
    }

    @Test
    public void commandAfterDisconnectFails() throws Exception {
        CdpClient client = new CdpClient(5000, 5000);
        client.connect(wsUrl());
        waitServerWs();
        serverWs.close(1000, "bye");
        long deadline = System.currentTimeMillis() + 5000;
        while (client.isConnected() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        try {
            client.command("Page.navigate", new JsonObject());
            fail("应抛 IOException");
        } catch (IOException expected) { }
    }
}
