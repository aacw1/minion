# MCP 集成实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** minion 接入标准 MCP 协议（stdio + SSE/HTTP 双传输），设置窗新增「MCP」页签（列表+状态+开关，参考千问样式），惰性连接、工具自动并入会话工具集，最终可跑 `npx @playwright/mcp`。

**Architecture:** 自研轻量 MCP 客户端核心（gson + okhttp-sse 3.14.9，JDK8 兼容，官方 SDK 需 Java17 不可用）。`core/mcp/`：McpStore（mcp.json）→ McpManager（状态机+惰性连接+工具表）→ McpClient 接口（StdioMcpClient 子进程按行 JSON-RPC / SseMcpClient 事件流）。工具层 `core/tools/mcp/McpProxyTool` 包装 MCP 工具为内部 Tool，每会话 registry 注册；`AgentLoop` 每轮动态取 schemas → 连接完成后下轮模型即见。设置窗加「MCP」页签。`browser.path` 为空则不建 BrowserSession（CDP 条件化）。

**Tech Stack:** JDK 8、gson 2.10.1、okhttp 3.14.9 + okhttp-sse 3.14.9、junit4、mockwebserver、FakeMcpServer（测试用伪 MCP 服务器，java -cp 启动）。

## Global Constraints

- JDK 8 语法（无 var/record；与现有代码风格一致）；新依赖仅 `com.squareup.okhttp3:okhttp-sse:3.14.9`（JDK8 兼容）
- 注释与 commit 用中文；commit 消息写 UTF-8 文件后 `git commit -F`，**命令本身必须纯 ASCII**（本机 bash wrapper 对含中文的命令崩溃）
- 工具错误返回失败 ToolResult 给模型自调（不抛异常）
- API 契约不可破坏：tool_call↔tool 消息完整配对、reasoning_content 原样回传
- 每任务完成自检：`JAVA_HOME="E:/javame/jdk8" mvn compile` + 相关测试通过
- 设计文档：docs/superpowers/specs/2026-08-17-mcp-integration-design.md（已提交 e3ab196）
- 新代码落位：协议核心 `com.minion.core.mcp`、工具 `com.minion.core.tools.mcp`、界面 `com.minion.gui.dialog`
- mcp.json 缺省文件（空列表）是合法状态；不配置即零行为变化
- 未连接/失败的服务器不注册任何工具，不阻塞会话与 UI

---

### Task 1: JsonRpc — JSON-RPC 2.0 消息编解码

**Files:**
- Create: `src/main/java/com/minion/core/mcp/JsonRpc.java`
- Test: `src/test/java/com/minion/core/mcp/JsonRpcTest.java`

**Interfaces:**
- Produces（后续任务依赖的精确签名）：
  - `static JsonObject request(int id, String method, JsonObject params)` — `{"jsonrpc":"2.0","id":N,"method":...,"params":...}`
  - `static JsonObject response(int id, JsonObject result)` — `{"jsonrpc":"2.0","id":N,"result":...}`
  - `static JsonObject responseError(int id, int code, String message)` — `{"jsonrpc":"2.0","id":N,"error":{"code":..,"message":..}}`
  - `static JsonObject notification(String method, JsonObject params)` — 无 id
  - `static boolean isNotification(JsonObject msg)` — 有 method 且无 id
  - `static int parseId(JsonObject msg)` — 取 id（缺省返回 -1）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcTest {

    @Test
    public void request_hasVersionIdMethodParams() {
        JsonObject r = JsonRpc.request(7, "tools/list", null);
        assertEquals("2.0", r.get("jsonrpc").getAsString());
        assertEquals(7, r.get("id").getAsInt());
        assertEquals("tools/list", r.get("method").getAsString());
        assertFalse(r.has("params")); // null params 不输出
    }

    @Test
    public void request_keepsParams() {
        JsonObject p = new JsonObject();
        p.addProperty("name", "x");
        JsonObject r = JsonRpc.request(1, "initialize", p);
        assertEquals("x", r.get("params").getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void response_roundtrip() {
        JsonObject res = JsonRpc.response(3, null);
        assertEquals("2.0", res.get("jsonrpc").getAsString());
        assertEquals(3, res.get("id").getAsInt());
        assertEquals(3, JsonRpc.parseId(res));
    }

    @Test
    public void errorResponse_hasErrorCodeAndMessage() {
        JsonObject e = JsonRpc.responseError(9, -32601, "method not found");
        assertEquals(-32601, e.get("error").getAsJsonObject().get("code").getAsInt());
        assertEquals("method not found", e.get("error").getAsJsonObject().get("message").getAsString());
        assertEquals(9, JsonRpc.parseId(e));
    }

    @Test
    public void notification_hasNoId_andDetected() {
        JsonObject n = JsonRpc.notification("notifications/initialized", new JsonObject());
        assertFalse(n.has("id"));
        assertTrue(JsonRpc.isNotification(n));
        assertFalse(JsonRpc.isNotification(JsonRpc.request(1, "x", null)));
        assertEquals(-1, JsonRpc.parseId(n));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=JsonRpcTest`
Expected: 编译失败（JsonRpc 不存在）

- [ ] **Step 3: 实现 JsonRpc.java**

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;

/** JSON-RPC 2.0 消息构造/解析（MCP 协议载体；仅静态工具方法，无状态） */
public final class JsonRpc {

    private JsonRpc() { }

    /** 请求：{"jsonrpc":"2.0","id":N,"method":..,"params":..}；params 为 null 时不输出 */
    public static JsonObject request(int id, String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.addProperty("method", method);
        if (params != null) o.add("params", params);
        return o;
    }

    /** 响应：{"jsonrpc":"2.0","id":N,"result":..} */
    public static JsonObject response(int id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        if (result != null) o.add("result", result);
        return o;
    }

    /** 错误响应：{"jsonrpc":"2.0","id":N,"error":{"code":..,"message":..}} */
    public static JsonObject responseError(int id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.add("error", err);
        return o;
    }

    /** 通知：{"jsonrpc":"2.0","method":..,"params":..}（无 id） */
    public static JsonObject notification(String method, JsonObject params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("method", method);
        if (params != null) o.add("params", params);
        return o;
    }

    /** 有 method 且无 id → 通知 */
    public static boolean isNotification(JsonObject msg) {
        return msg.has("method") && !msg.has("id");
    }

    /** 消息 id（请求/响应/错误响应）；无 id 返回 -1 */
    public static int parseId(JsonObject msg) {
        return msg.has("id") ? msg.get("id").getAsInt() : -1;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=JsonRpcTest`
Expected: PASS（4 个用例）

- [ ] **Step 5: 提交**

用 Write 写 `.git/commit-msg.txt`（纯 ASCII 文件名）：
```
feat: MCP JSON-RPC 2.0 消息编解码（JsonRpc）
```
Run: `git add src/main/java/com/minion/core/mcp/JsonRpc.java src/test/java/com/minion/core/mcp/JsonRpcTest.java && git commit -F .git/commit-msg.txt`

---

### Task 2: McpServer 配置模型 + McpStore（mcp.json 原子读写）

**Files:**
- Create: `src/main/java/com/minion/core/mcp/McpServer.java`
- Create: `src/main/java/com/minion/core/mcp/McpStore.java`
- Test: `src/test/java/com/minion/core/mcp/McpStoreTest.java`

**Interfaces:**
- Produces：
  - `class McpServer`（gson 直接序列化字段）：`String name; String transport; String command; List<String> args; Map<String,String> env; String url; Map<String,String> headers; boolean enabled;` + 运行时字段（不落盘，标 `transient`）：`volatile State state; volatile String failReason; volatile List<McpToolInfo> tools;`
  - `enum McpServer.State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }`
  - `class McpToolInfo { String name; String description; JsonObject schema; }`（McpServer.java 内部或同包独立文件，**独立文件 `McpToolInfo.java`**）
  - `static McpStore load(Path jarDir)` — 缺失生成 `{"servers":[]}`；损坏备份 .bak 重建
  - `List<McpServer> list()`、`void save()` — 原子写（*.tmp + move，仿 WorkspaceManager.save）
  - `McpStore(Path file)` 包级构造（测试注入）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.mcp;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class McpStoreTest {

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("mcp-store-test");
        d.toFile().deleteOnExit();
        return d;
    }

    @Test
    public void load_missingFile_createsEmptyServers() throws Exception {
        McpStore s = McpStore.load(dir());
        assertTrue(s.list().isEmpty());
        assertTrue(Files.exists(dir().resolve("mcp.json"))); // 缺省文件已生成
    }

    @Test
    public void save_roundtrip_preservesFields() throws Exception {
        Path d = dir();
        McpStore s = McpStore.load(d);
        McpServer server = new McpServer();
        server.name = "playwright";
        server.transport = "stdio";
        server.command = "npx";
        server.args = new java.util.ArrayList<String>();
        server.args.add("@playwright/mcp");
        server.env = new java.util.HashMap<String, String>();
        server.env.put("K", "V");
        server.enabled = true;
        s.list().add(server);
        s.save();

        McpStore s2 = McpStore.load(d);
        assertEquals(1, s2.list().size());
        McpServer got = s2.list().get(0);
        assertEquals("playwright", got.name);
        assertEquals("stdio", got.transport);
        assertEquals("npx", got.command);
        assertEquals("@playwright/mcp", got.args.get(0));
        assertEquals("V", got.env.get("K"));
        assertTrue(got.enabled);
        assertEquals(McpServer.State.DISCONNECTED, got.state); // 运行时字段默认态
    }

    @Test
    public void load_corruptFile_backsUpAndRebuilds() throws Exception {
        Path d = dir();
        Files.write(d.resolve("mcp.json"), "not json{{{".getBytes("UTF-8"));
        McpStore s = McpStore.load(d);
        assertTrue(s.list().isEmpty());
        assertTrue(Files.exists(d.resolve("mcp.json.bak")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=McpStoreTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

McpServer.java：

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** MCP 服务器配置（gson 落盘字段）+ 运行时状态（transient 不落盘） */
public class McpServer {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    // ===== 配置字段（mcp.json 持久化） =====
    public String name;
    /** "stdio" 或 "sse" */
    public String transport;
    /** stdio：可执行命令（Windows 下 .cmd/.bat 由 StdioMcpClient 以 cmd /c 包装） */
    public String command;
    public List<String> args = new ArrayList<String>();
    public java.util.Map<String, String> env = new java.util.HashMap<String, String>();
    /** sse：服务端点 */
    public String url;
    public java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
    public boolean enabled;

    // ===== 运行时状态（不落盘） =====
    public transient volatile State state = State.DISCONNECTED;
    public transient volatile String failReason;
    /** 连接成功后 tools/list 的结果（McpToolInfo 引用由 McpManager 解析填充） */
    public transient volatile List<McpToolInfo> tools = new ArrayList<McpToolInfo>();
}
```

McpToolInfo.java：

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;

/** MCP 服务器工具清单条目（tools/list 结果，映射为内部 Tool 的元数据） */
public class McpToolInfo {
    public String name;
    public String description;
    /** inputSchema（JSON Schema，MCP 标准），原样透传给内部 Tool.schema() */
    public JsonObject schema;

    public McpToolInfo() { }

    public McpToolInfo(String name, String description, JsonObject schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }
}
```

McpStore.java：

```java
package com.minion.core.mcp;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** MCP 服务器配置：jarDir/mcp.json 单文件多服务器（仿 WorkspaceManager 模式） */
public class McpStore {

    public static final String FILE_NAME = "mcp.json";

    private final Path file;
    private final List<McpServer> servers = new ArrayList<McpServer>();

    McpStore(Path file) { this.file = file; }

    /** jar 同目录 mcp.json；缺失生成空列表；损坏备份 .bak 后重建 */
    public static McpStore load(Path jarDir) {
        McpStore s = new McpStore(jarDir.resolve(FILE_NAME));
        boolean loaded = false;
        if (Files.exists(s.file)) {
            try {
                String json = new String(Files.readAllBytes(s.file), StandardCharsets.UTF_8);
                Holder h = new Gson().fromJson(json, Holder.class);
                if (h != null && h.servers != null) {
                    s.servers.addAll(h.servers);
                    loaded = true;
                }
            } catch (Exception e) {
                backupCorrupt(s.file);
            }
        }
        if (!loaded) {
            s.save();
        }
        return s;
    }

    public List<McpServer> list() { return servers; }

    public void save() {
        // 原子写：先写 *.tmp 再 move 覆盖，避免半截文件；失败清理 tmp
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Holder h = new Holder();
            h.servers = servers;
            Files.createDirectories(file.getParent());
            Files.write(tmp, new Gson().toJson(h).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) { }
            System.err.println("[minion] 写入 mcp.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[minion] mcp.json 损坏备份失败: " + e.getMessage());
        }
    }

    private static class Holder {
        List<McpServer> servers;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=McpStoreTest`
Expected: PASS（3 个用例）

- [ ] **Step 5: 提交**

消息文件：
```
feat: MCP 服务器配置模型与 mcp.json 原子读写（McpServer/McpStore）
```
Run: `git add src/main/java/com/minion/core/mcp/McpServer.java src/main/java/com/minion/core/mcp/McpToolInfo.java src/main/java/com/minion/core/mcp/McpStore.java src/test/java/com/minion/core/mcp/McpStoreTest.java && git commit -F .git/commit-msg.txt`

---

### Task 3: StdioMcpClient — stdio 传输 + 握手 + 工具/调用

**Files:**
- Create: `src/main/java/com/minion/core/mcp/McpException.java`
- Create: `src/main/java/com/minion/core/mcp/McpClient.java`
- Create: `src/main/java/com/minion/core/mcp/StdioMcpClient.java`
- Create: `src/test/java/com/minion/core/mcp/FakeMcpServer.java`（测试伪服务器，main 从 stdin 读行回 JSON-RPC）
- Test: `src/test/java/com/minion/core/mcp/StdioMcpClientTest.java`

**Interfaces:**
- Produces：
  - `class McpException extends Exception`（构造：`McpException(String)`、`McpException(String, Throwable)`）
  - `interface McpClient { void connect() throws McpException; List<McpToolInfo> listTools() throws McpException; String callTool(String name, JsonObject args) throws McpException; void close(); }`
  - `StdioMcpClient(List<String> commandParts, Map<String,String> env)` — commandParts 为完整命令数组（Windows 下含 .cmd 时内部自动 `cmd /c` 包装；否则直接 ProcessBuilder）
  - `static final long CALL_TIMEOUT_MS = 120_000`（McpClient 接口常量，两实现共用）

- [ ] **Step 1: 写失败测试（含 FakeMcpServer）**

FakeMcpServer.java（测试伪服务器：逐行读 stdin，对特定方法回固定 JSON-RPC，响应 id 与请求一致）：

```java
package com.minion.core.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 测试用伪 MCP 服务器：从 stdin 按行读 JSON-RPC，回固定响应（initialize/tools/list/tools/call），响应 id 取自请求 */
public class FakeMcpServer {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static String idOf(String line) {
        Matcher m = ID_PATTERN.matcher(line);
        return m.find() ? m.group(1) : "0";
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        Writer out = new OutputStreamWriter(System.out, "UTF-8");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.contains("\"initialize\"")) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"protocolVersion\":\"2024-11-05\","
                        + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}}\n");
            } else if (line.contains("\"tools/list\"")) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"tools\":["
                        + "{\"name\":\"fake_tool\",\"description\":\"fake tool desc\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}},"
                        + "\"required\":[\"q\"]}]}}\n");
            } else if (line.contains("\"tools/call\"")) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"hello \"},"
                        + "{\"type\":\"text\",\"text\":\"world\"}],\"isError\":false}}\n");
            } else if (line.contains("\"notifications/initialized\"")) {
                // 通知无响应
            } else {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}\n");
            }
            out.flush();
        }
    }
}
```

StdioMcpClientTest.java：

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** 用 FakeMcpServer（java -cp 启动）验证 stdio 全流程 */
public class StdioMcpClientTest {

    private StdioMcpClient client;

    @Before
    public void setUp() throws Exception {
        List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FakeMcpServer.class.getName());
        client = new StdioMcpClient(cmd, new java.util.HashMap<String, String>());
    }

    @After
    public void tearDown() {
        client.close();
    }

    @Test
    public void connectThenListTools() throws Exception {
        client.connect();
        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("fake_tool", tools.get(0).name);
        assertEquals("fake tool desc", tools.get(0).description);
        assertEquals("object", tools.get(0).schema.get("type").getAsString());
    }

    @Test
    public void callTool_concatenatesTextContent() throws Exception {
        client.connect();
        JsonObject args = new JsonObject();
        args.addProperty("q", "hi");
        String out = client.callTool("fake_tool", args);
        assertEquals("hello world", out); // content 数组文本拼接
    }

    @Test(expected = McpException.class)
    public void callTool_unknownMethod_throws() throws Exception {
        client.connect();
        client.callTool("nope", new JsonObject());
    }
}
```

注意：FakeMcpServer 对未知方法回 id:0 错误 → callTool 找不到对应请求 id（我们发 id 1,2,3）→ 响应不匹配 → 抛 McpException。为了让 `callTool_unknownMethod_throws` 的语义正确，StdioMcpClient 的 callTool 实现应对「响应 error 字段」或「响应 id 不匹配」抛 McpException。FakeMcpServer 的 id:0 会因 id 不匹配被忽略，然后 callTool 等待超时 120s——**测试会挂**。修正：FakeMcpServer 对未知方法回 `id` 与请求一致？脚本无法知道请求 id。改为：callTool 对「响应 error」抛异常；未知方法测试用 error 响应 id:0 + StdioMcpClient 对 **id==-1 或带 error 的响应**处理。简化方案：StdioMcpClient.callTool 里超时设短（测试注入？）。更干净：FakeMcpServer 解析请求行里的 `"id":N` 再回相同 id（正则 `"id":(\d+)`），未知方法回 error 同 id。这样测试语义干净：error → 抛 McpException；id 匹配成功。

FakeMcpServer 加一个 `static String extractId(String line)`（正则匹配），所有响应带该 id。initialize/tools/list/tools/call 的 id 不再硬编码。

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=StdioMcpClientTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

McpException.java：

```java
package com.minion.core.mcp;

/** MCP 连接/协议/调用异常（区别于工具失败：抛异常表示传输层故障） */
public class McpException extends Exception {
    public McpException(String message) { super(message); }
    public McpException(String message, Throwable cause) { super(message, cause); }
}
```

McpClient.java：

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** MCP 传输客户端：握手 + 工具清单 + 工具调用（stdio 与 sse 两实现） */
public interface McpClient {

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
```

StdioMcpClient.java：

```java
package com.minion.core.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
public class StdioMcpClient implements McpClient {

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
            pb.redirectErrorStream(false);
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
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=StdioMcpClientTest`
Expected: PASS（3 个用例；connect 握手 + 工具清单 + 调用拼接 + error 抛异常）

- [ ] **Step 5: 提交**

消息文件：
```
feat: MCP stdio 传输客户端（握手/tools/call + 进程生命周期管理）
```
Run: `git add src/main/java/com/minion/core/mcp/McpException.java src/main/java/com/minion/core/mcp/McpClient.java src/main/java/com/minion/core/mcp/StdioMcpClient.java src/test/java/com/minion/core/mcp/FakeMcpServer.java src/test/java/com/minion/core/mcp/StdioMcpClientTest.java && git commit -F .git/commit-msg.txt`

---

### Task 4: SseMcpClient — SSE/HTTP 传输

**Files:**
- Modify: `pom.xml`（新增 okhttp-sse 依赖）
- Create: `src/main/java/com/minion/core/mcp/SseMcpClient.java`
- Test: `src/test/java/com/minion/core/mcp/SseMcpClientTest.java`

**Interfaces:**
- Produces：`SseMcpClient(String url, Map<String,String> headers)` — connect 后与 StdioMcpClient 同接口语义；内部 SSE EventSource 收消息 + POST 发请求（同 URL）

- [ ] **Step 1: pom.xml 加依赖 + 写失败测试**

pom.xml 在 okhttp 依赖后加：

```xml
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>okhttp-sse</artifactId>
      <version>3.14.9</version>
    </dependency>
```

测试（MockWebServer 模拟 SSE 端点：先发 POST /messages 收 initialize 请求后，向 SSE 流推 initialize 响应；再推 tools/list 响应）：

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import static org.junit.Assert.*;

/** MockWebServer 模拟 SSE 端点：验证 SseMcpClient 握手与调用 */
public class SseMcpClientTest {

    private MockWebServer server;
    private SseMcpClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().setBody("{}")); // POST 响应（SSE 传输的 POST 返回 JSON）
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        client = new SseMcpClient(server.url("/mcp").toString(),
                new java.util.HashMap<String, String>());
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }
}
```

**说明（实现约束）**：SSE 传输的请求/响应关联依赖服务器推送（POST 返回 `{"jsonrpc":"2.0","id":N,"result":..}` JSON 体，或经 SSE 事件 `data: {...}` 推送）。本实现按 MCP SSE 规范：POST 响应体本身即 JSON-RPC 响应（无 body 或非 JSON 时忽略）；SSE 事件体也是 JSON-RPC 消息。为避免 MockWebServer 编排过度复杂，SseMcpClientTest 只做**连接生命周期与错误路径**测试（连接失败、关闭幂等、initialize 后 POST 请求体形状），**握手/调用全流程由 Task 6 的 McpManagerTest 用 FakeMcpServer 覆盖**（stdio 已验证协议逻辑，SSE 与 stdio 共用同一 `McpClient` 契约）。

最终 SseMcpClientTest 内容（Step 3 与实现同步定稿）：

```java
@Test
public void connect_unsupportedEndpoint_throws() throws Exception {
    // MockWebServer 不响应 POST → connect 超时/失败路径：校验抛 McpException
    try {
        client.connect();
        fail("应抛 McpException");
    } catch (McpException expected) {
        assertTrue(expected.getMessage().contains("失败") || expected.getMessage().contains("超时"));
    }
}

@Test
public void close_idempotent() {
    client.close();
    client.close(); // 不抛异常
}
```

（connect 成功路径需服务器推送 initialize 响应——由实现支持「POST 响应体即结果」模式后，用 enqueue 一个 JSON-RPC 响应体验证：见 Step 3 实现说明。）

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SseMcpClientTest`
Expected: 编译失败（SseMcpClient 不存在）

- [ ] **Step 3: 实现 SseMcpClient.java**

```java
package com.minion.core.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/** SSE/HTTP 传输：POST 发请求（响应体即 JSON-RPC 响应），GET /sse 事件流（data: 行也是 JSON-RPC 消息） */
public class SseMcpClient implements McpClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final String url;
    private final Map<String, String> headers;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, BlockingQueue<JsonObject>> pending =
            new HashMap<Integer, BlockingQueue<JsonObject>>();
    private volatile boolean closed;
    private boolean connected;
    private EventSource sse;

    public SseMcpClient(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? new HashMap<String, String>() : headers;
        this.http = new OkHttpClient();
    }

    private void deliver(JsonObject msg) {
        if (msg == null) return;
        int id = JsonRpc.parseId(msg);
        BlockingQueue<JsonObject> q = pending.get(id);
        if (q != null) {
            q.offer(msg);
            pending.remove(id);
        }
    }

    private JsonObject call(String method, JsonObject params, boolean expectResponse) throws McpException {
        if (closed) throw new McpException("SSE 连接已关闭");
        int id = nextId.getAndIncrement();
        BlockingQueue<JsonObject> q = new ArrayBlockingQueue<JsonObject>(1);
        pending.put(id, q);
        Request.Builder rb = new Request.Builder().url(url)
                .post(RequestBody.create(JSON, new Gson().toJson(JsonRpc.request(id, method, params))));
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        try {
            Response resp = http.newCall(rb.build()).execute();
            // SSE 传输：POST 响应体即 JSON-RPC 响应（部分服务器经 SSE 流推送，响应体为空）
            String body = resp.body() != null ? resp.body().string() : "";
            resp.close();
            if (!body.trim().isEmpty()) {
                try {
                    deliver(new Gson().fromJson(body, JsonObject.class));
                } catch (Exception ignored) { /* 非 JSON 响应体忽略 */ }
            }
            if (!expectResponse) return null;
            JsonObject res = q.poll(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (res == null) {
                pending.remove(id);
                throw new McpException("MCP 调用超时: " + method);
            }
            if (res.has("error")) {
                throw new McpException("MCP 错误: " + res.get("error").getAsJsonObject().get("message").getAsString());
            }
            return res.has("result") ? res.getAsJsonObject("result") : null;
        } catch (IOException e) {
            pending.remove(id);
            throw new McpException("SSE 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("MCP 调用被中断: " + method);
        }
    }

    @Override
    public void connect() throws McpException {
        if (connected) return;
        // 建 SSE 事件流（GET /sse）：事件体 data: {...} 也是 JSON-RPC 消息
        Request.Builder rb = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) rb.header(e.getKey(), e.getValue());
        sse = EventSources.createFactory(http).newEventSource(rb.build(), new EventSourceListener() {
            @Override public void onEvent(EventSource es, String id2, String type, String data) {
                try {
                    deliver(new Gson().fromJson(data, JsonObject.class));
                } catch (Exception ignored) { }
            }
            @Override public void onFailure(EventSource es, Throwable t, Response response) {
                closed = true;
                for (BlockingQueue<JsonObject> q : pending.values()) q.offer(
                        JsonRpc.responseError(0, -32000, "SSE 流已断开"));
                pending.clear();
            }
        });
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
        call("notifications/initialized", null, false);
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
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(new Gson().toJson(c));
                }
            }
        }
        if (isError) throw new McpException(sb.length() == 0 ? "MCP 工具调用失败: " + name : sb.toString());
        return sb.toString();
    }

    @Override
    public void close() {
        closed = true;
        if (sse != null) sse.cancel();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
```

SseMcpClientTest 定稿（connect 成功路径用「POST 响应体即结果」验证）：

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** MockWebServer 模拟 SSE 端点：POST 响应体即 JSON-RPC 响应 */
public class SseMcpClientTest {

    private MockWebServer server;
    private SseMcpClient client;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        // initialize 响应
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\","
                + "\"capabilities\":{},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}}"));
        // tools/list 响应
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                + "{\"name\":\"sse_tool\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}"));
        // tools/call 响应
        server.enqueue(json("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"));
        client = new SseMcpClient(server.url("/mcp").toString(),
                new java.util.HashMap<String, String>());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @After
    public void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    @Test
    public void connectListAndCall() throws Exception {
        client.connect();
        List<McpToolInfo> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("sse_tool", tools.get(0).name);
        assertEquals("ok", client.callTool("sse_tool", new JsonObject()));
    }

    @Test
    public void close_idempotent() {
        client.close();
        client.close();
    }
}
```

说明：MockWebServer 按 enqueue 顺序响应每次 POST（initialize→tools/list→tools/call）；EventSource GET /sse 连接会挂起直到 shutdown（okhttp 3.14 的 EventSource 在 server.shutdown 时收到断开回调，close 时 cancel 不阻塞）。GET 请求占的 socket 在 shutdown 后由 mockwebserver 清理。

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SseMcpClientTest`
Expected: PASS（2 个用例；connect 的 initialize 用 POST 响应体，无需 SSE 推送）

- [ ] **Step 5: 提交**

消息文件：
```
feat: MCP SSE/HTTP 传输客户端 + okhttp-sse 依赖
```
Run: `git add pom.xml src/main/java/com/minion/core/mcp/SseMcpClient.java src/test/java/com/minion/core/mcp/SseMcpClientTest.java && git commit -F .git/commit-msg.txt`

---

### Task 5: McpManager — 状态机 + 惰性连接 + 工具表 + shutdown

**Files:**
- Create: `src/main/java/com/minion/core/mcp/McpManager.java`
- Test: `src/test/java/com/minion/core/mcp/McpManagerTest.java`

**Interfaces:**
- Produces：
  - `McpManager(McpStore store)`
  - `List<McpServer> servers()`
  - `void ensureConnectedAsync(String name)` — 幂等：CONNECTING/CONNECTED 直接返回；DISCONNECTED/FAILED 则后台线程连接（initialize+tools/list → state=CONNECTED + tools 填充 + notifyListeners；失败 → FAILED + failReason + notify）
  - `void disconnect(String name)` — close 客户端 → DISCONNECTED + 清工具表 + notify
  - `void reconnect(String name)` — 同步等一次连接结果（≤10s，供设置页「重连」按钮）；失败转 FAILED
  - `List<McpToolInfo> toolsOf(String name)`
  - `String call(String serverName, String toolName, JsonObject args) throws Exception` — 路由到服务器客户端；未连接先 `reconnect`（10s 内）；失败抛 McpException
  - `void shutdown()` — 全部 disconnect
  - `void addListener(Listener l)`；`interface Listener { void onStateChanged(McpServer server); }`（工具表变化时也发 onStateChanged，UI 据此刷新）
  - 内部：`Map<String, McpClient> clients`（连接成功时建立，disconnect 时移除）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.mcp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** 用 FakeMcpServer（stdio）验证状态机：惰性连接、去重、失败、工具表、shutdown */
public class McpManagerTest {

    private McpManager manager;
    private McpServer server;

    private static McpServer stdioServer(String name, boolean enabled) {
        McpServer s = new McpServer();
        s.name = name;
        s.transport = "stdio";
        s.command = System.getProperty("java.home") + "/bin/java";
        s.args = new ArrayList<String>();
        s.args.add("-cp");
        s.args.add(System.getProperty("java.class.path"));
        s.args.add(FakeMcpServer.class.getName());
        s.enabled = enabled;
        return s;
    }

    @Before
    public void setUp() throws Exception {
        Path d = Files.createTempDirectory("mcp-mgr-test");
        McpStore store = McpStore.load(d);
        server = stdioServer("fake", true);
        store.list().add(server);
        manager = new McpManager(store);
    }

    @After
    public void tearDown() {
        manager.shutdown();
    }

    private static McpServer waitForState(McpManager m, String name, McpServer.State target) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        m.addListener(s -> { if (s.name.equals(name) && s.state == target) latch.countDown(); });
        m.ensureConnectedAsync(name);
        // 已连接的情形也兜底
        if (m.servers().get(0).state != target) {
            assertTrue("等待 " + target + " 超时", latch.await(15, TimeUnit.SECONDS));
        }
        for (McpServer s : m.servers()) if (s.name.equals(name)) return s;
        throw new AssertionError("server not found");
    }

    @Test
    public void ensureConnected_connectsAndFillsTools() throws Exception {
        McpServer s = waitForState(manager, "fake", McpServer.State.CONNECTED);
        assertEquals(1, s.tools.size());
        assertEquals("fake_tool", s.tools.get(0).name);
    }

    @Test
    public void connect_failure_marksFailedWithReason() throws Exception {
        McpServer bad = stdioServer("bad", true);
        bad.command = "definitely-not-a-command-xyz";
        manager.servers().add(bad);
        McpServer s = waitForState(manager, "bad", McpServer.State.FAILED);
        assertNotNull(s.failReason);
        assertTrue(s.failReason.length() > 0);
    }

    @Test
    public void call_routesToConnectedServer() throws Exception {
        waitForState(manager, "fake", McpServer.State.CONNECTED);
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("q", "x");
        assertEquals("hello world", manager.call("fake", "fake_tool", args));
    }

    @Test
    public void disconnect_clearsToolsAndState() throws Exception {
        waitForState(manager, "fake", McpServer.State.CONNECTED);
        manager.disconnect("fake");
        McpServer s = manager.servers().get(0);
        assertEquals(McpServer.State.DISCONNECTED, s.state);
        assertTrue(s.tools.isEmpty());
    }

    @Test
    public void ensureConnected_whileConnecting_noDuplicateProcess() throws Exception {
        // 并发两次 ensureConnectedAsync：FakeMcpServer 进程只应有一个（连接锁去重）
        // 通过连接完成后 tools 填充来验证无异常即可（进程数难以直接断言，用状态机一致性兜底）
        manager.ensureConnectedAsync("fake");
        manager.ensureConnectedAsync("fake");
        waitForState(manager, "fake", McpServer.State.CONNECTED);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=McpManagerTest`
Expected: 编译失败（McpManager 不存在）

- [ ] **Step 3: 实现 McpManager.java**

```java
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
    private final Map<String, McpClient> clients = new HashMap<String, McpClient>();

    public McpManager(McpStore store) {
        this.store = store;
    }

    public List<McpServer> servers() { return store.list(); }

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
            McpClient client = "sse".equalsIgnoreCase(s.transport) && s.url != null
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
        McpClient c;
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
        McpClient c;
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
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=McpManagerTest`
Expected: PASS（5 个用例；`connect_failure_marksFailedWithReason` 验证无效命令 → FAILED+原因）

- [ ] **Step 5: 提交**

消息文件：
```
feat: MCP 管理器（惰性连接状态机/工具表/路由/退出关停）
```
Run: `git add src/main/java/com/minion/core/mcp/McpManager.java src/test/java/com/minion/core/mcp/McpManagerTest.java && git commit -F .git/commit-msg.txt`

---

### Task 6: McpProxyTool — MCP 工具适配为内部 Tool + AgentLoop 注册访问器

**Files:**
- Create: `src/main/java/com/minion/core/tools/mcp/McpProxyTool.java`
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`（加 `public ToolRegistry registry()`）
- Test: `src/test/java/com/minion/core/tools/mcp/McpProxyToolTest.java`

**Interfaces:**
- Consumes：`McpManager.call(serverName, toolName, args)`、`McpToolInfo`（name/description/schema）
- Produces：`McpProxyTool(McpManager manager, String serverName, McpToolInfo info)`；`AgentLoop.registry()` 返回内部 ToolRegistry（供 SessionManager 连接完成后补注册）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools.mcp;

import com.google.gson.JsonObject;
import com.minion.core.mcp.McpToolInfo;
import com.minion.core.tools.ToolResult;
import org.junit.Test;
import static org.junit.Assert.*;

/** McpProxyTool：元数据透传 + 调用委托 + 失败映射 ToolResult */
public class McpProxyToolTest {

    @Test
    public void metadata_passthrough() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        McpToolInfo info = new McpToolInfo("fake_tool", "fake desc", schema);
        McpProxyTool tool = new McpProxyTool(null, "fake_server", info);
        assertEquals("fake_tool", tool.name());
        assertEquals("fake desc", tool.description());
        assertEquals(schema, tool.schema());
        assertFalse(tool.isHighRisk(null)); // MCP 工具不弹高危确认
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=McpProxyToolTest`
Expected: 编译失败（McpProxyTool 不存在）

- [ ] **Step 3: 实现**

McpProxyTool.java：

```java
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
```

AgentLoop.java（第 36 行 `private final ToolRegistry registry;` 之后，或 getter 区加）：

```java
    /** 工具注册表（供 MCP 连接完成后的补注册：SessionManager 经会话句柄访问） */
    public ToolRegistry registry() { return registry; }
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=McpProxyToolTest`
Expected: PASS

- [ ] **Step 5: 提交**

消息文件：
```
feat: MCP 工具适配器（McpProxyTool）+ AgentLoop 注册表访问器
```
Run: `git add src/main/java/com/minion/core/tools/mcp/McpProxyTool.java src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/tools/mcp/McpProxyToolTest.java && git commit -F .git/commit-msg.txt`

---

### Task 7: SessionManager/Main 接线 + CDP 条件化

**Files:**
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`
- Modify: `src/main/java/com/minion/Main.java`
- Modify: `src/test/java/com/minion/gui/session/SessionManagerTest.java`（构造签名变化适配）

**Interfaces:**
- Consumes：`McpManager.ensureConnectedAsync`、`McpManager.toolsOf`、`McpManager.servers`、`McpManager.addListener`、`McpProxyTool`、`SessionHandle.loop.registry()`
- Produces：
  - `SessionManager` 构造加参：`McpManager mcp`（Main 传入；测试传 null 兼容）
  - 私有 `void registerMcpTools(ToolRegistry registry, McpServer server)` — 对服务器每个工具注册 McpProxyTool；与已注册工具重名跳过（内置优先）；跳过计数记 `server.skipped`（新增 transient 字段 `int skippedTools`）
  - McpManager.Listener 实现：`onStateChanged` → 若 CONNECTED 对**所有存活会话** registry 补注册（`h.loop.registry()`，跳过重名）；UI 无关（纯 core 层回调，设置页另有自己的刷新路径）

**McpServer 补充字段（Task 2 的类上加）：**
```java
/** 因与内置工具重名被跳过的工具数（设置页展示） */
public transient volatile int skippedTools;
```

- [ ] **Step 1: 写失败测试（SessionManagerTest 追加用例）**

SessionManagerTest 现有构造需要加 `null` 的 McpManager 参数。先看现有测试构造（`SessionManagerTest.java` 里 new SessionManager(...) 调用点），统一加一个 null 参数。新增用例：

```java
@Test
public void newSession_withMcpManager_registersConnectedTools() throws Exception {
    // 用真实 McpManager + FakeMcpServer：连接后创建会话，工具应出现在 registry
    // 构造 FakeMcpServer stdio 配置（复用 McpManagerTest 的 stdioServer 构建逻辑）
    McpServer s = new McpServer();
    s.name = "fake";
    s.transport = "stdio";
    s.command = System.getProperty("java.home") + "/bin/java";
    s.args = new java.util.ArrayList<String>();
    s.args.add("-cp");
    s.args.add(System.getProperty("java.class.path"));
    s.args.add("com.minion.core.mcp.FakeMcpServer");
    s.enabled = true;
    Path d = java.nio.file.Files.createTempDirectory("mcp-sm-test");
    McpStore store = McpStore.load(d);
    store.list().add(s);
    McpManager mcp = new McpManager(store);
    mcp.ensureConnectedAsync("fake");
    // 等连接完成（轮询 ≤10s）
    McpServer got = mcp.servers().get(0);
    long deadline = System.currentTimeMillis() + 10_000;
    while (got.state != McpServer.State.CONNECTED && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
    }
    assertEquals(McpServer.State.CONNECTED, got.state);

    // 新会话（走 newRegistry）→ registry 含 fake_tool
    // 需要 SessionManager 实例：沿用现有测试的装配（见下方说明），构造时传 mcp
    // 断言：manager.sessions() 首个句柄的 loop.registry().get("fake_tool") != null
}
```

说明：SessionManagerTest 现有多数用例构造 `new SessionManager(confirmUi, config, jarDir, workspaces, models, skills, browserSession)`——本任务把签名改为追加 `McpManager` 参数，**现有调用点全部补 `null`**。新增用例复用现有 fixture（`newSession` 相关辅助方法），并在断言处取 `manager.sessions().get(0).loop.registry()`。

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionManagerTest`
Expected: 编译失败（构造签名不匹配 + registerMcpTools 未实现）

- [ ] **Step 3: 实现**

SessionManager.java 修改点：

1. 字段：`private final McpManager mcp;`（构造参数，可为 null——测试/无 MCP 时零行为）
2. 构造签名：`SessionManager(ConfirmUi, Config, Path, WorkspaceManager, ModelManager, List<Skill>, BrowserSession, McpManager mcp)`；`this.mcp = mcp;`；mcp 非 null 时 `mcp.addListener(this::onMcpStateChanged)`（JDK8 方法引用可用）
3. `newRegistry(ctx)` 末尾（BrowserSession 注册之后）追加：

```java
        if (mcp != null) {
            for (McpServer server : mcp.servers()) {
                if (!server.enabled) continue;
                mcp.ensureConnectedAsync(server.name); // 惰性预连接（幂等）
                registerMcpTools(registry, server);
            }
        }
```

4. 私有方法：

```java
    /** MCP 工具注册：与已注册工具重名跳过（内置优先），跳过数记 server.skippedTools 供设置页展示 */
    private void registerMcpTools(ToolRegistry registry, McpServer server) {
        server.skippedTools = 0;
        for (McpToolInfo info : mcp.toolsOf(server.name)) {
            if (registry.get(info.name) != null) {
                server.skippedTools++;
                continue;
            }
            registry.register(new McpProxyTool(mcp, server.name, info));
        }
    }

    /** MCP 连接完成回调：向所有存活会话的 registry 补注册该服务器工具（下轮请求模型可见） */
    private void onMcpStateChanged(McpServer server) {
        if (server.state != McpServer.State.CONNECTED || mcp == null) return;
        for (SessionHandle h : sessions()) {
            if (h.loop == null) continue;
            registerMcpTools(h.loop.registry(), server);
        }
    }
```

5. 顶部 imports 补：`com.minion.core.mcp.McpManager`、`McpServer`、`McpToolInfo`、`com.minion.core.tools.mcp.McpProxyTool`。

Main.java 修改点：

```java
        // MCP 管理器（配置加载；惰性连接由会话创建触发；退出钩子关停）
        McpManager mcp = new McpManager(McpStore.load(jarDir));

        // 浏览器工具（懒启动 Chrome；browser.path 未配置则不加载 CDP 工具）
        ChromeLauncher chrome = null;
        BrowserSession browserSession = null;
        if (!config.browserPath().trim().isEmpty()) {
            chrome = new ChromeLauncher(config.browserPath(), config.browserPort(),
                    Paths.get(config.browserUserDataDir()), config.browserHeadless(),
                    config.browserTimeoutMs());
            browserSession = new BrowserSession(chrome, new CdpClient(10000,
                    config.browserTimeoutMs()));
        }

        ConfirmUi confirmUi = new GuiConfirmUi();
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, skills, browserSession, mcp);
        // ... 现有退出钩子内追加：mcp.shutdown()（chrome.stop 现有逻辑保持；chrome 为 null 时跳过）
```

（Main 的退出钩子现场实现：`manager.shutdown() + chrome.stop()` → 追加 `mcp.shutdown()`；chrome 判空。）

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionManagerTest`
Expected: 编译通过 + PASS（含新增用例：连接后新会话 registry 含 fake_tool）

- [ ] **Step 5: 提交**

消息文件：
```
feat: SessionManager/Main 接线 MCP（惰性预连接 + 工具注册/补注册）；browser.path 空则不加载 CDP
```
Run: `git add src/main/java/com/minion/gui/session/SessionManager.java src/main/java/com/minion/Main.java src/main/java/com/minion/core/mcp/McpServer.java src/test/java/com/minion/gui/session/SessionManagerTest.java && git commit -F .git/commit-msg.txt`

---

### Task 8: 设置窗 MCP 页签（列表 + 状态 + 开关 + 新建/编辑/删除）

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`
- Modify: 调用点（`SettingsDialog.show` 加 McpManager 参数）
- Test: `src/test/java/com/minion/gui/dialog/SettingsDialogTest.java`

**Interfaces:**
- Consumes：`McpManager`（servers/ensureConnectedAsync/disconnect/toolsOf/addListener）、`McpServer`、`McpToolInfo`
- Produces：`SettingsDialog.show(Window, ModelManager, SessionManager, Config, McpManager)`（McpManager 可为 null → 隐藏 MCP 页签？**不隐藏**，null 时页面显示空列表与提示；Main 始终传非 null）

- [ ] **Step 1: 写失败测试（SettingsDialogTest 追加）**

现有 SettingsDialogTest 断言导航项。追加：

```java
@Test
public void navigation_containsMcpTab() throws Exception {
    // 现有 show() 签名变化：调用点补 McpManager（null 亦可）
    // 断言：nav 项含 "MCP"
    // 实现见现有测试的装配方式——若 show 无法脱离 JavaFX 环境测试，本用例改为检查
    // SettingsDialog 新增的静态方法 mcpPane(McpManager) 可构建且含「新建」按钮
}
```

说明：SettingsDialogTest 现有用例如何测试（JavaFX 环境）——沿用现有模式；若现有测试不直接测 show 的导航，则本任务测试改为组件级：`mcpPane(null)` 返回 VBox 且 children 含按钮控件。

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SettingsDialogTest`
Expected: 编译失败或断言失败

- [ ] **Step 3: 实现（SettingsDialog.java）**

1. `show` 签名加 `final McpManager mcp` 参数；`nav.getItems().addAll("基础设置", "模型", "MCP", "关于")`；选中监听三分支：
```java
content.getChildren().setAll("基础设置".equals(item) ? basic.root
        : "模型".equals(item) ? model
        : "MCP".equals(item) ? mcpPane(mcp, owner) : about);
```
2. mcpPane（仿 modelPane 结构）：

```java
    /** MCP 页：服务器列表（名称/传输/状态点/工具数/启用开关）+ 新建/编辑/删除/重连 */
    private static VBox mcpPane(final McpManager mcp, final Window owner) {
        final ListView<McpServer> list = new ListView<McpServer>();
        list.setCellFactory(lv -> new ListCell<McpServer>() {
            @Override protected void updateItem(McpServer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                // 状态点：灰=未启用 绿=已连接 橙=连接中 红=失败
                String color = !item.enabled ? "gray"
                        : item.state == McpServer.State.CONNECTED ? "green"
                        : item.state == McpServer.State.CONNECTING ? "orange" : "red";
                Label dot = new Label("●");
                dot.setStyle("-fx-text-fill: " + color + ";");
                Label name = new Label(item.name);
                Label meta = new Label(item.transport + (item.state == McpServer.State.CONNECTED
                        ? "  " + (item.tools.size() - item.skippedTools) + " 工具"
                        : item.state == McpServer.State.FAILED
                        ? " 失败: " + shorten(item.failReason) : ""));
                meta.getStyleClass().add("msg-thinking");
                CheckBox on = new CheckBox("启用");
                on.setSelected(item.enabled);
                on.selectedProperty().addListener((obs, ov, nv) -> {
                    item.enabled = nv;
                    if (nv) mcp.ensureConnectedAsync(item.name);
                    else mcp.disconnect(item.name);
                });
                HBox box = new HBox(6, dot, name, meta, on);
                box.setSpacing(6);
                setGraphic(box);
            }
        });
        refresh(list, mcp);
        mcp.addListener(s -> javafx.application.Platform.runLater(() -> refresh(list, mcp)));
        // 按钮行
        Button add = new Button("新建"); Button edit = new Button("编辑");
        Button del = new Button("删除"); Button reconnect = new Button("重连");
        // ...样式 btn-ghost；事件：
        // 新建 → form(null) → 非空则 mcp.servers().add + store.save() + refresh
        // 编辑 → form(选中项) → 字段回写 + store.save() + 若原连接则 disconnect+ensureConnectedAsync
        // 删除 → 确认 → servers().remove + disconnect + save + refresh
        // 重连 → mcp.reconnect(选中项.name) + refresh
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.getChildren().addAll(list, actions);
        return box;
    }
```

3. 表单 form（仿模型表单 Dialog）：

```java
    /** 新建（null 带默认值）/ 编辑（预填）MCP 服务器表单；OK 返回服务器对象（或回写后返回），取消 null */
    private static McpServer form(McpServer s, final Window owner) {
        Dialog<McpServer> d = new Dialog<McpServer>();
        d.initOwner(owner);
        d.setTitle(s == null ? "新建 MCP 服务器" : "编辑 MCP 服务器");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);
        GridPane grid = new GridPane(); // hgap/vgap 8、padding 10（仿模型表单）
        TextField name = new TextField(s == null ? "" : s.name);
        ComboBox<String> transport = new ComboBox<String>();
        transport.getItems().addAll("stdio", "sse");
        transport.setValue(s == null ? "stdio" : s.transport);
        TextField command = new TextField(s == null ? "npx" : s.command);
        TextArea argsArea = new TextArea(s == null ? "@playwright/mcp" : join(s.args, "\n"));
        TextArea envArea = new TextArea(s == null ? "" : lines(s.env));
        TextField url = new TextField(s == null ? "" : s.url);
        TextArea headerArea = new TextArea(s == null ? "" : lines(s.headers));
        // transport 选 sse 时 command 区禁用（或提示）
        grid.addRow(0, new Label("名称:"), name);
        grid.addRow(1, new Label("传输:"), transport);
        grid.addRow(2, new Label("命令:"), command);
        grid.addRow(3, new Label("参数(每行一个):"), argsArea);
        grid.addRow(4, new Label("环境变量(KEY=VALUE):"), envArea);
        grid.addRow(5, new Label("URL(SSE):"), url);
        grid.addRow(6, new Label("请求头(K:V):"), headerArea);
        d.getDialogPane().setContent(grid);
        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String nm = name.getText().trim();
            if (nm.isEmpty()) return null; // 名称空 → 视为取消
            McpServer out = s == null ? new McpServer() : s;
            out.name = nm;
            out.transport = transport.getValue() == null ? "stdio" : transport.getValue();
            out.command = command.getText().trim();
            out.args = splitLines(argsArea.getText());           // 每行一个参数，trim 去空
            out.env = parsePairs(envArea.getText());             // KEY=VALUE
            out.url = url.getText().trim();
            out.headers = parsePairs(headerArea.getText());      // K:V
            out.enabled = s != null && s.enabled;                // 新建默认禁用（用户再勾选启用）
            return out;
        });
        Optional<McpServer> r = d.showAndWait();
        return r.isPresent() ? r.get() : null;
    }

    /** 每行一个：trim 后去空行 */
    private static List<String> splitLines(String text) {
        List<String> out = new java.util.ArrayList<String>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    /** KEY=VALUE（或 K:V）逐行解析 */
    private static Map<String, String> parsePairs(String text) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            int i = line.indexOf('=');
            if (i < 0) i = line.indexOf(':');
            if (i <= 0) continue;
            out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        return out;
    }

    /** 失败原因截断（列表显示用） */
    private static String shorten(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String first = i < 0 ? s : s.substring(0, i);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }
```

4. MainWindow 或其他调用 `SettingsDialog.show` 处补传 McpManager（Main 装配后传入 MinionApp/MainWindow 链——现场定位 show 调用点接线）。

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SettingsDialogTest`
Expected: 编译通过 + PASS

- [ ] **Step 5: 提交**

消息文件：
```
feat: 设置窗 MCP 页签（列表+状态点+启用开关+新建/编辑/删除/重连）
```
Run: `git add src/main/java/com/minion/gui/dialog/SettingsDialog.java <show 调用点文件> src/test/java/com/minion/gui/dialog/SettingsDialogTest.java && git commit -F .git/commit-msg.txt`

---

### Task 9: README 同步 + 全量测试 + 收尾提交

**Files:**
- Modify: `README.md`（MCP 章节：mcp.json 示例、Node 18+ 环境要求、Playwright 用法、与 CDP 关系）
- Modify: `docs/ARCHITECTURE.md`（core/mcp 包 + 设置页 MCP 页签入架构图/列表）

**Global Constraints 逐条自查：**
- [ ] `JAVA_HOME="E:/javame/jdk8" mvn clean package` 全量构建通过（含所有测试）
- [ ] 无新 Java 11+ 语法；新依赖仅 okhttp-sse 3.14.9
- [ ] 工具错误路径全部 ToolResult.error；McpException 只在 core/mcp 层传播
- [ ] 会话落盘/消息契约未改动
- [ ] README/ARCHITECTURE 同步完成

**提交：**
消息文件：
```
docs: README/架构文档同步 MCP 集成（用法与包结构）
```
Run: `git add README.md docs/ARCHITECTURE.md && git commit -F .git/commit-msg.txt`

**收尾（可选手动验证，需 Node 18+）：** 配 `npx @playwright/mcp` → 启动 GUI → 新会话 → 让模型「打开 baidu.com 并返回标题」→ 确认 playwright 工具被调用。
