package com.minion.core.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** SSE/HTTP 传输：POST 发请求（响应体即 JSON-RPC 响应），GET /sse 事件流（data: 行也是 JSON-RPC 消息） */
public class SseMcpClient implements McpHandle {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final String url;
    private final Map<String, String> headers;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, BlockingQueue<JsonObject>> pending =
            new HashMap<Integer, BlockingQueue<JsonObject>>();
    private volatile boolean closed;
    private boolean connected;
    private EventSource sse;

    public SseMcpClient(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? new HashMap<String, String>() : headers;
        this.http = new OkHttpClient();
    }

    /** 响应分发：按 id 投递到对应 pending 队列 */
    private void deliver(JsonObject msg) {
        if (msg == null) return;
        int id = JsonRpc.parseId(msg);
        BlockingQueue<JsonObject> q = pending.get(id);
        if (q != null) {
            q.offer(msg);
            pending.remove(id);
        }
    }

    /** 同步调用：POST 请求 → 响应体即结果（或经 SSE 流推送）→ 等响应（id 关联） */
    private JsonObject call(String method, JsonObject params, boolean expectResponse) throws McpException {
        if (closed) throw new McpException("SSE 连接已关闭");
        int id = nextId.getAndIncrement();
        BlockingQueue<JsonObject> q = new ArrayBlockingQueue<JsonObject>(1);
        pending.put(id, q);
        Request.Builder rb = new Request.Builder().url(url)
                .post(RequestBody.create(JSON, new Gson().toJson(JsonRpc.request(id, method, params))));
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        try {
            Response resp = http.newCall(rb.build()).execute();
            // SSE 传输：POST 响应体即 JSON-RPC 响应（部分服务器经 SSE 流推送，响应体为空）
            String body = resp.body() != null ? resp.body().string() : "";
            resp.close();
            if (!body.trim().isEmpty()) {
                try {
                    deliver(new Gson().fromJson(body, JsonObject.class));
                } catch (Exception ignored) { /* 非 JSON 响应体忽略 */ }
            }
            if (!expectResponse) return null;
            JsonObject res = q.poll(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (res == null) {
                pending.remove(id);
                throw new McpException("MCP 调用超时: " + method);
            }
            if (res.has("error")) {
                throw new McpException("MCP 错误: " + res.get("error").getAsJsonObject().get("message").getAsString());
            }
            return res.has("result") ? res.getAsJsonObject("result") : null;
        } catch (IOException e) {
            pending.remove(id);
            throw new McpException("SSE 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("MCP 调用被中断: " + method);
        }
    }

    @Override
    public void connect() throws McpException {
        if (connected) return;
        // 建 SSE 事件流（GET /sse）：事件体 data: {...} 也是 JSON-RPC 消息
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        sse = EventSources.createFactory(http).newEventSource(rb.build(), new EventSourceListener() {
            @Override public void onEvent(EventSource es, String id2, String type, String data) {
                try {
                    deliver(new Gson().fromJson(data, JsonObject.class));
                } catch (Exception ignored) { }
            }
            @Override public void onFailure(EventSource es, Throwable t, Response response) {
                closed = true;
                for (BlockingQueue<JsonObject> q : pending.values()) q.offer(
                        JsonRpc.responseError(0, -32000, "SSE 流已断开"));
                pending.clear();
            }
        });
        JsonObject initParams = new JsonObject();
        initParams.addProperty("protocolVersion", "2024-11-05");
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "minion");
        clientInfo.addProperty("version", "0.1.0");
        initParams.add("clientInfo", clientInfo);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        initParams.add("capabilities", caps);
        call("initialize", initParams, true);
        call("notifications/initialized", null, false);
        connected = true;
    }

    @Override
    public List<McpToolInfo> listTools() throws McpException {
        JsonObject result = call("tools/list", null, true);
        List<McpToolInfo> tools = new ArrayList<McpToolInfo>();
        if (result != null && result.has("tools")) {
            for (JsonElement e : result.getAsJsonArray("tools")) {
                JsonObject t = e.getAsJsonObject();
                JsonElement schemaEl = t.get("inputSchema");
                JsonObject schema = schemaEl != null && schemaEl.isJsonObject()
                        ? schemaEl.getAsJsonObject() : new JsonObject();
                tools.add(new McpToolInfo(
                        t.get("name").getAsString(),
                        t.has("description") ? t.get("description").getAsString() : "",
                        schema));
            }
        }
        return tools;
    }

    @Override
    public String callTool(String name, JsonObject args) throws McpException {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", args == null ? new JsonObject() : args);
        JsonObject result = call("tools/call", params, true);
        StringBuilder sb = new StringBuilder();
        boolean isError = result != null && result.has("isError") && result.get("isError").getAsBoolean();
        if (result != null && result.has("content")) {
            for (JsonElement e : result.getAsJsonArray("content")) {
                JsonObject c = e.getAsJsonObject();
                if ("text".equals(c.get("type").getAsString())) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.get("text").getAsString());
                } else {
                    // resource/其他类型：转 JSON 文本
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(new Gson().toJson(c));
                }
            }
        }
        if (isError) {
            throw new McpException(sb.length() == 0 ? "MCP 工具调用失败: " + name : sb.toString());
        }
        return sb.toString();
    }

    @Override
    public void close() {
        closed = true;
        if (sse != null) sse.cancel();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
