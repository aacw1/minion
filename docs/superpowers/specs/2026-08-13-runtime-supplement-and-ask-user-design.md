# 运行中补充信息 + ask_user 工具 设计

日期：2026-08-13
状态：已实施（分支 feat/runtime-supplement；实施偏差见文末「实施记录」）

## 背景

用户在模型运行期间常有补充诉求：模型跑工具时想追加一句约束；模型提问等回答时想先补一段背景。现状输入框在运行中只有「终止」，运行中内容无法送达模型；模型"等待用户输入"没有显式机制（模型仅输出提问文本后回合自然结束）。

本设计引入两个能力：

1. **运行中补充**：运行状态下输入框有内容时，按钮变为补充箭头，内容注入正在进行的对话（流程不中断）。
2. **ask_user 工具**（参照 Claude Code AskUserQuestion）：模型需要用户信息/选择时主动调用，循环挂起等待回答，回答作为工具结果回传继续本轮。

## 需求列表

| # | 需求 |
|---|------|
| 1 | 运行中 + 输入框有内容 → 按钮为补充箭头，点击将内容注入运行中的对话 |
| 2 | 注入位置：模型调用工具后，工具结果之后拼接 user 消息，专门标识为运行中用户补充 |
| 3 | 模型等待用户输入（ask_user 挂起）时，挂起的补充等用户回答后同一请求拼接发送，补充标识保留 |
| 4 | 回合自然结束（模型文本提问收尾）时未注入的补充挂起，下次用户发送时与输入拼接一起发送 |
| 5 | 按钮图标化（去文案）：上箭头=可发送、未运行+空输入变淡、运行+空输入为方块（终止） |
| 6 | 新增 ask_user 工具：挂起等待回答、回答回传继续本轮、中断安全 |

## 总览

方案 = 「检查点注入」+ Claude Code 式 ask_user 工具。两部分共用同一注入检查点，情形统一：

- **ask_user**：模型调用时循环挂起（running 保持），UI 进入回答模式；回答作为 TOOL 消息回传（tool_call↔tool 配对不变，API 契约零回归），本轮继续。
- **运行中补充**：点补充 → 内容进挂起队列 + 聊天区立即显示标识气泡。注入检查点 = **每轮全部工具结果入历史之后、下一轮请求之前**。挂起时若模型调用 ask_user，补充自然等到回答后同一请求发出；若回合自然结束，补充挂起到会话，下次发送时与用户输入拼接发出。

## 节 1 ask_user 工具

新工具 [AskUserTool](src/main/java/com/minion/core/tools/AskUserTool.java)，模式仿 [TaskTool](src/main/java/com/minion/core/tools/TaskTool.java)（AgentLoop 构造自动注册，每会话独立实例）：

- 参数（参照 Claude Code AskUserQuestion 简化）：`question`（必填 string）、`header`（选填）、`options`（选填，`{label, description}` 数组 2-4 个）、`multiSelect`（选填 boolean）。schema 手写 JsonObject——[SchemaGenerator](src/main/java/com/minion/core/tools/SchemaGenerator.java) 只支持全 string，不扩它。
- `execute()` 阻塞在内部 `CompletableFuture<String>` 上等待回答（**无超时**，等待直到回答或终止）；回答经 `AgentLoop.answerAskUser(text)` → future.complete → `ToolResult.success(answer)` 返回，走现有工具结果路径入历史。无挂起时 complete 忽略。
- 同轮多次调用（并行）共享同一 pending future：后续 execute 链到已有 future，一次回答唤醒全部调用并返回相同回答；跟随者只在完成时清理自己占据的槽位（身份守卫），避免旧跟随者误清新一轮 pending。
- 挂起/结束回调：`AgentUi` 新增 `onAskUserStart(String question)` / `onAskUserDone(String answer)`（默认空实现）。[SessionController](src/main/java/com/minion/gui/session/SessionController.java) 转发给 SessionManager 注入的状态监听器（置 `SessionHandle.askPending/askQuestion` + 通知 UI）。
- 中断：挂起时终止 → `interrupt()` 取消 in-flight future（现有机制）→ execute 抛中断 → 工具结果丢弃 → 现有 [scrubHalfTurn](src/main/java/com/minion/core/agent/AgentLoop.java) 清洗 tool_call 残留（已有路径，零新增）；`askPending` 随 running→false 复位。
- 系统提示词 [SystemPromptBuilder](src/main/java/com/minion/core/agent/SystemPromptBuilder.java)：规则 2、7 改为「信息不足/需用户选择时**调用 ask_user 工具**向用户提问」。
- 子 agent 屏蔽：仿 task 既有做法——[SubAgentLoop.subAgentTools()](src/main/java/com/minion/core/agent/SubAgentLoop.java) 过滤 ask_user schema，`runOneTool` 加同名防御（返回错误「子 agent 不可询问用户」），避免嵌套挂起冲突。
- 非高危（isHighRisk 默认 false），不经确认门。

## 节 2 补充注入（数据流）

- [Message](src/main/java/com/minion/core/llm/Message.java) 新增 `public boolean supplement`（Gson 全字段落盘，旧数据缺失=false 兼容）；[Session](src/main/java/com/minion/core/agent/Session.java) 新增 `public List<String> pendingSupplements`（同样自动落盘——挂起补充随会话持久化，重启不丢；restoreSession 对 null 防御为空列表）。
- 提交路径：`InputView` 点补充 → `SessionManager.sendSupplement(h, text)` → ① `loop.offerSupplement(text)` 入挂起队列（synchronized）② `controller.onUserSupplement(text)` 发 USER_SUPPLEMENT 事件——聊天区**立即**显示标识气泡（UI 事件只发这一次）。
- 注入检查点（[AgentLoop.runUserTurn](src/main/java/com/minion/core/agent/AgentLoop.java)）：工具结果 for 循环之后、STUCK 提示之前，`if (!interrupted) drain()`——逐个 `Message.userSupplement(text)`（supplement=true）入历史：
  - **情形 1**：普通工具流 → 补充紧跟工具结果入历史，下一轮请求模型可见，流程不中断。
  - **情形 2**：本轮含 ask_user → 补充等回答的 TOOL 消息入历史后注入，与回答**同一请求**发给模型（拼接发送）。
  - interrupted 时不 drain：挂起保留（避免半轮 tool_call 未配对时插入 user 消息导致 400，也避免 scrub 误删补充）。
- 回合结束时仍有挂起（模型文本提问自然收尾）→ 下次 `runUserTurn(input)` 开头先 drain（入历史，不发 UI 事件——气泡已显示过），再 `onUserMessage(input)` → 拼接发送。
- 中断/轮数上限/LLM 错误退出路径：挂起一律保留到下次发送合并；会话删除则随对象销毁丢弃。
- 多次补充：每次点击 = 队列追加一条独立标识消息，顺序保持，同一检查点批量注入。

## 节 3 消息标识与聊天渲染

- **API 零污染**：[Message.toApiJson](src/main/java/com/minion/core/llm/Message.java) 不输出 supplement 字段，content 原样发送；标识只存在于本地历史（落盘）与 UI 层。
- **事件流**：[EventList.Kind](src/main/java/com/minion/gui/session/EventList.java) 新增 `USER_SUPPLEMENT`；`AgentUi` 新增 `onUserSupplement(String)`（默认空实现）。[SessionController.replayHistory](src/main/java/com/minion/gui/session/SessionController.java) 遇到 `supplement=true` 的 USER 消息发 USER_SUPPLEMENT 事件——恢复的历史会话回放同样显示标识。
- **聊天渲染**：[ChatView](src/main/java/com/minion/gui/chat/ChatView.java) 新增两个分支：
  - USER_SUPPLEMENT：气泡 = 小徽标「⤒ 补充」+ 内容 Label（基础样式复用 msg-user，徽标新样式类，theme.css 补充）。
  - TOOL_CALL 特判 `ask_user`：不显示原始 args JSON，渲染问题卡片「❓ 模型向你提问」+ question 文本（回答到达后照常显示工具成功行）。
- 点击补充的即时气泡与检查点注入/发送合并**只发一次 UI 事件**（点击时），历史注入不重复显示。

## 节 4 输入框状态机（图标按钮）

[InputView](src/main/java/com/minion/gui/input/InputView.java) 缓存 `running`（已有）、`askPending`、`askQuestion`（新增 `SessionManager.Listener.onSessionAskChanged` → [MainWindow](src/main/java/com/minion/gui/MainWindow.java) 转发，同 onRunningChanged 模式）。按钮纯图标（去文案），图标用 JavaFX `SVGPath`（随主题色、缩放不失真）：

| 状态 | 图标 | 外观 | 动作 |
|---|---|---|---|
| 未运行 + 有内容 | 上箭头 | 主色高亮（btn-primary 背景） | 发送 |
| 未运行 + 无内容 | 上箭头 | 变淡（降透明度，Claude Code 同款观感） | 点击无效（onSend 空内容守卫，现有逻辑） |
| 运行 + 无内容（含等待回答） | ■ 方块 | 危险色（btn-danger 背景） | 终止 |
| 运行 + 无 ask + 有内容 | 上箭头 | 主色高亮 | 补充（tooltip「补充信息给正在运行的模型」） |
| 等待回答 + 有内容 | 上箭头 | 主色高亮 | 回答（tooltip「回答模型的提问」） |

- 输入框 `textProperty` 内容变化实时刷新按钮态；Ctrl+Enter 始终等于当前按钮动作；补充/回答点击后清空输入框。
- 等待回答时占位提示变为 `回答: <askQuestion>`（截断显示）。
- 按钮文案/动作的判定抽为纯静态函数（running, askPending, hasContent → 模式），独立单测。
- 等待回答期间输入一律视为**回答**（不能继续追加补充——挂起补充已在队列里，随回答同一请求发出）。

## 节 5 边界与取舍

- **中断**：挂起补充保留到下次发送合并；ask_user 挂起被 interrupt 取消 → scrub 清洗；askPending 随 running→false 复位。
- **会话/工作空间删除**：挂起补充随对象销毁；挂起中的 ask_user 随中断取消。
- **多会话并行**：补充/回答只作用于当前激活会话；后台会话事件走 EventList 缓冲（现有机制）。
- **模型热切换**：状态在 handle/loop 上，`applyModelChanged` 换 llm 引用不受影响。
- **上下文压缩**：supplement 消息入历史后是普通 user 消息，参与压缩；未注入的 pending 不在压缩范围。
- **落盘兼容**：`Message.supplement`（缺失=false）、`Session.pendingSupplements`（缺失=null → restoreSession 防御），旧会话文件零迁移。
- **已确认取舍**：ask_user 无超时；等待回答期间不能追加补充；子 agent 不可调用 ask_user；补充/回答不引入额外图标变体（tooltip 区分语义）。

## 节 6 测试策略

| 测试类 | 新增用例 |
|---|---|
| AgentLoopTest（FakeLlmClient/RecordingUi 现有模式） | ① 工具轮后注入 supplement 消息（supplement=true，第二轮请求可见）② 回合自然结束→下次 runUserTurn 开头合并（顺序：补充→用户输入）③ ask_user 挂起→answerAskUser→历史含 TOOL 消息、run 正常结束 ④ ask_user 中断→退出+tool_call 被 scrub ⑤ 中断后 pending 保留、下次发送合并 |
| AskUserToolTest（新增） | 挂起阻塞、回答完成、无挂起时 complete 忽略 |
| MessageTest | supplement 字段 Gson 往返；toApiJson 不输出 supplement |
| SessionControllerTest | onUserSupplement→USER_SUPPLEMENT 事件；replayHistory 带 supplement 回放 |
| SessionStoreTest | 含 supplement 消息 + pendingSupplements 的会话落盘往返 |
| InputView 状态机（纯函数） | 5 态映射：图标/透明度/背景类/动作 |
| SystemPromptBuilderTest | 提示词含 ask_user 指引 |
| SubAgentLoopTest | ask_user schema 被过滤 + 防御错误 |
