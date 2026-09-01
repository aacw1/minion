package com.minion.core.mcp;

import com.ajaxjs.mcp.client.McpClient;
import com.ajaxjs.mcp.client.transport.McpTransport;
import com.ajaxjs.mcp.protocol.McpConstant;
import com.ajaxjs.mcp.protocol.tools.CallToolRequest;
import com.ajaxjs.mcp.protocol.tools.GetToolListRequest;
import com.ajaxjs.mcp.protocol.utils.pagination.Cursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 aj-mcp-client 的 MCP 连接（方案 A）：
 * 握手/版本协商/JSON-RPC 帧/传输全部交给库；tools/list 与 tools/call 走同一 transport 的原始请求，
 * 取回 JsonNode 转 gson——inputSchema 零损耗，image/audio/resource 等非 text 内容不抛异常。
 */
public class AjMcpClient implements McpHandle {

    /** 原始请求 id 段：库内部 idGenerator 从 1 递增，这里错开避免撞号 */
    private static final long RAW_ID_BASE = 100_000L;
    private static final int MAX_PAGES = 20;

    private final McpTransport transport;
    private final McpClient client;
    private final AtomicLong rawId = new AtomicLong(RAW_ID_BASE);
    private volatile boolean connected;

    public AjMcpClient(McpTransport transport) {
        this.transport = transport;
        this.client = McpClient.builder()
                .transport(transport)
                .clientName("minion")
                .clientVersion("0.1.0")
                .requestTimeout(Duration.ofMillis(CALL_TIMEOUT_MS))
                .build();
    }

    @Override
    public void connect() throws McpException {
        if (connected) return;
        try {
            client.initialize();
            // 服务端可主动 ping 客户端：回空 result（库默认无 handler 会回 -32601，部分服务端视为异常）
            client.onServerRequest(McpConstant.Methods.PING,
                    params -> JsonNodeFactory.instance.objectNode());
            connected = true;
        } catch (RuntimeException e) {
            close();
            throw new McpException("MCP 握手失败: " + rootMessage(e), e);
        }
    }

    @Override
    public List<McpToolInfo> listTools() throws McpException {
        List<McpToolInfo> out = new ArrayList<McpToolInfo>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            GetToolListRequest req = new GetToolListRequest();
            req.setId(rawId.getAndIncrement());
            if (cursor != null) req.setParams(new Cursor(cursor));
            JsonObject result = resultOf(req);
            for (JsonElement e : arrayOf(result, "tools")) {
                JsonObject t = e.isJsonObject() ? e.getAsJsonObject() : null;
                if (t == null || t.get("name") == null || t.get("name").isJsonNull()) continue;  // 畸形条目（缺 name/非对象）：跳过，不 NPE
                out.add(new McpToolInfo(
                        t.get("name").getAsString(),
                        t.has("description") ? t.get("description").getAsString() : "",
                        t.has("inputSchema") && t.get("inputSchema").isJsonObject()
                                ? t.getAsJsonObject("inputSchema") : new JsonObject()));
            }
            cursor = result.has("nextCursor") && !result.get("nextCursor").isJsonNull()
                    ? result.get("nextCursor").getAsString() : null;
            if (cursor == null || cursor.isEmpty()) return out;
        }
        throw new McpConnectionException("MCP tools/list 超过 " + MAX_PAGES + " 页，中止");
    }

    @Override
    public String callTool(String name, JsonObject args) throws McpException {
        CallToolRequest req = new CallToolRequest(name, args == null ? "{}" : args.toString());
        req.setId(rawId.getAndIncrement());
        JsonObject result = resultOf(req);
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : arrayOf(result, "content")) {
            JsonObject c = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            if (sb.length() > 0) sb.append('\n');
            String type = c.has("type") && !c.get("type").isJsonNull() ? c.get("type").getAsString() : null;
            if ("text".equals(type) && c.has("text") && !c.get("text").isJsonNull()) {
                sb.append(c.get("text").getAsString());
            } else {
                sb.append(c.toString());   // image/audio/resource 或缺 type 的畸形项：原样 JSON 文本
            }
        }
        boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
        if (isError) {
            throw new McpException(sb.length() == 0 ? "MCP 工具调用失败: " + name : sb.toString());
        }
        return sb.toString();
    }

    /** 发原始请求并取 result 节点（gson 视角，字段零损耗） */
    private JsonObject resultOf(com.ajaxjs.mcp.protocol.McpRequest req) throws McpException {
        JsonNode resp;
        try {
            resp = transport.sendRequestWithResponse(req).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new McpConnectionException("MCP 调用超时: " + req.getMethod());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpConnectionException("MCP 调用被中断: " + req.getMethod());
        } catch (Exception e) {
            throw new McpConnectionException("MCP 调用失败: " + rootMessage(e), e);
        }
        if (resp == null) return new JsonObject();
        JsonObject msg = McpJson.toJsonObject(resp);
        if (msg.has("error")) {
            JsonObject err = msg.getAsJsonObject("error");
            throw new McpException("MCP 错误: "
                    + (err.has("message") ? err.get("message").getAsString() : err.toString()));
        }
        return msg.has("result") && msg.get("result").isJsonObject() ? msg.getAsJsonObject("result") : new JsonObject();
    }

    /** 从 result 取数组字段（gson 视角；缺失/非数组 → 空列表） */
    private static List<JsonElement> arrayOf(JsonObject result, String key) {
        if (result.has(key) && result.get(key).isJsonArray()) {
            List<JsonElement> out = new ArrayList<JsonElement>();
            for (JsonElement e : result.getAsJsonArray(key)) out.add(e);
            return out;
        }
        return Collections.emptyList();
    }

    @Override
    public void close() {
        connected = false;
        try {
            client.close();
        } catch (RuntimeException ignored) { }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
