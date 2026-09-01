package com.minion.core.mcp;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/** MCP 连接抽象：握手 + 工具清单 + 工具调用（实现基于 aj-mcp-client，stdio/SSE/Streamable 三传输） */
public interface McpHandle {

    /** 工具调用超时（含 SSE 等待响应；playwright 导航可能较慢） */
    long CALL_TIMEOUT_MS = 120_000;

    /** 握手：initialize → notifications/initialized（幂等：已连接直接返回） */
    void connect() throws McpException;

    /** 工具清单（connect 之后调用） */
    List<McpToolInfo> listTools() throws McpException;

    /** 调用工具：content 数组文本化拼接（text 原样 / resource 转 JSON 文本），isError=true 时抛 McpException */
    String callTool(String name, JsonObject args) throws McpException;

    /** 关闭连接、释放进程/流 */
    void close();
}
