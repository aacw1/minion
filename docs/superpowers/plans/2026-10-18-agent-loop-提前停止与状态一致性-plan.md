# Agent 循环提前停止与运行状态一致性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 ReAct 循环「有工具调用却被静默跳过」「空回复静默停止」「停止后长时间显示运行中」「等待用户时指示器误显」四类缺陷。

**Architecture:** 分两层修：协议层（`DeepSeekClient` 归一 `finish_reason`；两个循环以 `toolCalls` 非空为执行依据）与等待层（工具登记前移、分段轮询中断、取消补行、指示器单一刷新入口）。GUI 状态由 `MainWindow.syncIndicator` 统一收敛，避免多处分支漂移。

**Tech Stack:** JDK 8、JUnit 4、MockWebServer、JavaFX 8、Gson。

## Global Constraints

- JDK 8 兼容（无 var/record/switch 表达式；`java.util.function.*` 可用）。
- 不新增第三方依赖。
- 资源目录是 `src/resource`；本计划不新增资源。
- 代码注释、测试名、commit message 用中文；commit 用 conventional 格式。
- 每个任务结束必须 `mvn compile` + 相关测试通过；Task 10 后跑全量 `mvn test`。
- 历史/落盘格式不变，不做迁移。

---

### Task 1: DeepSeekClient 归一 finish_reason

**Files:**
- Modify: `src/main/java/com/minion/core/llm/DeepSeekClient.java`（`streamChat` 末尾，`:245-250`）
- Test: `src/test/java/com/minion/core/llm/DeepSeekClientTest.java`

**Interfaces:**
- Consumes: 现有 `streamChat(List<Message>, List<JsonObject>, StreamHandler)`。
- Produces: `onFinish` 的 `finishReason` 契约——只要 `toolCalls` 非空，必为 `"tool_calls"`。

- [ ] **Step 1: 写失败测试（两条）**

在 `DeepSeekClientTest` 末尾追加：

```java
    /** 有工具参数但 finish_reason 为 stop（兼容网关/代理）：归一为 tool_calls，防主循环丢弃工具调用 */
    @Test
    public void streamChat_toolCallsWithStopFinish_normalized() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"{}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));
        final List<Object> out = new ArrayList<Object>();
        newClient().streamChat(Collections.<Message>singletonList(Message.user("读文件")),
                null, new StreamHandler() {
                    @Override public void onFinish(String finishReason, Usage usage, List<ToolCall> tcs) {
                        out.add(finishReason);
                        out.add(tcs);
                    }
                });
        assertEquals("tool_calls", out.get(0));
        assertEquals(1, ((List<?>) out.get(1)).size());
    }

    /** 流被截断（无 finish chunk）：默认 stop 同样归一，避免带半轮 tool_calls 静默结束 */
    @Test
    public void streamChat_truncatedStreamWithToolCalls_normalized() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c2\","
                + "\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"{}\"}}]}}]}\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));
        final List<Object> out = new ArrayList<Object>();
        newClient().streamChat(Collections.<Message>singletonList(Message.user("读文件")),
                null, new StreamHandler() {
                    @Override public void onFinish(String finishReason, Usage usage, List<ToolCall> tcs) {
                        out.add(finishReason);
                    }
                });
        assertEquals("tool_calls", out.get(0));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=DeepSeekClientTest#streamChat_toolCallsWithStopFinish_normalized+streamChat_truncatedStreamWithToolCalls_normalized -DfailIfNoTests=false`
Expected: FAIL（`expected:<tool_calls> but was:<stop>`）。

- [ ] **Step 3: 实现**

`DeepSeekClient.streamChat` 末尾，把现有反向防御改成双向：

```java
        // 协议归一：有工具参数即视为工具调用（流截断时 finish 保持默认 "stop"；
        // 部分 OpenAI 兼容网关对工具调用返回 stop/function_call）。主循环以 toolCalls 为执行依据，
        // 此处同时把对外契约修正为 tool_calls，防上层依赖 finish 的实现再次丢调用。
        if (!acc.isEmpty() && !"tool_calls".equals(finish)) {
            finish = "tool_calls";
        }
        if (finish.equals("tool_calls") && acc.isEmpty()) {
            throw new LlmException(LlmException.Type.BAD_REQUEST,
                    "模型声明工具调用但未返回工具参数", false);
        }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=DeepSeekClientTest -DfailIfNoTests=false`
Expected: PASS（含原有 30+ 用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/llm/DeepSeekClient.java src/test/java/com/minion/core/llm/DeepSeekClientTest.java
git commit -m "fix(llm): 有工具参数时归一 finish_reason 为 tool_calls"
```

---

### Task 2: AgentLoop 以 toolCalls 判定执行（去掉 finish 门槛）

**Files:**
- Modify: `src/test/java/com/minion/core/llm/FakeLlmClient.java`（新增可指定 finish 的出牌方法）
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java:655-657`
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: Task 1 的 `onFinish` 契约（真实客户端必归一）。
- Produces: `FakeLlmClient.addTurnWithToolsAndFinish(List<ToolCall>, String content, String finish)`，供 Task 2/6 共用。

- [ ] **Step 1: 写失败测试**

`AgentLoopTest` 追加：

```java
    /** 有 toolCalls 但 finish_reason 非 tool_calls：工具必须执行（旧实现直接 break，且留下半轮残留） */
    @Test
    public void toolCallsWithNonToolFinish_executesTool() {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"hi\"}";
        llm.addTurnWithToolsAndFinish(Collections.singletonList(tc), null, "stop");
        llm.addTurn("处理完成");
        AgentLoop loop = newLoop();
        loop.runUserTurn("调用一下");
        assertEquals(1, ui.toolCalls.size());
        assertEquals("example", ui.toolCalls.get(0));
        // 0:user 1:assistant(tool_calls) 2:tool 3:assistant
        assertEquals(4, loop.messages().size());
        assertEquals(Message.Role.TOOL, loop.messages().get(2).role);
        assertEquals("处理完成", loop.messages().get(3).content);
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#toolCallsWithNonToolFinish_executesTool -DfailIfNoTests=false`
Expected: 编译失败（`addTurnWithToolsAndFinish` 不存在）。先补桩再跑：

`FakeLlmClient.ScriptedTurn` 加字段：

```java
        /** 非 null：覆盖 onFinish 的 finishReason（null = 按旧规则 tool_calls/stop） */
        public String finishReason;
```

新增方法：

```java
    /** 指定 finishReason 的工具轮（模拟兼容网关/截断流返回 stop） */
    public void addTurnWithToolsAndFinish(List<ToolCall> toolCalls, String content, String finish) {
        ScriptedTurn t = new ScriptedTurn(toolCalls, content, null, null, false, null);
        t.finishReason = finish;
        turns.add(t);
    }
```

`streamChat` 的出牌段改为：

```java
        String finishReason = turn.finishReason != null ? turn.finishReason
                : (turn.toolCalls != null && !turn.toolCalls.isEmpty() ? "tool_calls" : "stop");
        if (turn.toolCalls != null && !turn.toolCalls.isEmpty()) {
            handler.onFinish(finishReason, u, turn.toolCalls);
        } else {
            handler.onContent(turn.content);
            handler.onFinish(finishReason, u, new ArrayList<ToolCall>());
        }
```

再跑一次，Expected: FAIL（`ui.toolCalls` 为空）。

- [ ] **Step 3: 实现**

`AgentLoop.java:655-657` 改为：

```java
                // 执行依据只看 toolCalls：finish_reason 可能被网关/截断流写成 stop，
                // 客户端已在 DeepSeekClient 归一，此处再兜一层，绝不因 finish 丢弃工具调用
                if (toolCalls[0] == null || toolCalls[0].isEmpty()) {
                    break;
                }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=AgentLoopTest -DfailIfNoTests=false`
Expected: PASS（含现有全部 AgentLoop 用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/llm/FakeLlmClient.java src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -m "fix(agent): 以 toolCalls 为工具执行依据，不再依赖 finish_reason"
```

---

### Task 3: 空回复自动重试一次、仍空则报错

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`（`runUserTurn` 内，`"error".equals(finish[0])` 之后、assistant 入历史之前）
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: `FakeLlmClient.addTurn(String)`（content 可为 `""`）、`addTurnWithTools(null, "", thinking)`。
- Produces: 空回复语义——`content`、`thinking` 都空时最多重发一次；有思考无正文直接报错。

- [ ] **Step 1: 写失败测试（三条）**

`AgentLoopTest` 追加：

```java
    /** 空回复（无正文无思考无工具）→ 自动重发一次，第二次成功 */
    @Test
    public void emptyReply_retriesOnceThenSucceeds() {
        llm.addTurn("");
        llm.addTurn("好的");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        assertEquals(2, llm.requests.size()); // 首次 + 重试
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("空回复")));
        assertEquals("好的", loop.messages().get(1).content);
        assertTrue("空回复不得入历史", loop.messages().size() == 2);
    }

    /** 连续两次空回复 → 明确报错并结束，不静默 */
    @Test
    public void emptyReplyTwice_reportsError() {
        llm.addTurn("");
        llm.addTurn("");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        assertEquals(2, llm.requests.size());
        assertTrue(ui.errors.stream().anyMatch(e -> e.contains("空回复")));
        assertEquals(1, loop.messages().size()); // 只有 user
    }

    /** 仅思考无正文 → 不重试（重试会重复渲染思考），直接报错 */
    @Test
    public void thinkingOnlyReply_noRetry_reportsError() {
        llm.addTurnWithTools(null, "", "只想了没写");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        assertEquals(1, llm.requests.size());
        assertTrue(ui.errors.stream().anyMatch(e -> e.contains("空回复")));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#emptyReply_retriesOnceThenSucceeds+emptyReplyTwice_reportsError+thinkingOnlyReply_noRetry_reportsError -DfailIfNoTests=false`
Expected: FAIL（请求次数为 1、无 warnings/errors）。

- [ ] **Step 3: 实现**

`runUserTurn` 里 `try {` 之前加局部计数：

```java
        int rounds = 0;
        int retries = 0;
        int emptyReplies = 0; // 空回复重试计数（每回合归零）
```

在 `if ("error".equals(finish[0])) { ... break; }` 之后、`if (content.length() > 0 ...)` 之前插入：

```java
                if (interrupted) break; // 中断优先：走末尾中断提示，不误报空回复

                // 空回复：既无正文也无工具调用（thinking 不单独成轮）。首次自动重发一次，
                // 仍空则明确报错——旧实现直接 break，用户只看到统计行、不知道为何停止。
                boolean noContentNoTools = content.length() == 0
                        && (toolCalls[0] == null || toolCalls[0].isEmpty());
                if (noContentNoTools) {
                    if (thinking.length() == 0 && emptyReplies == 0) {
                        emptyReplies++;
                        ui.onWarning("模型返回空回复，自动重试一次");
                        continue; // 消息未变，直接重发本轮；空消息不入历史
                    }
                    ui.onError("模型返回空回复，本轮已停止，可重试或换模型");
                    break;
                }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=AgentLoopTest -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -m "fix(agent): 空回复自动重试一次，仍空则明确报错"
```

---

### Task 4: 所有退出路径无条件清洗半轮

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java:729-731`
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: 现有 `scrubHalfTurn()`（只检查最近一条 assistant，完整配对不改动）。
- Produces: 退出契约——任何退出路径（正常/上限/错误/中断/异常）历史中都不得残留未配对 `toolCalls`。

- [ ] **Step 1: 写失败测试**

`AgentLoopTest` 追加（工具结果回调抛异常，走 `catch (Exception)` 退出路径）：

```java
    /** 非中断异常退出（如 UI 回调抛 RuntimeException）也不得留下半轮 toolCalls */
    @Test
    public void exceptionExit_leavesNoToolCallsResidue() {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"x\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        RecordingUi throwing = new RecordingUi() {
            @Override public synchronized void onToolResult(String name, ToolResult result) {
                throw new RuntimeException("boom");
            }
        };
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, throwing, null, new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.runUserTurn("任务");
        for (Message m : loop.messages()) {
            assertTrue("残留 toolCalls: " + m.role, m.toolCalls == null || m.toolCalls.isEmpty());
        }
        assertTrue(throwing.errors.stream().anyMatch(e -> e.contains("异常")));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#exceptionExit_leavesNoToolCallsResidue -DfailIfNoTests=false`
Expected: FAIL（历史里 assistant 仍带 toolCalls）。

- [ ] **Step 3: 实现**

```java
        if (interrupted) {
            ui.onWarning("本轮已被中断");
        }
        // 所有退出路径统一清洗：正常结束/轮数上限/错误/中断/异常都不得留下未配对的 toolCalls
        // （旧实现只在 interrupted 时清洗，正常 break 会把半轮写进历史，下次请求 400）
        scrubHalfTurn();
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=AgentLoopTest -DfailIfNoTests=false`
Expected: PASS（现有 `interrupt_duringToolExecution_leavesNoToolCallsResidue` 等用例继续通过）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -m "fix(agent): 所有退出路径统一清洗未配对的 toolCalls"
```

---

### Task 5: 工具登记前移 + 可中断等待 + 取消补行

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java:660-690`（提交与等待段）
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Produces: `static ToolResult awaitToolResult(Future<ToolResult> future, BooleanSupplier stopped, long sliceMs)`
  —— 返回 `null` 表示已停止/已取消；`ExecutionException` 转为错误 `ToolResult`。
- Produces: 中断时对未完成工具发 `ui.onToolResult(name, ToolResult.error("已取消（用户停止）"))`（只发 UI，不写历史）。

- [ ] **Step 1: 写失败测试（两条）**

`AgentLoopTest` 追加：

```java
    /** 可中断等待：停止标志为真时，不响应中断的 future 也必须在轮询片内返回 null */
    @Test
    public void awaitToolResult_stopped_returnsNullQuickly() throws Exception {
        java.util.concurrent.FutureTask<ToolResult> never =
                new java.util.concurrent.FutureTask<ToolResult>(
                        () -> ToolResult.success("x")); // 永不 run
        long start = System.currentTimeMillis();
        ToolResult r = AgentLoop.awaitToolResult(never, () -> true, 50);
        assertNull(r);
        assertTrue(System.currentTimeMillis() - start < 1000);
    }

    /** 可中断等待：正常完成原样返回 */
    @Test
    public void awaitToolResult_completed_returnsResult() throws Exception {
        java.util.concurrent.FutureTask<ToolResult> ft =
                new java.util.concurrent.FutureTask<ToolResult>(
                        () -> ToolResult.success("ok"));
        ft.run();
        assertEquals("ok", AgentLoop.awaitToolResult(ft, () -> false, 50).output);
    }

    /** 中断时未完成工具补发「已取消」结果行（聊天区不留无结果的工具行） */
    @Test
    public void interruptDuringTools_emitsCancelledResult() throws Exception {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "blocker";
        tc.arguments = "{}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        AgentLoop loop = newLoop();
        BlockingTool blocker = new BlockingTool();
        registry.register(blocker);
        Thread t = new Thread(() -> loop.runUserTurn("任务"));
        t.start();
        assertTrue(blocker.entered.await(5, TimeUnit.SECONDS));
        loop.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue("应补发已取消结果: " + ui.toolResults,
                ui.toolResults.contains("blocker"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#awaitToolResult_stopped_returnsNullQuickly+interruptDuringTools_emitsCancelledResult -DfailIfNoTests=false`
Expected: 编译失败（`awaitToolResult` 不存在）。补方法骨架后重跑，Expected: FAIL。

- [ ] **Step 3: 实现**

`AgentLoop` 新增包可见静态方法：

```java
    /** 可中断等待工具结果：200ms 分段轮询停止标志（用户停止只置标志，不中断工作线程）；
     *  返回 null = 已停止/已取消，调用方负责取消剩余 future 并补发「已取消」结果。
     *  ExecutionException 统一转错误结果（与旧路径一致），不向上抛。 */
    static ToolResult awaitToolResult(Future<ToolResult> future,
                                      java.util.function.BooleanSupplier stopped,
                                      long sliceMs) throws InterruptedException {
        while (true) {
            try {
                return future.get(sliceMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (stopped.getAsBoolean()) return null;
            } catch (ExecutionException e) {
                return ToolResult.error("工具执行异常: " + e.getMessage());
            } catch (CancellationException e) {
                return null;
            }
        }
    }
```

提交段改为「提交即登记」：

```java
                for (ToolCall call : calls) {
                    if (interrupted) break;
                    Future<ToolResult> f = pool.submit(() -> runOneTool(call));
                    futures.add(f);
                    // 提交即登记：消除 submit 与 addAll 之间的取消窗口（窗口内 interrupt 拿不到 future）
                    synchronized (inFlight) {
                        inFlight.add(f);
                    }
                }
```

等待段改为：

```java
                    for (int i = 0; i < futures.size(); i++) {
                        ToolResult result;
                        try {
                            result = awaitToolResult(futures.get(i),
                                    () -> interrupted || Thread.currentThread().isInterrupted(), 200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            result = null;
                        }
                        if (result == null) {
                            // 已停止：取消本批剩余工具，并为未出结果的调用补 UI 结果行
                            // （历史不补——scrubHalfTurn 统一清理半轮）
                            for (int j = i; j < futures.size(); j++) {
                                futures.get(j).cancel(true);
                                ui.onToolResult(calls.get(j).name,
                                        ToolResult.error("已取消（用户停止）"));
                            }
                            break;
                        }
                        if (result.ok) {
                            consecutiveToolErrors = 0;
                        } else {
                            consecutiveToolErrors++;
                        }
                        session.messages.add(Message.toolResult(
                                calls.get(i).id, calls.get(i).name,
                                ToolOutputGate.apply(calls.get(i).name,
                                        ToolResult.outputForApi(result.output, emptyOutputPlaceholder),
                                        sessionTmpDirPath())));
                        ui.onToolResult(calls.get(i).name, result);
                    }
```

删除旧的 `ExecutionException` / `CancellationException` 分支（已并入 `awaitToolResult`）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=AgentLoopTest -DfailIfNoTests=false`
Expected: PASS（含现有中断用例；`interrupt_duringToolExecution_leavesNoToolCallsResidue` 仍绿）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -m "fix(agent): 工具提交即登记，等待改为可中断轮询并补发取消结果"
```

---

### Task 6: 子代理执行门槛与空报告

**Files:**
- Modify: `src/main/java/com/minion/core/agent/SubAgentLoop.java:213-218`
- Test: `src/test/java/com/minion/core/agent/SubAgentLoopTest.java`

**Interfaces:**
- Consumes: `FakeLlmClient.addTurnWithToolsAndFinish`（Task 2 产出）。
- Produces: 空报告返回文案「子代理失败: 模型返回空报告（任务未完成）」，且不调用 `onSubAgentDone`。

- [ ] **Step 1: 写失败测试（两条）**

`SubAgentLoopTest` 追加：

```java
    /** 有 toolCalls 但 finish 非 tool_calls：工具必须执行（子代理此前会直接把半截正文当报告） */
    @Test
    public void subAgent_nonToolFinish_executesTool() {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ToolCall tc = new ToolCall();
        tc.id = "s1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"x\"}";
        llm.addTurnWithToolsAndFinish(Collections.singletonList(tc), null, "stop");
        llm.addTurn("完成");
        SubAgentLoop sub = new SubAgentLoop("sys", "任务", tmp.getRoot().getPath(),
                llm, registry, new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui, null, 1);
        assertEquals("完成", sub.run());
        assertTrue("工具应执行: " + ui.subToolCalls, ui.subToolCalls.contains("1:example"));
    }

    /** 空报告不得按成功返回（否则主代理误判子任务完成） */
    @Test
    public void subAgent_emptyReport_returnsFailure() {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        RecordingUi ui = new RecordingUi();
        llm.addTurn("");
        SubAgentLoop sub = new SubAgentLoop("sys", "任务", tmp.getRoot().getPath(),
                llm, registry, new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui, null, 1);
        String r = sub.run();
        assertTrue("空报告应报失败: " + r, r.startsWith("子代理失败"));
        assertTrue("不得发 DONE: " + ui.subDones, ui.subDones.isEmpty());
        assertTrue(ui.subNotices.stream().anyMatch(n -> n.contains("空报告")));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=SubAgentLoopTest#subAgent_nonToolFinish_executesTool+subAgent_emptyReport_returnsFailure -DfailIfNoTests=false`
Expected: FAIL（工具未执行 / 返回空串且 DONE 被发出）。

- [ ] **Step 3: 实现**

`SubAgentLoop` 中两处：

```java
                if (toolCalls[0] == null || toolCalls[0].isEmpty()) {
                    String report = content.toString();
                    if (report.trim().isEmpty()) {
                        // 空报告 = 任务未完成：不得让主代理误判成功（旧实现返回 "" 并 DONE）
                        String msg = "子代理失败: 模型返回空报告（任务未完成）";
                        ui.onSubAgentNotice(no, msg);
                        return msg;
                    }
                    String ret = persistReport(report);
                    ui.onSubAgentDone(no, ret);
                    return ret;
                }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=SubAgentLoopTest -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/SubAgentLoop.java src/test/java/com/minion/core/agent/SubAgentLoopTest.java
git commit -m "fix(subagent): 以 toolCalls 判定执行，空报告按失败返回"
```

---

### Task 7: RunningIndicator 增加「正在停止」与「等待用户」

**Files:**
- Modify: `src/main/java/com/minion/gui/RunningIndicator.java`
- Test: `src/test/java/com/minion/gui/RunningIndicatorTest.java`

**Interfaces:**
- Produces: `static final String STOPPING_TEXT = "正在停止..."`；
  `static String statusText(boolean stopping, boolean compressing, String current)`；
  `void setStopping(boolean)`；`void setWaitingUser(boolean)`。

- [ ] **Step 1: 写失败测试**

`RunningIndicatorTest` 追加：

```java
    /** 状态优先级：等待用户（隐藏）> 正在停止 > 压缩 > 轮换 */
    @Test
    public void statusText_stoppingOverridesCompressing() {
        assertEquals("正在停止...", RunningIndicator.STOPPING_TEXT);
        assertEquals("正在停止...", RunningIndicator.statusText(true, true, "正在加载中..."));
        assertEquals("正在停止...", RunningIndicator.statusText(true, false, "可随时补充信息..."));
        assertEquals("上下文压缩中...", RunningIndicator.statusText(false, true, "正在加载中..."));
        assertEquals("正在加载中...", RunningIndicator.statusText(false, false, "正在加载中..."));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=RunningIndicatorTest#statusText_stoppingOverridesCompressing -DfailIfNoTests=false`
Expected: 编译失败（常量/方法不存在）。

- [ ] **Step 3: 实现**

`RunningIndicator` 增加：

```java
    /** 停止请求已发出、等待工具/请求收尾的固定文案（优先于压缩与轮换） */
    static final String STOPPING_TEXT = "正在停止...";

    private boolean stopping;
    private boolean waitingUser;

    /** 文案优先级（纯静态可单测）：停止 > 压缩 > 轮换 */
    static String statusText(boolean stopping, boolean compressing, String current) {
        if (stopping) return STOPPING_TEXT;
        return displayText(compressing, current);
    }

    /** 停止态：显示固定文案（齿轮继续转），false 时按当前状态重绘 */
    public void setStopping(boolean stopping) {
        this.stopping = stopping;
        if (!running) return;
        renderText();
        if (stopping) {
            if (rotateText != null) rotateText.stop();
        } else {
            startRotateText();
        }
    }

    /** 等待用户（提问挂起/确认卡片）：隐藏但保留运行态；false 时按 running 恢复显示 */
    public void setWaitingUser(boolean waitingUser) {
        this.waitingUser = waitingUser;
        setVisible(running && !waitingUser);
    }
```

`renderText()` 改为（停止态优先于重试/压缩/轮换）：

```java
    private void renderText() {
        if (copyFeedback != null) { copyFeedback.stop(); copyFeedback = null; }
        if (stopping) {
            text.setText(STOPPING_TEXT);
            return;
        }
        if (retryBase != null) {
            text.setText(retryText(retryProgress, retryBase));
            return;
        }
        text.setText(statusText(stopping, compressing, pickText(rnd)));
    }
```

`setRunning(false)` 里复位：

```java
            compressing = false;
            retryBase = null;
            retryProgress = null;
            stopping = false;
            waitingUser = false;
```

`setRunning(true)` 分支改为 `setVisible(!waitingUser);`（先刷新运行态、后刷新等待态的调用顺序下，
避免 `setRunning(true)` 把等待用户态误显示出来）。

`setVisible` 的可见性监听保持不变（`resume` 语义由 `setWaitingUser(false)` 取代）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=RunningIndicatorTest -DfailIfNoTests=false`
Expected: PASS（原有用例不变）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/RunningIndicator.java src/test/java/com/minion/gui/RunningIndicatorTest.java
git commit -m "feat(gui): 运行指示器增加正在停止与等待用户状态"
```

---

### Task 8: stopRequested + 指示器单一刷新入口

**Files:**
- Modify: `src/main/java/com/minion/gui/session/SessionHandle.java`
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（`Listener`、`stop()`、`send` finally）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（监听器分支替换为 `syncIndicator`）
- Test: `src/test/java/com/minion/gui/MainWindowIndicatorTest.java`（新建，纯静态判定）

**Interfaces:**
- Produces: `SessionHandle.stopRequested`（volatile boolean）；
  `SessionManager.Listener.onSessionStopping(SessionHandle h)`（default 空实现）；
  `MainWindow.indicatorAllowed(boolean running, boolean askPending, boolean confirmShowing)`。

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/com/minion/gui/MainWindowIndicatorTest.java`：

```java
package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 指示器显示条件：运行中且不在等待用户（提问挂起/确认卡片） */
public class MainWindowIndicatorTest {

    @Test
    public void running_alone_allowed() {
        assertTrue(MainWindow.indicatorAllowed(true, false, false));
    }

    @Test
    public void idle_neverAllowed() {
        assertFalse(MainWindow.indicatorAllowed(false, false, false));
    }

    @Test
    public void askPending_hidesIndicator() {
        assertFalse(MainWindow.indicatorAllowed(true, true, false));
    }

    @Test
    public void confirmSheetShowing_hidesIndicator() {
        assertFalse(MainWindow.indicatorAllowed(true, false, true));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=MainWindowIndicatorTest -DfailIfNoTests=false`
Expected: 编译失败（`indicatorAllowed` 不存在）。

- [ ] **Step 3: 实现**

`SessionHandle` 加字段：

```java
    /** 已请求停止（等待工具/请求收尾）：指示器显示「正在停止...」，running=false 时复位 */
    public volatile boolean stopRequested;
```

`SessionManager.Listener` 加 default 方法：

```java
        /** 已发出停止请求（等待收尾；GUI 指示器显示「正在停止...」） */
        default void onSessionStopping(SessionHandle h) { }
```

`SessionManager.stop()`：

```java
    public void stop(SessionHandle h) {
        if (h != null && h.running) {
            h.stopRequested = true;
            for (Listener l : listeners) l.onSessionStopping(h);
            h.loop.interrupt(); // 只取消当前客户端；换模型后 in-flight 请求在旧（已退役）客户端上
            h.closeRetired();   // 一并取消旧客户端的流式请求，防「终止」失效等旧流自然结束（数分钟）
        }
    }
```

`send` 的 finally 中，在 `h.running = false;` 后加：

```java
                        h.stopRequested = false; // 运行结束：停止态复位（含中断/异常路径）
```

`MainWindow` 加静态判定与同步方法：

```java
    /** 指示器可见条件（纯静态可单测）：运行中且不在等待用户操作 */
    static boolean indicatorAllowed(boolean running, boolean askPending, boolean confirmShowing) {
        return running && !askPending && !confirmShowing;
    }

    /** 指示器单一刷新入口：running/ask/确认卡片/停止态任何变化都走这里，避免多分支漂移 */
    private void syncIndicator(SessionHandle h) {
        if (chatView == null || chatView.handle() != h) return;
        boolean allowed = indicatorAllowed(h.running, h.askPending, ConfirmSheet.isShowing());
        runningIndicator.setRunning(h.running);           // 先刷运行态
        runningIndicator.setStopping(h.running && h.stopRequested);
        runningIndicator.setWaitingUser(h.running && !allowed); // 再刷等待/停止态（可覆盖可见性）
    }
```

替换现有四处分支（`onSessionRunningChanged`、`onSessionAskChanged`、`onSessionActivated` 两处、`clearChatPane`），并新增监听：

```java
            @Override public void onSessionStopping(SessionHandle h) {
                Platform.runLater(() -> syncIndicator(h));
            }
```

注意两处调用时机：

- `onSessionActivated`：必须在 `chatView = cached` / `chatView = ChatView.forSession(h)` **之后**调用
  `syncIndicator(h)`，否则 `chatView.handle() != h` 直接 return。
- `clearChatPane`：此时 `chatView == null`，`syncIndicator` 会 return；保留显式复位：

```java
        runningIndicator.setRunning(false);
        runningIndicator.setWaitingUser(false);
```

`ConfirmSheet.setVisibilityListener` 回调改为 `Platform.runLater(() -> { if (chatView != null) syncIndicator(chatView.handle()); })`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q test -Dtest=MainWindowIndicatorTest,MainWindowTabTitleTest -DfailIfNoTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/session/SessionHandle.java src/main/java/com/minion/gui/session/SessionManager.java src/main/java/com/minion/gui/MainWindow.java src/test/java/com/minion/gui/MainWindowIndicatorTest.java
git commit -m "feat(gui): 停止态与等待用户态统一由 syncIndicator 刷新"
```

---

### Task 9: ConfirmSheet 可取消句柄 + 中断自动关闭

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/ConfirmSheet.java`
- Modify: `src/main/java/com/minion/gui/confirm/GuiConfirmUi.java`
- Test: `src/test/java/com/minion/core/tools/confirm/GuiConfirmUiTest.java`（回归，不新增 FX 测试）

**Interfaces:**
- Produces: `ConfirmSheet.isShowing()`；`ConfirmSheet.show(...)` 返回 `ConfirmSheet.Handle`（`cancel()` 必须在 FX 线程）。
- Consumes: `GuiConfirmUi.ask` 的 `q.take()` 中断路径。

- [ ] **Step 1: 写回归测试**

`GuiConfirmUiTest` 追加（无 FX toolkit：runLater 抛 IllegalStateException → 立即 REJECT，证明改动未破坏无 FX 环境）：

```java
    @Test
    public void ask_withoutFxThread_stillReturnsReject_afterHandleChange() throws Exception {
        final ConfirmUi ui = new com.minion.gui.confirm.GuiConfirmUi();
        final CountDownLatch done = new CountDownLatch(1);
        final ConfirmUi.Decision[] result = new ConfirmUi.Decision[1];
        Thread t = new Thread(() -> {
            result[0] = ui.ask("! 高危操作 Bash → rm -rf");
            done.countDown();
        });
        t.start();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(ConfirmUi.Decision.REJECT, result[0]);
    }
```

- [ ] **Step 2: 运行确认通过（现状）**

Run: `mvn -q test -Dtest=GuiConfirmUiTest -DfailIfNoTests=false`
Expected: PASS（此用例防回归；新行为靠 Step 4 手工冒烟）。

- [ ] **Step 3: 实现**

`ConfirmSheet`：

```java
    /** 可取消句柄：中断/放弃时撤销自己的确认（FX 线程调用） */
    public static final class Handle {
        private final String message;
        private final Consumer<ConfirmUi.Decision> callback;
        private volatile boolean cancelled;

        Handle(String message, Consumer<ConfirmUi.Decision> callback) {
            this.message = message;
            this.callback = callback;
        }

        void display() {
            if (cancelled) return;
            ConfirmSheet.display(message, callback);
        }

        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (currentCallback == callback) {
                finish(ConfirmUi.Decision.REJECT); // 当前显示中：走淡出收尾
            } else {
                pending.remove(this); // 还在队首：撤销，防事后弹出已放弃的确认
            }
        }
    }

    /** 确认卡片是否可见（指示器同步用） */
    public static boolean isShowing() { return showing; }
```

`pending` 类型改为 `Queue<Handle>`；`show` 返回句柄：

```java
    public static Handle show(String message, Consumer<ConfirmUi.Decision> callback) {
        Handle h = new Handle(message, callback);
        if (host == null) {
            System.err.println("[minion] ConfirmSheet host 未注册，确认请求自动拒绝");
            callback.accept(ConfirmUi.Decision.REJECT);
            return h;
        }
        if (showing) { pending.add(h); return h; }
        h.display();
        return h;
    }
```

`finish` 末尾出队改为 `Handle next = pending.poll(); if (next != null) next.display();`。

`GuiConfirmUi.ask`：

```java
    public Decision ask(String message) {
        final LinkedBlockingQueue<Decision> q = new LinkedBlockingQueue<Decision>(1);
        final java.util.concurrent.atomic.AtomicReference<ConfirmSheet.Handle> ref =
                new java.util.concurrent.atomic.AtomicReference<ConfirmSheet.Handle>();
        final java.util.concurrent.CountDownLatch posted = new java.util.concurrent.CountDownLatch(1);
        try {
            Platform.runLater(new Runnable() {
                @Override public void run() {
                    ref.set(ConfirmSheet.show(message, q::offer));
                    posted.countDown();
                }
            });
        } catch (IllegalStateException e) {
            return Decision.REJECT; // FX toolkit 未启动（无 GUI 环境），防御性拒绝
        }
        try {
            return q.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { posted.await(200, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            final ConfirmSheet.Handle h = ref.get();
            if (h != null) Platform.runLater(h::cancel); // 中断即关闭自己的卡片
            return Decision.REJECT;
        }
    }
```

- [ ] **Step 4: 编译 + 回归 + 手工冒烟**

Run: `mvn -q test -Dtest=GuiConfirmUiTest,ConfirmGateTest -DfailIfNoTests=false`
Expected: PASS。

手工冒烟（记录在提交说明里）：
1. 触发一次高危确认，卡片显示期间点停止 → 卡片 120ms 淡出关闭。
2. 并发两个高危确认：第一个显示时停止 → 第一个关闭，第二个随后正常显示。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/ConfirmSheet.java src/main/java/com/minion/gui/confirm/GuiConfirmUi.java src/test/java/com/minion/core/tools/confirm/GuiConfirmUiTest.java
git commit -m "fix(gui): 确认卡片支持取消句柄，中断时自动关闭自己的卡片"
```

---

### Task 10: 异常文案空消息兜底 + 全量验证

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`（`:726`、`:820`、`:824`）
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Produces: `static String errorText(String prefix, Exception e)`。

- [ ] **Step 1: 写失败测试**

`AgentLoopTest` 追加：

```java
    /** 异常 message 为 null 时用类名，不出现「异常: null」 */
    @Test
    public void errorText_nullMessage_usesClassName() {
        assertEquals("异常: NullPointerException",
                AgentLoop.errorText("异常: ", new NullPointerException()));
        assertEquals("异常: boom", AgentLoop.errorText("异常: ", new RuntimeException("boom")));
        assertEquals("工具执行异常: IllegalStateException",
                AgentLoop.errorText("工具执行异常: ", new IllegalStateException()));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=AgentLoopTest#errorText_nullMessage_usesClassName -DfailIfNoTests=false`
Expected: 编译失败（`errorText` 不存在）。

- [ ] **Step 3: 实现**

```java
    /** 异常文案：message 为空时退回类名（NPE 等无消息异常不再显示「异常: null」） */
    static String errorText(String prefix, Exception e) {
        String m = e.getMessage();
        return prefix + (m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m);
    }
```

三处调用替换：
- `:726` → `ui.onError(errorText("异常: ", e));`
- `:820` → `return ToolResult.error(errorText("工具执行异常: ", e));`
- `:824` → `return ToolResult.error(errorText("工具执行异常: ", e));`

- [ ] **Step 4: 全量验证**

Run: `mvn compile && mvn test`
Expected: BUILD SUCCESS；现有 1195+ 用例全绿，新增用例通过。

- [ ] **Step 5: 更新文档并提交**

- 在 `docs/superpowers/specs/2026-10-18-agent-loop-提前停止与状态一致性-design.md` 状态行改为
  `状态：已实施（2026-10-18，全量用例通过）`。
- 若 README 有相关行为描述（错误提示/停止），同步更新。

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java docs/superpowers/specs/2026-10-18-agent-loop-提前停止与状态一致性-design.md
git commit -m "fix(agent): 异常文案空消息兜底并完成文档同步"
```

---

## 手工冒烟清单（全部任务完成后）

1. 用假服务端（或 MockWebServer 测试）返回 `tool_calls` + `finish_reason:"stop"`：工具真实执行、聊天区有工具结果行、下一轮请求无未配对 `toolCalls`。
2. 模型返回空回复：聊天区出现「模型返回空回复，自动重试一次」，第二次仍空出现错误行。
3. 长工具执行中点停止：左下角立即显示「正在停止...」，工具结束后消失；不响应中断的工具不再无限等待（200ms 轮询退出）。
4. 提问挂起时切走再切回：指示器保持隐藏；回答后恢复运行态显示。
5. 高危确认卡片显示期间点停止：卡片自动淡出关闭。

## 自检记录

- 设计覆盖：spec 第 1 节→Task 1；2.1→Task 2；2.2→Task 3；2.3→Task 4；2.4/2.5→Task 5/8；
  3→Task 6；4.1/4.2→Task 7/8；4.3→Task 9；5→Task 10。
- 类型一致性：`awaitToolResult(Future<ToolResult>, BooleanSupplier, long)`、`errorText(String, Exception)`、
  `indicatorAllowed(boolean, boolean, boolean)` 全篇同名。
- 占位符：无 TBD/TODO；每个实现步骤含可粘贴代码。
