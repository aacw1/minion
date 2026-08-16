package com.minion.core.mcp;

import com.google.gson.JsonObject;

/** JSON-RPC 2.0 消息构造/解析（MCP 协议载体；仅静态工具方法，无状态） */
public final class JsonRpc {

    private JsonRpc() { }

    /** 请求：{"jsonrpc":"2.0","id":N,"method":..,"params":..}；params 为 null 时不输出 */
    public static JsonObject request(int id, String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.addProperty("method", method);
        if (params != null) o.add("params", params);
        return o;
    }

    /** 响应：{"jsonrpc":"2.0","id":N,"result":..} */
    public static JsonObject response(int id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        if (result != null) o.add("result", result);
        return o;
    }

    /** 错误响应：{"jsonrpc":"2.0","id":N,"error":{"code":..,"message":..}} */
    public static JsonObject responseError(int id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.add("error", err);
        return o;
    }

    /** 通知：{"jsonrpc":"2.0","method":..,"params":..}（无 id） */
    public static JsonObject notification(String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("method", method);
        if (params != null) o.add("params", params);
        return o;
    }

    /** 有 method 且无 id → 通知 */
    public static boolean isNotification(JsonObject msg) {
        return msg.has("method") && !msg.has("id");
    }

    /** 消息 id（请求/响应/错误响应）；无 id 返回 -1 */
    public static int parseId(JsonObject msg) {
        return msg.has("id") ? msg.get("id").getAsInt() : -1;
    }
}
