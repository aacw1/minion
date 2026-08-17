package com.minion.demo.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * minion MCP 演示服务器（开发/测试用）。
 * 两种模式（同一 jar）：
 *   stdio     本地进程：stdin 按行读 JSON-RPC 请求，stdout 按行写响应
 *   sse <端口> 远程 HTTP：POST / 请求（响应体即 JSON-RPC 响应）+ GET / SSE 事件流
 * 用法：java -jar mcp-demo.jar stdio
 *       java -jar mcp-demo.jar sse 8090
 */
public class McpDemoServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String serverName; // demo-local / demo-remote

    public McpDemoServer(String serverName) {
        this.serverName = serverName;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: java -jar mcp-demo.jar stdio | sse <端口>");
            return;
        }
        if ("stdio".equals(args[0])) {
            new McpDemoServer("demo-local").runStdio();
        } else if ("sse".equals(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8090;
            new McpDemoServer("demo-remote").runSse(port);
        } else {
            System.err.println("未知模式: " + args[0]);
        }
    }

    // ==================== stdio 模式 ====================

    private void runStdio() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        System.err.println("[demo] " + serverName + " stdio 模式已启动");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            JsonObject msg;
            try {
                msg = new Gson().fromJson(line, JsonObject.class);
            } catch (Exception e) {
                continue; // 非 JSON 行忽略
            }
            if (msg == null || !msg.has("id")) continue; // 通知：不响应
            JsonObject resp = dispatch(msg);
            out.write(new Gson().toJson(resp) + "\n");
            out.flush();
        }
        System.err.println("[demo] stdio 输入结束，退出");
    }

    // ==================== sse 模式 ====================

    private void runSse(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", this::handleHttp);
        server.setExecutor(Executors.newCachedThreadPool()); // 非守护线程池，主线程返回后 JVM 不退出
        server.start();
        System.err.println("[demo] " + serverName + " SSE 服务器已启动: http://127.0.0.1:" + port + "/");
        // 进程由线程池撑住，主线程直接返回
    }

    private void handleHttp(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            if ("POST".equals(method)) {
                handlePost(ex);
            } else if ("GET".equals(method)) {
                handleGet(ex);
            } else {
                ex.sendResponseHeaders(405, -1);
            }
        } catch (Exception e) {
            ex.close();
        }
    }

    /** POST /：读请求体 → 分发 → 响应体即 JSON-RPC 响应（通知无 id → 200 空体） */
    private void handlePost(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        JsonObject msg;
        try {
            msg = new Gson().fromJson(body, JsonObject.class);
        } catch (Exception e) {
            msg = null;
        }
        if (msg == null || !msg.has("id")) {
            ex.sendResponseHeaders(200, -1); // 通知：空响应
            ex.close();
            return;
        }
        byte[] bytes = new Gson().toJson(dispatch(msg)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    /** GET /：SSE 事件流（客户端仅在服务端主动推送时才用到；保持连接 + 定时注释保活） */
    private void handleGet(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0); // chunked，保持连接打开
        final OutputStream os = ex.getResponseBody();
        Thread keepAlive = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (true) {
                        os.write((": keepalive\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        Thread.sleep(5000);
                    }
                } catch (Exception ignored) {
                    // 客户端断开（sse.cancel/进程退出）→ 写入失败 → 收尾
                } finally {
                    ex.close();
                }
            }
        }, "sse-keepalive");
        keepAlive.setDaemon(true);
        keepAlive.start();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8));
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }

    // ==================== JSON-RPC 分发 ====================

    /** 处理一条带 id 的请求 → 完整响应（result 或 error） */
    private JsonObject dispatch(JsonObject msg) {
        int id = msg.get("id").getAsInt();
        String method = msg.has("method") ? msg.get("method").getAsString() : "";
        JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : null;
        try {
            return response(id, handle(method, params));
        } catch (McpError e) {
            return responseError(id, e.code, e.message);
        }
    }

    /** 方法分发：initialize / ping / tools/list / tools/call；其余 -32601 */
    private JsonObject handle(String method, JsonObject params) throws McpError {
        if ("initialize".equals(method)) {
            JsonObject result = new JsonObject();
            result.addProperty("protocolVersion", PROTOCOL_VERSION);
            JsonObject caps = new JsonObject();
            caps.add("tools", new JsonObject());
            result.add("capabilities", caps);
            JsonObject info = new JsonObject();
            info.addProperty("name", serverName);
            info.addProperty("version", "1.0.0");
            result.add("serverInfo", info);
            return result;
        }
        if ("ping".equals(method)) {
            return new JsonObject();
        }
        if ("tools/list".equals(method)) {
            JsonObject result = new JsonObject();
            result.add("tools", listTools());
            return result;
        }
        if ("tools/call".equals(method)) {
            String name = params != null && params.has("name")
                    ? params.get("name").getAsString() : "";
            JsonObject args = params != null && params.has("arguments")
                    ? params.getAsJsonObject("arguments") : new JsonObject();
            JsonObject result = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("type", "text");
            item.addProperty("text", callTool(name, args));
            content.add(item);
            result.add("content", content);
            return result;
        }
        throw new McpError(-32601, "方法不存在: " + method);
    }

    /** 工具清单：inputSchema 为 JSON Schema（object + properties + required） */
    private JsonArray listTools() {
        JsonArray tools = new JsonArray();
        for (ToolDef t : toolDefs()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name);
            o.addProperty("description", t.description);
            o.add("inputSchema", t.schema);
            tools.add(o);
        }
        return tools;
    }

    private static class ToolDef {
        final String name;
        final String description;
        final JsonObject schema;
        ToolDef(String name, String description, JsonObject schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }
    }

    private static JsonObject objSchema(String[] required, Object... props) {
        // props: [name, schemaJson, name, schemaJson, ...]
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        for (int i = 0; i < props.length; i += 2) {
            properties.add((String) props[i], new Gson().fromJson((String) props[i + 1], JsonObject.class));
        }
        schema.add("properties", properties);
        JsonArray req = new JsonArray();
        for (String r : required) req.add(r);
        schema.add("required", req);
        return schema;
    }

    /** 工具定义：demo-local（stdio）与 demo-remote（sse）各一套，工具名不重复便于区分来源 */
    private List<ToolDef> toolDefs() {
        if ("demo-local".equals(serverName)) {
            return java.util.Arrays.asList(
                    new ToolDef("time_now", "获取当前本地日期时间（来自本地 stdio 服务器 demo-local）",
                            objSchema(new String[0])),
                    new ToolDef("add_numbers", "计算两个数字之和（来自本地 stdio 服务器 demo-local）",
                            objSchema(new String[]{"a", "b"},
                                    "a", "{\"type\":\"number\",\"description\":\"第一个数字\"}",
                                    "b", "{\"type\":\"number\",\"description\":\"第二个数字\"}"))
            );
        }
        return java.util.Arrays.asList(
                new ToolDef("echo_text", "原样返回输入文本（来自远程 SSE 服务器 demo-remote）",
                        objSchema(new String[]{"text"},
                                "text", "{\"type\":\"string\",\"description\":\"要回显的文本\"}")),
                new ToolDef("today_date", "获取今天的日期（来自远程 SSE 服务器 demo-remote）",
                        objSchema(new String[0])),
                new ToolDef("random_number", "生成随机整数（来自远程 SSE 服务器 demo-remote）",
                        objSchema(new String[0],
                                "min", "{\"type\":\"number\",\"description\":\"最小值，默认 1\"}",
                                "max", "{\"type\":\"number\",\"description\":\"最大值，默认 100\"}"))
        );
    }

    /** 工具执行：未知工具抛 -32601；参数缺失/非法抛 -32602 */
    private String callTool(String name, JsonObject args) throws McpError {
        if ("time_now".equals(name)) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        }
        if ("add_numbers".equals(name)) {
            double a = numArg(args, "a");
            double b = numArg(args, "b");
            return a + " + " + b + " = " + (a + b);
        }
        if ("echo_text".equals(name)) {
            if (args == null || !args.has("text")) throw new McpError(-32602, "缺少参数 text");
            return "echo: " + args.get("text").getAsString();
        }
        if ("today_date".equals(name)) {
            return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        }
        if ("random_number".equals(name)) {
            int min = args != null && args.has("min") ? (int) numArg(args, "min") : 1;
            int max = args != null && args.has("max") ? (int) numArg(args, "max") : 100;
            if (max <= min) throw new McpError(-32602, "max 必须大于 min");
            return String.valueOf(ThreadLocalRandom.current().nextInt(min, max + 1));
        }
        throw new McpError(-32601, "未知工具: " + name);
    }

    private static double numArg(JsonObject args, String key) throws McpError {
        if (args == null || !args.has(key)) throw new McpError(-32602, "缺少参数 " + key);
        try {
            return args.get(key).getAsDouble();
        } catch (Exception e) {
            throw new McpError(-32602, "参数 " + key + " 必须是数字");
        }
    }

    // ==================== 响应构造 ====================

    private static class McpError extends Exception {
        final int code;
        final String message;
        McpError(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private static JsonObject response(int id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        if (result != null) o.add("result", result);
        return o;
    }

    private static JsonObject responseError(int id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.add("error", err);
        return o;
    }
}
