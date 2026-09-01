package com.minion.core.mcp;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MCP 管理器：状态机 + 惰性连接（首次 ensureConnectedAsync 才 spawn 进程）+ 全局工具表 + 退出关停 */
public class McpManager {

    /** 重连（同步等待）上限：设置页「重连」与 call 路由的等待时间 */
    private static final long RECONNECT_TIMEOUT_MS = 10_000;

    public interface Listener {
        /** 状态或工具表变化（连接成功/失败/断开/工具更新） */
        void onStateChanged(McpServer server);
    }

    private final McpStore store;
    private final List<Listener> listeners = new ArrayList<Listener>();
    /** name → 已建立的客户端（连接成功后放入，disconnect/shutdown 移除） */
    private final Map<String, McpHandle> clients = new HashMap<String, McpHandle>();

    public McpManager(McpStore store) {
        this.store = store;
    }

    public List<McpServer> servers() { return store.list(); }

    /** 持久化服务器配置（设置页新建/编辑/删除后调用） */
    public void save() { store.save(); }

    public void addListener(Listener l) { listeners.add(l); }

    /** 惰性连接入口（幂等）：CONNECTING/CONNECTED 直接返回；否则后台线程连接 */
    public void ensureConnectedAsync(final String name) {
        final McpServer s = find(name);
        if (s == null || !s.enabled) return;
        synchronized (this) {
            if (s.state == McpServer.State.CONNECTING || s.state == McpServer.State.CONNECTED) return;
            s.state = McpServer.State.CONNECTING;
            s.failReason = null;
        }
        notifyListeners(s);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { doConnect(s); }
        }, "mcp-connect-" + name);
        t.setDaemon(true);
        t.start();
    }

    /** 连接流程（连接线程内执行）：建客户端 → 握手 → 工具清单 → CONNECTED；异常 → FAILED + 原因 */
    private void doConnect(McpServer s) {
        try {
            McpHandle client = "sse".equalsIgnoreCase(s.transport) && s.url != null
                    ? new SseMcpClient(s.url, s.headers)
                    : new StdioMcpClient(commandParts(s), s.env);
            try {
                client.connect();
                List<McpToolInfo> tools = client.listTools();
                synchronized (this) {
                    clients.put(s.name, client);
                    s.tools = new ArrayList<McpToolInfo>(tools);
                    s.state = McpServer.State.CONNECTED;
                }
            } catch (Exception e) {
                client.close();
                throw e;
            }
        } catch (Exception e) {
            synchronized (this) {
                s.state = McpServer.State.FAILED;
                s.failReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        notifyListeners(s);
    }

    /** stdio 命令组装：command + args（含 .cmd/.bat 的包装由 StdioMcpClient 负责） */
    private static List<String> commandParts(McpServer s) {
        List<String> parts = new ArrayList<String>();
        parts.add(s.command);
        if (s.args != null) parts.addAll(s.args);
        return parts;
    }

    /** 关闭连接：进程销毁 + 状态 DISCONNECTED + 清工具表 */
    public void disconnect(String name) {
        final McpServer s = find(name);
        if (s == null) return;
        McpHandle c;
        synchronized (this) {
            c = clients.remove(name);
            s.state = McpServer.State.DISCONNECTED;
            s.tools = new ArrayList<McpToolInfo>();
            s.failReason = null;
        }
        if (c != null) c.close();
        notifyListeners(s);
    }

    /** 重连（同步等待结果 ≤10s）：设置页「重连」按钮与 call 路由前调用 */
    public void reconnect(String name) {
        final McpServer s = find(name);
        if (s == null) return;
        ensureConnectedAsync(name);
        long deadline = System.currentTimeMillis() + RECONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (s.state == McpServer.State.CONNECTED || s.state == McpServer.State.FAILED) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public List<McpToolInfo> toolsOf(String name) {
        McpServer s = find(name);
        return s == null ? new ArrayList<McpToolInfo>() : new ArrayList<McpToolInfo>(s.tools);
    }

    /** 路由工具调用：未连接先同步重连（≤10s）；失败抛 McpException（上层映射 ToolResult.error） */
    public String call(String serverName, String toolName, JsonObject args) throws Exception {
        McpServer s = find(serverName);
        if (s == null) throw new McpException("MCP 服务器不存在: " + serverName);
        McpHandle c;
        synchronized (this) {
            c = clients.get(serverName);
        }
        if (c == null) {
            reconnect(serverName);
            synchronized (this) {
                c = clients.get(serverName);
            }
        }
        if (c == null) {
            String reason = s.failReason == null ? "未连接" : s.failReason;
            throw new McpException("MCP 服务器不可用(" + serverName + "): " + reason);
        }
        return c.callTool(toolName, args);
    }

    /** 退出关停：全部断开 */
    public void shutdown() {
        for (McpServer s : new ArrayList<McpServer>(servers())) {
            disconnect(s.name);
        }
    }

    private McpServer find(String name) {
        for (McpServer s : servers()) {
            if (s.name != null && s.name.equals(name)) return s;
        }
        return null;
    }

    private void notifyListeners(McpServer s) {
        for (Listener l : listeners) l.onStateChanged(s);
    }
}
