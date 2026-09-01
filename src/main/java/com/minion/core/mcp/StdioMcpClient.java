package com.minion.core.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** stdio 传输：spawn 子进程，stdin 写请求（按行 JSON-RPC），stdout 读响应；Windows 下 .cmd/.bat 用 cmd /c 包装 */
public class StdioMcpClient implements McpHandle {

    private final Process process;
    private final Writer stdin;
    private final BufferedReader stdout;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, BlockingQueue<JsonObject>> pending =
            new HashMap<Integer, BlockingQueue<JsonObject>>();
    private final Thread reader;
    private volatile boolean closed;
    private boolean connected;

    /** @param commandParts 完整命令数组（如 [npx, @playwright/mcp]）；含 .cmd/.bat 时自动 cmd /c 包装 */
    public StdioMcpClient(List<String> commandParts, Map<String, String> env) throws McpException {
        try {
            List<String> finalCmd = commandParts;
            String head = commandParts.get(0);
            if (head.endsWith(".cmd") || head.endsWith(".bat")) {
                // Windows：npx 实为 npx.cmd，ProcessBuilder 直跑会找 .exe 失败，须 cmd /c 包装
                finalCmd = new ArrayList<String>();
                finalCmd.add("cmd");
                finalCmd.add("/c");
                finalCmd.addAll(commandParts);
            }
            ProcessBuilder pb = new ProcessBuilder(finalCmd);
            if (env != null && !env.isEmpty()) pb.environment().putAll(env);
            process = pb.start();
            stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            reader = new Thread(new Runnable() {
                @Override public void run() { readLoop(); }
            }, "mcp-stdio-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            throw new McpException("启动 MCP 进程失败: " + e.getMessage(), e);
        }
    }

    /** 响应分发：按 id 投递到对应 pending 队列（进程退出 → EOF → 各队列补错误响应唤醒等待方） */
    private void readLoop() {
        String line;
        try {
            while (!closed && (line = stdout.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JsonObject msg;
                try {
                    msg = new Gson().fromJson(line, JsonObject.class);
                } catch (Exception e) {
                    continue; // 非 JSON 行（进程日志）忽略
                }
                if (msg == null) continue;
                int id = JsonRpc.parseId(msg);
                BlockingQueue<JsonObject> q = pending.get(id);
                if (q != null) {
                    q.offer(msg);
                    pending.remove(id);
                }
            }
        } catch (IOException ignored) {
            // EOF/进程退出：统一唤醒
        } finally {
            closed = true;
            for (BlockingQueue<JsonObject> q : pending.values()) {
                q.offer(JsonRpc.responseError(0, -32000, "MCP 进程已退出"));
            }
            pending.clear();
        }
    }

    /** 同步调用：写请求 → 等响应（id 关联）；notify 型调用（expectResponse=false）不等待 */
    private JsonObject call(String method, JsonObject params, boolean expectResponse) throws McpException {
        if (closed) throw new McpException("MCP 进程已退出");
        int id = nextId.getAndIncrement();
        BlockingQueue<JsonObject> q = new ArrayBlockingQueue<JsonObject>(1);
        pending.put(id, q);
        try {
            synchronized (stdin) {
                stdin.write(new Gson().toJson(JsonRpc.request(id, method, params)) + "\n");
                stdin.flush();
            }
            if (!expectResponse) return null; // 通知：不等待
            JsonObject res = q.poll(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (res == null) {
                pending.remove(id);
                throw new McpException("MCP 调用超时: " + method);
            }
            if (res.has("error")) {
                throw new McpException("MCP 错误: " + res.get("error").getAsJsonObject().get("message").getAsString());
            }
            return res.has("result") ? res.getAsJsonObject("result") : null;
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("MCP 调用被中断: " + method);
        } catch (IOException e) {
            throw new McpException("写入 MCP 进程失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void connect() throws McpException {
        if (connected) return;
        JsonObject initParams = new JsonObject();
        initParams.addProperty("protocolVersion", "2024-11-05");
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "minion");
        clientInfo.addProperty("version", "0.1.0");
        initParams.add("clientInfo", clientInfo);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        initParams.add("capabilities", caps);
        call("initialize", initParams, true);
        call("notifications/initialized", null, false); // 通知，无响应
        connected = true;
    }

    @Override
    public List<McpToolInfo> listTools() throws McpException {
        JsonObject result = call("tools/list", null, true);
        List<McpToolInfo> tools = new ArrayList<McpToolInfo>();
        if (result != null && result.has("tools")) {
            for (JsonElement e : result.getAsJsonArray("tools")) {
                JsonObject t = e.getAsJsonObject();
                JsonElement schemaEl = t.get("inputSchema");
                JsonObject schema = schemaEl != null && schemaEl.isJsonObject()
                        ? schemaEl.getAsJsonObject() : new JsonObject();
                tools.add(new McpToolInfo(
                        t.get("name").getAsString(),
                        t.has("description") ? t.get("description").getAsString() : "",
                        schema));
            }
        }
        return tools;
    }

    @Override
    public String callTool(String name, JsonObject args) throws McpException {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", args == null ? new JsonObject() : args);
        JsonObject result = call("tools/call", params, true);
        StringBuilder sb = new StringBuilder();
        boolean isError = result != null && result.has("isError") && result.get("isError").getAsBoolean();
        if (result != null && result.has("content")) {
            for (JsonElement e : result.getAsJsonArray("content")) {
                JsonObject c = e.getAsJsonObject();
                if ("text".equals(c.get("type").getAsString())) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.get("text").getAsString());
                } else {
                    // resource/其他类型：转 JSON 文本
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(new Gson().toJson(c));
                }
            }
        }
        if (isError) {
            throw new McpException(sb.length() == 0 ? "MCP 工具调用失败: " + name : sb.toString());
        }
        return sb.toString();
    }

    @Override
    public void close() {
        closed = true;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try { stdin.close(); } catch (IOException ignored) { }
        try { stdout.close(); } catch (IOException ignored) { }
    }
}
