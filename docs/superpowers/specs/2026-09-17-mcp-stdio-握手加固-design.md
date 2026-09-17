# MCP stdio 握手加固：非协议行容错 + 三步握手脱离读线程 + stderr 诊断 + 连接重试

日期：2026-09-17
状态：已实施（2026-09-17，全量 1201 用例通过 + 新旧传输对照探针通过）

## 背景与问题

内网（麒麟系统）minion 连 MCP 服务器**频繁握手失败**，报错原文（用户提供，界面截断）：

```
MCP 握手失败: MCP process stdout closed unexpectedly
```

同一台机器上，该 MCP 服务器用千问 agent 能稳定连接 → 服务器本身与网络无问题，是 minion 侧
（aj-mcp-client 1.5 的 stdio 传输）的行为差异。

minion 侧现状：`AjMcpClient.connect()` 调 `client.initialize()`，握手、版本协商、JSON-RPC 帧、
传输全部交给 aj-mcp-client 1.5。

### 实测复现（本机 JDK8，探针 DiagProbe，6 种形态各跑一遍）

| 场景 | minion 实际显示 |
|------|-----------------|
| A 命令不存在 | `MCP 握手失败: CreateProcess error=2, 系统找不到指定的文件。`（异常链含 `java.io.UncheckedIOException`） |
| B 进程秒退（未响应） | `MCP 握手失败: StdioTransport is not running` |
| C 响应 initialize 后立即退出 | `MCP 握手失败: StdioTransport is not running` |
| D 不响应就退出 | `MCP 握手失败: MCP process stdout closed unexpectedly` |
| E **stdout 先输出一行非 JSON**（banner/日志）后正常服务 | `MCP 握手失败: MCP process stdout closed unexpectedly`（与 D 同文本，误导） |
| F 正常服务器 | 连接 / tools/list / tools/call 全部通过 |

结论：用户报错（D/E 同文本）指向**读线程死亡**；结合「千问能连」这一事实，最可能是 **E**。

### 上游版本核对（1.5 vs 1.6 vs master）

| 对比项 | 结果 |
|--------|------|
| `StdioTransport.class` 1.5 vs 1.6 字节码 | **完全一致（diff 0 行）** |
| `McpClientBase.initialize()` 1.5 vs 1.6 | **完全一致** |
| 类清单 1.5 vs 1.6 | 完全相同 |
| master（未发布） | 仅两处变化：stdio 读写改显式 UTF-8；`initialize` 抽成 `completeInitialization(...)`——**仍是 `thenCompose`，仍在读线程发 initialized**；读循环仍是 `handle(JsonUtils.json2Node(line))`，**依旧零容忍非 JSON 行** |

→ **升级 1.6 无收益；该缺陷在 1.5 / 1.6 / master 全线存在。**
（`StdioTransport.close()` 的兜底文本 `MCP process stdout closed unexpectedly` 就是本 bug 在 GUI 上显示的原文。）

## 根因

1. **读线程零容错（主因）**：`StdioTransport` 读线程 `handle(JsonUtils.json2Node(line))`，
   `JsonUtils.json2Node` 对**任何非 JSON 行**（服务器 banner、`console.log`/`print` 日志、空行、
   启动脚本输出、乱码字节）抛 `RuntimeException`；读线程只 catch `IOException`，
   `RuntimeException` 直接杀死读线程并 `failPendingRequests("MCP process stdout closed unexpectedly")`，
   所有 pending 请求被判死 → 表现为「握手失败」。
   官方 SDK（千问所用）的 stdio 读循环对解析失败的行是**记录并继续**，所以同一服务器千问能连。
2. **三步握手第三帧在读线程上发**：`StdioTransport.initialize()` =
   `execute(initialize).thenCompose(→ execute(notifications/initialized))`，而 `execute` 的 future 由读线程
   `handle()` complete → `thenCompose` 回调**在读线程上写 stdin**。写一行小 JSON 本身不阻塞，
   但进程若已退出则该写失败 → 整个握手被判失败（实测场景 C）。
3. **平台默认编码（隐患）**：1.5/1.6 的 stdio 读写 `InputStreamReader`/`PrintStream` 未指定字符集，
   中文 Windows / C locale 麒麟下与服务器 UTF-8 不一致（master 已修，minion 未受益）。
4. **失败原因不含服务器 stderr**：库的 stderr 读线程只 `log.warn`（GUI 不可见），
   失败原因里没有任何服务器侧信息，排障黑洞。
5. **无重试**：库不重试，minion `connect()` 单发即定，偶发失败直接进 FAILED。

> 说明（对既有内部分析的修正）：内网分析称「thenCompose 在读线程发通知阻塞了读 stdout 的唯一职责」——
> 机制描述部分成立（写确实发生在读线程），但**写一行小 JSON 不会阻塞读取**；真正的杀手是 **第 1 条读线程零容错**，
> 其提出的「手动三步握手 + 重试」单独实施**治不了这个病**（读线程照样死、照样报同一句话）。

## 方案选择（用户确认）

| 候选 | 结论 |
|------|------|
| 升级 aj-mcp-client 1.6 | 否决：字节码 diff 0 行，未修 |
| 只改 `AjMcpClient`（手动握手 + 重试） | 否决（不充分）：读线程仍在库里，服务器 stdout 一有杂行仍必失败；仅改善错误表现 |
| 复制库类为**同名同包**覆盖（`com.ajaxjs...StdioTransport`） | 否决：实测 maven-shade 3.4.1 下项目类确实胜出、技术上可行，但**静默屏蔽上游修复**（升级 1.6 会直接编译不过——1.6 删了 `McpTransport.setPendingRequests()`）、把第三方实现吞进本仓库（License/维护/排障跳转均劣化），且并不更省代码（Lombok builder 仍需复刻） |
| **新增 `MinionStdioTransport`（独立包名，继承库公开抽象基类 `McpTransport`）** | **采纳**：用库预留的传输扩展点，不覆盖、不动第三方类；HTTP/SSE 继续用库传输；顺手把 UTF-8、诊断、重试一并做掉 |

## 详细设计

### 1. 新增 `com.minion.core.mcp.MinionStdioTransport extends McpTransport`

只依赖 1.5 的 public/protected API（`start` / `sendRequestWithResponse` / `sendRequestWithoutResponse` /
`sendJson` / `markInitialized` / `setNegotiatedProtocolVersion` / `saveRequest` / `handle` /
`failPendingRequests` / `numericId` / `requireInitialized`），改动第三方库零行。

**字段**：`command`、`environment`、`logEvents`（构造注入，手写 Builder 替代 Lombok）、
`process`、`out`（`PrintStream(..., true, "UTF-8")`）、`stdoutReaderThread`、`stderrReaderThread`、
`closed`、`stderrTail`（环形缓冲，默认 20 行）、`noiseTail`（非协议行环形缓冲，默认 20 行）、
`notifyExecutor`（守护单线程，专发 initialized 通知）。

**`start(pendingRequests)`**：`setPendingRequests(...)` → `ProcessBuilder` 启动（环境合并，沿用现状）
→ `out = new PrintStream(process.getOutputStream(), true, "UTF-8")` → 起读线程。

**stdout 读线程（核心修复）**：

```java
while ((line = reader.readLine()) != null) {
    JsonNode node;
    try {
        node = JsonUtils.json2Node(line);
    } catch (RuntimeException e) {
        recordNoise(line);          // 记录到 noiseTail（供失败原因）；空行只跳过不记
        log.warn("跳过非 JSON 协议行: {}", abbreviate(line, 200));
        continue;                   // ← 宽容：不再杀死读线程
    }
    if (node == null || node.isNull()) continue;
    try {
        handle(node);
    } catch (RuntimeException e) {  // 协议内容异常也不杀线程（真断流才 EOF）
        recordNoise("handler: " + e);
        log.warn("处理协议帧异常", e);
    }
}
// EOF（进程退出）：failPendingRequests(new IOException("MCP 服务器进程已退出（stdout EOF）"))
```

**stderr 读线程**：每行入 `stderrTail` 环形缓冲 + `log.warn`。

**`execute(json, id)`**：同库语义（进程存活检查 → `saveRequest` → `println` → `checkError()` 检查），
文本换成中文可读文案；`id == null` 时立即完成。

**`initialize(InitializeRequest)`（手动三步握手，通知脱离读线程）**：

```java
CompletableFuture<JsonNode> resp = execute(JsonUtils.toJson(request), numericId(request.getId()));
CompletableFuture<JsonNode> done = new CompletableFuture<JsonNode>();
resp.whenComplete((node, err) -> {
    if (err != null) { done.completeExceptionally(err); return; }
    notifyExecutor.execute(() -> {           // 专用写线程：不在读线程、不在调用线程
        try {
            checkProtocolVersion(node);      // 校验 result.protocolVersion ∈ ProtocolVersion.supportedVersions()
            execute(JsonUtils.toJson(new InitializationNotification()), null);
            done.complete(node);
        } catch (RuntimeException e) {
            done.completeExceptionally(e);
        }
    });
});
return done;                                  // 语义与库一致：完成时=响应已到且 initialized 已发出
```

语义与库 `initialize()` 返回的 future 一致 → 库的 `McpClientBase.initialize()`（含 `awaitResponse`、
版本校验、`markInitialized`）**继续复用，无需分叉**。

**其余**：`sendRequestWithResponse`（`requireInitialized` + `execute`）、`sendRequestWithoutResponse`、
`sendJson`、`checkHealth`、`close`（`failPendingRequests` → 关流 → `destroy`/`destroyForcibly` → join 线程，
并 shutdown `notifyExecutor`）与库一致。

**诊断 API（minion 自用）**：`List<String> stderrTail()`、`List<String> noiseTail()`。

### 2. `AjMcpClient` 改造

- 构造器二选一：
  - `AjMcpClient(McpTransport transport)`：单次连接（现有测试、HTTP 场景兼容）；
  - `AjMcpClient(TransportFactory factory)`：**每次尝试重建传输**（stdio 需要新进程），`connect()` 内重试。
- `connect()`：最多 3 次尝试，失败间隔 500ms；全部失败抛
  `McpException("MCP 握手失败: " + rootMessage(e) + 诊断尾巴)`，其中诊断尾巴取
  `MinionStdioTransport.stderrTail()` / `noiseTail()` 末尾若干行（形如
  `；服务器 stderr 末尾: [ERROR] xxx`、`；被跳过的非协议输出: Diag MCP server starting...`）。
- 成功后注册 `onServerRequest(PING,…)`；`connected = true`；失败路径 `close()` 收尾（沿用）。

### 3. `McpManager` 改造

`transportOf(s)` 拆成 `transportFactoryOf(s)`：stdio → `() -> new MinionStdioTransport(command, env, false)`；
streamable/sse → `() -> StreamableHttpTransport…/HttpMcpTransport…`（每次重建，重试同样受益）。
`doConnect` 改用 `new AjMcpClient(factory)`。状态机 / FACADE / FAILED 语义不变。

### 4. 不变的部分

- HTTP（streamable）与 SSE 传输仍用库实现与库握手（无「读线程」问题）。
- `McpHandle` 接口、`McpProxyTool`、GUI 展示、`mcp.json` 结构均不变。
- 库版本仍为 1.5（不升级、不覆盖）。

## 影响面

| 文件 | 改动 |
|------|------|
| `core/mcp/MinionStdioTransport.java` | 新增（约 300 行） |
| `core/mcp/AjMcpClient.java` | 工厂构造器 + 重试 + 失败原因附诊断 |
| `core/mcp/McpManager.java` | `transportOf` → `transportFactoryOf`（stdio 换新传输） |
| `test/.../NoisyMcpServer.java` | 新增假服务器（banner/中文描述/按启动次数退出等开关） |
| `test/.../MinionStdioTransportTest.java` | 新增（核心回归） |
| `test/.../AjMcpClientTest.java` | 补：重试与诊断尾巴用例 |
| `README.md` + `docs/ARCHITECTURE.md` | MCP 章节同步实现说明 |

## 测试计划

1. **核心回归（当前必红、改后必绿）**：服务器先输出 banner / 空行 / 日志行，再正常服务 →
   `connect()` + `listTools()` + `callTool()` 全部成功。
2. UTF-8 往返：工具 description 与调用参数含中文，服务器按 UTF-8 读写，内容零损耗。
3. 进程秒退（带 stderr 输出）→ 失败原因含 stderr 末尾行，不再是误导的 `stdout closed unexpectedly`。
4. `initialize` 响应后进程退出 → 握手结论准确（不再因通知写失败而整体判死）。
5. 重试：前 2 次启动即退、第 3 次正常 → `connect()` 成功（断言共启动 3 次）。
6. 既有 `AjMcpClientTest` / `AjMcpClientStreamableTest` / `AjMcpClientLegacySseTest` / `McpManagerTest` 全绿。
7. `mvn test` 全量通过；`mvn package` 后用端到端探针（DiagProbe 风格）复跑场景 D/E/F 验证。

## 验收标准

- 场景 E（stdout 混非 JSON 行）不再失败，且被跳过的行可在失败原因/诊断中看到。
- 场景 D（进程退出）失败原因附带服务器 stderr 末尾，可定位服务器侧原因。
- 重试生效（3 次、500ms 间隔）。
- 全量测试通过，README/ARCHITECTURE 同步。

## 风险与对策

| 风险 | 对策 |
|------|------|
| 容错读导致「真错误被吞」 | 非协议行一律进 `noiseTail` 并 warn 日志；失败原因自动附上末尾若干行 |
| `notifyExecutor` 线程泄漏 | 守护线程 + `close()` 里 shutdown |
| 与库升级耦合 | 只用 1.5 public/protected API；升级编译期暴露，不静默 |
| 自实现传输语义偏差 | 以库 1.5 字节码为对照逐个方法对齐（execute/close/checkHealth 语义逐条比对），并有既有测试兜底 |

## 实施记录（2026-09-17）

### 改动清单

| 文件 | 变更 |
|------|------|
| `core/mcp/MinionStdioTransport.java` | 新增（约 330 行）：读循环容错（JSON 解析失败记录后跳过、`handle` 异常不杀线程）、UTF-8 显式读写、`initialize` 通知专用写线程、协议版本预校验、stderr/噪声环形缓冲（各 20 行）+ `stderrTail()`/`noiseTail()`、`close()` 幂等（先断流杀进程再 join 读线程保证诊断完整） |
| `core/mcp/AjMcpClient.java` | 新增 `TransportFactory` 构造器（3 次尝试 × 500ms，每次重建传输）、失败原因附加 `diagnostics()`（stderr 末尾 + 非协议输出）；单传输构造器语义不变 |
| `core/mcp/McpManager.java` | `transportOf` → `transportFactoryOf`（stdio 用 `MinionStdioTransport`，streamable/sse 仍用库传输且每次新建） |
| `test/core/mcp/NoisyMcpServer.java` | 新增测试脚手架：banner/blank/log/stderr=N/dieAfterInit/dieNow/flaky=计数文件 开关 + 中文工具与回声 |
| `test/core/mcp/MinionStdioTransportTest.java` | 新增 6 用例 |

### 验证

| 项 | 结果 |
|----|------|
| `MinionStdioTransportTest`（含核心回归：banner+空行+日志混入仍完成握手/中文调用） | 6/6 通过 |
| MCP 相关回归（McpManagerTest 等 8 个类） | 32/32 通过 |
| 全量 `mvn test` | **1201 通过，0 失败** |
| 打包 `target/minion-0.1.0.jar` | 含 `MinionStdioTransport*.class`；库 `StdioTransport.class` 原样保留（未覆盖） |
| 新旧对照探针（同一噪声服务器） | 库传输：读线程 `RuntimeException: JsonParseException: Unrecognized token 'NoisyMCP'` 死亡 → 握手 `TimeoutException` 失败；新传输：连接成功、工具数 1 |

### 实施中发现并修正

- 单传输模式的 `transport` 字段未赋值 → `listTools()` NPE、诊断取空（已在 `doConnect` 统一赋值修复）。
- 噪声行解析原走库 `JsonUtils.json2Node`，其内部对每次解析失败都打一条带堆栈的 WARN（噪音大）→ 改为直接用 `JsonUtils.OBJECT_MAPPER.readTree`。

### 遗留（未做）

- 内网真实环境复测（部署新 jar 后用裸 `npx` 重连；若仍失败，失败原因会带 stderr 末尾与非协议输出，可一眼分因）。
- 上游 issue 材料（1.5/1.6/master 的读循环零容错缺陷；字节码级证据见本文「上游版本核对」）。
- 三处遗留问题（重试归层、上游处理口径、注释版本口径）按用户指示暂不处理。
