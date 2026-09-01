package com.minion.core.mcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MCP 服务器配置（gson 落盘字段）+ 运行时状态（transient 不落盘） */
public class McpServer {

    /** 传输类型：stdio（本地子进程）/ sse（旧版 HTTP+SSE）/ streamable（Streamable HTTP，规范推荐远程传输） */
    public static final String STDIO = "stdio";
    public static final String SSE = "sse";
    public static final String STREAMABLE = "streamable";

    /** 传输值归一化：null/未知 → stdio（旧配置兼容） */
    public static String normalizedTransport(String t) {
        if (t == null) return STDIO;
        String v = t.trim().toLowerCase();
        if (SSE.equals(v)) return SSE;
        if (STREAMABLE.equals(v)) return STREAMABLE;
        return STDIO;
    }

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    // ===== 配置字段（mcp.json 持久化） =====
    public String name;
    /** "stdio" | "sse" | "streamable"（读入时经 normalizedTransport 归一） */
    public String transport;
    /** stdio：可执行命令（Windows 下 .cmd/.bat 由 McpCommands 以 cmd /c 包装） */
    public String command;
    public List<String> args = new ArrayList<String>();
    public Map<String, String> env = new HashMap<String, String>();
    /** sse：服务端点 */
    public String url;
    public Map<String, String> headers = new HashMap<String, String>();
    public boolean enabled;

    // ===== 运行时状态（不落盘） =====
    public transient volatile State state = State.DISCONNECTED;
    public transient volatile String failReason;
    /** 连接成功后 tools/list 的结果（由 McpManager 填充） */
    public transient volatile List<McpToolInfo> tools = new ArrayList<McpToolInfo>();
    /** 因与内置工具重名被跳过的工具数（设置页展示） */
    public transient volatile int skippedTools;
}
