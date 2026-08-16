package com.minion.core.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcTest {

    @Test
    public void request_hasVersionIdMethodParams() {
        JsonObject r = JsonRpc.request(7, "tools/list", null);
        assertEquals("2.0", r.get("jsonrpc").getAsString());
        assertEquals(7, r.get("id").getAsInt());
        assertEquals("tools/list", r.get("method").getAsString());
        assertFalse(r.has("params")); // null params 不输出
    }

    @Test
    public void request_keepsParams() {
        JsonObject p = new JsonObject();
        p.addProperty("name", "x");
        JsonObject r = JsonRpc.request(1, "initialize", p);
        assertEquals("x", r.get("params").getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void response_roundtrip() {
        JsonObject res = JsonRpc.response(3, null);
        assertEquals("2.0", res.get("jsonrpc").getAsString());
        assertEquals(3, res.get("id").getAsInt());
        assertEquals(3, JsonRpc.parseId(res));
    }

    @Test
    public void errorResponse_hasErrorCodeAndMessage() {
        JsonObject e = JsonRpc.responseError(9, -32601, "method not found");
        assertEquals(-32601, e.get("error").getAsJsonObject().get("code").getAsInt());
        assertEquals("method not found", e.get("error").getAsJsonObject().get("message").getAsString());
        assertEquals(9, JsonRpc.parseId(e));
    }

    @Test
    public void notification_hasNoId_andDetected() {
        JsonObject n = JsonRpc.notification("notifications/initialized", new JsonObject());
        assertFalse(n.has("id"));
        assertTrue(JsonRpc.isNotification(n));
        assertFalse(JsonRpc.isNotification(JsonRpc.request(1, "x", null)));
        assertEquals(-1, JsonRpc.parseId(n));
    }
}
