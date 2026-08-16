package com.minion.core.mcp;

/** MCP 连接/协议/调用异常（区别于工具失败：抛异常表示传输层故障） */
public class McpException extends Exception {
    public McpException(String message) { super(message); }
    public McpException(String message, Throwable cause) { super(message, cause); }
}
