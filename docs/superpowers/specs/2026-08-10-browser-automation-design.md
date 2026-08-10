# 浏览器自动化 + 网页调试工具 — 设计文档

日期：2026-08-10

## 需求

minion 目前只有只读的 WebFetchTool（抓 HTML 转文本），无法操作网页。
用户需要：能根据指令**操作页面**——输入用户名/密码、点击登录、点击、输入、
查询并处理相关信息，以及**调试网页**（看页面结构、网络请求、console 日志、执行 JS、截图）。

目标环境：**Win7 内网** + 公司内部现代前端网站（React/Vue SPA，JS 动态渲染）。

已确认的约束与决策：

- 目标网站是现代 SPA（React/Vue）→ 必须真实浏览器执行 JS，纯 HTTP 模拟出局
- 目标机器已装 **Chrome 109**（最后支持 Win7 的 Chrome 版本）→ CDP 驱动 Chrome
- 登录凭证在对话里直接告诉 minion（模型提取后填入表单），不进配置、不落盘存储
- 方案：**手写 CDP 客户端**（okhttp 3.x 自带 WebSocket，零新增依赖），
  排除 Selenium（依赖 +4MB 违反 2M 定位、内网 driver 管理麻烦、黑盒调试难）
  与 HtmlUnit（旧 JS 引擎跑不动 SPA）
- 调试范围：DOM/文本查看、截图、网络请求记录、JS 执行、console 日志（全都要）
- 工具落位 `core/tools/browser/` 子包（先例：`core/tools/confirm/`），
  符合「工具 → core/tools」分层规约

## 架构

```
core/tools/browser/
├── ChromeLauncher      启动/关闭 Chrome 进程、调试端口就绪轮询、端口冲突检测
├── CdpClient           okhttp WebSocket 封装：命令 id 匹配、事件分发、请求超时
├── BrowserSession      浏览器连接状态 + 网络/console 环形缓冲（容量上限防内存膨胀）
├── BrowserTool         导航（open/back/refresh/status）
├── BrowserEvalTool     执行 JS（输入/点击/取数据都走这里）
├── BrowserScreenshotTool  截图存工作区
└── BrowserDebugTool    调试信息（网络请求/console 日志/页面信息）
```

依赖方向：`core/tools/browser` 只依赖 `core/util`（ConsoleIo 等）与第三方库，
不反向依赖 cli/agent（与 confirm/ 子包一致）。工具在 Main 注册（现有模式）。

### CdpClient 协议封装

- 连接：`ws://127.0.0.1:<port>/devtools/page/<id>`（页面端点）或
  `.../devtools/browser/<id>`（浏览器端点，供 Browser 工具复用连接）
- 命令：自增 id + `{id, method, params}`；响应按 id 匹配回填
- 事件（Network.*、Runtime.consoleAPICalled、Page.loadEventFired）推入事件队列，
  由调用方轮询/取数（不用异步回调，保持 AgentLoop 同步模型）
- 超时：命令等待上限 browser.timeoutMs（默认 30s），超时抛工具失败

### Chrome 生命周期

- 启动：`ProcessBuilder` 拉起 `chrome.exe --remote-debugging-port=<port>
  --user-data-dir=<dir> --no-first-run`（headless 时加 `--headless=new`）；
  Chrome 默认绑定 127.0.0.1，不暴露局域网
- 就绪：轮询 `http://127.0.0.1:<port>/json` 最多 10s，取可用页面端点
- 关闭：minion 退出时（Runtime 钩子）优雅 kill；异常退出残留进程由
  下次启动前的端口探测兜底（端口占用 → 复用已有 Chrome 或报错提示）
- userDataDir 默认工作区 `.minion/browser-profile`：登录状态跨会话保留，
  清空目录即重置（工具返回错误时提示）

### BrowserEval JS 执行

- 走 `Runtime.evaluate`，`returnByValue=true`；结果 JSON 序列化返回
- JS 异常 → 失败 ToolResult：异常消息 + 近 3 条 console 错误（辅助定位 SPA 报错）
- SPA 受控组件（React onChange）填值：注入页面级辅助函数 `__minion_set_value(el, v)`
  （原生 value setter + 触发 input 事件），工具描述中给出推荐写法，
  模型直接调用，不用每次手写事件细节

## 工具设计（4 个，单一职责）

| 工具 | 参数 | 职责 |
|---|---|---|
| `Browser` | `action`(open/back/refresh/status) + `url`(open 时必填) | 导航与状态：当前 URL/标题/是否加载完成 |
| `BrowserEval` | `expression`(必) + `awaitPage`(选,默认 true) | 执行 JS 返回结果；输入/点击/取表格数据全走这里 |
| `BrowserScreenshot` | `path` + `fullPage`(选,默认 true) | 截图存工作区（png）；模型可随后 Read 查看 |
| `BrowserDebug` | `action`(network/console/page) + `limit`(选) | 网络请求列表（方法/URL/状态/时长）、console 日志（错误标注）、当前页面信息 |

高危判定：全部默认 `isHighRisk=false`（页面操作不破坏工作区文件）。Screenshot 写文件
走 PathsGuard.errorIfOutside（与现有文件工具一致）：工作区内直接放行，工作区外拒绝。

## 配置（新增 5 项，同步 src/resource/config.properties）

```
browser.path=              # Chrome 可执行文件路径，留空自动探测（注册表/常见安装位置）
browser.port=9222
browser.userDataDir=./.minion/browser-profile
browser.headless=false     # 默认有头（调试可见）；自动化可改 true
browser.timeoutMs=30000
```

Config 增加对应 getter；README 增加使用说明。

## 错误处理

| 场景 | 行为 |
|---|---|
| Chrome 找不到（path 空且探测失败） | 失败 ToolResult：提示配置 browser.path |
| 启动失败 / 端口被占 | 失败 ToolResult：提示检查端口占用与配置；已有 Chrome 复用其调试端口 |
| 连接中断（Chrome 被关/崩溃） | 失败 ToolResult：提示重新 open 或检查 Chrome |
| JS 异常 | 失败 ToolResult：异常信息 + 近 3 条 console 错误 |
| 命令超时 | 失败 ToolResult：提示可能页面卡死，可再执行或刷新 |
| 网络/console 缓冲满 | 丢弃最旧（各限 500 条），不报错 |

## 测试

- `CdpClientTest`：mockwebserver 原生支持 WebSocket——测命令 id 匹配、
  事件分发、超时、断线异常（mockwebserver 已依赖，零新增）
- `ChromeLauncherTest`：注入 fake 启动器/伪 /json 响应——测就绪轮询、
  端口占用、headless 参数拼接
- `BrowserEvalToolTest` / `BrowserScreenshotToolTest` / `BrowserDebugToolTest`：
  参数校验（缺参/非法 action/路径越界）与 BrowserSession 缓冲逻辑
- 真实 Chrome 集成测试（`@Ignore`，手动跑）：导航 → 填表 → 点击 → 取数 → 截图闭环
- 现有 `mvn test` 全绿

## 不做的（YAGNI）

- 多标签页管理（单页面上下文）
- 等待条件队列 / 元素选择器封装（模型直接用 JS）
- 文件下载、上传管理
- 表单自动填充（凭证无存储，模型从对话提取）
- 引入 Selenium/Playwright/HtmlUnit 等新依赖

## 组件改动清单

| 文件 | 改动 |
|---|---|
| `core/tools/browser/ChromeLauncher.java` | 新增 |
| `core/tools/browser/CdpClient.java` | 新增 |
| `core/tools/browser/BrowserSession.java` | 新增 |
| `core/tools/browser/BrowserTool.java` 等 4 工具 | 新增 |
| `Main.java` | 注册 4 个工具 + 配置装配 |
| `core/config/Config.java` | 新增 browser.* getter |
| `src/resource/config.properties` | 新增 5 项默认值 |
| `src/test/java/com/minion/core/tools/browser/*Test.java` | 新增 |
| `docs/ARCHITECTURE.md` | 包结构与工具清单更新 |
| `README.md` | 使用说明：浏览器工具 + 配置项 + 凭证用法 |
