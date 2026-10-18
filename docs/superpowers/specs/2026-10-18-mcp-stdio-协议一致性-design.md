# MCP stdio 协议一致性修正：最新协议版本协商 + LF 帧 + _meta 清理 + 退出诊断 + 陈旧工具表

日期：2026-10-18
状态：已实施（2026-10-18，全量 1268 用例通过）

## 背景与问题

公司机器上同一 stdio MCP 服务器（Node.js 包，npx 启动）**千问能连、minion 连不上**；
minion 设置页曾显示工具数，随后状态为失败（用户口径：能识别工具数量，但无法连上）。
工作区无该机器配置与失败原文，先做实现层面的规范核对。

### 本机实测（证据）

用一个最小标准 Node MCP 服务器（行分隔 JSON-RPC、stdout 只输出协议行、日志走 stderr）验证：

| 验证项 | 结果 |
|--------|------|
| `AjMcpClient` + `MinionStdioTransport` 连接真实 node 进程 | 通过（握手 → tools/list → 中文 tools/call） |
| `McpCommands` Windows 组装 `npx` | 通过：`cmd /c <PATH>\npx.cmd -y ...` |
| 抓取真实帧（server 原始字节捕获） | **发现问题**：帧以 `CRLF` 结束、`protocolVersion=2024-11-05`、`tools/call` 带 `"_meta":null` |
| 握手后进程退出 | **发现问题**：失败原因只有「MCP stdio 进程未运行 / stdout EOF」，不含 stderr 与退出码 |

→ stdio 主链路可用，偏差集中在**协议版本偏好、帧行尾、_meta、失败诊断、陈旧工具表**五处。

## 根因（与 MCP 规范 / 官方 SDK 的差异）

1. **协议版本偏好过旧**：aj-mcp-client 默认 `protocolVersion=2024-11-05`，`AjMcpClient` 未覆盖。
   官方新 SDK（千问等）发最新版本（`2025-06-18`），服务端按规范返回自己支持的版本。
   只支持新版本的服务端遇到旧版本客户端可能直接拒绝/退出 → 与「千问能连、minion 不能」现象一致。
2. **帧行尾随平台**：`PrintStream.println` 用 `line.separator`，Windows 实际发 `CRLF`；
   规范与官方 SDK 只发 `\n`。宽容实现能解析，严格实现（自研/非 JS）可能失败。
3. **`params._meta: null`**：库的 `CallToolRequest` 序列化出 null 的 `_meta`（可选保留字段），
   严格校验的服务端可能以参数错误拒绝。
4. **退出诊断缺失**：stdout EOF 与「进程未运行」分支的错误只含固定文案；
   握手成功后（tools/list 阶段）断开时，`AjMcpClient.listTools` 的错误不附 stderr → 用户看不到 npx/node 真实报错。
5. **陈旧工具表**：`McpManager.doConnect` 失败与重连开始时不清 `tools`，
   上一次成功连接的工具表残留 → 出现「显示有 N 个工具，但实际连不上」。

## 方案

| 位置 | 修改 |
|------|------|
| `AjMcpClient` | 协议版本候选取库支持列表（从新到旧：2025-06-18 → 2025-03-26 → 2024-11-05）；单传输用最新，工厂模式第 N 次尝试用第 N 个版本（服务端不合规地拒绝新版本时自动降级；合规服务端的协商行为不变） |
| `MinionStdioTransport` | 写帧改为 `print(json) + write('\n') + flush()`，固定 LF；`sendRequestWithResponse` 前清理 `params._meta == null`；stdout EOF 与「进程未运行」错误附退出码 + stderr 末尾 + 被跳过的非协议输出（EOF 前先等 stderr 读线程 ≤300ms） |
| `McpManager` | `ensureConnectedAsync` 进入 CONNECTING 时与 `doConnect` 失败时清空 `tools` |

## 测试（新增 `MinionStdioProtocolConformanceTest` 6 例，先红后绿）

| 用例 | 断言 |
|------|------|
| `initialize_usesLatestProtocolVersion` | initialize 帧含 `"protocolVersion":"2025-06-18"` |
| `frames_endWithUnixLineFeedOnly` | 捕获帧无 `CR` 前缀（只有 `LF`） |
| `toolsCall_omitsNullMeta` | tools/call 帧不含 `"_meta":null` |
| `connect_succeedsWhenServerOnlyAcceptsLatestVersion` | 服务端只认最新版本时能连上 |
| `connect_fallsBackWhenServerRejectsLatestVersions` | 服务端只认 2024-11-05 时降级后连上 |
| `processExitAfterHandshake_messageCarriesStderrAndExitCode` | 握手后退出：错误含 `noisy-stderr-line-3` 与退出码 `3` |

另在 `McpManagerTest` 新增 `reconnectFailure_clearsStaleTools`（重连失败后旧工具表为空）。
测试辅助 `NoisyMcpServer` 增加 `capture=文件`（按原始字节记录 `LF|`/`CR|` 前缀行）与
`onlyVersion=X`（只接受指定协议版本，否则 exit 9）。

## 影响与风险

- 协议版本发最新：合规服务端按规范返回自己的版本，客户端支持列表不变；只认旧版且**直接报错**的服务端由第 2/3 次尝试降级兜底。
- 降级与「启动失败重试」共用 3 次尝试（版本随尝试递降），日志/失败原因不受影响。
- LF 帧与 `_meta` 清理只作用于 minion 自实现的 stdio 传输；SSE / Streamable HTTP 仍由库处理。
- 清理 `tools` 只影响列表展示与后续注册来源；已注入会话的旧工具对象无法撤销，但重连成功后按新工具表重注册。
- 未处理项：stdio 的 `cwd` 配置（现无此字段，官方 SDK 有）；如需再补。
