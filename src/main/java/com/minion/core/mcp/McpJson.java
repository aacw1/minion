package com.minion.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Jackson JsonNode → gson（仅转换，不解析业务结构）：tools/list、tools/call 原始响应零损耗透传 */
public final class McpJson {

    private McpJson() { }

    public static JsonObject toJsonObject(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return new JsonObject();
        return JsonParser.parseString(node.toString()).getAsJsonObject();
    }
}
