package com.minion.core.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试用「噪声 MCP 服务器」：在标准协议之外输出 banner / 空行 / 日志 / stderr，验证客户端读循环容错与诊断能力。
 *
 * <p>开关（命令行 args）：
 * <ul>
 *   <li>{@code banner}    启动即输出一行非 JSON（模拟启动脚本/库的横幅输出）</li>
 *   <li>{@code blank}     启动即输出一个空行</li>
 *   <li>{@code log}       每处理一行请求前，先输出一行非 JSON 日志</li>
 *   <li>{@code stderr=N}  启动时向 stderr 输出 N 行</li>
 *   <li>{@code dieAfterInit} 响应 initialize 后立即退出（exit 3）</li>
 *   <li>{@code dieNow}    启动即向 stderr 输出后退出（exit 8，不读 stdin）</li>
 *   <li>{@code flaky=文件} 计数文件：前 2 次启动直接退出（exit 7），第 3 次才正常服务（用于重试验证）</li>
 *   <li>{@code capture=文件} 按原始字节读取 stdin，把每行以 {@code LF|}/{@code CR|} 前缀原样追加到文件
 *       （用于协议一致性断言：行尾、字段内容）</li>
 *   <li>{@code onlyVersion=X} 只接受 protocolVersion=X 的 initialize，其余版本打印 stderr 后 exit 9
 *       （用于协议版本降级验证）</li>
 * </ul>
 *
 * <p>协议面：initialize / notifications/initialized / tools/list（中文工具描述）/ tools/call（中文回声），
 * 全程 UTF-8 读写，用于验证中文内容零损耗。
 */
public class NoisyMcpServer {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    public static void main(String[] args) throws Exception {
        boolean banner = has(args, "banner");
        boolean blank = has(args, "blank");
        boolean log = has(args, "log");
        boolean dieAfterInit = has(args, "dieAfterInit");
        boolean dieNow = has(args, "dieNow");
        int stderrLines = intArg(args, "stderr=", 0);
        String flakyFile = arg(args, "flaky=");
        String captureFile = arg(args, "capture=");
        String onlyVersion = arg(args, "onlyVersion=");

        for (int i = 1; i <= stderrLines; i++) {
            System.err.println("noisy-stderr-line-" + i);
        }
        System.err.flush();

        if (flakyFile != null) {
            int attempt = bumpAndCount(flakyFile);
            if (attempt < 3) {
                System.err.println("flaky-start-fail: attempt " + attempt);
                System.err.flush();
                System.exit(7);
            }
        }
        if (dieNow) {
            System.err.println("noisy-stderr-die-now");
            System.err.flush();
            System.exit(8);
        }

        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        if (banner) out.println("NoisyMCP server starting (banner, not json)");
        if (blank) out.println();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        String line;
        if (captureFile != null || onlyVersion != null) {
            serveRaw(out, log, dieAfterInit, captureFile, onlyVersion);
            return;
        }
        while ((line = in.readLine()) != null) {
            if (log) out.println("noisy-log: received " + line.length() + " chars (not json)");
            if (line.trim().isEmpty()) continue;
            String resp = respondTo(line);
            if (resp != null) out.println(resp);
            if (dieAfterInit && line.contains("\"initialize\"")) {
                out.flush();
                System.exit(3);
            }
        }
    }

    /** 原始字节读循环：捕获每行行尾形态（CR|/LF|），可限制协议版本；用于协议一致性测试 */
    private static void serveRaw(PrintStream out, boolean log, boolean dieAfterInit,
                                 String captureFile, String onlyVersion) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = System.in.read()) != -1) {
            if (b != '\n') {
                buf.write(b);
                continue;
            }
            String raw = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            buf.reset();
            boolean cr = raw.endsWith("\r");
            String line = cr ? raw.substring(0, raw.length() - 1) : raw;
            if (captureFile != null) {
                Files.write(Paths.get(captureFile),
                        ((cr ? "CR|" : "LF|") + line + "\n").getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            if (log) out.println("noisy-log: received " + line.length() + " chars (not json)");
            if (line.trim().isEmpty()) continue;
            if (onlyVersion != null && line.contains("\"initialize\"")
                    && !line.contains("\"protocolVersion\":\"" + onlyVersion + "\"")) {
                System.err.println("rejected-protocol-version");
                System.err.flush();
                System.exit(9);
            }
            String resp = respondTo(line);
            if (resp != null) out.println(resp);
            if (dieAfterInit && line.contains("\"initialize\"")) {
                out.flush();
                System.exit(3);
            }
        }
    }

    /** 单行 JSON-RPC 请求 → 响应（通知返回 null）；与 FakeMcpServer 保持同一协议形态 */
    static String respondTo(String line) {
        JsonObject req;
        try {
            req = new JsonParser().parse(line).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;   // 非 JSON 请求：忽略（测试不覆盖）
        }
        String method = req.has("method") ? req.get("method").getAsString() : "";
        String id = idOf(line);
        if ("initialize".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"noisy\",\"version\":\"1.0\"}}}";
        }
        if ("notifications/initialized".equals(method)) {
            return null;
        }
        if ("tools/list".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{"
                    + "\"name\":\"echo_zh\",\"description\":\"中文回声工具（UTF-8 验证）\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"文本\":{\"type\":\"string\"}},\"required\":[\"文本\"]}}]}}";
        }
        if ("tools/call".equals(method)) {
            String text = "";
            try {
                JsonObject params = req.getAsJsonObject("params");
                if (params != null && params.has("arguments") && params.getAsJsonObject("arguments").has("文本")) {
                    text = params.getAsJsonObject("arguments").get("文本").getAsString();
                }
            } catch (RuntimeException ignored) { }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\","
                    + "\"text\":\"回声: " + text + "\"}],\"isError\":false}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
    }

    private static String idOf(String line) {
        Matcher m = ID_PATTERN.matcher(line);
        return m.find() ? m.group(1) : "0";
    }

    /** 计数文件自增并返回最新值（用于 flaky 场景：前 N 次启动即退） */
    private static int bumpAndCount(String file) throws IOException {
        Path p = Paths.get(file);
        int n = Files.exists(p) ? Integer.parseInt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim()) : 0;
        n++;
        Files.write(p, String.valueOf(n).getBytes(StandardCharsets.UTF_8));
        return n;
    }

    private static boolean has(String[] args, String key) {
        for (String a : args) if (key.equals(a)) return true;
        return false;
    }

    private static String arg(String[] args, String prefix) {
        for (String a : args) if (a.startsWith(prefix)) return a.substring(prefix.length());
        return null;
    }

    private static int intArg(String[] args, String prefix, int dflt) {
        String v = arg(args, prefix);
        return v == null ? dflt : Integer.parseInt(v.trim());
    }
}
