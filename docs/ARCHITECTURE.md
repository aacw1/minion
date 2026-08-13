# minion 架构 — 功能类路径

> 后续开发的类路径与扩展点指引。开发规约见 [CONVENTIONS.md](CONVENTIONS.md)，用户使用见 [README.md](../README.md)。
> 若本文与代码不一致，以代码为准并更新本文。

## 1. 包结构总览

```
com.minion
├── Main                    入口：装配配置/技能/浏览器/GUI，启动 JavaFX 主窗口（GUI 为唯一界面，CLI 已移除）
├── gui/                    JavaFX 界面：主窗口、侧栏、聊天渲染、输入、弹窗、确认、会话管理
└── core/
    ├── agent/              AgentLoop（主循环）、SubAgentLoop（子 agent）、Session、TodoList、SystemPromptBuilder、TitleGenerator
    ├── llm/                DeepSeekClient（SSE 流式，内置 deepseek/qwen 思考参数适配）、Message、ToolCall、Usage、UsageTracker
    ├── tools/              Tool 接口、ToolRegistry、13 个工具、SchemaGenerator、confirm/、browser/、PathsGuard
    ├── skills/             SkillManager、Skill（YAML frontmatter 解析）
    ├── context/            ContextManager、TokenCounter
    ├── storage/            SessionStore
    └── config/             Config、WorkspaceManager、ModelManager（workspace.json / model.json）
```

## 2. 各包职责与关键类

### com.minion（根）

- `Main`：程序入口。装配 Config / WorkspaceManager / ModelManager / SkillManager（技能扫描）/ ChromeLauncher+CdpClient+BrowserSession（浏览器）/ GuiConfirmUi，构造 `SessionManager` 后 `MinionApp.start` 启动 JavaFX。`Config.jarDir()` 为 jar 所在目录（配置文件与会话目录基准）。

### gui/

| 类 | 职责 |
|---|---|
| MinionApp | JavaFX 启动（Application），静态注入 Config/WorkspaceManager/ModelManager/SessionManager |
| MainWindow | 主窗口：无边框自绘标题栏（TitleBar）+ SplitPane 1:3（左侧会话/工作空间，右侧消息区+输入区）；关闭确认 confirmClose 由 ✕ 按钮与系统关闭共用；标题栏页签 selectedItem 监听激活会话（启动/切空间用 suppressingTabSelect 补齐页签防误激活，关页签自动激活邻接会话）；消息区贴底自动滚动经 AutoScrollPolicy |
| TitleBar | 自绘标题栏（拖动移动/双击最大化，最小化/最大化/关闭按钮，⚙ 设置入口） |
| ResizeHelper | 无边框窗口边缘/四角拖拽缩放（8 个透明区域） |
| sidebar/SessionListView、WorkspaceListView | 会话/工作空间列表（新建、切换；悬停显示 ✎/⚙/✕ 操作按钮替代右键菜单，isHoverButton 防按钮点击误切换；工作空间可拖拽排序；会话项非悬停显示最近消息时间） |
| sidebar/TimeFormatter | 消息时间格式化：ts 与 now 的相对距离（<1min→"1m"、<1h→"Nm"、<24h→"Nh"、≥24h→"Nd"），ts<=0（旧数据）返回 null 不显示 |
| chat/ChatView、MarkdownRenderer、BlockNodeFactory | 每会话一个 ChatView 绑定其 EventList（重建 + bind 重放存量）；Markdown 渲染（BlockNodeFactory 对段落/列表/表格内 Text 显式 setFill，保证深色主题下可读） |
| input/InputView | 输入区（Ctrl+Enter 发送、Enter 换行）；绑定会话后发送走 SessionManager.send |
| dialog/SettingsDialog、ConfirmDialog | 设置窗（模型/基础设置/关于三页签，页签横排 setSide(TOP)+tabMinWidth(90)，基础设置 GridPane 列约束防标签截断）；高危操作确认弹窗 |
| theme/Theme | 弹窗深色样式挂载（Dialog 不继承 Scene 样式表） |
| confirm/GuiConfirmUi | 确认交互实现：工具线程 ask → FutureTask 投递 FX 线程弹窗 → 阻塞等待（无 GUI 环境防御性 REJECT） |
| session/SessionManager | 会话外壳与装配中枢（见 §3） |
| session/SessionHandle | 会话句柄（状态/id/title/running + 专属线程池 + loop/controller） |
| session/SessionController | 会话侧事件源，输出到该会话 EventList；replayHistory(List\<Message\>) 把历史消息转 Ev 灌入事件流（USER→USER_MESSAGE、ASSISTANT 非空 content→CONTENT、跳过 SYSTEM/TOOL/空消息），restoreSessions 恢复后调用 |
| session/EventList | 事件缓冲：工作线程写、FX 线程读（`bind(true)` 全量重放） |
| session/AutoScrollPolicy | 消息区自动滚动贴底策略（纯逻辑，无 JavaFX 依赖）：onScroll(vvalue,vmax) 更新贴底状态，shouldFollow() 供内容增长时判断；MainWindow vvalue/vmax 双监听配合，vmax 变化后 Platform.runLater 设 setVvalue(vmax) 防 clamp 吞掉 |

### core/agent/

| 类 | 职责 |
|---|---|
| AgentLoop | 主循环：追加消息 → 估算/压缩 → 流式请求 → 工具执行 → 落盘；轮数上限 DEFAULT_ROUND_LIMIT=10000；TaskTool 在此注册 |
| SubAgentLoop | 子 agent：独立 system prompt + 消息数组 + 完整工具集，但不注册 task 工具（防无限递归）；无轮数/输出上限 |
| Session | 会话状态：消息列表、统计 |
| TodoList | 任务清单（TodoWrite 工具的后端） |
| SystemPromptBuilder | system prompt 组装：内置提示词 → project.md → 技能列表 → 已加载技能 |

### core/llm/

| 类 | 职责 |
|---|---|
| LlmClient / DeepSeekClient | SSE 流式请求（内置 deepseek/qwen 思考参数适配）；HTTP 连接 30s / 读取 300s（写死常量） |
| Message | 消息模型（role/content/reasoningContent/toolCalls/toolCallId/name/summary/ts）；ts 为创建时间戳（毫秒，四个工厂方法打点，随会话 JSON 落盘，旧数据 ts==0 向后兼容）；assistant 的 reasoningContent 必须原样回传（DeepSeek 硬性要求） |
| ToolCall / Usage / UsageTracker | 工具调用增量解析；按轮+会话累计 input/output/thinking |

### core/tools/

- `Tool` 接口（name/description/schema/execute/isHighRisk）→ 13 个实现：ReadTool、WriteTool、EditTool、GlobTool、GrepTool、BashTool、WebFetchTool、TaskTool、TodoWriteTool、BrowserTool、BrowserEvalTool、BrowserScreenshotTool、BrowserDebugTool
- `ToolRegistry`：注册表（name 小写索引）；`schemas()` 生成 OpenAI function calling 格式
- `SchemaGenerator`：Java 结构 → JSON Schema
- `ConfirmGate`：高危确认（Write 覆盖已有文件 / Edit 始终 / Bash 命中危险命令表）；确认交互经 `ConfirmUi` 接口注入（GUI 下为 GuiConfirmUi）
- `ConfirmGate` / `ConfirmUi` 位于 `core/tools/confirm/` 子包
- `PathsGuard`：文件工具路径限制（工作路径 + 技能目录；技能目录可配置为工作路径外的绝对路径）
- `core/tools/browser/` 子包：ChromeLauncher(Chrome 进程管理)、CdpClient(CDP WebSocket 协议)、BrowserSession(浏览器会话与事件缓冲)、Browser/BrowserEval/BrowserScreenshot/BrowserDebug 四个工具
- `example/ExampleTool`：新工具模板示例（未注册）

### core/skills/ · core/context/ · core/storage/ · core/config/

- `SkillManager`：扫描 `skills/<名>/SKILL.md`（superpowers 格式）或 `skills/<名>.skill.md`，YAML frontmatter 解析
- `ContextManager` / `TokenCounter`：上下文压缩（达 maxContextTokens×compressThreshold 触发，按完整回合链压缩，保留最近 keepRecentMessages 条）
- `SessionStore`：会话 JSON 落盘（原子写；每次 API 请求完成后写盘），目录 `session/<workSpaceName>/`
- `Config`：config.properties（classpath 默认值 + jar 同目录外部覆盖，首次运行自动生成）
- `WorkspaceManager` / `ModelManager`：workspace.json / model.json（jar 同目录，单文件多条目；缺失自动生成，损坏备份后重建）；workspace.json 数组顺序即侧栏显示顺序，`WorkspaceManager.move(name, newIndex)` 拖拽排序持久化（越界返回 false 不改列表；SessionManager.moveWorkspace 转发但不发通知，避免拖拽时清空聊天区）

## 3. 会话管理与线程模型（gui/session/SessionManager）

```
用户输入 → InputView → SessionManager.send(handle, text)
  → 该会话专属工作线程（SessionHandle.pool）执行：
      titlePending 时先摘要生成标题 → AgentLoop.runUserTurn(text)
  → AgentLoop 每步（thinking/正文/工具调用/完成）写到 SessionController → 会话 EventList
  → FX 线程 ChatView.bind(true) 重放渲染；onSessionRunningChanged 刷新页签/输入区
```

- **每会话一个 AgentLoop + 独占工作线程**（真并行，切换工作空间/会话不打断后台运行）
- **每工作空间一套上下文**（WorkspaceCtx：Workspace/SessionStore/ConfirmGate 空间级共享），恢复/新建会话时经 `SessionManager.newRegistry` 注册工具——**每会话独立 ToolRegistry**（TaskTool 绑定本会话 loop，防 task 事件串流）
- **事件缓冲**：工作线程只写 EventList，FX 线程读取渲染（UI 不被工具执行阻塞）
- **确认交互**：GuiConfirmUi 用 FutureTask 把弹窗投到 FX 线程并**阻塞工具线程**等结果（不阻塞 FX 线程）；无 GUI 环境防御性 REJECT
- 会话落盘：`loop.setSessionStore(store)` 每轮/退出兜底落盘；关闭窗口 `shutdown()` 终止全部运行中会话（有运行中会话先弹确认）
- **资源生命周期**：每会话一个 DeepSeekClient（`LlmClient.close()` 取消 in-flight + `dispatcher().executorService().shutdown()` + `connectionPool().evictAll()`，幂等）；会话删除/工作空间删除/`shutdown()` 三处释放，退出钩子统一收口（`manager.shutdown()` + `chrome.stop()`）——否则 okhttp 连接池非 daemon 清理线程把 JVM 拖住约 5 分钟
- **模型变更**：经 `SessionManager.applyModelChanged()` 全量 propagate（换 LlmClient + ContextManager.update，旧客户端延迟回收）

## 4. 核心数据流

```
用户输入 → InputView → SessionManager.send → 会话工作线程 → AgentLoop
  1. 追加 user 消息；TokenCounter 估算达阈值 → 自动压缩
  2. 流式请求（thinking / 正文 / tool_calls 增量 → EventList → UI 渲染）
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
| Bash 输出截断 MAX_OUTPUT | 30k 字符 | BashTool.MAX_OUTPUT |
| HTTP 连接超时 CONNECT_TIMEOUT | 30s | DeepSeekClient.java |
| HTTP 读取超时 READ_TIMEOUT | 300s | DeepSeekClient.java |
| CdpClient 连接超时 | 10000ms | Main.java |
| GuiConfirmUi 弹窗兜底超时 | 3s | GuiConfirmUi.java |

> 改动以上常量须在设计阶段说明理由，不随手改。
