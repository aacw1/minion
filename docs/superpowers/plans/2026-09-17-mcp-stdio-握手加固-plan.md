# MCP stdio 握手加固实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复内网 stdio 场景「服务器 stdout 混入非 JSON 行（banner/日志/空行）→ 库读线程死亡 → 误报 `MCP process stdout closed unexpectedly`」，并补上 UTF-8 显式编码、握手通知脱离读线程、stderr/噪声诊断与连接重试（500ms×3）。

**Architecture:** 复用库公开抽象基类 `McpTransport`（正规传输扩展点），新增 `MinionStdioTransport` 只修补读循环与握手细节——**不覆盖第三方类、不改库代码、不升级依赖**；`AjMcpClient` 增加传输工厂构造器（失败即重建传输/进程重试 3 次）与失败诊断附加；`McpManager.transportOf` 改为 `transportFactoryOf`（stdio 用新传输，SSE/Streamable 原样用库传输）。

**Tech Stack:** Java 8、aj-mcp-client 1.5（不升级）、JUnit 4、Maven（maven-shade-plugin 3.4.1）。

**设计文档:** `docs/superpowers/specs/2026-09-17-mcp-stdio-握手加固-design.md`（已获批）

## Global Constraints

- **JDK 8 语法**（禁 `var`/`List.of`）；注释、提交信息均中文（commit 用 conventional 格式）。
- **不覆盖、不改、不升级第三方**：只用 aj-mcp-client 1.5 的 public/protected API；`com.ajaxjs.*` 包内不新增/修改文件。
- **语义逐条对齐库 1.5**（以字节码为对照）：`start` 的已启动/已关闭保护、`sendRequestWithResponse` 初始化校验、`sendRequestWithoutResponse` 不校验、`close` 幂等 + 失败未决请求。
- **HTTP/SSE 传输与库握手保持原样**；`AjMcpClient(McpTransport)` 单次连接语义保留（既有测试与 HTTP 用法兼容）。
- **诊断格式定稿**：失败原因 = 原 message + `\n服务器 stderr 末尾:`（多行缩进）+ `\n被跳过的非协议输出:`（多行缩进）；两份数据各留最近 20 行。
- 每任务结束：相关测试全绿；最后 `mvn test` 全量 + `mvn -q -DskipTests package`。

---

### Task 1: 新增 MinionStdioTransport（TDD）

**Files:**
- Create: `src/test/java/com/minion/core/mcp/NoisyMcpServer.java`（测试脚手架，不入生产）
- Create: `src/test/java/com/minion/core/mcp/MinionStdioTransportTest.java`
- Create: `src/main/java/com/minion/core/mcp/MinionStdioTransport.java`

**Interfaces:**
- Produces（Task 2/3 依赖）：`MinionStdioTransport.builder().command(List<String>).environment(Map).logEvents(boolean).build()`；
  `List<String> stderrTail()`、`List<String> noiseTail()`；继承 `McpTransport` 全部抽象方法。

- [x] **Step 1: 写测试脚手架 `NoisyMcpServer`**

开关：`banner` / `blank` / `log` / `stderr=N` / `dieAfterInit` / `dieNow` / `flaky=<计数文件>`；
协议面：initialize（2024-11-05）/ notifications/initialized / tools/list（中文描述 `echo_zh`）/ tools/call（中文回声），全程 UTF-8。

- [x] **Step 2: 写回归用例（观察红）**

核心用例：banner+blank+log 混入 → 握手 + 中文工具描述 + 中文调用回显全部成功。
Run: `mvn -o test -Dtest=MinionStdioTransportTest`
Expected: 编译失败——`找不到符号: 类 MinionStdioTransport`（功能缺失，符合预期）。✅ 实测一致。

- [x] **Step 3: 实现 `MinionStdioTransport`**

要点：读循环 `JsonUtils.OBJECT_MAPPER.readTree(line)`（绕开库封装，避免其内部对每次解析失败打带堆栈 WARN）
解析失败 → `noiseTail` 记一行 + `continue`；`handle(node)` 异常也不杀线程；UTF-8 显式读写；
`initialize()` 响应到达后在专用守护写线程发 `notifications/initialized`（不在读线程），通知未送达不判死握手；
`close()` 先断流/杀进程再 join 读线程（保证 stderr 尾巴完整）、`failPendingRequests` 收尾。

- [x] **Step 4: 测试通过**

Run: `mvn -o test -Dtest=MinionStdioTransportTest`
Result: `Tests run: 6, Failures: 0, Errors: 0` ✅（含核心回归、UTF-8、stderr 诊断、dieAfterInit 不误判、重试成功、重试耗尽）。

---

### Task 2: AjMcpClient 重试 + 诊断

**Files:**
- Modify: `src/main/java/com/minion/core/mcp/AjMcpClient.java`
- Test: `src/test/java/com/minion/core/mcp/MinionStdioTransportTest.java`（追加 2 用例）

**Interfaces:**
- Produces（Task 3 依赖）：`interface TransportFactory { McpTransport create(); }`；`AjMcpClient(TransportFactory)`。

- [x] **Step 1: 写重试用例（观察红）**

`connect_retriesAndSucceedsOnThirdAttempt`（服务器前 2 次启动即退 → 第 3 次成功，计数文件断言 = 3）；
`connect_allAttemptsFail_throwsMcpException`（三次全失败，计数 = 3）。
Run: `mvn -o test -Dtest=MinionStdioTransportTest`
Expected: 编译失败——构造器 `AjMcpClient(TransportFactory)` 不存在。✅ 实测一致。

- [x] **Step 2: 实现**

`TransportFactory` 构造器（工厂模式 3 次尝试、每次失败 `close()` 清理并 500ms 后重建；单传输模式保持一次）；
失败原因 = `MCP 握手失败（已尝试 3 次）: <rootMessage>` + `diagnostics()`（stderr 尾巴 + 噪声行）。

- [x] **Step 3: 测试通过（含修 bug）**

Result: `Tests run: 6, Failures: 0` ✅。
过程中发现并修复：单传输模式 `transport` 字段未赋值 → `listTools()` NPE（被包装成连接异常）、诊断取空。

---

### Task 3: McpManager 工厂化

**Files:**
- Modify: `src/main/java/com/minion/core/mcp/McpManager.java`

- [x] **Step 1: `transportOf` → `transportFactoryOf`**

stdio → `() -> MinionStdioTransport.builder().command(McpCommands.build(...)).environment(s.env).build()`；
streamable/sse → 库传输（每次连接尝试新建，重试同受益）；URL 缺失校验保持在工厂构造前（同步抛 McpException）。

- [x] **Step 2: MCP 相关回归**

Run: `mvn -o test -Dtest='McpCommandsTest,McpJsonTest,McpStoreTest,McpManagerTest,AjMcpClientTest,AjMcpClientStreamableTest,AjMcpClientLegacySseTest,MinionStdioTransportTest'`
Result: `Tests run: 32, Failures: 0, Errors: 0` ✅。

---

### Task 4: 端到端对照探针 + 全量 + 打包

- [x] **Step 1: 新旧传输对照探针（临时，不入库）**

同一台「banner + 空行 + 每请求日志」的噪声服务器：

| 传输 | 结果 |
|------|------|
| 库 `StdioTransport` | 读线程抛 `RuntimeException: JsonParseException: Unrecognized token 'NoisyMCP'` 死亡 → `MCP 握手失败: TimeoutException` ❌（缺陷复现） |
| `MinionStdioTransport` | 连接成功，工具数 = 1 ✅ |

- [x] **Step 2: 全量测试**

Run: `mvn -o test`
Result: `Tests run: 1201, Failures: 0, Errors: 0` ✅。

- [x] **Step 3: 打包**

Run: `mvn -o -q -DskipTests package`
Result: `target/minion-0.1.0.jar` ✅；含 `com/minion/core/mcp/MinionStdioTransport*.class`；
库 `com/ajaxjs/mcp/client/transport/StdioTransport.class` 原样保留（未覆盖，符合设计）。

---

### Task 5: 文档同步

- [x] `README.md`：MCP 段补 stdio 传输实现与诊断/重试说明
- [x] `docs/ARCHITECTURE.md`：core/mcp 段补 `MinionStdioTransport`、`transportFactoryOf`、重试与诊断
- [x] 设计文档补「实施记录」（文件清单 / 验证数字 / 遗留）
