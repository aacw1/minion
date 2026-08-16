# 2026-08-17 MCP 集成设计

## 背景与现状

minion 目前能力封闭在自带工具集（读写/搜索/Bash/浏览器 CDP 四工具），无法挂接外部工具生态。业界标准是 **MCP（Model Context Protocol）**——Anthropic 开源的 JSON-RPC 2.0 协议，千问/DeepSeek 等客户端均兼容；Playwright 官方提供 `npx @playwright/mcp` 浏览器自动化服务（点击/表单/多页导航，现有 CDP 工具做不到）。目标：接入 MCP，最终能用 Playwright。

现状相关点：

- 工具接口 `Tool`（name/description/schema/execute）＋每会话独立 `ToolRegistry`，`AgentLoop` 每轮动态取 `registry.schemas()`（`AgentLoop.java:363`）→ 工具集支持运行期补注册
- 设置窗左侧导航 ListView：基础设置 / 模型 / 关于（`SettingsDialog.java`）
- 浏览器工具注册条件：`Main` 无条件创建 `BrowserSession`，`browser.path` 为空也会尝试启动
- 依赖：gson、okhttp 3.14（JDK8）；官方 MCP Java SDK 需 Java 17+，不可用 → 自研协议核心
- 本机 Node 仅 v8.12.0（`E:\javame\nodejs`），`@playwright/mcp` 需 Node 18+；Node 官方不支持 Win7（最后 13.6.0），Playwright 对 Win7 仅 best-effort。**MCP 是纯可选能力：不配置不 spawn 任何进程、不注册任何工具；环境不满足只显示「连接失败」，不影响软件其余体验**

## 目标

- 设置窗新增「MCP」页签（与基础设置/模型/关于同级），样式参考千问 MCP 管理页：服务器列表 + 状态点 + 启用开关 + 新建/编辑/删除
- 支持标准 MCP 协议，两种传输：**stdio**（子进程 stdin/stdout）与 **SSE/HTTP**（GET /sse + POST /messages）
- 配置存独立 `mcp.json`（jar 同目录），字段：name/transport/command/args/env/url/headers/enabled
- 惰性连接：应用启动零开销，首次创建会话时对启用中的服务器**后台异步**连接；连接失败不阻塞
- 连接后 MCP 工具自动并入各会话工具集（下一轮请求模型可见），可直接被模型调用
- 浏览器：`browser.path` 未配置 → 不创建 BrowserSession、不注册 CDP 四工具；配置了 → 与 MCP 并存
- 环境要求：Playwright 需 Node 18+（用户环境 Win10 可装）；无 Node 时该服务器标记连接失败，其余不受影响

## 方案

### 1. 协议核心 `core/mcp/`

| 类 | 职责 |
|---|---|
| `McpServer` | 配置模型 + 运行时状态：name/transport/command/args/env/url/headers/enabled ＋ `State`（DISCONNECTED/CONNECTING/CONNECTED/FAILED）+ 失败原因 + 工具名集合 |
| `McpStore` | mcp.json 读写（原子写，仿 WorkspaceManager 模式）；空文件自动初始化 |
| `McpManager` | 全局单例：持有服务器表 + 每服务器 `McpClient` + 全局工具表；`ensureConnectedAsync(name)`、`routeCall(server, tool, args)`、`shutdown()`；连接状态变化回调 Listener 列表 |
| `JsonRpc` | JSON-RPC 2.0 消息编解码（gson）：request/response/notification/error 四型 |
| `McpClient` | 传输接口：`initialize()` / `listTools()` / `callTool()` / `close()` |
| `StdioMcpClient` | ProcessBuilder 启动子进程，**Windows 下命令以 .cmd/.bat 结尾时用 `cmd /c` 包装**（npx 实际是 npx.cmd）；读线程按行解析响应，写侧按行写请求；单写锁串行 |
| `SseMcpClient` | okhttp-sse 3.14.9：`GET <url>` 建 EventSource 收消息流，`POST` 发请求（json 头 + session 头）；读线程分发 |
| `McpException` | 连接/协议/调用异常 |

握手顺序（两传输一致）：`initialize`（protocolVersion/clientInfo/capabilities）→ `notifications/initialized` → `tools/list` → 工具表就绪。`tools/call` 结果映射 `ToolResult`：`content` 数组各元素文本化（text 原样 / resource 序列化），`isError=true` → `ToolResult.error`。

### 2. 工具接入 `core/tools/mcp/McpProxyTool.java`

- `implements Tool`：`name`/`description`/`schema`（inputSchema 原样，MCP 本就是 JSON Schema）来自 McpManager 工具表；`execute` 委托 `McpManager.routeCall(serverName, toolName, args)`
- `isHighRisk` 恒 false（与 Browser 工具一致，不弹确认；MCP 工具不动文件系统、不执行 shell）
- **冲突策略**：内置工具优先注册；MCP 同名工具跳过并计数，计数暴露给设置页提示
- 注册时机：McpManager 连接完成 → 工具表更新 → 回调 SessionManager → 对每个已建会话的 registry 补注册（`ToolRegistry.register` 是 Map.put，天然可后补）；`AgentLoop` 每轮重取 schemas → **下一轮**请求模型可见

### 3. 接线（Main / SessionManager）

- `Main`：`McpManager mcp = new McpManager(new McpStore(jarDir))`；构造参数传给 SessionManager；退出钩子追加 `mcp.shutdown()`
- `Main` 浏览器条件化：`config.browserPath()` 为空 → 不创建 BrowserSession（`browserSession=null`，newRegistry 里现有 `if (browserSession != null)` 分支即生效）
- `SessionManager.newRegistry`：对每个 enabled 服务器 `mcp.ensureConnectedAsync(name)`（幂等，已连接/连接中直接返回）；已连接服务器的工具注册 McpProxyTool
- `SessionManager` 实现 McpManager.Listener：`onToolsUpdated(server)` → 遍历存活会话 registry 补注册/更新该服务器工具（删旧加新）

### 4. 设置页「MCP」页签

- 导航项：`基础设置 / 模型 / MCP / 关于`（选中监听加一个分支）
- 服务器列表 ListView（CellFactory）：名称 + 传输类型 + 状态点（灰=未启用、绿=已连接+工具数、橙=连接中、红=失败+原因 Tooltip）+ 启用 CheckBox（选中立即写 mcp.json，on 时触发连接、off 时断连）
- [新建] [编辑] [删除] 按钮：表单（仿模型表单 Dialog）——名称、传输（下拉 stdio/SSE）、命令、参数（多行，空格/引号按行拆）、环境变量（多行 KEY=VALUE）、URL、请求头（多行 K:V，仅 SSE 显示）；「应用」保存 mcp.json
- 状态变化经 Listener 在 UI 线程刷新（Platform.runLater）
- 配置变更（增删改/开关）对**新会话**生效；已连接服务器配置变更 → 标记重连

### 5. 错误处理

| 场景 | 行为 |
|---|---|
| 连接失败（Node 缺失/命令不存在/握手超时） | 服务器 FAILED + 原因（进程 stderr 前几行）；不弹窗不阻塞；其余服务器/工具/会话不受影响 |
| 进程启动慢（npx 首次下载 10-60s） | CONNECTING 状态；异步无阻塞；完成自动进 CONNECTED |
| 调用失败/进程崩溃/EOF | `ToolResult.error`（模型自调重试）；服务器转 FAILED；下次调用尝试重连 |
| 工具名冲突 | 跳过 MCP 工具并计数，设置页提示 |
| 并发 | 单服务器内请求串行（写锁）；多服务器并行；两会话同时触发连接 → McpManager 连接锁去重（state 判 CONNECTING） |
| 退出 | `shutdown()`：stdio destroy + 超时 destroyForcibly；SSE 取消订阅；各线程 join 回收 |

### 6. 测试计划

- **JsonRpcTest**：四种消息编解码往返
- **StdioMcpClientTest**：测试用假 MCP 脚本（bat 回显 JSON-RPC 行，如 `echo {"jsonrpc":"2.0","id":1,"result":...}`）验证 initialize/tools/list/call 全流程；进程退出/EOF 行为
- **McpStoreTest**：原子写、读回、空文件初始化
- **McpManagerTest**：连接去重（CONNECTING 中二次调用不重复 spawn）、失败状态、shutdown
- **SseMcpClientTest**：MockWebServer 模拟 SSE 流 + POST 端点（现有 mockwebserver 依赖）
- **手动验证**：Node 18+ 环境配 `npx @playwright/mcp`，会话中让模型「打开百度搜索并截图」验证工具可见可调用

## 兼容性

- JDK 8：不用 record/var/lambda 外新语法；gson 既有
- 新依赖仅 `okhttp-sse:3.14.9`（与 okhttp 3.14 同族，JDK8 兼容；SSE 解析复用库实现避免手写协议边界）——设计理由如上
- mcp.json 独立文件，不影响 config.properties/model.json/workspace.json；不配置即零行为变化
- 会话落盘、消息契约、tool_call↔tool 配对不变；MCP 工具名进 schemas 遵循 OpenAI 兼容契约（工具名本身满足 `[a-zA-Z0-9_-]`）
- README 同步：MCP 章节（配置 mcp.json、Node 18+ 环境要求、Playwright 示例）
- 已知限制：Playwright 在 Win7 上不可用（Node 官方支持止于 13.6.0 + Chromium 兼容性），MCP 框架本身与 OS 无关
