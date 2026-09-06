package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（每会话一个）。
 * 可插拔工具用「拉模式」生效：工具无条件注册并打上所属插件标签，取用（schemas/get/all）时
 * 经 PluginGate 按当前启用状态过滤——不启用的工具其 name/description/parameters 完全不进请求，
 * 改开关无需通知任何会话，AgentLoop 下一轮 registry.schemas() 自动生效。
 * gate 为 null 或工具无插件标签（内置工具）时不过滤，行为与改造前一致。
 */
public class ToolRegistry {

    /** 插件启用判定；由 ToolPluginManager 实现，所有会话共享同一实例 */
    public interface PluginGate {
        boolean enabled(String pluginId);
    }

    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();
    /** 工具名（小写）→ 插件 id；null 表示内置工具（永不过滤） */
    private final Map<String, String> ownerOf = new HashMap<String, String>();
    private PluginGate gate;

    /** 内置工具注册（无插件归属，不受 gate 影响） */
    public void register(Tool tool) { register(null, tool); }

    /** 可插拔工具注册：pluginId 用于 gate 过滤 */
    public void register(String pluginId, Tool tool) {
        String key = tool.name().toLowerCase();
        tools.put(key, tool);
        ownerOf.put(key, pluginId);   // 同名覆盖时归属一并更新
    }

    public void setGate(PluginGate gate) { this.gate = gate; }

    /** 按名取工具；属于已禁用插件的工具返回 null（AgentLoop 回「工具不存在或已停用」） */
    public Tool get(String name) {
        if (name == null) return null;
        String key = name.toLowerCase();
        Tool t = tools.get(key);
        return t != null && visible(key) ? t : null;
    }

    public List<Tool> all() {
        List<Tool> out = new ArrayList<Tool>();
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (visible(e.getKey())) out.add(e.getValue());
        }
        return out;
    }

    /** OpenAI 兼容契约：{type:"function", function:{name,description,parameters}} */
    public List<JsonObject> schemas() {
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (!visible(e.getKey())) continue;
            Tool t = e.getValue();
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

    private boolean visible(String key) {
        if (gate == null) return true;
        String owner = ownerOf.get(key);
        return owner == null || gate.enabled(owner);
    }
}
