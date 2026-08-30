# minion 架构 — 功能类路径

> 后续开发的类路径与扩展点指引。开发规约见 [CONVENTIONS.md](CONVENTIONS.md)，用户使用见 [README.md](../README.md)。
> 若本文与代码不一致，以代码为准并更新本文。

## 1. 包结构总览

```
com.minion
├── Boot                    自举启动器（shade 打包入口）：PRISM/控制台/JDK8 探测与重启，--relaunched 防循环
├── Main                    入口：装配配置/技能/浏览器/MCP/GUI，启动 JavaFX 主窗口（GUI 为唯一界面，CLI 已移除）
├── gui/                    JavaFX 界面：主窗口、侧栏、聊天渲染、输入、弹窗、确认、图标、会话管理
└── core/
    ├── agent/              AgentLoop（主循环）、SubAgentLoop（子 agent）、Session、TodoList、SystemPromptBuilder、TitleGenerator
    ├── llm/                DeepSeekClient（SSE 流式，内置 deepseek/qwen 思考参数适配）、Message、ImagePart（图片内容块，content 数组化）、ToolCall、Usage、UsageTracker
    ├── tools/              Tool 接口、ToolRegistry、13 个工具、SchemaGenerator、confirm/、browser/、mcp/（McpProxyTool）、PathsGuard
    ├── mcp/                MCP 客户端核心：McpManager（状态机/惰性连接/路由）、StdioMcpClient、SseMcpClient、McpStore（mcp.json）、McpServer、JsonRpc
    ├── skills/             SkillManager（scanTree 递归扫描）、SkillSet（内置+项目合并快照）、Skill（YAML frontmatter 解析）
    ├── context/            ContextManager、TokenCounter
    ├── storage/            SessionStore
    └── config/             Config、WorkspaceManager、ModelManager（workspace.json / model.json）、WorkspacePaths（相对路径按项目路径解析）
```

## 2. 各包职责与关键类

### com.minion（根）

- `Boot`：shade 打包入口（自举启动器：PRISM/控制台/JDK8 探测与重启，--relaunched 防循环），直启或子进程调用 `Main` 进入正常装配
- `Main`：程序入口。装配 Config / WorkspaceManager / ModelManager / SkillManager（技能扫描）/ McpManager（MCP，惰性连接）/ ChromeLauncher+CdpClient+BrowserSession（浏览器，`browser.path` 未配置则不创建，CDP 工具不加载）/ GuiConfirmUi，构造 `SessionManager` 后 `MinionApp.start` 启动 JavaFX。退出钩子统一收口：manager.shutdown（会话+LLM+MCP 子进程）→ chrome.stop。`Config.jarDir()` 为 jar 所在目录（配置文件与会话目录基准）。

### gui/

| 类 | 职责 |
|---|---|
| MinionApp | JavaFX 启动（Application），静态注入 Config/WorkspaceManager/ModelManager/SessionManager |
| MainWindow | 主窗口：无边框自绘标题栏（TitleBar）+ GridPane 25%/75% 固定比例（无分隔线、不可拖拽，左侧会话/工作空间，右侧页签栏+消息区+输入区（外包 StackPane 承载 ConfirmSheet））；关闭确认 confirmClose 由关闭按钮与系统关闭共用；右侧顶部页签栏（tabs-bar，空页签整行隐藏）selectedItem 监听激活会话（启动/切空间用 suppressingTabSelect 补齐页签防误激活，关页签自动激活邻接会话）；消息区贴底自动滚动经 AutoScrollPolicy |
| TitleBar | 自绘标题栏（拖动移动/双击最大化，最小化/最大化/关闭按钮，设置入口齿轮图标） |
| ResizeHelper | 无边框窗口边缘/四角拖拽缩放（8 个透明区域） |
| sidebar/SessionListView、WorkspaceListView | 会话/工作空间列表（新建、切换；会话项悬停重命名/删除、工作空间项悬停修改/删除（重命名并入修改弹窗）；当前工作空间名称右侧主色圆点标记（SVG）；名称用 cell-text 样式类显式上色；会话时间 60 秒周期刷新，isHoverButton 防按钮点击误切换；工作空间可拖拽排序；会话项非悬停显示最近消息时间；会话项长标题/摘要省略号截断（无横向滚动条）） |
| sidebar/TimeFormatter | 消息时间格式化：ts 与 now 的相对距离（<1min→"1m"、<1h→"Nm"、<24h→"Nh"、≥24h→"Nd"），ts<=0（旧数据）返回 null 不显示 |
| chat/ChatView（控制台输出流：每条消息 HBox = 彩色加粗标签 Label + 白色正文 MessageTextArea，段间无缝；正文高度自适应无内部滚动条）、MarkdownRenderer、BlockNodeFactory | 每会话一个 ChatView 绑定其 EventList（重建 + bind 重放存量）；Markdown 渲染（BlockNodeFactory 对段落/列表/表格内 Text 显式 setFill，保证深色主题下可读）；AskUserQuestion 提问渲染委托 core `AskUserQuestionTool.normalize`（键名写错/数组退化成字符串/参数标记吞正文皆可救回，永不产出空白），摘要行带 header，提问段无视 500 字折叠阈值恒展开（超 4000 才折叠），`toolResultBody(name,data)` 对 AskUserQuestion 成功态抑制正文（回答已由【输入】段渲染，失败态仍显示） |
| input/InputView | 输入区 0.618 黄金比例宽居中大框（占正文面板宽 61.8%，上=块行+输入框、下=底部操作行：上传按钮左+发送按钮右，LCD 抗锯齿）：Ctrl+Enter 发送、Enter 换行、Esc 关闭补全弹层/终止运行；键盘经 capture 过滤器处理（弹层 ↑↓/Enter/Tab 选择优先于 TextArea 默认行为）；按钮状态机（提问挂起空输入=变淡回答箭头），发送/补充/回答/终止统一 btn-danger 红底；回形针上传按钮（FileChooser 选图→5MB/3 张校验→base64 建 IMAGE 块），带图消息跳过斜杠命令直发 send，回答模式带图拦截提示；发送走 SessionManager.dispatchCommand（斜杠命令本地分发） |
| input/SuggestionPopup、CompletionParser、Slash/FileSuggester | 补全弹层（Popup+ListView 锚定大框上方同宽；↑↓/Enter/Tab/Esc/鼠标）：触发解析（/、@ 词首、/skill 前一词三模式）+ 数据提供（5 内置命令+技能条目、工作空间文件遍历 10 秒缓存）+ 过滤排序（前缀优先→短路径→字典序） |
| input/InputChip | 输入块模型与纯逻辑（compose 组装发送文本、粘贴 >1000 字符变块阈值、粘贴块光标处占位符原位展开、弹层模式→块类型映射） |
| command/CommandDispatcher | 斜杠命令本地分发（/help /skills /skill /compact /tokens）：结果经 SYSTEM 事件渲染，永不发给 LLM；/compact 提交会话工作线程执行 |
| dialog/SettingsDialog、ConfirmSheet | 设置窗（左列 ListView 导航：基础设置/模型/MCP/关于 + StackPane 内容切换；导航列 minWidth 120 防 HBox 空间不足时被 HGrow 内容压塌；基础设置 HBox 行布局标签固定 160 宽（去 ScrollPane——裁剪内灰阶 AA 致发虚），skills.dir 可浏览选取）；MCP 页：服务器列表（状态点（SVG 圆点）绿/橙/红/灰 + 传输 + 工具数/失败原因 + 启用开关）+ 新建/编辑/删除/重连，表单支持 stdio 命令/参数/环境变量与 sse URL/请求头（传输切换联动禁用）；模型页已激活模型名称右侧主色圆点标记（SVG）；连接线程回调经 Platform.runLater 刷新列表；高危操作确认底部卡片（右侧底部两行紧凑小卡滑入，距底 1 行（24px），遮罩仅右侧，Esc 拒绝/Enter 同意，并发串行排队）；基础页按钮栏「应用」（保存不关窗）与 browser.path 文件浏览 |
| theme/Theme | 弹窗深色样式挂载（Dialog 不继承 Scene 样式表） |
| icon/IconFactory | SVG 图标工厂：21 个 Material Symbols Outlined 24×24 path 常量 + 工厂方法（每图标带 .icon-* 样式类）+ size() 等比缩放；全部界面图标集中于此，颜色/尺寸由 theme.css 控制，不依赖系统字体（Win7 缺字形环境不再显示方块）；RunningIndicator 齿轮亦经此迁移（IconFactory.gear + .running-indicator-gear） |
| confirm/GuiConfirmUi | 确认交互实现：工具线程 ask → Platform.runLater 投递 ConfirmSheet → take() 无限阻塞等待点击（不阻塞 FX 线程；无 GUI 环境防御性 REJECT） |
| session/SessionManager | 会话外壳与装配中枢（见 §3） |
| session/SessionHandle | 会话句柄（状态/id/title/running + 专属线程池 + loop/controller） |
| session/SessionController | 会话侧事件源，输出到该会话 EventList；onAskUserDone 把 AskUserQuestion 回答投递为 USER_SUPPLEMENT 事件（【输入】段，与提问成对显示）；replayHistory(List\<Message\>) 把历史消息转 Ev 灌入事件流（USER→USER_MESSAGE、ASSISTANT 非空 content→CONTENT、AskUserQuestion 的 TOOL 消息先重演回答再成功标记（内容以 `AskUserQuestionTool.INVALID_PREFIX` 开头的失败输出不重演为回答，避免伪装成用户发言）、跳过 SYSTEM/空消息），restoreSessions 恢复后调用 |
| session/EventList | 事件缓冲：工作线程写、FX 线程读（`bind(true)` 全量重放） |
| session/AutoScrollPolicy | 消息区自动滚动贴底策略（纯逻辑，无 JavaFX 依赖，归一化语义）：sync(vvalue,eps) 滚动位置变化重算贴底（动态半屏容差 eps=0.5×视口高/可滚动行程，随内容变长收窄；eps>=1 恒贴底），forceFollow() 用户发消息强制贴底；MainWindow 监听 vvalue + 内容节点 layoutBounds 高度变化驱动置底（vmax 恒 1.0 不可用，无 onVmaxChanged） |
| WheelScrollAccelerator | 正文消息区滚轮加速：ScrollEvent 过滤器把滚轮增量换算为固定像素（每格 100px，Windows WHEEL_DELTA=40 基准，平滑滚轮小数增量连续换算），setVvalue + consume 阻止皮肤默认比例滚动；Ctrl/Shift 修饰或无滚动行程放行皮肤；MainWindow 构造 chatScroll 后 attach 一次（换 content 无需重挂） |

### core/agent/

| 类 | 职责 |
|---|---|
| AgentLoop | 主循环：追加消息 → 估算/压缩 → 流式请求 → 工具执行 → 落盘；轮数上限 DEFAULT_ROUND_LIMIT=10000；TaskTool 在此注册；每轮结束经 ui.onStatsLine 发射统计行（StatsLine 格式化，正常/错误/中断路径均发射） |
| SubAgentLoop | 子 agent：独立 system prompt + 消息数组 + 完整工具集，但不注册 task 工具（防无限递归）；无轮数/输出上限 |
| Session | 会话状态：消息列表、统计（pendingSupplements 运行中补充队列 + pendingSupplementImages 补充图片队列，随会话落盘） |
| TodoList | 任务清单（TodoWrite 工具的后端） |
| SystemPromptBuilder | system prompt 组装：内置提示词 → 项目主说明文件（未配置则整段不注入）→ 技能列表 → 已加载技能 |
| StatsLine | 统计行格式化：耗时 · in/out/thinking（UsageTracker 会话累计）· ctx 上下文占比（"⏱ " 前缀由 GUI 渲染层剥离为计时器图标）；formatTokens 缩写（<1000 原样/整千 "900k"/≥10 万整 k/其余 "7.8k"） |

### core/llm/

| 类 | 职责 |
|---|---|
| LlmClient / DeepSeekClient | SSE 流式请求（内置 deepseek/qwen 思考参数适配）；HTTP 连接 30s / 读取 300s（写死常量） |
| Message | 消息模型（role/content/reasoningContent/toolCalls/toolCallId/name/summary/ts/images）；ts 为创建时间戳（毫秒，四个工厂方法打点，随会话 JSON 落盘，旧数据 ts==0 向后兼容）；assistant 的 reasoningContent 必须原样回传（DeepSeek 硬性要求）；user 带图时 toApiJson 输出 content 数组（text + image_url，OpenAI 兼容视觉协议） |
| ImagePart | 图片内容块（mime/base64/name；MAX_FILE_BYTES=5MB、MAX_IMAGES=3、IMAGE_TOKENS=500 粗估；displayText 拼「图片：<名>」占位） |
| ToolCall / Usage / UsageTracker | 工具调用增量解析；按轮+会话累计 input/output/thinking |

### core/tools/

- `Tool` 接口（name/description/schema/execute/isHighRisk）→ 13 个实现：ReadTool、WriteTool、EditTool、GlobTool、GrepTool、BashTool、WebFetchTool、TaskTool、TodoWriteTool、BrowserTool、BrowserEvalTool、BrowserScreenshotTool、BrowserDebugTool
- `ToolRegistry`：注册表（name 小写索引）；`schemas()` 生成 OpenAI function calling 格式
- `SchemaGenerator`：Java 结构 → JSON Schema
- `ConfirmGate`：高危确认（Write 覆盖已有文件 / Edit 始终 / Bash 命中危险命令表）；确认交互经 `ConfirmUi` 接口注入（GUI 下为 GuiConfirmUi）
- `ConfirmGate` / `ConfirmUi` 位于 `core/tools/confirm/` 子包
- `PathsGuard`：文件工具路径限制（工作路径 + 额外放行目录 + 技能目录 + 会话临时目录；技能目录可配置为工作路径外的绝对路径）。`Workspace.extraAllowedDirs()`（volatile 替换语义）放行项目级技能目录——`SessionManager.buildCtx` 按当前空间配置热更新，文件工具据此可读取项目技能源文件（Read 按绝对路径读）
- `TextFiles`：文本编码辅助——UTF-8 严格解码优先，失败自动降级 GBK（Windows 记事本 ANSI 保存的常见编码）；ReadTool/GrepTool/EditTool 统一复用，EditTool 按实际编码写回不破坏文件
- `OutputDump`：工具输出超限落盘公共类——Bash/Grep 输出超上限时完整结果写会话临时目录 `<jarDir>/.session/tmp/<sessionId>/`（`write(Path tmpDir, ...)` 失败返回 null 降级），`cleanup(Path, long)` 启动时扫所有会话子目录清理修改超 3 天（`RETENTION_MS`）的旧文件，`tail` 供截断显示读取
- `ReadTool`：UTF-8 严格解码优先；失败（如 GBK 文件）自动降级重读，输出首行标注「[GBK 编码文件，已自动转码显示]」，标注不占行号与 offset/limit 计数
- `core/tools/browser/` 子包：ChromeLauncher(Chrome 进程管理)、CdpClient(CDP WebSocket 协议)、BrowserSession(浏览器会话与事件缓冲)、Browser/BrowserEval/BrowserScreenshot/BrowserDebug 四个工具
- `core/tools/mcp/` 子包：`McpProxyTool`（MCP 工具适配器——元数据透传 + 调用委托 McpManager 路由，失败映射 ToolResult.error 给模型自调；不弹高危确认）
- `example/ExampleTool`：新工具模板示例（未注册）

### core/mcp/（MCP 客户端核心，JDK8 自研，无官方 SDK 依赖）

- `McpManager`：状态机（DISCONNECTED/CONNECTING/CONNECTED/FAILED）+ 惰性连接（首次 ensureConnectedAsync 才 spawn 进程，幂等去重）+ 全局工具表 + call 路由（未连接先同步重连 ≤10s）+ `save()`（配置持久化）+ shutdown；`addListener` 连接线程回调（GUI 层 Platform.runLater 刷新）
- `StdioMcpClient`：spawn 子进程 + stdin/stdout 按行 JSON-RPC（`.cmd/.bat` 自动 `cmd /c` 包装）；按 id 关联 pending 队列同步等待响应；调用超时 120s
- `SseMcpClient`：okhttp-sse EventSource（GET /sse 流）+ POST 响应体作为 JSON-RPC 响应
- `McpStore`：jarDir/mcp.json 单文件多服务器（原子写，损坏备份 .bak）
- `McpServer`：配置字段（name/transport/command/args/env/url/headers/enabled，gson 落盘）+ transient 运行时状态（state/failReason/tools/skippedTools）
- `McpToolInfo` / `JsonRpc`：工具元数据 / JSON-RPC 2.0 消息编解码（request/response/responseError/notification）

### core/skills/ · core/context/ · core/storage/ · core/config/

- `SkillManager`：扫描 `skills/<名>/SKILL.md`（superpowers 格式）或 `skills/<名>.skill.md`，YAML frontmatter 解析；`scanTree(root, maxDepth, maxCount)` 递归扫描任意目录树（跳过 .git/node_modules/target 等噪声目录，深度/数量触顶截断并回告警，不抛异常），产出带 `[项目]` 来源标注的技能
- `SkillSet`：内置技能 + 项目级技能合并器——`resolve(projectDir)` 每次实扫（SkillSet 自身无缓存；调用方 `SessionManager` 按空间缓存扫描结果、配置变更时失效），同名（忽略大小写）项目级覆盖内置，产出**不可变快照**；`[项目]` 技能排在内置之前
- `ContextManager` / `TokenCounter`：上下文压缩（达 maxContextTokens×compressThreshold 触发，按完整回合链压缩；保留区按 token 占比动态缩小、下限 12 条；压缩失败时按 token 均衡分段递归降级，部分成功自动应用、全部失败原样返回）
- `SessionStore`：会话 JSON 落盘（原子写；每次 API 请求完成后写盘），目录 `session/<workSpaceName>/`
- `Config`：config.properties（classpath 默认值 + jar 同目录外部覆盖，首次运行自动生成）
- `WorkspaceManager` / `ModelManager`：workspace.json / model.json（jar 同目录，单文件多条目；缺失自动生成，损坏备份后重建）；workspace.json 数组顺序即侧栏显示顺序，`WorkspaceManager.move(name, newIndex)` 拖拽排序持久化（越界返回 false 不改列表；SessionManager.moveWorkspace 转发但不发通知，避免拖拽时清空聊天区）

## 3. 会话管理与线程模型（gui/session/SessionManager）

```
用户输入 → InputView → SessionManager.dispatchCommand(handle, text)
  → 斜杠命令命中 → 本地执行 + USER_MESSAGE 回显 + SYSTEM 结果事件（不入 LLM 历史）
  → 非命令 → send：该会话专属工作线程（SessionHandle.pool）执行：
      titlePending 时先摘要生成标题 → AgentLoop.runUserTurn(text)
  → AgentLoop 每步（thinking/正文/工具调用/完成）写到 SessionController → 会话 EventList
  → FX 线程 ChatView.bind(true) 重放渲染；onSessionRunningChanged 刷新页签/输入区
```

- **每会话一个 AgentLoop + 独占工作线程**（真并行，切换工作空间/会话不打断后台运行）
- **每工作空间一套上下文**（WorkspaceCtx：Workspace/SessionStore/ConfirmGate 空间级共享），恢复/新建会话时经 `SessionManager.newRegistry` 注册工具——**每会话独立 ToolRegistry**（TaskTool 绑定本会话 loop，防 task 事件串流）
- **会话级技能快照**：`Main` 启动扫内置技能 → `SessionManager` 建/恢复会话时 `SkillSet.resolve(项目级技能目录)`（项目覆盖同名内置，`WorkspacePaths` 按各空间 workDir 解析相对路径；结果按空间缓存，配置变更时失效）→ **不可变快照**塞进 `AgentLoop.setAllSkills` → `SystemPromptBuilder` 每轮渲染快照（`[项目]/[内置]` 标注 + 目录行）。快照随会话固化，切换工作空间/改配置互不串台、只对新会话生效；`/skills` 与 `@`/`/` 补全读 `SessionManager.currentSkills()`（激活会话快照；无会话时按当前空间缓存结果实算）
- **MCP 接线**：newRegistry 对每个启用服务器触发 `ensureConnectedAsync`（首次建会话即后台预连接，不阻塞界面）；连接完成（McpManager.Listener）补注册该服务器工具进所有存活会话的 registry（AgentLoop 每轮动态 `registry.schemas()`，下一轮即可被模型调用）；与内置工具重名跳过并计数 skippedTools；新建/恢复会话另有兜底补注册（覆盖连接完成于会话注册前毫秒级竞态）
- **事件缓冲**：工作线程只写 EventList，FX 线程读取渲染（UI 不被工具执行阻塞）
- **确认交互**：GuiConfirmUi 经 Platform.runLater 投递 ConfirmSheet，工具线程 take() 无限等点击（不阻塞 FX 线程；点击结果即决策，无超时竞态）；无 GUI 环境防御性 REJECT
- 会话落盘：`loop.setSessionStore(store)` 每轮/退出兜底落盘；关闭窗口 `shutdown()` 终止全部运行中会话（有运行中会话先弹确认）
- **资源生命周期**：每会话一个 DeepSeekClient（`LlmClient.close()` 取消 in-flight + `dispatcher().executorService().shutdown()` + `connectionPool().evictAll()`，幂等）；会话删除/工作空间删除/`shutdown()` 三处释放，退出钩子统一收口（`manager.shutdown()` + `chrome.stop()`）——否则 okhttp 连接池非 daemon 清理线程把 JVM 拖住约 5 分钟
- **模型变更**：经 `SessionManager.applyModelChanged()` 全量 propagate（换 LlmClient + ContextManager.update，旧客户端延迟回收）

## 4. 核心数据流

```
用户输入 → InputView → SessionManager.dispatchCommand（命令本地/消息 send）→ 会话工作线程 → AgentLoop
  1. 追加 user 消息；TokenCounter 估算达阈值 → 自动压缩
  2. 流式请求（thinking / 正文 / tool_calls 增量 → EventList → UI 渲染；ChatView 流式缓冲在轮次边界——用户消息/补充/工具调用——重置，防多轮回复文本跨轮累积拼接）
  3. finish_reason==tool_calls → 执行工具（同回合并行；Bash 超时 120s；高危经 ConfirmGate→GuiConfirmUi 弹窗）→ tool 消息回传 → 下一轮
  4. 无工具调用 → 本轮完成 → 落盘
```

细节时序见 `docs/superpowers/specs/2026-08-08-minion-design.md` §7。

## 5. 依赖方向

- `gui` 可依赖 `core` 任意包；`core` 不依赖 `gui`（确认交互经 `ConfirmUi` 接口注入，实现 `GuiConfirmUi` 在 gui 侧）
- `core` 内包之间经接口 + 构造注入协作；唯一反向依赖：`TaskTool`（tools）→ `AgentLoop`（agent），经构造注入实现
- 新增工具/技能走 §6 扩展点，不改变依赖方向

## 6. 扩展点

### 新增工具

1. 实现 `Tool` 接口（name/description/schema/execute + 默认 isHighRisk=false）
2. 涉及路径限制的用 `PathsGuard` 校验；高危操作实现 `isHighRisk`（走 ConfirmGate）
3. 在 `SessionManager.newRegistry` 注册 `registry.register(new XxxTool(...))`（每会话 registry；TaskTool 由 AgentLoop 注册）
4. 附测试 `src/test/java/com/minion/core/tools/XxxToolTest.java`（JDK8 语法、JUnit4）

### 新增技能

- 无需代码：`skills/<名>/SKILL.md` + YAML frontmatter（name/description/metadata），SkillManager 自动发现

### 新增 GUI 功能

- 视图放 `gui/` 对应子包（主窗口组装在 MainWindow.show）；会话事件经 SessionController 写入 EventList，视图用 `bind` 重放
- 新增弹窗放 `gui/dialog/`；静态注入经 `MinionApp` 传递，不在视图内直接 new 核心对象

## 7. 关键写死常量

| 常量 | 值 | 位置 |
|---|---|---|
| 主循环工具轮数上限 DEFAULT_ROUND_LIMIT | 10000 | AgentLoop.java |
| Bash 默认超时（timeoutSeconds 可覆盖） | 120s | BashTool.DEFAULT_TIMEOUT |
| Bash 输出截断（内存保留上限 TOTAL_MAX，头 18k+尾 12k） | 30k 字符 | BashTool.HEAD_MAX/TAIL_MAX/TOTAL_MAX |
| Bash/Grep 超限落盘目录 | `<jarDir>/.session/tmp/<sessionId>/`（返回绝对路径） | OutputDump |
| 落盘文件保留期 RETENTION_MS | 3 天（启动清理） | OutputDump |
| Grep 单行截断 LINE_MAX | 1000 字符 | GrepTool |
| Grep 结果条数 MAX_RESULTS / 显示层 DISPLAY_CHARS | 250 条 / 30k 字符 | GrepTool |
| HTTP 连接超时 CONNECT_TIMEOUT | 30s | DeepSeekClient.java |
| HTTP 读取超时 READ_TIMEOUT | 300s | DeepSeekClient.java |
| CdpClient 连接超时 | 10000ms | Main.java |

> 改动以上常量须在设计阶段说明理由，不随手改。
