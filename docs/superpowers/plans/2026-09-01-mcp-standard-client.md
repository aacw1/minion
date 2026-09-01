# MCP 标准客户端改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 minion 的 MCP 客户端从自研 JSON-RPC 换成 `aj-mcp-client:1.5` 标准实现（stdio / 旧版 SSE / Streamable HTTP 三传输），并把 MCP 配置表单的传输方式改成三选一单选、不适用字段隐藏并清空。

**Architecture:** 协议与传输（握手、版本协商、JSON-RPC 帧、子进程/连接生命周期）全部交给库；minion 持有同一个 `McpTransport` 实例，对 `tools/list` 与 `tools/call` 走原始 `JsonNode` 取回（绕开库里有损的 `JsonSchema` 类型模型与对非 text 内容抛异常的 `callTool()`）。`McpManager` 状态机、工具注册链路、`McpProxyTool` 全部保留。

**Tech Stack:** JDK 8、Maven 单模块、gson 2.10.1、okhttp/okhttp-sse/mockwebserver 4.12.0、aj-mcp-client 1.5（传递 jackson 2.18.3 + slf4j-simple 1.7.36）、JUnit 4。

## Global Constraints

- JDK 8 语法：不用 `var`、`List.of`、文本块、`switch` 表达式；lambda 可用（项目在用）
- 中文注释与中文 commit（conventional 格式）
- 资源目录是 `src/resource`（非 src/main/resources）
- `mcp.json` 字段结构不变（name/transport/command/args/env/url/headers/enabled），只扩展 `transport` 取值
- 库的 API 签名以本计划为准（均已从 jar 的 javap/sources 实测）：`McpClient.builder().transport(t).clientName(..).clientVersion(..).requestTimeout(Duration).build()`；`transport.sendRequestWithResponse(McpRequest)` 返回 `CompletableFuture<JsonNode>`；`GetToolListRequest`/`CallToolRequest(name, jsonArgsStr)` 均需先 `setId(Long)`
- `mvn test` 全绿与 `mvn package` 成功是每个任务的完成前提

---

### Task 1: 依赖切换（okhttp 4.12 + aj-mcp-client + slf4j-simple）并全量回归

**Files:**
- Modify: `pom.xml`
- Create: `src/resource/simplelogger.properties`
- 回归：`src/test/java/com/minion/core/llm/DeepSeekClientTest.java`、`src/test/java/com/minion/core/tools/browser/CdpClientTest.java`、`src/test/java/com/minion/core/tools/WebFetchToolTest.java`（okhttp 4 风险面）

**Interfaces:**
- Consumes: 无（项目现状）
- Produces: 依赖就绪的构建环境；`slf4j-simple` 配置（warn 级，压制库的 `log.info("JSON RPC {}")` 刷屏，保留 `log.warn("[ERROR] {}")` 子进程 stderr 线索）

- [ ] **Step 1: 改 pom.xml 的 okhttp 三件套版本**

把 `pom.xml` 中 `com.squareup.okhttp3` 的三个依赖（okhttp、okhttp-sse 主依赖，mockwebserver test 依赖）版本 `3.14.9` 全部改为 `4.12.0`，并把 okhttp-sse 上方注释改为：

```xml
    <!-- HTTP/SSE：EventSource 事件流解析（4.12 与 aj-mcp-client 对齐，单份 okhttp；JDK8 字节码 52） -->
```

- [ ] **Step 2: 给 aj-mcp-client 加 logback 排除，并补 slf4j-simple**

`pom.xml` 中（用户已加、未提交的）`com.ajaxjs:aj-mcp-client:1.5` 依赖改为：

```xml
    <!-- 标准 MCP 客户端（协议/协商/stdio/SSE/Streamable 传输）；排除父 pom 硬塞的 logback 后端（会 DEBUG 劫持控制台） -->
    <dependency>
      <groupId>com.ajaxjs</groupId>
      <artifactId>aj-mcp-client</artifactId>
      <version>1.5</version>
      <exclusions>
        <exclusion>
          <groupId>ch.qos.logback</groupId>
          <artifactId>*</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <!-- 库日志后端：warn 级（子进程 stderr 以 warn 打印，排障可见；info 级逐请求 JSON 刷屏压掉） -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>1.7.36</version>
    </dependency>
```

- [ ] **Step 3: 新建 src/resource/simplelogger.properties**

```
org.slf4j.simpleLogger.defaultLogLevel=warn
org.slf4j.simpleLogger.showThreadName=false
org.slf4j.simpleLogger.showLogName=false
org.slf4j.simpleLogger.showDateTime=false
```

- [ ] **Step 4: 编译并全量测试**

Run: `mvn -q compile && mvn test`
Expected: BUILD SUCCESS；DeepSeekClientTest/CdpClientTest/WebFetchToolTest 全绿（okhttp 4 兼容的实证）；现有 MCP 测试（StdioMcpClientTest/SseMcpClientTest/McpManagerTest）也仍绿（`RequestBody.create(MediaType,String)`、`MediaType.parse`、`EventSources.createFactory` 在 4.12 保留为 deprecated 静态方法，可直接编译）。

- [ ] **Step 5: 核对依赖树与产物**

Run: `mvn -q dependency:tree -Dincludes=ch.qos.logback,org.slf4j,com.ajaxjs,com.squareup.okhttp3`
Expected: 无 `ch.qos.logback` 条目；有 `org.slf4j:slf4j-simple:1.7.36` 与 `slf4j-api:1.7.36`；`com.ajaxjs:aj-mcp-client:1.5`（含传递 `aj-mcp-common:1.7`、jackson）；okhttp/okhttp-sse 均为 4.12.0 且只有一份。
Run: `mvn -q package` 后 `ls -la target/minion-0.1.0.jar`
Expected: 构建成功；体积约 7.5–8.5 MB（基线 2.75 MB，新增 jackson/kotlin/okhttp4/aj）。

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/resource/simplelogger.properties
git commit -m "build: okhttp 升 4.12 与 aj-mcp-client 1.5（标准 MCP 客户端），日志后端 slf4j-simple warn"
```

---

### Task 2: McpServer 传输常量 + McpCommands（stdio 命令组装，Windows .cmd 解析）

**Files:**
- Modify: `src/main/java/com/minion/core/mcp/McpServer.java`
- Create: `src/main/java/com/minion/core/mcp/McpCommands.java`
- Test: `src/test/java/com/minion/core/mcp/McpCommandsTest.java`

**Interfaces:**
- Consumes: `McpServer`（现有字段：`transport/command/args`）
- Produces: `McpServer.STDIO/SSE/STREAMABLE` 常量；`McpServer.normalizedTransport(String)`；`McpCommands.build(String command, List<String> args)`（返回最终命令数组，Windows 下把裸 `npx` 解析为 `npx.cmd` 并以 `cmd /c` 包装）；包级 `McpCommands.findInPath(String name, String ext)` 供测试注入

- [ ] **Step 1: 写失败测试 `McpCommandsTest`**

```java
package com.minion.core.mcp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** stdio 命令组装：Windows 下 npx→npx.cmd 解析 + cmd /c 包装；非 Windows 原样 */
public class McpCommandsTest {

    private static List<String> args(String... a) { return new ArrayList<String>(Arrays.asList(a)); }

    @Test
    public void nonWindows_noWrap_noResolve() {
        List<String> cmd = McpCommands.build("node", args("-v"), false, null);
        assertEquals(Arrays.asList("node", "-v"), cmd);
    }

    @Test
    public void windows_bareNpx_resolvedToCmd_andWrapped() {
        // PATH 探测注入：npx → C:\nvm\npx.cmd
        List<String> cmd = McpCommands.build("npx", args("@playwright/mcp"), true,
                name -> name.equals("npx") ? "C:\\nvm\\npx.cmd" : null);
        assertEquals(Arrays.asList("cmd", "/c", "C:\\nvm\\npx.cmd", "@playwright/mcp"), cmd);
    }

    @Test
    public void windows_absoluteExe_noWrap() {
        List<String> cmd = McpCommands.build("C:\\tools\\node.exe", args("-v"), true, null);
        assertEquals(Arrays.asList("C:\\tools\\node.exe", "-v"), cmd);
    }

    @Test
    public void windows_cmdAlreadyTyped_wrapped() {
        List<String> cmd = McpCommands.build("npx.cmd", args("x"), true, null);
        assertEquals(Arrays.asList("cmd", "/c", "npx.cmd", "x"), cmd);
    }

    @Test
    public void windows_resolveFailed_fallsBackRaw() {
        List<String> cmd = McpCommands.build("npx", args("x"), true, name -> null);
        assertEquals(Arrays.asList("npx", "x"), cmd);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=McpCommandsTest`
Expected: 编译失败（`McpCommands` 不存在）。

- [ ] **Step 3: 实现 McpCommands**

```java
package com.minion.core.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** stdio 命令组装：库的 StdioTransport 直接 new ProcessBuilder，Windows 下 npx 实为 npx.cmd 必须 cmd /c 包装 */
public final class McpCommands {

    /** 探测器：命令名 → 绝对路径；找不到返回 null（测试注入，生产用 PATH 扫描） */
    public interface Probe { String resolve(String name); }

    private McpCommands() { }

    /** 生产入口：按当前 OS 组装（Windows 探测 PATH，其余原样） */
    public static List<String> build(String command, List<String> args) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return build(command, args, windows, McpCommands::findInPath);
    }

    /** 测试入口：windows 与 probe 可注入 */
    static List<String> build(String command, List<String> args, boolean windows, Probe probe) {
        List<String> out = new ArrayList<String>();
        String head = command == null ? "" : command.trim();
        boolean needsShell = false;
        if (windows && !head.isEmpty() && head.indexOf(File.separatorChar) < 0 && head.indexOf('.') < 0) {
            String found = probe == null ? null : probe.resolve(head);
            if (found != null) head = found;
        }
        String lower = head.toLowerCase();
        if (windows && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) needsShell = true;
        if (needsShell) { out.add("cmd"); out.add("/c"); }
        out.add(head);
        if (args != null) out.addAll(args);
        return out;
    }

    /** PATH 扫描：找 name+ext（如 npx.cmd）命中返回绝对路径，否则 null */
    static String findInPath(String name, String ext) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.trim().isEmpty()) continue;
            File f = new File(dir.trim(), name + ext);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }
}
```

注意：`build(command, args, true, probe)` 的 `probe.resolve("npx")` 返回 `C:\nvm\npx.cmd`；`windows_absoluteExe_noWrap` 因 head 含 `\`（路径分隔符）不探测。`windows_cmdAlreadyTyped_wrapped` 因含 `.` 不探测、`endsWith(".cmd")` 触发包装。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=McpCommandsTest`
Expected: 5 个用例全绿。

- [ ] **Step 5: McpServer 加常量与归一化**

`src/main/java/com/minion/core/mcp/McpServer.java` 顶部加：

```java
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
```

并把字段注释改为：

```java
    /** "stdio" | "sse" | "streamable"（读入时经 normalizedTransport 归一） */
    public String transport;
```

- [ ] **Step 6: 全量测试 + Commit**

Run: `mvn -q test`
Expected: 全绿。
```bash
git add src/main/java/com/minion/core/mcp/McpServer.java src/main/java/com/minion/core/mcp/McpCommands.java src/test/java/com/minion/core/mcp/McpCommandsTest.java
git commit -m "feat(mcp): stdio 命令组装（Windows npx→cmd /c 包装）与传输类型常量"
```

---

### Task 3: McpHandle 接口（McpClient 改名）+ McpJson（Jackson→gson）

**Files:**
- Rename: `src/main/java/com/minion/core/mcp/McpClient.java` → `McpHandle.java`（接口改名，方法签名不变）
- Create: `src/main/java/com/minion/core/mcp/McpJson.java`
- Test: `src/test/java/com/minion/core/mcp/McpJsonTest.java`

**Interfaces:**
- Consumes: `McpToolInfo`、`McpException`
- Produces:
  - `McpHandle`：`long CALL_TIMEOUT_MS = 120_000;` + `void connect() throws McpException;` + `List<McpToolInfo> listTools() throws McpException;` + `String callTool(String name, JsonObject args) throws McpException;` + `void close();`
  - `McpJson.toJsonObject(JsonNode)`：Jackson JsonNode → gson JsonObject（null/isNull/非对象 → 空对象）

- [ ] **Step 1: 改名接口**

Run: `git mv src/main/java/com/minion/core/mcp/McpClient.java src/main/java/com/minion/core/mcp/McpHandle.java`
把接口名 `McpClient` 改为 `McpHandle`，类注释改为：

```java
/** MCP 连接抽象：握手 + 工具清单 + 工具调用（实现基于 aj-mcp-client，stdio/SSE/Streamable 三传输） */
```

- [ ] **Step 2: 同步修正 McpManager 的类型引用**

`McpManager.java` 中所有 `McpClient` 类型引用改为 `McpHandle`（接口名变了，不改则编译失败）：
- `private final Map<String, McpClient> clients` → `Map<String, McpHandle> clients`
- `doConnect` 内 `McpClient client = ...` → `McpHandle client = ...`
- `disconnect` 内 `McpClient c;` → `McpHandle c;`
- `call()` 内 `McpClient c;` → `McpHandle c;`

Run: `mvn -q compile`
Expected: 成功。

- [ ] **Step 3: 写失败测试 McpJsonTest**

```java
package com.minion.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Jackson JsonNode → gson：MCP 响应的 inputSchema 等字段原样透传，不经库的有损类型模型 */
public class McpJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void toJsonObject_preservesNestedAndEnum() throws Exception {
        String json = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]},"
                + "\"nested\":{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"integer\"}}},"
                + "\"list\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";
        JsonObject o = McpJson.toJsonObject(MAPPER.readTree(json));
        assertEquals("object", o.get("type").getAsString());
        JsonObject q = o.getAsJsonObject("properties").getAsJsonObject("q");
        assertEquals("a", q.getAsJsonArray("enum").get(0).getAsString());
        JsonObject nested = o.getAsJsonObject("properties").getAsJsonObject("nested");
        assertTrue(nested.getAsJsonObject("properties").has("k"));
        assertTrue(o.getAsJsonObject("properties").getAsJsonObject("list").has("items"));
    }

    @Test
    public void toJsonObject_null_or_missing_isEmptyObject() throws Exception {
        assertEquals(new JsonObject(), McpJson.toJsonObject(null));
        assertEquals(new JsonObject(), McpJson.toJsonObject(MAPPER.readTree("null")));
        assertEquals(new JsonObject(), McpJson.toJsonObject(MAPPER.readTree("\"str\"")));
    }
}
```

- [ ] **Step 4: 运行确认失败**

Run: `mvn -q test -Dtest=McpJsonTest`
Expected: 编译失败（McpJson 不存在）。

- [ ] **Step 5: 实现 McpJson**

```java
package com.minion.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Jackson JsonNode → gson（仅转换，不解析业务结构）：tools/list、tools/call 原始响应零损耗透传 */
public final class McpJson {

    private McpJson() { }

    public static JsonObject toJsonObject(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return new JsonObject();
        return JsonParser.parseString(node.toString()).getAsJsonObject();
    }
}
```

- [ ] **Step 6: 运行确认通过 + 全量 + Commit**

Run: `mvn -q test`
Expected: 全绿。
```bash
git add src/main/java/com/minion/core/mcp/McpHandle.java src/main/java/com/minion/core/mcp/McpJson.java src/test/java/com/minion/core/mcp/McpJsonTest.java
git commit -m "refactor(mcp): 客户端接口改名 McpHandle，新增 Jackson→gson 转换"
```

---

### Task 4: AjMcpClient（握手 + 原始 tools/list 分页 + 原始 tools/call + 错误映射）+ FakeMcpServer 升级

**Files:**
- Create: `src/main/java/com/minion/core/mcp/McpConnectionException.java`
- Create: `src/main/java/com/minion/core/mcp/AjMcpClient.java`
- Modify: `src/test/java/com/minion/core/mcp/FakeMcpServer.java`（升级：分页 + 复杂 schema + image + isError + tool_die）
- Create: `src/test/java/com/minion/core/mcp/AjMcpClientTest.java`

**Interfaces:**
- Consumes: `McpHandle`、`McpJson`、`McpToolInfo`、`McpException`、`McpCommands`（下一任务）；库 `com.ajaxjs.mcp.client.*`
- Produces:
  - `McpConnectionException extends McpException`：连接层失败（超时/断流/进程退出/未连接）专用，供 McpManager 区分「连接死亡需要重连」与「工具业务错误」
  - `AjMcpClient(McpTransport transport)`；id 从 `RAW_ID_BASE=100_000L` 自增（避开库内 idGenerator 从 1 起的段）

- [ ] **Step 1: 升级 FakeMcpServer（测试桩，仍回 2024-11-05）**

`FakeMcpServer.java` 的 `tools/list` 分支改为分页 + 四个工具；新增 `tool_die` 分支（连接死亡模拟）：

```java
            } else if (line.contains("\"tools/list\"")) {
                // 分页：第一页带 nextCursor，第二页（请求带 cursor）返回剩余
                boolean page2 = line.contains("\"cursor\":\"PAGE2\"");
                if (!page2) {
                    out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"tools\":["
                            + "{\"name\":\"fake_tool\",\"description\":\"fake tool desc\","
                            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}},"
                            + "{\"name\":\"tool_schema\",\"description\":\"rich schema\","
                            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                            + "\"q\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]},"
                            + "\"nested\":{\"type\":\"object\",\"properties\":{\"k\":{\"type\":\"integer\"}}},"
                            + "\"list\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"q\"]}},"
                            + "{\"name\":\"tool_image\",\"description\":\"image\",\"inputSchema\":{\"type\":\"object\"}},"
                            + "{\"name\":\"tool_error\",\"description\":\"error\",\"inputSchema\":{\"type\":\"object\"}}"
                            + "],\"nextCursor\":\"PAGE2\"}}\n");
                } else {
                    out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"tools\":["
                            + "{\"name\":\"paged_tool\",\"description\":\"page2 tool\",\"inputSchema\":{\"type\":\"object\"}}"
                            + "]}}\n");
                }
            } else if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_image\"")) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                        + "{\"type\":\"image\",\"data\":\"aGVsbG8=\",\"mimeType\":\"image/png\"}],\"isError\":false}}\n");
            } else if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_error\"")) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"boom\"}],\"isError\":true}}\n");
            } else if (line.contains("\"tools/call\"") && line.contains("\"name\":\"tool_die\"")) {
                out.write("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(line) + ",\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"dying\"}]}}\n");
                out.flush();
                System.exit(1);   // 模拟进程崩溃：读线程 EOF → failPendingRequests
```

注意：`tools/call` 的 `fake_tool` 与未知工具分支保持原样（McpManagerTest 依赖 `hello \nworld` 与 -32602 错误）。

- [ ] **Step 2: 写失败测试 AjMcpClientTest**

```java
package com.minion.core.mcp;

import com.google.gson.JsonObject;
import com.ajaxjs.mcp.client.transport.StdioTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** AjMcpClient：库握手 + 原始 tools/list（分页/schema 保真）+ 原始 tools/call（text/image/isError/断连） */
public class AjMcpClientTest {

    private AjMcpClient client;

    private static StdioTransport stdioTransport() {
        List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(FakeMcpServer.class.getName());
        return StdioTransport.builder().command(cmd).logEvents(false).build();
    }

    @Before
    public void setUp() throws Exception {
        client = new AjMcpClient(stdioTransport());
        client.connect();
    }

    @After
    public void tearDown() {
        client.close();
    }

    @Test
    public void connect_thenListTools_mergesPages() throws Exception {
        List<McpToolInfo> tools = client.listTools();
        assertEquals(5, tools.size());   // 第一页 4 + 第二页 1
        assertTrue(tools.stream().anyMatch(t -> "paged_tool".equals(t.name)));
        assertTrue(tools.stream().anyMatch(t -> "fake_tool".equals(t.name)));
    }

    @Test
    public void listTools_inputSchemaPassthrough() throws Exception {
        List<McpToolInfo> tools = client.listTools();
        McpToolInfo rich = tools.stream().filter(t -> "tool_schema".equals(t.name)).findFirst().orElse(null);
        assertNotNull(rich);
        assertEquals("a", rich.schema.getAsJsonObject("properties").getAsJsonObject("q").getAsJsonArray("enum").get(0).getAsString());
        assertTrue(rich.schema.getAsJsonObject("properties").getAsJsonObject("nested").getAsJsonObject("properties").has("k"));
        assertTrue(rich.schema.getAsJsonObject("properties").getAsJsonObject("list").has("items"));
    }

    @Test
    public void callTool_textConcatenated() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("q", "hi");
        assertEquals("hello \nworld", client.callTool("fake_tool", args));
    }

    @Test
    public void callTool_image_serializedAsJson() throws Exception {
        String out = client.callTool("tool_image", new JsonObject());
        assertTrue(out.contains("\"type\":\"image\""));
        assertTrue(out.contains("aGVsbG8="));
    }

    @Test(expected = McpException.class)
    public void callTool_isError_throws() throws Exception {
        client.callTool("tool_error", new JsonObject());
    }

    @Test(expected = McpConnectionException.class)
    public void callTool_afterProcessExit_connectionException() throws Exception {
        client.callTool("tool_die", new JsonObject());   // 服务端退出 → 读线程 EOF
        client.callTool("fake_tool", new JsonObject());  // 进程已死 → 连接层异常
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q test -Dtest=AjMcpClientTest`
Expected: 编译失败（AjMcpClient/McpConnectionException 不存在）。

- [ ] **Step 4: 实现 McpConnectionException 与 AjMcpClient**

```java
package com.minion.core.mcp;

/** 连接层失败（超时/断流/进程退出/未连接）：区别于工具业务错误，McpManager 用它触发重连 */
public class McpConnectionException extends McpException {
    public McpConnectionException(String message) { super(message); }
    public McpConnectionException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package com.minion.core.mcp;

import com.ajaxjs.mcp.client.McpClient;
import com.ajaxjs.mcp.client.transport.McpTransport;
import com.ajaxjs.mcp.protocol.McpConstant;
import com.ajaxjs.mcp.protocol.tools.CallToolRequest;
import com.ajaxjs.mcp.protocol.tools.GetToolListRequest;
import com.ajaxjs.mcp.protocol.utils.pagination.Cursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 aj-mcp-client 的 MCP 连接（方案 A）：
 * 握手/版本协商/JSON-RPC 帧/传输全部交给库；tools/list 与 tools/call 走同一 transport 的原始请求，
 * 取回 JsonNode 转 gson——inputSchema 零损耗，image/audio/resource 等非 text 内容不抛异常。
 */
public class AjMcpClient implements McpHandle {

    /** 原始请求 id 段：库内部 idGenerator 从 1 递增，这里错开避免撞号 */
    private static final long RAW_ID_BASE = 100_000L;
    private static final int MAX_PAGES = 20;

    private final McpTransport transport;
    private final McpClient client;
    private final AtomicLong rawId = new AtomicLong(RAW_ID_BASE);
    private volatile boolean connected;

    public AjMcpClient(McpTransport transport) {
        this.transport = transport;
        this.client = McpClient.builder()
                .transport(transport)
                .clientName("minion")
                .clientVersion("0.1.0")
                .requestTimeout(Duration.ofMillis(CALL_TIMEOUT_MS))
                .build();
    }

    @Override
    public void connect() throws McpException {
        if (connected) return;
        try {
            client.initialize();
            // 服务端可主动 ping 客户端：回空 result（库默认无 handler 会回 -32601，部分服务端视为异常）
            client.onServerRequest(McpConstant.Methods.PING,
                    params -> JsonNodeFactory.instance.objectNode());
            connected = true;
        } catch (RuntimeException e) {
            close();
            throw new McpException("MCP 握手失败: " + rootMessage(e), e);
        }
    }

    @Override
    public List<McpToolInfo> listTools() throws McpException {
        List<McpToolInfo> out = new ArrayList<McpToolInfo>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            GetToolListRequest req = new GetToolListRequest();
            req.setId(rawId.getAndIncrement());
            if (cursor != null) req.setParams(new Cursor(cursor));
            JsonObject result = resultOf(req);
            for (JsonElement e : arrayOf(result, "tools")) {
                JsonObject t = e.getAsJsonObject();
                out.add(new McpToolInfo(
                        t.get("name").getAsString(),
                        t.has("description") ? t.get("description").getAsString() : "",
                        t.has("inputSchema") && t.get("inputSchema").isJsonObject()
                                ? t.getAsJsonObject("inputSchema") : new JsonObject()));
            }
            cursor = result.has("nextCursor") && !result.get("nextCursor").isJsonNull()
                    ? result.get("nextCursor").getAsString() : null;
            if (cursor == null || cursor.isEmpty()) return out;
        }
        throw new McpConnectionException("MCP tools/list 超过 " + MAX_PAGES + " 页，中止");
    }

    @Override
    public String callTool(String name, JsonObject args) throws McpException {
        CallToolRequest req = new CallToolRequest(name, args == null ? "{}" : args.toString());
        req.setId(rawId.getAndIncrement());
        JsonObject result = resultOf(req);
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : arrayOf(result, "content")) {
            JsonObject c = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            if (sb.length() > 0) sb.append('\n');
            if ("text".equals(c.get("type").getAsString()) && c.has("text")) {
                sb.append(c.get("text").getAsString());
            } else {
                sb.append(c.toString());   // image/audio/resource 等：原样 JSON 文本
            }
        }
        boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
        if (isError) {
            throw new McpException(sb.length() == 0 ? "MCP 工具调用失败: " + name : sb.toString());
        }
        return sb.toString();
    }

    /** 发原始请求并取 result 节点（gson 视角，字段零损耗） */
    private JsonObject resultOf(com.ajaxjs.mcp.protocol.McpRequest req) throws McpException {
        JsonNode resp;
        try {
            resp = transport.sendRequestWithResponse(req).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new McpConnectionException("MCP 调用超时: " + req.getMethod());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpConnectionException("MCP 调用被中断: " + req.getMethod());
        } catch (Exception e) {
            throw new McpConnectionException("MCP 调用失败: " + rootMessage(e), e);
        }
        if (resp == null) return new JsonObject();
        JsonObject msg = McpJson.toJsonObject(resp);
        if (msg.has("error")) {
            JsonObject err = msg.getAsJsonObject("error");
            throw new McpException("MCP 错误: "
                    + (err.has("message") ? err.get("message").getAsString() : err.toString()));
        }
        return msg.has("result") && msg.get("result").isJsonObject() ? msg.getAsJsonObject("result") : new JsonObject();
    }

    /** 从 result 取数组字段（gson 视角；缺失/非数组 → 空列表） */
    private static List<JsonElement> arrayOf(JsonObject result, String key) {
        if (result.has(key) && result.get(key).isJsonArray()) {
            List<JsonElement> out = new ArrayList<JsonElement>();
            for (JsonElement e : result.getAsJsonArray(key)) out.add(e);
            return out;
        }
        return Collections.emptyList();
    }

    @Override
    public void close() {
        connected = false;
        try {
            client.close();
        } catch (RuntimeException ignored) { }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn -q test -Dtest=AjMcpClientTest,McpJsonTest,McpCommandsTest`
Expected: 相关用例全绿（含 image 序列化、isError 抛普通 McpException、进程退出后第二次调用抛 McpConnectionException）。
注意：`McpManagerTest` 此刻**预期会红**（FakeMcpServer 的 tools/list 从 1 个工具变 4+1 个，`ensureConnected_connectsAndFillsTools` 断言 `assertEquals(1, ...)` 会失败）——这是升级测试桩的预期中间态，Task 6 会同步更新断言；此处**不要**改 McpManagerTest。

- [ ] **Step 6: 全量测试（已知红项除外）+ Commit**

Run: `mvn -q test -Dtest='!McpManagerTest'`
Expected: 全绿。
```bash
git add src/main/java/com/minion/core/mcp/ src/test/java/com/minion/core/mcp/AjMcpClientTest.java src/test/java/com/minion/core/mcp/FakeMcpServer.java
git commit -m "feat(mcp): AjMcpClient 标准握手 + 原始 tools/list/call（schema 零损耗，连接层异常独立类型）"
```

---

### Task 5: Streamable 与旧版 SSE 传输的标准协议测试

**Files:**
- Create: `src/test/java/com/minion/core/mcp/FakeSseMcpServer.java`（com.sun.net.httpserver，JDK8 自带）
- Create: `src/test/java/com/minion/core/mcp/AjMcpClientStreamableTest.java`
- Create: `src/test/java/com/minion/core/mcp/AjMcpClientLegacySseTest.java`

**Interfaces:**
- Consumes: `AjMcpClient`、`McpTransport` 构造（`StreamableHttpTransport.builder().endpointUrl(..).openEventStream(false).timeout(..).requestHeaders(..)` / `HttpMcpTransport.builder().sseUrl(..)`）
- Produces: 验证标准报文的回归测试——Streamable 带 `Mcp-Session-Id` + `MCP-Protocol-Version` 头、旧版 SSE 走 `endpoint` 事件拿 POST 地址

- [ ] **Step 1: 写失败测试（先不建 Fake 服务器，用 MockWebServer 直接扮 Streamable）**

`AjMcpClientStreamableTest.java`：

```java
package com.minion.core.mcp;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/** Streamable HTTP 标准报文验证：Mcp-Session-Id 保持、MCP-Protocol-Version 头、notifications/initialized */
public class AjMcpClientStreamableTest {

    private MockWebServer server;
    private final CopyOnWriteArrayList<RecordedRequest> requests = new CopyOnWriteArrayList<RecordedRequest>();

    private static String idOf(String body) {
        int i = body.indexOf("\"id\":");
        if (i < 0) return "0";
        int j = body.indexOf(',', i);
        return body.substring(i + 5, j < 0 ? body.length() : j).trim();
    }

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                requests.add(request);
                if (body.contains("\"initialize\"")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setHeader("Mcp-Session-Id", "sess-1")
                            .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body) + ",\"result\":{\"protocolVersion\":\"2025-03-26\","
                                    + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}}");
                }
                if (body.contains("\"notifications/initialized\"")) {
                    return new MockResponse().setResponseCode(202);
                }
                if (body.contains("\"tools/list\"")) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + idOf(body) + ",\"result\":{\"tools\":["
                                    + "{\"name\":\"fake_tool\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}");
                }
                return new MockResponse().setResponseCode(400);
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void streamable_negotiatesAndKeepsSessionAndVersionHeaders() throws Exception {
        com.ajaxjs.mcp.client.transport.StreamableHttpTransport transport =
                com.ajaxjs.mcp.client.transport.StreamableHttpTransport.builder()
                        .endpointUrl(server.url("/mcp").toString())
                        .openEventStream(false)
                        .timeout(java.time.Duration.ofSeconds(30))
                        .requestHeaders(java.util.Collections.singletonMap("Authorization", "Bearer tok"))
                        .build();
        AjMcpClient client = new AjMcpClient(transport);
        try {
            client.connect();
            assertEquals(1, client.listTools().size());
        } finally {
            client.close();
        }
        // 断言四个请求及其头（initialize / initialized / tools/list）
        assertTrue(requests.size() >= 3);
        RecordedRequest init = requests.get(0);
        assertTrue(init.getBody().readUtf8().contains("\"protocolVersion\""));
        assertTrue(init.getBody().readUtf8().contains("\"clientInfo\":{\"name\":\"minion\""));
        RecordedRequest notif = requests.get(1);
        assertTrue(notif.getBody().readUtf8().contains("\"notifications/initialized\""));
        RecordedRequest list = requests.get(2);
        assertEquals("sess-1", list.getHeader("Mcp-Session-Id"));
        assertEquals("2025-03-26", list.getHeader("MCP-Protocol-Version"));
        assertEquals("Bearer tok", list.getHeader("Authorization"));
        assertEquals("application/json, text/event-stream", list.getHeader("Accept"));
    }
}
```

注意 `requests` 是并发容器（库内部 enqueue 异步），断言前 connect+listTools 已完成同步等待，顺序即请求顺序。

- [ ] **Step 2: 运行确认通过（这是库行为验证，应直接绿）**

Run: `mvn -q test -Dtest=AjMcpClientStreamableTest`
Expected: PASS（若失败说明库版本行为与设计不符，停下检查依赖版本）。

- [ ] **Step 3: 写旧版 SSE 测试（需要能保持流的假服务器）**

`FakeSseMcpServer.java`（com.sun.net.httpserver，测试内起本地 HTTP 服务）：

```java
package com.minion.core.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用旧版 HTTP+SSE 服务器（标准流程）：
 * GET /sse → 下发 event: endpoint（POST 地址 /messages）并保持流；
 * POST /messages → 202，JSON-RPC 响应经 SSE event: message 推回。
 */
public class FakeSseMcpServer {

    private HttpServer server;
    private final CopyOnWriteArrayList<OutputStream> streams = new CopyOnWriteArrayList<OutputStream>();
    volatile String lastMessagePath = "";

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            streams.add(os);
            os.write("event: endpoint\r\ndata: /messages?sessionId=s1\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        });
        server.createContext("/messages", ex -> {
            lastMessagePath = ex.getRequestURI().toString();
            String body = readBody(ex);
            ex.sendResponseHeaders(202, -1);
            ex.close();
            String reply = FakeMcpServer.respondTo(body);   // 复用 stdio 桩的应答逻辑（见 Step 5）
            if (reply != null) broadcast("event: message\r\ndata: " + reply + "\r\n\r\n");
        });
        server.start();
    }

    public String sseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
    }

    private void broadcast(String sse) {
        for (OutputStream os : streams) {
            try { os.write(sse.getBytes(StandardCharsets.UTF_8)); os.flush(); } catch (IOException ignored) { }
        }
    }

    public void stop() {
        for (OutputStream os : streams) { try { os.close(); } catch (IOException ignored) { } }
        if (server != null) server.stop(0);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = ex.getRequestBody().read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 4: 重构 FakeMcpServer 暴露 respondTo（静态应答函数，供 SSE 服务器复用）**

把 `FakeMcpServer.main` 里按行分发逻辑抽成：

```java
    /** 单行 JSON-RPC 请求 → 响应 JSON 行；通知类返回 null（无需回） */
    static String respondTo(String line) {
        if (line.contains("\"initialize\"")) { ... return "..."; }
        if (line.contains("\"notifications/initialized\"")) return null;
        if (line.contains("\"tools/list\"")) { ... }
        ...
        return "...";
    }
```

`main` 改为 `while ((line = in.readLine()) != null) { String r = respondTo(line); if (r != null) { out.write(r + "\n"); out.flush(); } }`。
注意 `tool_die` 分支要在 `respondTo` 里保留（写响应后 `System.exit(1)`），`AjMcpClientTest.callTool_afterProcessExit_connectionException` 依赖它。`main` 里对该分支：`String r = respondTo(line); out.write(r + "\n"); out.flush();` 后 `System.exit(1)` —— 把 `System.exit(1)` 留在 `main` 的分支判断里（respondTo 返回后 main 检测 `line.contains("\"tool_die\"")` 再 exit）。

- [ ] **Step 5: 写旧版 SSE 测试**

`AjMcpClientLegacySseTest.java`：

```java
package com.minion.core.mcp;

import com.ajaxjs.mcp.client.transport.HttpMcpTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 旧版 HTTP+SSE 标准流程：endpoint 事件取 POST 地址 → POST /messages → 响应经 SSE 流回 */
public class AjMcpClientLegacySseTest {

    private FakeSseMcpServer server;

    @Before
    public void setUp() throws Exception {
        server = new FakeSseMcpServer();
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    public void legacySse_endpointEventThenMessagePost() throws Exception {
        HttpMcpTransport transport = HttpMcpTransport.builder().sseUrl(server.sseUrl()).build();
        AjMcpClient client = new AjMcpClient(transport);
        try {
            client.connect();
            List<McpToolInfo> tools = client.listTools();
            assertEquals(5, tools.size());   // 复用 FakeMcpServer 的应答（含分页）
            assertTrue(tools.stream().anyMatch(t -> "fake_tool".equals(t.name)));
            assertEquals("hello \nworld", client.callTool("fake_tool", new com.google.gson.JsonObject()));
        } finally {
            client.close();
        }
        assertTrue("POST 应打到 /messages", server.lastMessagePath.startsWith("/messages"));
    }
}
```

- [ ] **Step 6: 运行确认两个新测试通过**

Run: `mvn -q test -Dtest=AjMcpClientStreamableTest,AjMcpClientLegacySseTest`
Expected: 2 个测试全绿（证明库在标准服务端语义下工作正常）。

- [ ] **Step 7: 全量 + Commit**

Run: `mvn -q test`
Expected: 全绿。
```bash
git add src/test/java/com/minion/core/mcp/
git commit -m "test(mcp): Streamable 与旧版 SSE 标准报文验证（Session-Id/协议头/endpoint 事件）"
```

---

### Task 6: McpManager 接线 AjMcpClient + 删除自研实现

**Files:**
- Modify: `src/main/java/com/minion/core/mcp/McpManager.java`
- Delete: `src/main/java/com/minion/core/mcp/JsonRpc.java`、`StdioMcpClient.java`、`SseMcpClient.java`
- Delete: `src/test/java/com/minion/core/mcp/JsonRpcTest.java`、`StdioMcpClientTest.java`、`SseMcpClientTest.java`
- Modify: `src/test/java/com/minion/core/mcp/McpManagerTest.java`

**Interfaces:**
- Consumes: `AjMcpClient`、`McpTransports` 工厂逻辑（在 McpManager 内做 switch 或抽到 `McpTransports.create(McpServer)`——本任务内联在 `doConnect`，保持最小改动）
- Produces: `McpManager.clients: Map<String, McpHandle>`；`call()` 捕获 `McpConnectionException` 时 `disconnect` 后重抛（下次调用自动重连）

- [ ] **Step 1: 改 McpManager**

`doConnect` 中构造客户端改为：

```java
            McpHandle client = new AjMcpClient(transportOf(s));
```

新增私有工厂（放在 `doConnect` 上方）：

```java
    /** 按传输类型构造库传输：stdio 经 McpCommands 组装命令；streamable 带请求头；sse 为旧版端点 */
    private static com.ajaxjs.mcp.client.transport.McpTransport transportOf(McpServer s) throws McpException {
        String t = McpServer.normalizedTransport(s.transport);
        if (McpServer.STREAMABLE.equals(t) || McpServer.SSE.equals(t)) {
            if (s.url == null || s.url.trim().isEmpty())
                throw new McpException("MCP 服务器缺少 URL 配置: " + s.name);
        }
        if (McpServer.STREAMABLE.equals(t)) {
            return com.ajaxjs.mcp.client.transport.StreamableHttpTransport.builder()
                    .endpointUrl(s.url.trim())
                    .openEventStream(false)
                    .timeout(java.time.Duration.ofMillis(McpHandle.CALL_TIMEOUT_MS))
                    .requestHeaders(s.headers)
                    .build();
        }
        if (McpServer.SSE.equals(t)) {
            return com.ajaxjs.mcp.client.transport.HttpMcpTransport.builder().sseUrl(s.url.trim()).build();
        }
        return com.ajaxjs.mcp.client.transport.StdioTransport.builder()
                .command(McpCommands.build(s.command, s.args))
                .environment(s.env)
                .logEvents(false)
                .build();
    }
```

同时：
- 类型引用已在 Task 3 Step 2 全部改为 `McpHandle`（clients map 与局部变量），此处仅核对一遍
- 删除 `commandParts(...)` 方法（`transportOf` 取代）
- `call()` 中 `c.callTool(toolName, args)` 包一层：

```java
        try {
            return c.callTool(toolName, args);
        } catch (McpConnectionException e) {
            // 连接层失败（进程退出/流断开/空闲超时）：断开置 DISCONNECTED，下次调用走 reconnect 自动重建
            disconnect(serverName);
            throw e;
        }
```

- 类注释更新为「状态机 + 惰性连接 + 全局工具表 + 路由（基于 aj-mcp-client 标准实现）」

- [ ] **Step 2: 删旧实现与旧测试**

Run:
```bash
git rm src/main/java/com/minion/core/mcp/JsonRpc.java src/main/java/com/minion/core/mcp/StdioMcpClient.java src/main/java/com/minion/core/mcp/SseMcpClient.java src/test/java/com/minion/core/mcp/JsonRpcTest.java src/test/java/com/minion/core/mcp/StdioMcpClientTest.java src/test/java/com/minion/core/mcp/SseMcpClientTest.java
```

- [ ] **Step 3: 同步 McpManagerTest 断言（FakeMcpServer 工具数 1→5）**

`McpManagerTest.ensureConnected_connectsAndFillsTools` 中 `assertEquals(1, s.tools.size())` 改为：

```java
        assertEquals(5, s.tools.size());   // FakeMcpServer 第一页 4 个 + 第二页 1 个
        assertEquals("fake_tool", s.tools.get(0).name);
        assertTrue(s.tools.stream().anyMatch(t -> "paged_tool".equals(t.name)));
```

（Task 4 升级测试桩后此用例已红，这里同步。）

- [ ] **Step 4: 编译 + 全量测试**

Run: `mvn -q compile && mvn test`
Expected: 全绿（`connect_failure_marksFailedWithReason` 因进程找不到 → McpException → FAILED，仍成立；`call_routesToConnectedServer` 走 AjMcpClient 后返回值不变）。

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/com/minion/core/mcp src/test/java/com/minion/core/mcp
git commit -m "refactor(mcp): McpManager 接线 AjMcpClient，删除自研 JsonRpc/stdio/SSE 实现"
```

---

### Task 7: McpFormPolicy（表单字段联动口径，纯逻辑）

**Files:**
- Create: `src/main/java/com/minion/gui/dialog/McpFormPolicy.java`
- Test: `src/test/java/com/minion/gui/dialog/McpFormPolicyTest.java`

**Interfaces:**
- Consumes: `McpServer`（trim 用）
- Produces:
  - `enum McpFormPolicy.Field { COMMAND, ARGS, ENV, URL, HEADERS }`
  - `static Set<Field> fieldsOf(String transport)`（归一后：stdio→{COMMAND,ARGS,ENV}；sse→{URL}；streamable→{URL,HEADERS}）
  - `static String labelOf(String transport)`（列表/表单显示名：stdio/SSE/Streamable）
  - `static void trim(McpServer s)`（保存裁剪：只保留本传输相关字段，其余清空）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.gui.dialog;

import com.minion.core.mcp.McpServer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static org.junit.Assert.*;

/** MCP 表单字段联动口径：三行矩阵 + 保存裁剪 */
public class McpFormPolicyTest {

    @Test
    public void fieldsOf_stdio_onlyCommandGroup() {
        assertEquals(McpFormPolicy.fieldsOf("stdio"),
                new java.util.HashSet<McpFormPolicy.Field>(Arrays.asList(
                        McpFormPolicy.Field.COMMAND, McpFormPolicy.Field.ARGS, McpFormPolicy.Field.ENV)));
    }

    @Test
    public void fieldsOf_sse_onlyUrl() {
        assertEquals(McpFormPolicy.fieldsOf("sse"),
                new java.util.HashSet<McpFormPolicy.Field>(Arrays.asList(McpFormPolicy.Field.URL)));
    }

    @Test
    public void fieldsOf_streamable_urlAndHeaders() {
        assertEquals(McpFormPolicy.fieldsOf("streamable"),
                new java.util.HashSet<McpFormPolicy.Field>(Arrays.asList(
                        McpFormPolicy.Field.URL, McpFormPolicy.Field.HEADERS)));
    }

    @Test
    public void fieldsOf_unknown_fallsBackStdio() {
        assertEquals(McpFormPolicy.fieldsOf("nonsense"),
                McpFormPolicy.fieldsOf("stdio"));
    }

    @Test
    public void trim_keepsOnlyTransportFields() {
        McpServer s = new McpServer();
        s.transport = "sse";
        s.command = "npx";
        s.args = new ArrayList<String>(Arrays.asList("@playwright/mcp"));
        s.env = new HashMap<String, String>();
        s.env.put("K", "V");
        s.url = "http://h/sse";
        s.headers = new HashMap<String, String>();
        s.headers.put("A", "b");
        McpFormPolicy.trim(s);
        assertEquals("", s.command);
        assertTrue(s.args.isEmpty());
        assertTrue(s.env.isEmpty());
        assertEquals("http://h/sse", s.url);
        assertTrue(s.headers.isEmpty());
    }

    @Test
    public void labelOf_friendlyNames() {
        assertEquals("stdio", McpFormPolicy.labelOf("stdio"));
        assertEquals("SSE", McpFormPolicy.labelOf("sse"));
        assertEquals("Streamable", McpFormPolicy.labelOf("streamable"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=McpFormPolicyTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 McpFormPolicy**

```java
package com.minion.gui.dialog;

import com.minion.core.mcp.McpServer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Set;

/** MCP 表单字段与传输方式的联动口径（纯逻辑，单测三行矩阵；GUI 只消费不实现规则） */
public final class McpFormPolicy {

    public enum Field { COMMAND, ARGS, ENV, URL, HEADERS }

    private McpFormPolicy() { }

    /** 某传输可见字段：stdio→命令组；sse→URL（旧版 SSE 库不支持自定义头）；streamable→URL+请求头 */
    public static Set<Field> fieldsOf(String transport) {
        String t = McpServer.normalizedTransport(transport);
        if (McpServer.STREAMABLE.equals(t)) return EnumSet.of(Field.URL, Field.HEADERS);
        if (McpServer.SSE.equals(t)) return EnumSet.of(Field.URL);
        return EnumSet.of(Field.COMMAND, Field.ARGS, Field.ENV);
    }

    /** 显示名（列表 meta 与表单标题） */
    public static String labelOf(String transport) {
        String t = McpServer.normalizedTransport(transport);
        if (McpServer.STREAMABLE.equals(t)) return "Streamable";
        if (McpServer.SSE.equals(t)) return "SSE";
        return "stdio";
    }

    /** 保存裁剪：只保留本传输相关字段，其余清空（防止切传输后残留脏配置） */
    public static void trim(McpServer s) {
        Set<Field> keep = fieldsOf(s.transport);
        if (!keep.contains(Field.COMMAND)) s.command = "";
        if (!keep.contains(Field.ARGS)) s.args = new ArrayList<String>();
        if (!keep.contains(Field.ENV)) s.env = new HashMap<String, String>();
        if (!keep.contains(Field.URL)) s.url = "";
        if (!keep.contains(Field.HEADERS)) s.headers = new HashMap<String, String>();
        s.transport = McpServer.normalizedTransport(s.transport);
    }
}
```

- [ ] **Step 4: 运行确认通过 + 全量 + Commit**

Run: `mvn -q test`
Expected: 全绿。
```bash
git add src/main/java/com/minion/gui/dialog/McpFormPolicy.java src/test/java/com/minion/gui/dialog/McpFormPolicyTest.java
git commit -m "feat(gui): MCP 表单字段联动口径（stdio/SSE/Streamable 三行矩阵 + 保存裁剪）"
```

---

### Task 8: SettingsDialog MCP 表单改三选一单选 + 隐藏/清空

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`

**Interfaces:**
- Consumes: `McpFormPolicy`（fieldsOf/labelOf/trim）、`McpServer`、现有 `form(McpServer s, Window owner)`、现有 `joinLines/pairLines/splitLines/parsePairs`
- Produces: 表单内传输改 `RadioButton` × 3（ToggleGroup），切换即「不适用行隐藏（setVisible+setManaged=false）+ 清空」，URL 标签随传输变文案，保存走 `McpFormPolicy.trim`

- [ ] **Step 1: 替换 form() 里的传输控件与联动**

在 `SettingsDialog.form` 中：

把 `ComboBox<String> transport = new ComboBox<String>(); transport.getItems().addAll("stdio", "sse"); ...` 整段替换为：

```java
        ToggleGroup transportGroup = new ToggleGroup();
        RadioButton rbStdio = transportRadio("stdio（本地进程）", McpServer.STDIO, transportGroup);
        RadioButton rbSse = transportRadio("SSE（旧版 HTTP+SSE）", McpServer.SSE, transportGroup);
        RadioButton rbStream = transportRadio("Streamable HTTP（推荐远程）", McpServer.STREAMABLE, transportGroup);
        HBox transportBox = new HBox(10, rbStdio, rbSse, rbStream);
        String t0 = McpServer.normalizedTransport(s == null ? McpServer.STDIO : s.transport);
        selectTransport(transportGroup, t0);
```

命令/参数/环境变量/URL/请求头控件与原来一致（去掉对 `transport.valueProperty` 的旧监听与 `stdio0` 初始禁用逻辑）。

- [ ] **Step 2: 行布局改为「每行一个 Label+控件」，新增两个提示行**

`grid.addRow(...)` 改为（行号即联动依据）：

```java
        Label envTip = new Label("传给 stdio 子进程的环境变量，如 GITHUB_PERSONAL_ACCESS_TOKEN=…（仅 stdio 生效）");
        envTip.getStyleClass().add("msg-thinking");
        Label headerTip = new Label("随请求头发送（如 Authorization: Bearer …）；旧版 SSE 传输不支持自定义头，仅 Streamable 生效");
        headerTip.getStyleClass().add("msg-thinking");

        grid.addRow(0, new Label("名称:"), name);
        grid.addRow(1, new Label("传输:"), transportBox);
        grid.addRow(2, new Label("命令:"), command);
        grid.addRow(3, new Label("参数(每行一个):"), argsArea);
        grid.addRow(4, new Label("环境变量(KEY=VALUE):"), envArea);
        grid.addRow(5, envTip);
        grid.addRow(6, urlLabel, url);
        grid.addRow(7, new Label("请求头(K:V):"), headerArea);
        grid.addRow(8, headerTip);
```

其中 `Label urlLabel = new Label(...)` 初始文案按 t0（streamable → “URL(MCP 端点，如 http://host:port/mcp):”，否则 “URL(SSE 端点，如 http://host:port/sse):”）。

- [ ] **Step 3: 加联动与私有辅助方法**

在 `form` 里加（放在 addRow 之后、`setResultConverter` 之前）：

```java
        Runnable applyTransport = () -> {
            String t = selectedTransport(transportGroup);
            Set<McpFormPolicy.Field> keep = McpFormPolicy.fieldsOf(t);
            showRows(grid, t, keep, urlLabel);
        };
        // 用户切换传输：先清空「不再属于本传输」的行，再按新口径显隐
        transportGroup.selectedToggleProperty().addListener((obs, ov, nv) -> {
            String t = selectedTransport(transportGroup);
            Set<McpFormPolicy.Field> keep = McpFormPolicy.fieldsOf(t);
            clearHiddenRows(grid, keep);
            applyTransport.run();
        });
        applyTransport.run();
```

在类底部新增三个私有静态方法（放在 `parsePairs` 附近），并在文件顶部 import 区补：

```java
import com.minion.core.mcp.McpFormPolicy;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import java.util.Set;
```

```java
    /** 传输单选按钮工厂 */
    private static RadioButton transportRadio(String text, String value, ToggleGroup group) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setUserData(value);
        return rb;
    }

    /** 按值选中（旧配置打开时回显） */
    private static void selectTransport(ToggleGroup group, String value) {
        for (Toggle t : group.getToggles()) {
            if (value.equals(t.getUserData())) { t.setSelected(true); return; }
        }
    }

    /** 当前选中传输（未选回退 stdio） */
    private static String selectedTransport(ToggleGroup group) {
        Toggle t = group.getSelectedToggle();
        return t == null ? McpServer.STDIO : String.valueOf(t.getUserData());
    }

    /** 按联动口径显隐行：命令组(2-4) / URL(6) / 请求头(7-8)；隐藏行清空；URL 文案按传输区分 sse 与 streamable */
    private static void showRows(GridPane grid, String transport, Set<McpFormPolicy.Field> keep, Label urlLabel) {
        boolean stdio = keep.contains(McpFormPolicy.Field.COMMAND);
        boolean url = keep.contains(McpFormPolicy.Field.URL);
        boolean headers = keep.contains(McpFormPolicy.Field.HEADERS);
        setRowVisible(grid, 2, stdio);
        setRowVisible(grid, 3, stdio);
        setRowVisible(grid, 4, stdio);
        setRowVisible(grid, 5, stdio);     // 环境变量提示行
        setRowVisible(grid, 6, url);
        setRowVisible(grid, 7, headers);
        setRowVisible(grid, 8, headers);   // 请求头提示行
        urlLabel.setText(McpServer.STREAMABLE.equals(transport)
                ? "URL(MCP 端点，如 http://host:port/mcp):"
                : "URL(SSE 端点，如 http://host:port/sse):");
    }

    /** 隐藏某 GridPane 行（行内所有控件 setVisible+setManaged=false 并清空文本） */
    private static void setRowVisible(GridPane grid, int row, boolean on) {
        for (javafx.scene.Node n : new ArrayList<javafx.scene.Node>(grid.getChildren())) {
            Integer r = GridPane.getRowIndex(n);
            if (r == null || r != row) continue;
            n.setVisible(on);
            n.setManaged(on);
            if (!on && n instanceof TextInputControl) ((TextInputControl) n).clear();
        }
    }

    /** 切换传输时：把「当前不再保留」的行清空（showRows 会再隐藏） */
    private static void clearHiddenRows(GridPane grid, Set<McpFormPolicy.Field> keep) {
        boolean stdio = keep.contains(McpFormPolicy.Field.COMMAND);
        boolean url = keep.contains(McpFormPolicy.Field.URL);
        boolean headers = keep.contains(McpFormPolicy.Field.HEADERS);
        if (!stdio) { clearRowText(grid, 2); clearRowText(grid, 3); clearRowText(grid, 4); }
        if (!url) clearRowText(grid, 6);
        if (!headers) clearRowText(grid, 7);
    }

    private static void clearRowText(GridPane grid, int row) {
        for (javafx.scene.Node n : grid.getChildren()) {
            Integer r = GridPane.getRowIndex(n);
            if (r != null && r == row && n instanceof TextInputControl) ((TextInputControl) n).clear();
        }
    }
```

注意：`Label urlLabel` 在控件声明处创建，初始文案按 `t0`（streamable → “URL(MCP 端点，如 http://host:port/mcp):”，否则 “URL(SSE 端点，如 http://host:port/sse):”），`showRows` 会随后覆盖。

- [ ] **Step 4: resultConverter 里按传输裁剪**

`setResultConverter` 中：

- 把 `out.transport = transport.getValue() == null ? "stdio" : transport.getValue();` 删除（`McpFormPolicy.trim` 会归一）
- 其余赋值行（command/args/env/url/headers）保留原样
- 在 `out.enabled = s != null && s.enabled;` 之前插入：

```java
            McpFormPolicy.trim(out);   // 只保留本传输相关字段，其余清空
```

- [ ] **Step 5: 列表 meta 显示友好名**

`mcpPane` 的 cellFactory 里 `String metaText = item.transport + ...` 改为：

```java
                String metaText = McpFormPolicy.labelOf(item.transport) + ...
```

- [ ] **Step 6: 编译 + 全量测试 + 手工冒烟**

Run: `mvn -q test`
Expected: 全绿（GUI 单测不覆盖 Dialog 内部，靠编译与现有 SettingsDialogTest 兜底）。
手工冒烟：启动 jar → 设置 → MCP → 新建，切三种传输，确认：stdio 只显示命令组；sse 只显示 URL（文案带 /sse）；streamable 显示 URL（文案带 /mcp）+请求头；切换时旧字段被清空；保存后 mcp.json 无脏字段；列表显示「SSE/Streamable」友好名。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "feat(gui): MCP 表单传输改三选一单选，不适用字段隐藏并清空"
```

---

### Task 9: 文档同步与全量构建

**Files:**
- Modify: `README.md`（MCP 章节 + 依赖说明）
- Modify: `docs/ARCHITECTURE.md`（core/mcp 段）
- Modify: `docs/CONVENTIONS.md`（依赖约束：okhttp 3.x 例外说明）
- Modify: `CLAUDE.md`（依赖行 + 包结构 core/mcp 行）

- [ ] **Step 1: README MCP 章节更新**

`README.md` 的「MCP 工具扩展（stdio / SSE）」改为「MCP 工具扩展（stdio / SSE / Streamable HTTP）」，字段说明改为：

```markdown
    name=playwright            # 服务器名（工具名前缀区分来源）
    transport=stdio            # stdio（本地进程）| sse（旧版 HTTP+SSE）| streamable（Streamable HTTP，推荐远程）
    command=npx                # stdio：可执行命令（Windows 下 npx 自动解析为 npx.cmd 并以 cmd /c 包装）
    args=@playwright/mcp       # 参数，每行一个
    env=KEY=VALUE              # 环境变量，每行一个（仅 stdio：传给子进程，如 GITHUB_PERSONAL_ACCESS_TOKEN）
    url=                       # sse：SSE 端点（如 http://host:port/sse）；streamable：MCP 端点（如 http://host:port/mcp）
    headers=K:V                # 请求头，每行一个（仅 streamable；旧版 SSE 传输不支持自定义头，需鉴权请用 streamable）

传输方式为单选：选中一种后其余字段隐藏并清空（表单按传输类型裁剪保存）。
```

同时把「实现说明」加一段：

```markdown
MCP 客户端基于 aj-mcp-client 1.5 标准实现（JDK8 兼容）：握手、协议版本协商（2024-11-05/2025-03-26/2025-06-18）、
stdio/SSE/Streamable 三传输由库完成；tools/list 与 tools/call 取原始 JSON（inputSchema 原样透传，非文本内容不丢）。
依赖变化：okhttp 升 4.12（与库对齐，单份 okhttp + kotlin-stdlib），新增 jackson、slf4j-simple（warn 级日志），
产物体积约 2.75 MB → 8 MB。
```

- [ ] **Step 2: ARCHITECTURE core/mcp 段重写**

`docs/ARCHITECTURE.md` 的「core/mcp/（MCP 客户端核心，JDK8 自研，无官方 SDK 依赖）」整段替换为：

```markdown
### core/mcp/（MCP 客户端核心，基于 aj-mcp-client 1.5 标准实现，JDK8）

- `McpManager`：状态机（DISCONNECTED/CONNECTING/CONNECTED/FAILED）+ 惰性连接（幂等去重）+ 全局工具表 + call 路由（未连接先同步重连 ≤10s，连接层异常自动断开待下次重建）+ `save()` + shutdown；`addListener` 连接线程回调（GUI 层 Platform.runLater 刷新）
- `AjMcpClient`：包装库客户端——`McpClient.builder().transport(t)` 完成握手/版本协商/JSON-RPC 帧；`tools/list`（游标分页）与 `tools/call` 走同一 transport 的原始请求取 JsonNode 转 gson（inputSchema 零损耗；非 text 内容序列化为 JSON 文本）；传输失败抛 `McpConnectionException`（区别于工具业务错误）
- `McpTransports` 工厂逻辑在 `McpManager.transportOf`：stdio → `StdioTransport`（命令经 `McpCommands` 组装，Windows npx→cmd /c）；sse → `HttpMcpTransport`（旧版 endpoint 事件握手）；streamable → `StreamableHttpTransport`（`Mcp-Session-Id`/`MCP-Protocol-Version` 头 + 请求头）
- `McpCommands`：stdio 命令组装（Windows `.cmd/.bat` 自动 `cmd /c` 包装，PATH 探测 npx→npx.cmd）
- `McpJson`：Jackson JsonNode → gson 转换（仅转换不解析）
- `McpStore`：jarDir/mcp.json 单文件多服务器（原子写，损坏备份 .bak）
- `McpServer`：配置字段（name/transport(stdio|sse|streamable)/command/args/env/url/headers/enabled）+ transient 状态
- `McpToolInfo` / `McpException` / `McpConnectionException`：工具元数据 / 业务异常 / 连接层异常
```

- [ ] **Step 3: CONVENTIONS 依赖约束更新**

`docs/CONVENTIONS.md` 第 20 行改为：

```markdown
- 新依赖必须 JDK8 兼容（实测字节码 major ≤52）；避免 Kotlin/重量级依赖（okhttp 长期选 3.x；唯一例外：MCP 库 aj-mcp-client 编译期依赖 okhttp 4.12 + kotlin-stdlib，全项目统一升 4.12，理由见 2026-09-01-mcp-standard-client-design.md）
```

- [ ] **Step 4: CLAUDE.md 更新**

第 4 行依赖列表改为：

```markdown
JDK 8 + Maven 单模块。GUI 为唯一界面（JavaFX 8，JDK 自带 jfxrt）。依赖：gson、okhttp 4.12、okhttp-sse 4.12、aj-mcp-client 1.5（MCP 标准客户端，含 jackson/slf4j-simple）、snakeyaml、flexmark 0.62.2（测试：junit4、mockwebserver 4.12）。
```

第 14 行 `core/` 下的 mcp 行改为：

```markdown
        ├── mcp/     MCP 客户端：McpManager（状态机/惰性连接/路由）、AjMcpClient（aj-mcp-client 包装，三传输）、McpCommands、McpJson、McpStore（mcp.json）、McpServer
```

- [ ] **Step 5: 全量构建验证**

Run: `mvn -q clean package && ls -la target/minion-0.1.0.jar`
Expected: 构建成功；记录体积（约 8 MB）并确认无 logback 类：

Run: `unzip -l target/minion-0.1.0.jar | grep -c logback`
Expected: 0。

- [ ] **Step 6: Commit**

```bash
git add README.md docs/ARCHITECTURE.md docs/CONVENTIONS.md CLAUDE.md
git commit -m "docs: MCP 标准客户端改造的文档同步（三传输/依赖/体积）"
```

---

### Task 10: 真机验收（依赖用户提供的 aj-mcp + Spring Boot demo）

**Files:** 无（手工清单）

**Interfaces:**
- Consumes: 用户提供的 demo 服务端（应含 `/sse` 与 `/mcp` 端点）；本机 Node 18+（可选验 stdio）

- [ ] **Step 1: streamable 联调**

在设置窗新建：传输 `Streamable HTTP`、URL 填 demo 的 `/mcp` 地址（如 `http://127.0.0.1:8080/mcp`），勾选启用 → 状态点变绿、工具数 > 0；让模型调用其中一个工具，观察返回。

- [ ] **Step 2: 旧版 SSE 联调**

新建：传输 `SSE`、URL 填 demo 的 `/sse` 地址 → 状态点变绿、工具数正确；调用一个工具成功。（验证 endpoint 事件握手在真实服务端下工作。）

- [ ] **Step 3: stdio 联调（可选，需 Node 18+）**

新建：传输 `stdio`、命令 `npx`、参数 `@playwright/mcp` → 状态点变绿、工具数 > 0；让模型「打开网页并返回标题」。

- [ ] **Step 4: 失败路径冒烟**

把 URL 填错 → 状态点变红且显示原因；改回正确 → 重连恢复；停止 demo 服务后调用工具 → 自动重连路径不报错。

- [ ] **Step 5: 收尾**

如发现库缺陷（如协议版本协商失败、超时处理不当），记入设计文档「已知限制」并决定是否给上游提 issue；更新 README 验收结果一段。
