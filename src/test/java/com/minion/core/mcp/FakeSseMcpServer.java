package com.minion.core.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用旧版 HTTP+SSE 服务器（标准流程）：
 * GET /sse → 下发 event: endpoint（POST 地址 /messages）并保持流；
 * POST /messages → 202，JSON-RPC 响应经 SSE event: message 推回。
 */
public class FakeSseMcpServer {

    private HttpServer server;
    private final CopyOnWriteArrayList<OutputStream> streams = new CopyOnWriteArrayList<OutputStream>();
    volatile String lastMessagePath = "";

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            streams.add(os);
            os.write("event: endpoint\r\ndata: /messages?sessionId=s1\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        });
        server.createContext("/messages", ex -> {
            lastMessagePath = ex.getRequestURI().toString();
            String body = readBody(ex);
            ex.sendResponseHeaders(202, -1);
            ex.close();
            String reply = FakeMcpServer.respondTo(body);   // 复用 stdio 桩的应答逻辑
            if (reply != null) broadcast("event: message\r\ndata: " + reply + "\r\n\r\n");
        });
        server.start();
    }

    public String sseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
    }

    private void broadcast(String sse) {
        for (OutputStream os : streams) {
            try { os.write(sse.getBytes(StandardCharsets.UTF_8)); os.flush(); } catch (IOException ignored) { }
        }
    }

    public void stop() {
        for (OutputStream os : streams) { try { os.close(); } catch (IOException ignored) { } }
        if (server != null) server.stop(0);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = ex.getRequestBody().read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
