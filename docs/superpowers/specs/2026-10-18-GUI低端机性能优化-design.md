# GUI 低端机性能优化设计（流式渲染 / 长会话首屏 / 切会话）

日期：2026-10-18
状态：已实施（2026-10-18，全量 1279 用例通过 + 真实 JavaFX 探针验证）

## 一、现象与用户确认的场景

用户报告低端机器上界面卡顿。经询问确认覆盖：

- 流式输出时（模型吐字/思考中界面卡、滚动迟滞）
- 长会话滚动 / 切换会话时
- 输入框打字、@ 补全时
- 整体都卡

## 二、实测证据（本机 JDK8 JavaFX 探针，低端机预计 2~5 倍放大）

探针：真实 `Scene + ScrollPane + ChatView`，按真实 SSE 节奏逐 chunk 投递 `EventList` 事件，
在 FX 线程队列排空后计时（不含 pulse 渲染）。

| 场景 | 实测 | 说明 |
| --- | --- | --- |
| 12k 字符回复、600 个 `CONTENT` chunk | FX 线程累计 **1641ms** | 现状逐 chunk 全量渲染 |
| 24k 字符、1200 chunk | **3752ms** | 随字符数超线性增长 |
| `THINKING` 600 chunk / 12k 字符 | **650ms** | 无 markdown 转换，仍需全量 `setText` |
| 纯 markdown 转换（600 chunk / 12k） | **1810ms** | 其中 `Parser.builder().build()` 约 1170ms，parse 约 750ms |
| 200 段折叠工具结果（1000 字符）首屏 | **806ms** | 折叠段仍创建 TextArea skin |
| 200 段短消息（20 字符）首屏 | **657ms** | 每段 TextArea skin 约 2.5~4ms |
| 200 段重布局 | 0.6ms/次 | 布局本身不慢 |

## 三、根因链

1. **每个 SSE delta 都产生一个 `CONTENT` 事件**（`SessionController.onContent`），
   立即 `Platform.runLater` 到 FX 线程（`ChatView.fxListener`）。
2. `ChatView.onEventFx` 对每个 chunk 执行 `MarkdownRenderer.toPlainText(整个累积文本)`：
   - 每次都新建 flexmark `Parser`（约 1.95ms/次，600 chunk ≈ 1.17s）；
   - 每次全量 parse 累积文本（O(n²)，600 chunk / 12k 字符 ≈ 0.75s）。
3. 结果全量 `MessageTextArea.setStreamText` → `setText` → `relayout()`：
   - `measurer.setText(全文)` + `getLayoutBounds()` 全量文本布局；
   - 每次调度双 `runLater` 精校 + `lookupAll(".text")` CSS 查找；
   - `setPrefHeight` 变化触发父 VBox 重排（每 chunk 一次）。
4. `THINKING` 走 `CollapsibleText.setStreamText`，每 chunk 还额外做
   `setExpanded(true)` → `update()`：重复更新 chevron 图形、`state.setText("展开（N 字符）")`、
   `setVisible/setManaged`，全部是无谓开销。
5. **长会话首屏/切会话**：每段正文是独立 `TextArea`，挂载到场景时创建 skin（约 2.5~4ms/段）。
   折叠段虽然 `visible/managed=false`，但节点仍在 children 中，皮肤照样创建——200 段即 650~800ms。
   低端机（常回退 `prism.order=sw` 软件渲染）放大 2~5 倍。
6. 输入/@ 补全路径已检查：`FileSuggester` 有缓存 + 后台线程 + 防抖，`CompletionParser` 为 O(文本长度)，
   未发现与流式同量级的热点；侧栏 60s 刷新一次，亦非主因（列为后续观测项）。

## 四、优化方案

### P0-1 流式渲染合并刷新（ChatView）

**目标**：把「每 chunk 一次全量渲染」改为「按时间窗合并，每个窗口只渲染最新缓冲一次」。

- `ChatView` 内新增 80ms 合并器（`PauseTransition`），策略为 leading + trailing：
  - 距上次刷新 ≥ 80ms：立即渲染（保持流式"逐段出现"的手感）；
  - 80ms 内的后续增量只更新 `StreamBuffer` 并标记该主人/流为脏；
  - 到点统一渲染脏流：每个 `(kind, subAgentId)` 只取最新缓冲、只做一次 `toPlainText`、一次 `setText`。
- **顺序保证（关键）**：`onEventFx` 遇到任何非 `THINKING/CONTENT` 事件时，先 `flushStreams()`
  再处理该事件。以下路径必须 flush：
  - 轮次边界（`USER_MESSAGE/USER_SUPPLEMENT/TOOL_CALL`）清缓冲之前；
  - `finalizeThinking` 之前（否则思考段尚未创建，定稿逻辑找不到段）；
  - `STATS` 的滚动到底、`SUB_AGENT_DONE` 的回收之前；
  - `clear()` 时取消定时器并丢弃脏标记。
- `replayed` 游标仍在事件到达时推进（语义不变，切走再切回不重复/不丢失）。
- 渲染仍是现有 `stream()` 路径，`ActiveStreams`/`sameStream`/思考定稿语义不变。

**预期**：12k 字符/600 chunk 的渲染次数从 600 降到约 125（按 10s 流式计算），
单次成本不变 → FX 线程累计占用从 1.64s 降到约 0.3~0.4s；THINKING 从 650ms 降到约 150ms。
手工下滚/阅读时不再被逐 chunk 重排打断。

### P0-2 Markdown 转换复用 Parser（MarkdownRenderer）

- 把 `Parser.builder().extensions(...).build()` 从 `parse()` 内的每次新建改为 `ThreadLocal<Parser>` 缓存
  （flexmark Parser 可复用；ThreadLocal 避免任何线程安全争议，兼容测试多线程）。
- 输出语义不变，现有 `MarkdownRendererTest` 全部保留。
- **预期**：600 chunk 的转换耗时 1810ms → 与 P0-1 合并后约 150~250ms。

### P0-3 MessageTextArea 流式更新去重

- `setStreamText` 中文本与当前 `getText()` 相同则直接返回（合并窗口内缓冲未变时不再 `setText`）。
- `scheduleCorrect` 的 `correctAttempts` 上限逻辑保持不变（防自续死循环），仅靠上游降频减少触发。
- 不改动高度自适应的三道防线（保守测量/精校/只增不减），避免回归折叠文本布局崩溃修复。

### P0-4 CollapsibleText 折叠态不挂载内容体

- `update()` 中按 `expanded && hasText` 动态挂载/移除内容 `TextArea`：
  - 折叠：从 children 移除（保持文本为空，遵守「折叠态不得持有文本」的既有约定）；
  - 展开：重新加入 children（顺序为 [摘要行, 内容体]）并写入全文。
- 抽取纯函数 `shouldAttachContent(expanded, hasText)` 供单测。
- **预期**：长会话中大量折叠的工具结果/思考段不再创建 skin，
  200 折叠段首屏 806ms → 预计 200~300ms；展开长内容首次点击有一次性 skin 创建（约 3~5ms，可接受）。
- 风险控制：`ChatView.Seg` 仍持有 `contentArea()` 引用（对象存在，只是不挂载），
  焦点治理、流式更新、复制逻辑不受影响。

### P1 重放分批（切会话不再冻结）——建议本次一并实施

- `ChatView.bind(true)` 的全量重放按每批 10~16 条分批（`Platform.runLater` 链），
  重放期间新到的直通事件先入持久队列，重放完成后再按序处理（避免插队导致顺序错乱）。
- 重放中途切走（`bind(false)`）时停止后续批次，`replayed` 只推进到已渲染位置；
  切回时从该位置继续（视图缓存内容连续）。
- **预期**：切会话首屏从「冻结 0.6~0.8s」变为「分帧渐进出现，界面可响应」。
- 若实施中发现顺序/游标风险不可控，退回同步重放（P0-4 已能覆盖折叠段成本）。

### 本次不做（记录为后续观测项）

- 降低 `MAX_SEGS`（产品行为，不擅自改）
- 消息区虚拟化/复用池（改动大，先看 P0 收益）
- 状态点/齿轮动画低配降级（软件渲染下持续小区域重绘，待实测确认是否仍有感）
- 输入框与侧栏改动（已检查无同量级热点）

## 五、兼容性与风险

- 全部改动在 `gui/chat` 包内，无新依赖，JDK 8 兼容。
- 流式事件的展示延迟最多 80ms（肉眼不可感），最终状态与现状完全一致。
- 事件顺序依赖 flush 点，需重点回归：多子代理并发交错、思考定稿折叠、轮次边界清缓冲、
  工具行出现在回复之后、截断保活。
- 回滚：合并窗口常量置 0 即逐事件渲染；折叠懒挂载与 Parser 缓存可独立回退。

## 六、测试与验收

### 单测

- `MarkdownRendererTest`：全部保留（Parser 复用不改变输出）。
- 新增 `ChatView` 纯逻辑用例：合并窗口的 leading/trailing 判定（抽纯函数）。
- 新增 `CollapsibleText.shouldAttachContent` 用例（折叠不挂载、展开挂载、空内容不挂载）。
- 现有 `ChatViewStreamBufferTest`、`CollapsibleTextTest`、`ChatTextTest` 全绿。

### 探针复测（与本文第二节同口径）

| 场景 | 目标 |
| --- | --- |
| 12k 字符 / 600 chunk | FX 线程累计 ≤ 500ms（现状 1641ms） |
| THINKING 600 chunk / 12k | ≤ 250ms（现状 650ms） |
| 200 折叠段首屏 | ≤ 350ms（现状 806ms） |
| 200 展开段首屏 | 不劣化；配合 P1 分帧可响应 |

### 手动验证清单

- 流式回复：正文/思考逐段出现、无重复前缀、思考结束后按长度折叠、多子代理不串台。
- 工具调用与回复顺序正确；轮次结束自动贴底。
- 长会话（>200 段）滚动、切走再切回，内容与滚动位置连续。
- 折叠消息点击展开/收起正常，可拖选复制。
- `mvn test` 全绿（现有 1260+ 用例）。

## 七、实施结果（2026-10-18）

实现落点：

- `ChatView`：`StreamFlushPolicy`（80ms leading + trailing）、`flushStreams/flushThinking`
  （非流式事件、轮次边界、思考定稿、重放结束前保序落地）、`ReplayGate` + `REPLAY_BATCH = 12` 分批重放、
  `clearGen` 视图清理代数（清空前排队的 runLater 事件不再推进游标）。
- `MarkdownRenderer`：`ThreadLocal<Parser>` 复用（每线程只构建一次）。
- `MessageTextArea.setStreamText`：文本未变直接跳过（合并窗口内重复 flush 不再触发全量重排）。
- `CollapsibleText`：折叠态内容体不挂载（`shouldAttachContent`），展开时才进场景图创建 skin。

### 帧率探针（真实节奏：20ms/chunk、600 chunk、12k 字符；AnimationTimer 帧间隔）

| 场景 | 优化前 | 优化后 |
| --- | --- | --- |
| CONTENT：平均 / p95 / >33ms 帧 / >50ms 帧 | 20ms / 47ms / 95 / 20 | **14ms / 16ms / 6 / 1** |
| THINKING：平均 / p95 / >33ms 帧 / >50ms 帧 | 22ms / 60ms / 105 / 45 | **14ms / 16ms / 10 / 3** |

### 首屏与切会话（200 段）

| 场景 | 优化前 | 优化后 |
| --- | --- | --- |
| 200 折叠段（1000 字符）首屏布局 | 806ms | **235ms** |
| 200 段历史 bind 调用（同步冻结） | 269~291ms | **49~57ms**（每批 12 条） |
| 200 折叠段 bind 调用 | 235ms | **37ms** |
| 无间隔 600 chunk 的 FX 队列排空 | 1641ms | 84ms（合并为每窗口一次渲染） |

说明：200 段**全部展开**时，每段 TextArea skin 创建仍是约 3~4ms/段的固定成本（低端机按倍数放大），
分批重放把它摊到多帧（bind 冻结降到 ~50ms、界面可响应、内容渐进出现），而非消除该成本。
真实长会话中折叠段占多数（思考/工具输出），P0-4 的懒挂载收益更大。
