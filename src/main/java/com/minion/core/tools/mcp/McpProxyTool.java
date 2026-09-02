package com.minion.core.tools.mcp;

import com.google.gson.JsonObject;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpToolInfo;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** MCP 工具适配器：把 MCP 服务器的工具暴露为内部 Tool（调用委托 McpManager 路由） */
public class McpProxyTool implements Tool {

    private final McpManager manager;
    private final String serverName;
    private final McpToolInfo info;

    public McpProxyTool(McpManager manager, String serverName, McpToolInfo info) {
        this.manager = manager;
        this.serverName = serverName;
        this.info = info;
    }

    @Override public String name() { return info.name; }
    @Override public String description() { return info.description; }
    @Override public JsonObject schema() { return info.schema; }

    /** 所属服务器名（注册冲突判定：同名已注册工具是否本服务器旧注册） */
    public String serverName() { return serverName; }

    @Override
    public ToolResult execute(JsonObject args) {
        try {
            return ToolResult.success(manager.call(serverName, info.name, args));
        } catch (Exception e) {
            // 传输层失败/工具错误：返回失败 ToolResult 给模型自调
            return ToolResult.error(e.getMessage() == null ? "MCP 调用失败" : e.getMessage());
        }
    }

    @Override public boolean isHighRisk(JsonObject args) { return false; }
}
