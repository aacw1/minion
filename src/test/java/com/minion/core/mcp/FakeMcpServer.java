package com.minion.core.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 测试用伪 MCP 服务器：从 stdin 按行读 JSON-RPC，回固定响应（initialize/tools/list 分页/tools/call），响应 id 取自请求 */
public class FakeMcpServer {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static String idOf(String line) {
        Matcher m = ID_PATTERN.matcher(line);
        return m.find() ? m.group(1) : "0";
    }

    /**
     * 单行 JSON-RPC 请求 → 响应 JSON 行（不带换行）；通知类返回 null（无需回）。
     * 供 stdio main 与 FakeSseMcpServer（HTTP+SSE 复用应答逻辑）共用。
     */
    static String respondTo(String line) {
        if (line.contains("\"initialize\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}}";
        }
        if (line.contains("\"notifications/initialized\"")) {
            return null;   // 通知无响应
        }
        if (line.contains("\"tools/list\"")) {
            // 分页：第一页带 nextCursor，第二页（请求带 cursor）返回剩余
            boolean page2 = line.contains("\"cursor\":\"PAGE2\"");
            if (!page2) {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"tools\":["
                        + "{\"name\":\"fake_tool\",\"description\":\"fake tool desc\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}},"
                        + "{\"name\":\"tool_schema\",\"description\":\"rich schema\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"q\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]},"
                        + "\"nested\":{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"integer\"}}},"
                        + "\"list\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"q\"]}},"
                        + "{\"name\":\"tool_image\",\"description\":\"image\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"tool_error\",\"description\":\"error\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"description\":\"no name\",\"inputSchema\":{\"type\":\"object\"}}"   // 畸形条目：缺 name，客户端应跳过
                        + "],\"nextCursor\":\"PAGE2\"}}";
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"tools\":["
                    + "{\"name\":\"paged_tool\",\"description\":\"page2 tool\",\"inputSchema\":{\"type\":\"object\"}}"
                    + "]}}";
        }
        if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_image\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                    + "{\"type\":\"image\",\"data\":\"aGVsbG8=\",\"mimeType\":\"image/png\"}],\"isError\":false}}";
        }
        if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_error\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"boom\"}],\"isError\":true}}";
        }
        if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_die\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"dying\"}]}}";
        }
        if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_malformed\"")) {
            // 畸形 content：一项缺 type、一项缺 text 的 null 值；客户端不得 NPE
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                    + "{\"text\":\"orphan\"},"
                    + "{\"type\":\"text\",\"text\":null},"
                    + "{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}";
        }
        if (line.contains("\"tools/call\"") && line.contains("\"name\":\"fake_tool\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                    + "{\"type\":\"text\",\"text\":\"hello \"},"
                    + "{\"type\":\"text\",\"text\":\"world\"}],\"isError\":false}}";
        }
        if (line.contains("\"tools/call\"") && !line.contains("\"name\":\"fake_tool\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"error\":{\"code\":-32602,\"message\":\"unknown tool: "
                    + "nope\"}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}";
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        Writer out = new OutputStreamWriter(System.out, "UTF-8");
        String line;
        while ((line = in.readLine()) != null) {
            String r = respondTo(line);
            if (r != null) {
                out.write(r + "\n");
                out.flush();
            }
            if (line.contains("\"tool_die\"")) {
                System.exit(1);   // 模拟进程崩溃：读线程 EOF → failPendingRequests
            }
        }
    }
}
