package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public void register(Tool tool) { tools.put(tool.name().toLowerCase(), tool); }

    public Tool get(String name) { return tools.get(name == null ? null : name.toLowerCase()); }

    public List<Tool> all() { return new ArrayList<Tool>(tools.values()); }

    public List<JsonObject> schemas() {
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (Tool t : tools.values()) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", t.name());
            fn.addProperty("description", t.description());
            fn.add("parameters", t.schema());
            JsonObject o = new JsonObject();
            o.addProperty("type", "function");
            o.add("function", fn); // OpenAI 兼容契约：function 字段必填（真实 API 校验）
            list.add(o);
        }
        return list;
    }
}
