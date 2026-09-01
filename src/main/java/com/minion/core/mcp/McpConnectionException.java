package com.minion.core.mcp;

/** 连接层失败（超时/断流/进程退出/未连接）：区别于工具业务错误，McpManager 用它触发重连 */
public class McpConnectionException extends McpException {
    public McpConnectionException(String message) { super(message); }
    public McpConnectionException(String message, Throwable cause) { super(message, cause); }
}
