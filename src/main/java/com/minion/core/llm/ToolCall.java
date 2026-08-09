package com.minion.core.llm;

import com.google.gson.JsonObject;

/** 工具调用（模型发出或落盘持久化） */
public class ToolCall {
    public String id;
    public String type = "function";
    public String name;
    public String arguments; // 参数 JSON 字符串

    public JsonObject toApiJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", arguments == null ? "{}" : arguments);
        o.add("function", fn);
        return o;
    }

    public static ToolCall fromApi(JsonObject o) {
        ToolCall tc = new ToolCall();
        tc.id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
        if (o.has("type") && !o.get("type").isJsonNull()) tc.type = o.get("type").getAsString();
        JsonObject fn = o.has("function") ? o.getAsJsonObject("function") : null;
        if (fn != null) {
            if (fn.has("name") && !fn.get("name").isJsonNull()) tc.name = fn.get("name").getAsString();
            if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) tc.arguments = fn.get("arguments").getAsString();
        }
        return tc;
    }
}
