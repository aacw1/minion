# 2026-09-01 MCP 标准客户端改造设计

## 背景与问题

`core/mcp/` 目前是自研 JSON-RPC + 两条手写传输。实测缺陷（行号为写本文时真实代码）：

| 缺陷 | 位置 | 后果 |
| --- | --- | --- |
| 「SSE」不标准：直接 POST 到配置里那个 `/sse` URL，不解析 `endpoint` 事件、无 `Mcp-Session-Id`、无 `MCP-Protocol-Version` 头、不按 `text/event-stream` 拆帧 | `SseMcpClient.java:63-70`、`connect()` 里的 `GET url` | 标准 MCP HTTP 服务端一律连不上——这是「用不了标准的工具」的根因 |
| 协议版本硬编码 `2024-11-05`，不做协商 | `StdioMcpClient.java:113` | 只接受 `2025-03-26/06-18` 的新服务端握手即失败 |
| 不处理服务端主动请求（`ping`、`roots/list`、`sampling`）与 `notifications/*` | 无 | 带心跳的服务端等不到应答后超时 |
| 无 Streamable HTTP 传输 | 无 | 当前规范推荐的远程传输完全不支持 |
| `close()` 在 stdio 走 `destroy()` 但不 join 读线程；SSE 只 `cancel` | `StdioMcpClient.java:190` | 连接失败路径线程/管道残留 |

需求：

1. 改成**标准实现**——用第三方库完成协议与传输，不自研；支持 **stdio / SSE / Streamable HTTP** 三种传输。
2. MCP 配置里「传输」改为**选项**：选中一个后，其余不用填的字段清空并置灰/隐藏。
3. 回答「环境变量」字段用途、能否去掉。

## 决策记录（本会话用户确认）

| # | 决策 | 说明 |
| --- | --- | --- |
| 1 | 用 `com.ajaxjs:aj-mcp-client:1.5`，shade 内嵌 | **许可冲突已知悉并接受**：GitHub 仓库 `LICENSE` 与 README 徽章是 GPL-3.0，而 Maven POM（`ajaxjs-parent:1.35`）声明 Apache-2.0，作者自相矛盾。minion 仓库公开 + shade 分发，若按仓库 LICENSE 解读有 copyleft 传染风险。风险由用户承担 |
| 2 | 整站 `okhttp 3.14.9 → 4.12.0` | aj 编译期用了 4.x 才有的签名（`RequestBody.create(byte[])`），exclude 不掉。已 javap 验证 minion 现有 5 个调用点（`MediaType.parse`、`RequestBody.create(MediaType,String)`、`EventSources.createFactory`、`WebSocket/WebSocketListener`、`dispatcher()/connectionPool()`）在 4.12 全部保留 → 预期零源码改动 |
| 3 | 「环境变量」**保留**，仅 stdio 可填 | 见下节答复 |
| 4 | 「请求头」**仅 Streamable 可填** | 库的 `HttpMcpTransport`（旧版 SSE）无任何 header 注入点（构造参数只有 `sseUrl/logRequests/logResponses`，master 分支同样），旧版 SSE 本就是规范里已废弃的传输 |
| 5 | 范围：**只做 tools** | 不暴露 resources / prompts（库支持，但本次不做，YAGNI） |
| 6 | 表单联动方式：不适用字段**隐藏**（`setVisible(false)+setManaged(false)`）并清空 | 用户明确选择「隐藏」而非仅置灰 |
| 7 | 验收：离线测试全绿 + **用户自建 aj-mcp + Spring Boot 服务端 demo 真连** | 三传输各连一次，状态点绿 + 工具数正确 + 模型实际调用成功 |

「环境变量」用途答复：它**当前真实生效**（`StdioMcpClient.java:48` `pb.environment().putAll(env)`），作用是把环境变量注入 stdio 子进程——大量官方 MCP 服务器只能这样配密钥，如 `server-github` 需 `GITHUB_PERSONAL_ACCESS_TOKEN`、`server-amap` 需 `AMAP_MAPS_API_KEY`。所以不能整体删除；但只有 stdio 有子进程，选 SSE/Streamable 时它毫无意义 → 保留字段、按传输联动隐藏。库侧 `StdioTransport` 同样支持 `environment`，迁移后继续有效。

## 候选库实测（为何选 aj）

| 候选 | 字节码 | JDK8 可用 | 闭包 | API 形态 |
| --- | --- | --- | --- | --- |
| **aj-mcp-client 1.5** | 全链路 major 52（含 okhttp4/okio3/kotlin-stdlib/jackson） | ✓ | 15 jar / 5.5 MB（排掉父 pom 硬塞的 logback 后） | 同步阻塞 |
| `io.modelcontextprotocol.sdk:mcp:2.0.1`（官方） | major **61** | ✗ 需 Java 17 | — | — |
| `dev.langchain4j:langchain4j-mcp:1.19.0-beta29` | major **61** | ✗ | — | — |
| `org.noear:solon-ai-mcp:3.9.3` | 自身 52，但 **reactor-core 3.7.4 = major 55** | ✗ | 34 jar / 11 MB | Mono/Flux |
| `org.noear:mcp-core + mcp-json-jackson2:4.0.6`（官方 SDK 的 Java8 回移植） | 自身 52，但 **reactor-core 3.8.5 = major 55** | ✗ | 20 jar / 6.2 MB | Mono/Flux |

即：JDK 8 约束下 aj-mcp-client 是唯一能跑的现成标准实现；solon 系还比它大。库能力已核实：`StdioTransport`（`command:List<String>` + `environment:Map`，独立线程读 stderr 并 `log.warn("[ERROR] {}")`）、`HttpMcpTransport`（`startSseChannel` + `URI.create(sseUrl).resolve(endpoint)`，标准旧版 SSE）、`StreamableHttpTransport`（`requestHeaders` + `Mcp-Session-Id` 保持 + `MCP-Protocol-Version` 头）；`McpClientBase.initialize()` 做版本协商（`2024-11-05/2025-03-26/2025-06-18`）并 `markInitialized()`；`Cursor(String opaqueCursor)` 支持不透明游标。

## 关键陷阱：库的类型化工具模型是有损的

`aj-mcp-common:1.7` 里：

```java
class JsonSchema { String type; Map<String,JsonSchemaProperty> properties; List<String> required; Boolean additionalProperties; }
class JsonSchemaProperty { private String type; private String description; }   // 嵌套 properties 在源码里被注释掉
```

`JsonUtils` 又配了 `FAIL_ON_UNKNOWN_PROPERTIES=false`（静默丢字段）。于是 `enum`、`items`、`$ref`、`default`、嵌套 object 全丢——直接拿 `client.listTools()` 喂模型，Playwright / Figma / GitHub 这类带枚举和嵌套参数的工具会被削成残废，等于把「用不了标准工具」从连接层搬到 schema 层。

另外 `McpClient.callTool()` 的便利方法遇到非 text 内容**直接抛异常**（`extractSuccessfulResult`：`Unsupported content type: image`），Playwright 截图必炸；它还会把 timeout 吞成一句 `"There was a timeout executing the tool"` 字符串而不是失败。

结论：**协议与传输交给库，`tools/list` 与 `tools/call` 的响应用原始 `JsonNode` 取回**（方案 A）。落点是自己构造并**同时持有** `McpTransport` 引用：`client.initialize()` 内部会 `transport.start(pendingRequests)` 完成登记，此后直接调 `transport.sendRequestWithResponse(req)` 即可拿到原始响应，走同一张 pending 表（id 从 100000 起自增，避开库内 `idGenerator`，实测不冲突）。

## 架构

### 依赖变更（pom）

```xml
<!-- 新增：标准 MCP 客户端（协议/协商/三传输）；排掉父 pom 硬塞的 logback 后端 -->
<dependency>
  <groupId>com.ajaxjs</groupId><artifactId>aj-mcp-client</artifactId><version>1.5</version>
  <exclusions><exclusion><groupId>ch.qos.logback</groupId><artifactId>*</artifactId></exclusion></exclusions>
</dependency>
<!-- 新增：日志后端，级别钉 warn（见下） -->
<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-simple</artifactId><version>1.7.36</version></dependency>
<!-- 升级：okhttp / okhttp-sse / mockwebserver 3.14.9 → 4.12.0 -->
```

新增资源 `src/resource/simplelogger.properties`：`defaultLogLevel=warn`。理由：库把子进程 stderr 以 `log.warn` 输出（warn 级正好可见，便于排「服务器启动失败」），但每个请求的 `log.info("JSON RPC {}")` 会刷屏，必须压掉。

体积：jar 2.75 MB → 约 8 MB（kotlin-stdlib 1.6M + jackson 2.3M + okhttp4/okio3 净增 + aj/slf4j）。

### core/mcp 类清单

| 类 | 处置 | 职责 |
| --- | --- | --- |
| `JsonRpc.java` | **删** | JSON-RPC 编解码由库承担 |
| `StdioMcpClient.java` | **删** | 由 `StdioTransport` 替代（Windows 包装逻辑迁出） |
| `SseMcpClient.java` | **删** | 由 `HttpMcpTransport` 替代（不标准实现消失） |
| `McpClient.java`（接口） | **改名 `McpHandle`** | 与 `com.ajaxjs.mcp.client.McpClient` 重名。签名不变：`connect()/tools()/call()/close()`，仍抛 `McpException` |
| `AjMcpClient.java` | 新 | `implements McpHandle`。构造 transport（按 `McpServer.transport`）→ `com.ajaxjs...McpClient.builder().transport(t).clientName("minion").clientVersion("0.1.0").requestTimeout(120s).build()` → `initialize()` 完成握手+协商；`tools()`/`call()` 走原始 `JsonNode`；库的 `RuntimeException/ExecutionException/TimeoutException` 统一包成 `McpException`（取 rootCause 消息）；`close()` 委托库 `close()`（内部关 transport） |
| `McpTransportFactory.java` | 新 | 纯函数：`McpServer → McpTransport`。stdio 用 `StdioTransport.builder().command(...).environment(...)`；sse 用 `HttpMcpTransport.builder().sseUrl(url)`；streamable 用 `StreamableHttpTransport.builder().endpointUrl(url).openEventStream(false).requestHeaders(headers)` |
| `McpCommands.java` | 新 | Windows 命令鲁棒解析（见下） |
| `McpJson.java` | 新 | Jackson `JsonNode` ↔ gson `JsonObject` 转换（`node.toString()` → `JsonParser`） |
| `McpManager.java` | 小改 | `Map<String, McpHandle>`；`doConnect` 换实现；`call()` 捕获连接类异常时把状态置 `DISCONNECTED`（旧版 SSE 长连接在库内 readTimeout 固定 60s，空闲被掐后需下次自动重连）；`ensureConnectedAsync/reconnect(≤10s)/shutdown/Listener` 语义不动 |
| `McpServer.java` | 小改 | 新增传输常量 `stdio｜sse｜streamable`；字段表**不增不删**（env/headers 保留）；注释修正 |
| `McpStore/McpToolInfo/McpException`、`core/tools/mcp/McpProxyTool`、`SessionManager` 注册链路、MCP 列表页 | **不动** | 工具名仍取服务器原始名 + 同名跳过计数 |

### stdio 命令解析（`McpCommands`）

库是裸 `new ProcessBuilder(command)`，不做任何包装 → Windows 上配 `npx` 必然起不来（实际文件是 `npx.cmd`，`CreateProcess` 只补 `.exe`）。这也很可能是用户当前「标准工具用不了」的直接原因之一。做法：命令不含路径分隔符时先用 `where`（Unix `which`）解析绝对路径；结果以 `.cmd/.bat` 结尾则最终列表前置 `cmd /c`；解析失败原样交给库（错误进 `failReason`）。

### 配置与 UI

`mcp.json` 结构不变（`name/transport/command/args/env/url/headers/enabled`，无 `streamUrl` 字段），仅 `transport` 取值扩为 `stdio｜sse｜streamable`；用户现有 `run/mcp.json` 为空 `{"servers":[]}`，无迁移成本。语义变化需在 README 写明：`sse` 现在指**标准旧版 HTTP+SSE**（GET `/sse` + `endpoint` 事件 + POST message 端点），不再是「直接 POST 到该 URL」。

表单（`SettingsDialog.form`）：传输由 `ComboBox` 改为 3 个 `RadioButton`（`ToggleGroup`，文案 `stdio（本地进程）` / `SSE（HTTP+SSE 旧标准，端点填 /sse）` / `Streamable HTTP（推荐，端点填 /mcp）`），联动矩阵：

| 传输 | 名称 | 命令 | 参数 | 环境变量 | URL | 请求头 |
| --- | --- | --- | --- | --- | --- | --- |
| stdio | ✓ | ✓ | ✓ | ✓ | 隐藏+清空 | 隐藏+清空 |
| sse | ✓ | 隐藏+清空 | 隐藏+清空 | 隐藏+清空 | ✓ | 隐藏+清空 |
| streamable | ✓ | 隐藏+清空 | 隐藏+清空 | 隐藏+清空 | ✓ | ✓ |

「隐藏」= `setVisible(false)+setManaged(false)`（版面随之收紧）+ 文本清空；同时「保存裁剪」：`resultConverter` 按当前传输只写本组字段，另一组写空。URL 提示文案随传输变化；环境变量与请求头各加一行说明（用途 + 例 `GITHUB_PERSONAL_ACCESS_TOKEN=...`）。裁剪/联动逻辑抽成可测纯函数（沿用现有 `parsePairs/splitLines` 的「静态方法 + 测试」风格）：`McpFormPolicy.fieldsOf(transport)` 返回启用字段集合、`McpFormPolicy.trim(McpServer, transport)` 做保存裁剪。

### 错误处理

| 场景 | 行为 |
| --- | --- |
| 握手/协商失败、进程起不来 | `McpException("stdio 启动失败: …" / "…")` → `McpManager` 置 `FAILED` + `failReason`（UI 红点显示，沿用现状） |
| 工具返回 `isError=true` 或 JSON-RPC `error` | 抛 `McpException` → `McpProxyTool` 转 `ToolResult.error`，交模型自调 |
| 超时 | 原始请求 `future.get(120s, MILLISECONDS)`（沿用现有 `McpHandle.CALL_TIMEOUT_MS=120_000`，playwright 导航慢），`TimeoutException` → `McpException("MCP 调用超时: <method>")`（不采用库的吞异常写法） |
| 连接类失败（流断开/进程退出） | 状态置 `DISCONNECTED`，下次 `call` 走既有 `reconnect`（≤10s）自动重建 |
| 退出 | `McpManager.shutdown()` → 每个 `McpHandle.close()` → 库内 transport 关流、销毁子进程 |

## 测试

| 测试 | 内容 |
| --- | --- |
| `AjMcpClientStdioTest` | 起 `FakeMcpServer`（改造现有测试桩）：应答标准 `initialize` 协商；`tools/list` 返回带 `enum`+嵌套 object+`items` 的 schema → 断言**逐字节透传**；返回 `image` 内容 → 断言不抛异常；返回 `isError=true` → 断言失败；返回 `nextCursor` → 断言翻页取全 |
| `StreamableTransportTest` | `MockWebServer` 扮标准 `/mcp`：`Mcp-Session-Id`、`MCP-Protocol-Version` 头与 `notifications/initialized` 断言（证明走的是标准流程） |
| `LegacySseTransportTest` | `MockWebServer` 扮 `/sse`：GET 事件流 + `endpoint` 事件下发 message 路径 → 断言 message 打到该路径 |
| `McpCommandsTest` | `.cmd` 检测与 `cmd /c` 包装、无 PATH 命中时原样交给库 |
| `McpFormPolicyTest` | 三行联动矩阵 + 保存裁剪 |
| `McpManagerTest/McpStoreTest` | 沿用，仅替换构造 |
| okhttp 4 回归 | `DeepSeekClientTest`（SSE 流式）、`CdpClientTest`（WebSocket）、`WebFetchToolTest` 必须全绿——整站升 okhttp4 的真实风险面 |
| 删除 | `JsonRpcTest`、`StdioMcpClientTest`、`SseMcpClientTest` |

真机验收：用户提供的 aj-mcp + Spring Boot demo 端点（`/sse`、`/mcp`）+ `npx @playwright/mcp`（stdio），三传输各连一次并调用成功。

## 已知限制与风险

1. **许可**（最高）：aj-mcp 仓库 GPL-3.0 与 POM Apache-2.0 冲突，用户已知悉并选择接受；缓解方案（不 shade、改外部 lib 目录随发行包）已记录备用。
2. **旧版 SSE 无法带自定义请求头**（库限制）→ 表单层面 sse 不提供该字段，文案引导「需要鉴权请用 Streamable HTTP」。
3. **jar 体积 2.75MB → ~8MB**，且引入 Kotlin 运行时——与 CLAUDE.md 里「okhttp 3.14 避开 kotlin」的旧约定冲突，需同步改约定说明。
4. 子进程 stderr 以 warn 级打到控制台（`slf4j-simple`），node 系服务器启动噪声可能较多，但排障价值高于安静。

## 文档同步

`README.md`（MCP 章：三传输 + env/headers 适用条件 + 体积）、`docs/ARCHITECTURE.md`（`core/mcp` 类清单）、`CLAUDE.md`（依赖行 okhttp 3.14 → 4.12 + 新依赖理由）、`docs/CONVENTIONS.md`（规约 1 的「okhttp 3.14 避 kotlin」表述修正）。
