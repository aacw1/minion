package com.minion.core.mcp;

import com.google.gson.JsonObject;

/** MCP 服务器工具清单条目（tools/list 结果，映射为内部 Tool 的元数据） */
public class McpToolInfo {
    public String name;
    public String description;
    /** inputSchema（JSON Schema，MCP 标准），原样透传给内部 Tool.schema() */
    public JsonObject schema;

    public McpToolInfo() { }

    public McpToolInfo(String name, String description, JsonObject schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }
}
