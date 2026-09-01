package com.minion.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Jackson JsonNode → gson：MCP 响应的 inputSchema 等字段原样透传，不经库的有损类型模型 */
public class McpJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void toJsonObject_preservesNestedAndEnum() throws Exception {
        String json = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]},"
                + "\"nested\":{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"integer\"}}},"
                + "\"list\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";
        JsonObject o = McpJson.toJsonObject(MAPPER.readTree(json));
        assertEquals("object", o.get("type").getAsString());
        JsonObject q = o.getAsJsonObject("properties").getAsJsonObject("q");
        assertEquals("a", q.getAsJsonArray("enum").get(0).getAsString());
        JsonObject nested = o.getAsJsonObject("properties").getAsJsonObject("nested");
        assertTrue(nested.getAsJsonObject("properties").has("k"));
        assertTrue(o.getAsJsonObject("properties").getAsJsonObject("list").has("items"));
    }

    @Test
    public void toJsonObject_null_or_missing_isEmptyObject() throws Exception {
        assertEquals(new JsonObject(), McpJson.toJsonObject(null));
        assertEquals(new JsonObject(), McpJson.toJsonObject(MAPPER.readTree("null")));
        assertEquals(new JsonObject(), McpJson.toJsonObject(MAPPER.readTree("\"str\"")));
    }
}
