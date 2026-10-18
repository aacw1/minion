# Agent 循环提前停止与运行状态一致性

日期：2026-10-18
状态：已实施（2026-10-18，全量用例通过）

## 背景与问题

用户报告两类现象，代码审查确认均存在可触发路径：

1. **模型任务明显未跑完，但流程停止**：模型返回了 `tool_calls`，但 `finish_reason` 不是
   `tool_calls` 时，主循环直接结束本轮，工具不执行、无任何提示。
2. **调用工具后左下角显示「正在加载中」，但后台已停止/无进展**：停止按钮存在取消窗口，
   工具等待无超时，等待用户回答/确认时指示器状态与真实状态不一致。

### 根因证据

**P0-1：finish_reason 与 tool_calls 不一致 → 静默停止 + 半轮残留**

- `DeepSeekClient.java:158` `finish` 初值为 `"stop"`。SSE 流被网关/网络截断时
  `readUtf8Line()` 返回 null 属正常结束，不会抛异常，`finish` 保持 `"stop"`；
  `:250` 照常 `handler.onFinish("stop", usage, acc)`（`acc` 非空）。
  OpenAI 兼容网关也可能对工具调用返回 `"stop"` / `"function_call"`。
- `AgentLoop.java:643-649` 已把带 `toolCalls` 的 assistant 消息加入历史；
  `:655-657` 只认 `"tool_calls".equals(finish[0])`，于是直接 `break`：工具不执行、
  无 `onError`/`onWarning`，用户只看到模型正文后流程停止。
- `AgentLoop.java:730` 仅 `interrupted` 时调用 `scrubHalfTurn()`，正常 break 不清理，
  历史中留下「assistant 带 toolCalls、无对应 TOOL 消息」的半轮。
  下一次发送会原样带上该消息 → OpenAI 兼容 API 400，且 400 不重试
  （`AgentLoop.java:637`），会话此后每次发送都失败，只能重启/新建会话恢复。

探针实测（临时测试，验证后已删除）：

```
[PROBE] toolCalls=[]  errors=[]  warnings=[]        ← 工具没跑，无任何提示
[PROBE] lastRole=ASSISTANT lastToolCalls=1          ← 半轮残留已入历史
[PROBE] secondRequestRoles=SYSTEM,USER,ASSISTANT,USER
[PROBE] 请求中残留未配对 assistant toolCalls: 1      ← 下一轮请求必然 400
```

**P0-2：空回复静默停止**

`content` 为空、`thinking` 可能有值、`toolCalls` 为空（空流 / 只回 reasoning_content）时：
`AgentLoop.java:643` 不入历史 → `:655` 直接 break，只发统计行，用户看不到停止原因。
`SubAgentLoop.java:213-218` 同样以 finish 判定，空/半截内容直接作为报告成功返回。

**P1-1：停止按钮存在取消窗口，工具等待不可中断**

- `AgentLoop.java:662-668` 先循环 `pool.submit`，全部提交完才 `inFlight.addAll`。
  两次操作之间点停止，`interrupt()` 读到的 `inFlight` 不含这些 future，停止对它们无效。
- `AgentLoop.java:675` 使用 `futures.get(i).get()`（无超时）。工具不响应中断时主循环永久等待。
- `interrupt()` 后没有任何「正在停止」反馈，指示器文案仍轮换「正在加载中…」。

**P1-2：等待用户的状态与指示器不一致**

- `MainWindow.java:255-267` 在 ask 状态变化的那一刻 `suspend()`；`:322-323` 切回会话时
  只按 `h.running` 恢复 → 提问挂起（实际阻塞在 `AskUserQuestionTool` 的 `fut.get()` 等回答）
  时切走再切回，指示器显示「正在加载中…」。高危确认卡片显示期间切会话同理。
- `AgentLoop.java:678` 中断时 `CancellationException` 直接 break，剩余工具不发
  `onToolResult`，聊天区留下没有结果行的工具调用，看起来像仍在执行。

**P2：两个小问题**

- `GuiConfirmUi` 的 `q.take()` 被中断后返回 REJECT，但 `ConfirmSheet` 卡片不自动关闭。
- `AgentLoop.java:726`（及 `:820/:824`）`getMessage()` 为 null 时提示「异常: null」。

## 方案选择（用户确认）

| 决策点 | 结论 |
|--------|------|
| 工具调用判定 | **客户端 + 主循环双保险**：客户端归一 finish_reason；两个循环以 `toolCalls` 非空为执行依据 |
| 空回复 | **自动重试 1 次，仍空则明确报错**；仅有思考无正文不重试（避免思考重复渲染） |
| 停止可靠性 | **登记前移 + 可中断等待 + 「正在停止…」文案** 三项全做 |
| 等待态显示 | **指示器一致性 + 中断补发「已取消」结果行**；不加提问挂起时的 Esc/点击中止入口 |
| P2 范围 | **子代理同源修复 + 两个小修**；不做排队任务停止、子代理轮数上限 |

## 设计

### 1. 客户端层：finish_reason 归一（`DeepSeekClient`）

`streamChat` 末尾、`handler.onFinish` 之前：

```java
if (!acc.isEmpty() && !"tool_calls".equals(finish)) {
    finish = "tool_calls"; // 流截断/兼容网关：有工具参数即视为工具调用
}
```

- 现有反向防御（`finish == "tool_calls"` 且 `acc` 为空 → BAD_REQUEST）保持不变。
- 只改 `finish` 的对外值，不动 `usage` 估算与消息内容。
- 单测覆盖：`finish_reason:"stop"` + tool_calls 增量；流截断（无 finish chunk）。

### 2. 主循环（`AgentLoop`）

**2.1 执行门槛**

```java
if (toolCalls[0] == null || toolCalls[0].isEmpty()) {
    break; // 模型不再调用工具：正常结束
}
```

去掉 `!"tool_calls".equals(finish[0])`。`"error".equals(finish[0])` 的处理保持在前面。

**2.2 空回复**

在 assistant 入历史前判定（局部计数器，每回合归零）：

- `content`、`thinking` 均空、`toolCalls` 空，且 `emptyRetries == 0`：
  `ui.onWarning("模型返回空回复，自动重试一次")` → `continue`（不写历史、不算工具轮次）。
- 再次为空：`ui.onError("模型返回空回复，本轮已停止，可重试或换模型")` → `break`。
- `thinking` 非空但 `content`/`toolCalls` 空：不重试，直接走上面的报错分支
  （重试会重复渲染一遍思考）。

**2.3 退出清洗**

```java
if (interrupted) ui.onWarning("本轮已被中断");
scrubHalfTurn(); // 所有退出路径无条件执行
```

`scrubHalfTurn()` 的「完整才保留」语义不变：正常结束且结果齐全时不会改动历史；
任何退出路径（正常/轮数上限/错误/中断/异常）都不会留下未配对 `toolCalls`。

**2.4 工具等待可中断**

- 每个 future `submit` 后**立即**登记：

  ```java
  Future<ToolResult> f = pool.submit(() -> runOneTool(call));
  futures.add(f);
  synchronized (inFlight) { inFlight.add(f); }
  ```

  消除 `submit` 与 `addAll` 之间的取消窗口；`interrupt()` 无需改动。
- 等待改为 200ms 分段轮询：

  ```java
  result = awaitResult(futures.get(i)); // get(200ms) 循环；检查 interrupted / 线程中断
  ```

  每 200ms 检查一次 `interrupted || Thread.currentThread().isInterrupted()`；
  中断时：取消本批剩余 future、停止等待，状态交由外层 `while (!interrupted)` 退出。
- 未出结果的工具补发 UI 事件：`ui.onToolResult(name, ToolResult.error("已取消（用户停止）"))`；
  历史不补（由 2.3 的 `scrubHalfTurn()` 清理整段），与现有中断语义一致。
- `CancellationException` 分支不再直接 `break` 丢弃后续工具，改为走上面的取消路径。

**2.5 停止反馈**

- `SessionHandle` 新增 `volatile boolean stopRequested`。
- `SessionManager.stop()`：仅在 `h.running` 时置 `h.stopRequested = true`（排队中的任务不置位，
  与「排队任务不可停止」的既有语义一致）→ 新增监听回调 `onSessionStopping(h)` →
  `h.loop.interrupt()` → `h.closeRetired()`。
- `send()` 的 finally：`h.stopRequested = false`，再 `notifyRunningChanged(h, false)`。
- 切回会话时按 `h.stopRequested` 恢复「正在停止…」，避免切走再切回丢失状态。

### 3. 子代理（`SubAgentLoop`）

- 执行门槛改为 `toolCalls` 非空即执行（同 2.1），去掉 finish 条件。
- 空报告：`toolCalls` 空且 `content` 空白 → 不发 `onSubAgentDone`，返回
  `"子代理失败: 模型返回空报告（任务未完成）"`，主代理按工具输出看到失败原因。
- `toolCalls` 非空但 `content` 为空时正常执行工具，不视为空报告。

### 4. GUI 指示器一致性

**4.1 单一刷新入口**

`MainWindow` 新增 `syncIndicator(SessionHandle h)`：

```java
if (chatView == null || chatView.handle() != h) return;        // 仅当前激活会话
boolean allowed = indicatorAllowed(h.running, h.askPending, ConfirmSheet.isShowing());
runningIndicator.setRunning(h.running);                        // 先刷运行态
runningIndicator.setPausedByModal(ConfirmSheet.isShowing());   // 遮挡态由单一真相驱动
runningIndicator.setStopping(h.running && h.stopRequested);
runningIndicator.setWaitingUser(h.running && !allowed);        // 再刷等待态（可覆盖可见性）
```

`MainWindow` 中所有 `runningIndicator.suspend()/resume()` 调用删除，统一走 `syncIndicator`。

调用点：`onSessionRunningChanged`、`onSessionAskChanged`、`onSessionStopping`、
`onSessionActivated`（两个分支）、`ConfirmSheet` 可见性回调、`clearChatPane`。
删除现在散落的 `setRunning/suspend/resume` 分支，避免状态漂移。

**4.2 `RunningIndicator`**

- 新增 `setStopping(boolean)`：显示固定文案「正在停止...」，优先级高于重试/压缩/轮换；
  停止态仍显示齿轮动画。`false` 时按当前状态重绘。
- 新增 `setWaitingUser(boolean)`：隐藏但不改变 `running/compressing/retry` 状态
  （替代裸 `suspend/resume`）；`setRunning(false)` 时一并复位 stopping/waiting。
- 状态优先级：`waitingUser`（隐藏）> `stopping` > `retry` > `compressing` > 轮换。

**4.3 确认卡片**

- `ConfirmSheet` 增加 `public static boolean isShowing()`。
- `show(...)` 返回可 `cancel()` 的句柄：当前显示中 → `finish(REJECT)`（复用淡出收尾）；
  仍在 `pending` 队列 → 从队列移除（防止事后弹出已放弃的确认）。
  `pending` 队列元素由 `Runnable` 改为「句柄 + 任务」，撤销按句柄身份匹配。
- `GuiConfirmUi.ask` 捕获 `InterruptedException` 后
  `Platform.runLater(handle::cancel)`，卡片自动关闭；返回值仍为 REJECT。

### 5. 小修

- 异常文案统一：`e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()`，
  覆盖 `AgentLoop.java:726/820/824`。

## 影响面

| 文件 | 变化 |
|------|------|
| `core/llm/DeepSeekClient.java` | finish_reason 归一（有 tool_calls 且 finish 非 tool_calls） |
| `core/agent/AgentLoop.java` | 执行门槛、空回复重试与报错、无条件 scrub、工具登记前移与分段等待、取消补行、异常文案 |
| `core/agent/SubAgentLoop.java` | 执行门槛、空报告失败文案 |
| `gui/session/SessionHandle.java` | 新增 `stopRequested` |
| `gui/session/SessionManager.java` | `Listener.onSessionStopping`（default 空）、`stop()` 置位并通知、`send` finally 复位 |
| `gui/MainWindow.java` | `syncIndicator` 单一入口，替换现有指示器分支 |
| `gui/RunningIndicator.java` | `setStopping` / `setWaitingUser` 与状态优先级 |
| `gui/dialog/ConfirmSheet.java` | `isShowing()`、`show` 返回可取消句柄、pending 撤销 |
| `gui/confirm/GuiConfirmUi.java` | 中断时自动关闭自己的卡片 |
| 历史/落盘格式 | 不变（无需迁移；旧会话恢复时现有 `scrubHalfTurn` 已清理半轮） |

## 测试计划（TDD）

1. `DeepSeekClientTest`：tool_calls 增量 + `finish_reason:"stop"` → onFinish 的 finishReason 为 `tool_calls`。
2. `DeepSeekClientTest`：流截断（无 finish chunk）→ 同上。
3. `AgentLoopTest`：非 tool_calls finish + toolCalls → 工具执行；历史 assistant/tool 配对完整。
4. `AgentLoopTest`：首次空回复 → 自动重试一次 → 第二次正常回复，历史无空 assistant。
5. `AgentLoopTest`：连续两次空回复 → `onError` 文案、无工具执行、历史只有 user。
6. `AgentLoopTest`：仅思考无正文 → 不重发，直接报错（请求次数断言为 1）。
7. `AgentLoopTest`：工具阶段中断 → 历史无未配对 toolCalls（`scrubHalfTurn` 生效）。
8. `AgentLoopTest`：中断时未完成工具 → UI 收到「已取消（用户停止）」结果事件。
9. `SubAgentLoopTest`：非 tool_calls finish + toolCalls → 工具执行。
10. `SubAgentLoopTest`：空报告 → 失败文案，且 `onSubAgentDone` 不调用。
11. `RunningIndicatorTest`：stopping 文案优先级、setRunning(false) 复位 stopping/waiting。
12. `MainWindow` 静态判定（抽 `indicatorAllowed(running, askPending, confirmShowing)` 供单测）。
13. `GuiConfirmUiTest` / `ConfirmSheetTest`（FX）：cancel 当前卡片与排队卡片（按现有 FX 测试风格）。
14. `AgentLoopTest`：null message 异常 → 用类名，不出现「异常: null」。

## 不纳入

- 排队中的任务无法被 `stop()` 取消（任务开始前的窗口）。
- 子代理轮数上限（维持「无上限」设计）。
- 提问挂起时的 Esc/点击指示器中止入口。

## 验收标准

- `mvn compile` + `mvn test` 全绿（现有 1195 用例无回归，新增用例覆盖上述清单）。
- 手工冒烟：以 finish="stop"+tool_calls 的假响应驱动一轮，工具真实执行、历史配对、
  下一轮请求不再携带未配对 toolCalls。
- 停止体验：工具执行中点停止，指示器立即显示「正在停止…」，工具结束后隐藏；
  提问挂起时切走再切回，指示器保持隐藏（直到回答或停止）。
