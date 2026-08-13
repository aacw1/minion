# UI 修复第四轮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按设计文档（docs/superpowers/specs/2026-08-13-ui-fixes-round4-design.md）完成 6 项 UI 修复：应用按钮、会话列表横向滚动条、浏览器路径浏览、分隔线去除、token 统计恢复、自动滚动根因修复。

**Architecture:** 纯逻辑先行（StatsLine 新类、AutoScrollPolicy 修正，TDD），再接线 GUI（ChatView 回调、MainWindow 布局与监听、SettingsDialog 重构、SessionListView 截断），最后文档同步与全量验收。

**Tech Stack:** Java 8 + JavaFX 8 + Maven + junit4。GUI 改动无自动化测试（JavaFX 无 TestFX 依赖），验证 = 编译/既有测试全过 + 手工清单。

## Global Constraints

- JDK 8 兼容：可用 lambda/stream（JDK 8 特性），禁用 var 等 9+ 语法；flexmark 等依赖不动。
- 测试命令：`JAVA_HOME="E:/javame/jdk8" mvn test`；构建：`JAVA_HOME="E:/javame/jdk8" mvn clean package`（产物 target/minion-0.1.0.jar）。
- 启动 GUI 手工验收：`minion.bat`（本机 PATH 的 java 须为 JDK 8）。
- 注释、文档、commit 用中文；commit 用 conventional 格式（feat:/fix:/docs:）。
- **git commit 必须用 `-F` 消息文件**：bash wrapper 对非 ASCII 命令行崩溃（报 exit 127）。每步 commit 先用 Write 工具写 `.git/COMMIT_MSG.txt`（内容为中文消息），再执行纯 ASCII 的 `git add … && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt`。**不要在 bash 命令行里直接写中文。**
- 本轮不改 API 契约（reasoning_content 原样回传、tool_call↔tool 配对），不新增依赖。
- 资源目录是 `src/resource`；设计文档在 `docs/superpowers/specs/`，实施计划在 `docs/superpowers/plans/`。

---

### Task 1: StatsLine 统计行工具类（TDD，纯逻辑）

**Files:**
- Create: `src/main/java/com/minion/core/agent/StatsLine.java`
- Test: `src/test/java/com/minion/core/agent/StatsLineTest.java`

**Interfaces:**
- Consumes: `com.minion.core.llm.UsageTracker`（`record(Usage)`、`sessionInput()`、`sessionOutput()`、`sessionThinking()`）、`com.minion.core.llm.Usage`（public 字段 `inputTokens/outputTokens/reasoningTokens`）
- Produces: `StatsLine.format(UsageTracker, long elapsedMillis, int currentCtx, int maxCtx) → String`、`StatsLine.formatTokens(int) → String`（Task 2 使用）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/minion/core/agent/StatsLineTest.java`：

```java
package com.minion.core.agent;

import com.minion.core.llm.Usage;
import com.minion.core.llm.UsageTracker;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 统计行格式化：token 缩写边界与整行格式（GUI 版无 CLI 的 * 前缀） */
public class StatsLineTest {

    @Test public void formatTokens_below1000_plain() {
        assertEquals("999", StatsLine.formatTokens(999));
        assertEquals("0", StatsLine.formatTokens(0));
    }

    @Test public void formatTokens_exactThousands_k() {
        assertEquals("1k", StatsLine.formatTokens(1000));
        assertEquals("900k", StatsLine.formatTokens(900000));
    }

    @Test public void formatTokens_large_roundedK() {
        assertEquals("100k", StatsLine.formatTokens(100000));
        assertEquals("131k", StatsLine.formatTokens(131072));
    }

    @Test public void formatTokens_middle_oneDecimal() {
        assertEquals("7.8k", StatsLine.formatTokens(7800));
    }

    @Test public void format_completeLine() {
        UsageTracker t = new UsageTracker();
        Usage u = new Usage();
        u.inputTokens = 1200;
        u.outputTokens = 345;
        u.reasoningTokens = 123;
        t.record(u);
        String line = StatsLine.format(t, 12300, 45000, 900000);
        assertEquals("⏱ 12.3s · in 1.2k · out 345 · thinking 123 · ctx 45k/900k (5%)", line);
    }

    @Test public void format_zeroMaxCtx_printsZeroPct() {
        String line = StatsLine.format(new UsageTracker(), 500, 0, 0);
        assertEquals("⏱ 0.5s · in 0 · out 0 · thinking 0 · ctx 0/0 (0%)", line);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=StatsLineTest`
Expected: 编译失败（StatsLine 不存在）

- [ ] **Step 3: 实现 StatsLine**

`src/main/java/com/minion/core/agent/StatsLine.java`：

```java
package com.minion.core.agent;

import com.minion.core.llm.UsageTracker;

import java.util.Locale;

/** 每轮结束的统计行格式化（GUI 展示；移植自已删除的 cli/StatsLine，去掉 CLI 的 * 前缀） */
public class StatsLine {

    public static String format(UsageTracker usage, long elapsedMillis,
                                int currentCtx, int maxCtx) {
        double secs = elapsedMillis / 1000.0;
        int pct = maxCtx > 0 ? (int) Math.round(currentCtx * 100.0 / maxCtx) : 0;
        return String.format(Locale.ROOT,
                "⏱ %.1fs · in %s · out %s · thinking %s · ctx %s/%s (%d%%)",
                secs,
                formatTokens(usage.sessionInput()),
                formatTokens(usage.sessionOutput()),
                formatTokens(usage.sessionThinking()),
                formatTokens(currentCtx), formatTokens(maxCtx), pct);
    }

    /** 1000 以下原样；整千 "900k"；10 万以上四舍五入到整 k（如 131072 → 131k）；其余 "7.8k" */
    public static String formatTokens(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n % 1000 == 0) return (n / 1000) + "k";
        if (n >= 100000) return Math.round(n / 1000.0) + "k";
        return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=StatsLineTest`
Expected: 6 个测试全 PASS

- [ ] **Step 5: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`feat: 新增 StatsLine 统计行工具类（每轮结束统计格式化）`），然后：

```bash
git add src/main/java/com/minion/core/agent/StatsLine.java src/test/java/com/minion/core/agent/StatsLineTest.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 2: AgentLoop 每轮结束发射统计行（TDD）

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`（runUserTurn 首尾）
- Modify: `src/test/java/com/minion/core/agent/RecordingUi.java`（加 statsLines 收集）
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`（新增 2 个测试）

**Interfaces:**
- Consumes: Task 1 的 `StatsLine.format(...)`；`TokenCounter.estimateMessages(List<Message>)`（com.minion.core.context）；`ContextManager.estimate(List<Message>)`、`ContextManager.maxTokens()`；AgentUi.onStatsLine（默认空实现已存在）
- Produces: `AgentUi.onStatsLine(String)` 每轮 runUserTurn 结束时恰好调用一次（正常/轮数上限/错误/中断/异常所有路径）

- [ ] **Step 1: 写失败测试**

先扩展 `src/test/java/com/minion/core/agent/RecordingUi.java`：字段区加一行、覆写区加一个方法：

```java
    public final List<String> statsLines = new ArrayList<String>();
```

```java
    @Override public synchronized void onStatsLine(String line) { statsLines.add(line); }
```

再在 `AgentLoopTest` 末尾（`FailingTool` 类之后、`}` 之前）加两个测试：

```java
    /** 需求 5：每轮结束发射统计行（正常路径） */
    @Test
    public void statsLine_emittedAfterTurn() {
        llm.addTurn("好的");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        assertEquals(1, ui.statsLines.size());
        String line = ui.statsLines.get(0);
        assertTrue(line.startsWith("⏱ "));
        assertTrue(line.contains("in 10"));   // FakeLlmClient: input 10
        assertTrue(line.contains("out 5"));   // FakeLlmClient: output 5
        assertTrue(line.contains("thinking 0"));
        assertTrue(line.contains("ctx "));
    }

    /** 需求 5：中断路径也发射统计行 */
    @Test
    public void statsLine_emittedOnInterrupt() throws Exception {
        BlockingLlmClient blocking = new BlockingLlmClient();
        blocking.addTurn("长回复");
        AgentLoop loop = new AgentLoop(blocking, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        Thread t = new Thread(() -> loop.runUserTurn("长任务"));
        t.start();
        assertTrue(blocking.entered.await(5, TimeUnit.SECONDS));
        loop.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(1, ui.statsLines.size());
        assertTrue(ui.statsLines.get(0).startsWith("⏱ "));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AgentLoopTest#statsLine_emittedAfterTurn+statsLine_emittedOnInterrupt`
Expected: 两个新测试 FAIL（statsLines 为空）

- [ ] **Step 3: 修改 AgentLoop**

`src/main/java/com/minion/core/agent/AgentLoop.java` 两处：

a) 加 import（放在现有 `import com.minion.core.context.ContextManager;` 之后）：

```java
import com.minion.core.context.TokenCounter;
```

b) `runUserTurn` 方法入口（`interrupted = false;` 之后）记录开始时间：

```java
    public void runUserTurn(String input) {
        interrupted = false;
        long start = System.currentTimeMillis(); // 统计行：轮次耗时
```

c) 方法末尾（现有 `persistSession();` 之后、方法结束 `}` 之前）追加发射：

```java
        // 所有退出路径的兜底落盘：正常结束 / 轮数上限 / 错误 / 中断 / 异常
        persistSession();
        // 每轮结束统计行（置于 scrubHalfTurn/persistSession 之后：中断路径的 ctx 估算是清洗半轮后的准确值）
        long elapsed = System.currentTimeMillis() - start;
        int currentCtx = contextManager != null
                ? contextManager.estimate(session.messages)
                : TokenCounter.estimateMessages(session.messages);
        int maxCtx = contextManager != null ? contextManager.maxTokens() : 0;
        ui.onStatsLine(StatsLine.format(session.usage, elapsed, currentCtx, maxCtx));
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AgentLoopTest`
Expected: 全 PASS（含新增 2 个）

- [ ] **Step 5: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`feat: 每轮回复结束发射 token 统计行（恢复 CLI 移除前的统计输出）`），然后：

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java src/test/java/com/minion/core/agent/RecordingUi.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 3: 自动滚动根因修复（AutoScrollPolicy TDD + ChatView/MainWindow 接线）

**Files:**
- Modify: `src/main/java/com/minion/gui/session/AutoScrollPolicy.java`（容差半屏、sync/onVmaxChanged/forceFollow）
- Modify: `src/test/java/com/minion/gui/session/AutoScrollPolicyTest.java`（onScroll→sync + 新增 4 测试）
- Modify: `src/main/java/com/minion/gui/chat/ChatView.java`（scrollBottomRequest 回调）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（policy 提为字段、vmax 监听重算、激活会话注入回调）

**Interfaces:**
- Consumes: 无（policy 纯逻辑；ChatView 由 MainWindow 构造后注入）
- Produces: `AutoScrollPolicy.sync(double vvalue, double vmax)`、`AutoScrollPolicy.onVmaxChanged(double vvalue, double prevVmax, double curVmax)`、`AutoScrollPolicy.forceFollow()`、`ChatView.setScrollBottomRequest(Runnable)`（Task 4/5 不依赖，仅本任务消费）

- [ ] **Step 1: 写失败测试**

整体替换 `src/test/java/com/minion/gui/session/AutoScrollPolicyTest.java`：

```java
package com.minion.gui.session;

import org.junit.Test;

import static org.junit.Assert.*;

/** 贴底判定：贴底/离开/半屏容差/forceFollow/vmax 增长保持跟随；初始视为贴底（内容未超一屏时 vvalue==vmax==0） */
public class AutoScrollPolicyTest {

    @Test
    public void initiallyPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        assertTrue(p.shouldFollow());
    }

    @Test
    public void atBottom_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(100.0, 100.0);
        assertTrue(p.shouldFollow());
    }

    @Test
    public void scrolledUp_isNotPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(100.0, 100.0);
        p.sync(50.0, 100.0);
        assertFalse(p.shouldFollow());
    }

    @Test
    public void withinHalfScreen_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(99.5, 100.0); // 距底恰好半屏（0.5 视口单位）→ 贴底
        assertTrue(p.shouldFollow());
    }

    @Test
    public void beyondHalfScreen_isNotPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(99.49, 100.0); // 距底超过半屏 → 离开
        assertFalse(p.shouldFollow());
    }

    @Test
    public void backToBottom_pinsAgain() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(50.0, 100.0);
        p.sync(99.9995, 100.0);
        assertTrue(p.shouldFollow());
    }

    @Test
    public void contentFits_noScroll_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.0, 0.0);
        assertTrue(p.shouldFollow());
    }

    /** 根因回归：贴底时内容增长（vvalue 停在旧 vmax、vmax 增大）→ 保持跟随 */
    @Test
    public void vmaxGrows_whilePinned_staysPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(100.0, 100.0);
        p.onVmaxChanged(100.0, 100.0, 100.6); // 增长超过半屏容差也必须跟随：增长前就在底部
        assertTrue(p.shouldFollow());
    }

    /** 根因回归：上翻离开底部后内容增长 → 不跟随（阅读历史不被拽回） */
    @Test
    public void vmaxGrows_afterScrolledUp_staysUnpinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(50.0, 100.0);
        p.onVmaxChanged(50.0, 100.0, 101.0);
        assertFalse(p.shouldFollow());
    }

    /** 用户发消息强制贴底：布局抖动短暂离开底部也不失效 */
    @Test
    public void forceFollow_restoresPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(50.0, 100.0);
        assertFalse(p.shouldFollow());
        p.forceFollow();
        assertTrue(p.shouldFollow());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AutoScrollPolicyTest`
Expected: 编译失败（sync/onVmaxChanged/forceFollow 不存在）

- [ ] **Step 3: 改写 AutoScrollPolicy**

整体替换 `src/main/java/com/minion/gui/session/AutoScrollPolicy.java`：

```java
package com.minion.gui.session;

/** 消息区自动滚动策略（纯逻辑）：贴底判定与内容增长跟随；离开底部即暂停，拖回底部恢复。
 *  贴底容差半屏（固定不随内容变长）；vvalue/vmax 任一变化都必须重算，
 *  否则流式增长中 vvalue 停在旧 vmax、vmax 继续增大时会误判"离开底部"→ 跟随永久失效 */
public class AutoScrollPolicy {

    /** 贴底阈值（vvalue 视口单位）：距底小于该值视为贴底（半屏容差） */
    private static final double EPSILON = 0.5;

    private boolean pinned = true; // 初始视为贴底：内容未超一屏时 vvalue==vmax==0

    /** 滚动位置变化时重算贴底状态（vvalue 监听器调用） */
    public void sync(double vvalue, double vmax) {
        pinned = vvalue >= vmax - EPSILON;
    }

    /** 内容高度变化（vmax: prev → cur）时重算（vmax 监听器调用）：
     *  增长前贴底（vvalue≈prev）视为仍贴底——内容增长跟随，且不受增长幅度影响 */
    public void onVmaxChanged(double vvalue, double prevVmax, double curVmax) {
        pinned = (vvalue >= curVmax - EPSILON) || (vvalue >= prevVmax - EPSILON);
    }

    /** 用户发消息时强制贴底：新一轮回复必然跟随 */
    public void forceFollow() {
        pinned = true;
    }

    /** 内容增长后是否应跟随滚动到底（贴底时 true） */
    public boolean shouldFollow() {
        return pinned;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AutoScrollPolicyTest`
Expected: 10 个测试全 PASS

- [ ] **Step 5: ChatView 加回调**

`src/main/java/com/minion/gui/chat/ChatView.java`：

a) 字段区（`pendingThinking` 之后）加：

```java
    /** 用户消息到达时的"滚动到底"回调（MainWindow 注入：强制贴底 + 布局完成后置底） */
    private Runnable scrollBottomRequest;

    /** MainWindow 注入：USER_MESSAGE 事件时请求滚动到底 */
    public void setScrollBottomRequest(Runnable r) { this.scrollBottomRequest = r; }
```

b) `onEventFx` 的 `USER_MESSAGE` 分支末尾（`pendingThinking.setLength(0);` 之后、`break;` 之前）加：

```java
                if (scrollBottomRequest != null) scrollBottomRequest.run(); // 发送消息后强制滚动到底
```

- [ ] **Step 6: MainWindow 接线**

`src/main/java/com/minion/gui/MainWindow.java` 三处：

a) 字段区（`chatScroll` 声明附近）加 policy 字段：

```java
    private final AutoScrollPolicy policy = new AutoScrollPolicy();
```

b) `setupAutoScroll()` 整体替换（去掉局部 policy 创建、vmax 监听补重算）：

```java
    /** 需求：消息区自动滚动——贴底时随新内容滚到底，离开底部即暂停，拖回底部恢复。
     *  vmax 变化经 onVmaxChanged 重算贴底（增长前贴底则保持跟随，根因修复）；
     *  内容增长后 runLater 延迟设置 vvalue，避免布局未完成时 setVvalue 被旧 vmax clamp 吞掉 */
    private void setupAutoScroll() {
        chatScroll.vvalueProperty().addListener((obs, ov, nv) ->
                policy.sync(nv.doubleValue(), chatScroll.getVmax()));
        chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
            policy.onVmaxChanged(chatScroll.getVvalue(), ov.doubleValue(), nv.doubleValue());
            if (policy.shouldFollow()) {
                // 执行时重读当前 vmax 并二次确认贴底：捕获监听时旧值会在内容继续增长时
                // 把 vvalue 卡在旧底部 < 新 vmax，被误判"离开底部"→ pinned 永不复原（失效根因）
                Platform.runLater(() -> {
                    if (policy.shouldFollow()) chatScroll.setVvalue(chatScroll.getVmax());
                });
            }
        });
    }
```

c) `onSessionActivated` 监听器里，`chatView = ChatView.forSession(h);` 之后加：

```java
                    chatView.setScrollBottomRequest(() -> {
                        policy.forceFollow();
                        Platform.runLater(() -> chatScroll.setVvalue(chatScroll.getVmax()));
                    });
```

- [ ] **Step 7: 全量测试与编译**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: 全 PASS（285 个既有 + 本轮新增）

- [ ] **Step 8: 手工验证**

`minion.bat` 启动：发送一条能产生长回复的消息（回复超过一屏），确认：发送后视图立即滚到底部、流式内容持续跟随；向上翻一屏以上后，新内容不再拽回；翻回距底半屏内，恢复跟随。

- [ ] **Step 9: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`fix: 自动滚动根因修复——vmax 变化重算贴底 + 半屏容差 + 发送强制置底`），然后：

```bash
git add src/main/java/com/minion/gui/session/AutoScrollPolicy.java src/test/java/com/minion/gui/session/AutoScrollPolicyTest.java src/main/java/com/minion/gui/chat/ChatView.java src/main/java/com/minion/gui/MainWindow.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 4: 分隔线去除 + 侧栏固定 1/4（MainWindow 布局）

**Files:**
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（SplitPane → GridPane）

**Interfaces:**
- Consumes: 无（纯布局替换；sidebar/right 节点不变）
- Produces: 无

- [ ] **Step 1: 替换布局代码**

`src/main/java/com/minion/gui/MainWindow.java`：

a) import 区删掉 `import javafx.scene.control.SplitPane;`，加：

```java
import javafx.scene.layout.ColumnConstraints;
```

b) `show()` 中替换：

```java
        SplitPane split = new SplitPane();
        split.setDividerPositions(0.25); // 需求 5：左右比例 1:3
        split.getItems().addAll(sidebar, right);
        root.setCenter(split);
```

为：

```java
        // 需求：左右无分隔线、不可拖拽，侧栏严格占整体 1/4（GridPane 百分比列随窗口缩放）
        GridPane center = new GridPane();
        ColumnConstraints leftCol = new ColumnConstraints();
        leftCol.setPercentWidth(25);
        ColumnConstraints rightCol = new ColumnConstraints();
        rightCol.setPercentWidth(75);
        center.getColumnConstraints().addAll(leftCol, rightCol);
        center.add(sidebar, 0, 0);
        center.add(right, 1, 0);
        root.setCenter(center);
```

（`GridPane` 已 import——onNewWorkspace 弹窗在用；sidebar 的 `setMinWidth(200)` 保留不动）

- [ ] **Step 2: 编译 + 全量测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: 编译过、全 PASS

- [ ] **Step 3: 手工验证**

`minion.bat` 启动：左右之间无白色粗线；鼠标在交界处无拖拽光标、不可拖动；拉伸窗口时侧栏始终占宽 1/4；最小窗口（960px）下侧栏约 240px 正常显示。

- [ ] **Step 4: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`fix: 去除左右分隔线——SplitPane 改 GridPane 固定 25%/75%，不可拖拽`），然后：

```bash
git add src/main/java/com/minion/gui/MainWindow.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 5: 设置窗「应用」按钮（BasicPane 重构 + 按钮栏接线）

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`

**Interfaces:**
- Consumes: 无
- Produces: 内部类 `SettingsDialog.BasicPane`（`final Node root`、`void apply()`）——Task 6 在其构造器内改 browser.path 行

- [ ] **Step 1: 重构 basicPane 为内部类**

`src/main/java/com/minion/gui/dialog/SettingsDialog.java`：

a) import 区加：

```java
import javafx.scene.control.ButtonBar;
```

b) 把 `// ===== 基础设置页 =====` 之后的整个 `basicPane(...)` 静态方法替换为：

```java
    // ===== 基础设置页 =====

    /** 基础设置页：控件 + 保存逻辑（按钮栏「应用」接线用） */
    private static class BasicPane {
        final Node root;
        private final Config config;
        private final TextField skillsDir;
        private final TextArea toolWhitelist;
        private final TextArea cmdWhitelist;
        private final CheckBox allowOutside;
        private final CheckBox skipConfirm;
        private final TextField browserPath;
        private final TextField browserPort;
        private final TextField browserUserData;
        private final CheckBox browserHeadless;
        private final TextField browserTimeout;

        BasicPane(final Config config, final Window owner) {
            this.config = config;
            HBox skillsBox = new HBox(6);
            skillsDir = new TextField(config.skillsDir());
            HBox.setHgrow(skillsDir, Priority.ALWAYS);
            Button browse = new Button("浏览…");
            browse.getStyleClass().add("btn-ghost");
            browse.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                String cur = skillsDir.getText().trim();
                if (!cur.isEmpty()) {
                    java.io.File f = new java.io.File(cur);
                    if (f.isDirectory()) dc.setInitialDirectory(f);
                }
                java.io.File dir = dc.showDialog(owner);
                if (dir != null) skillsDir.setText(dir.getAbsolutePath());
            });
            skillsBox.getChildren().addAll(skillsDir, browse);
            toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
            toolWhitelist.setPrefRowCount(2);
            toolWhitelist.setPrefColumnCount(20); // 默认 40 列偏好宽 ≈624px 把基础页撑到 794，触发 HBox 压缩导航列；20 列后偏好宽 ~500 与内容区匹配
            cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
            cmdWhitelist.setPrefRowCount(2);
            cmdWhitelist.setPrefColumnCount(20);
            allowOutside = new CheckBox("允许读取工作区外文件（Read/Grep/Glob）");
            allowOutside.setSelected(config.readAllowOutside());
            skipConfirm = new CheckBox("跳过高危操作确认");
            skipConfirm.setSelected(config.confirmSkip());
            Label browserNote = new Label("浏览器配置（以下项需重启后生效）");
            browserNote.getStyleClass().add("msg-thinking");
            browserPath = new TextField(config.browserPath());
            browserPort = new TextField(String.valueOf(config.browserPort()));
            browserUserData = new TextField(config.browserUserDataDir());
            browserHeadless = new CheckBox("无头模式");
            browserHeadless.setSelected(config.browserHeadless());
            browserTimeout = new TextField(String.valueOf(config.browserTimeoutMs()));

            VBox rows = new VBox(10);
            rows.getChildren().addAll(
                    row("技能目录 skills.dir:", skillsBox),
                    row("确认白名单\n(工具, 逗号分隔):", toolWhitelist),
                    row("确认白名单\n(命令, 逗号分隔):", cmdWhitelist),
                    row("读逃逸:", allowOutside),
                    row("确认开关:", skipConfirm),
                    browserNote,
                    row("browser.path:", browserPath),
                    row("browser.port:", browserPort),
                    row("browser.userDataDir:", browserUserData),
                    row("browser.headless:", browserHeadless),
                    row("browser.timeoutMs:", browserTimeout));

            VBox contentBox = new VBox(10);
            contentBox.getChildren().addAll(rows);
            contentBox.setPadding(new Insets(12));
            ScrollPane sp = new ScrollPane(contentBox); // 窗口小时可滚动，选项不再被裁剪
            sp.setFitToWidth(true);
            this.root = sp;
        }

        /** 「应用」按钮：全部配置项写入，窗口不关闭；port/timeoutMs 非法弹错且该项不写 */
        void apply() {
            config.set("skills.dir", skillsDir.getText().trim());
            // 白名单是逗号分隔的单行配置：多行粘贴的换行替换为空格，否则落盘后重载会静默丢内容
            config.set("confirm.whitelist.tools",
                    toolWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
            config.set("confirm.whitelist.commands",
                    cmdWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
            config.set("paths.read.allowOutside", String.valueOf(allowOutside.isSelected()));
            config.set("confirm.skip", String.valueOf(skipConfirm.isSelected()));
            config.set("browser.path", browserPath.getText().trim());
            if (!setInt("browser.port", browserPort.getText(), config)) {
                error("保存失败", "browser.port 必须是整数，未保存");
            }
            config.set("browser.userDataDir", browserUserData.getText().trim());
            config.set("browser.headless", String.valueOf(browserHeadless.isSelected()));
            if (!setInt("browser.timeoutMs", browserTimeout.getText(), config)) {
                error("保存失败", "browser.timeoutMs 必须是整数，未保存");
            }
        }
    }
```

（`row(...)`、`setInt(...)`、`error(...)` 均为 SettingsDialog 既有静态方法，内部类可直接调用，保留不动）

- [ ] **Step 2: show() 接线**

`show()` 方法两处：

a) 替换：

```java
        final Node basic = basicPane(config, owner);
```

为：

```java
        final BasicPane basic = new BasicPane(config, owner);
```

并把内容切换处的 `basic` 改为 `basic.root`：

```java
            content.getChildren().setAll("基础设置".equals(item) ? basic.root
                    : "模型".equals(item) ? model : about);
```

b) 替换：

```java
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
```

为：

```java
        // 按钮栏从左到右「应用」「关闭」：APPLY 排在 CANCEL_CLOSE 之前；应用=保存不关窗，关闭=放弃修改关窗
        ButtonType applyType = new ButtonType("应用", ButtonBar.ButtonData.APPLY);
        d.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CLOSE);
        ((Button) d.getDialogPane().lookupButton(applyType)).setOnAction(e -> basic.apply());
```

- [ ] **Step 3: 编译 + 全量测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: 编译过、全 PASS（SettingsDialog 无单测，靠编译 + 手工验证）

- [ ] **Step 4: 手工验证**

`minion.bat` → ⚙ 设置：按钮栏从左到右为「应用」「关闭」；修改 skills.dir 点「应用」窗口不关闭、config.properties 已写入；browser.port 填 abc 点「应用」弹「保存失败」且该项未写；点「关闭」不写未应用修改。

- [ ] **Step 5: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`feat: 设置窗基础设置改按钮栏「应用」按钮（保存不关窗，位于关闭左侧）`），然后：

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 6: 浏览器路径文件选择（browser.path 浏览按钮）

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`（BasicPane 构造器内 browser.path 行）

**Interfaces:**
- Consumes: Task 5 的 `BasicPane`（owner 参数已存在）
- Produces: 无

- [ ] **Step 1: 改造 browser.path 行**

`BasicPane` 构造器内，替换：

```java
            browserPath = new TextField(config.browserPath());
```

为：

```java
            browserPath = new TextField(config.browserPath());
            HBox browserPathBox = new HBox(6);
            HBox.setHgrow(browserPath, Priority.ALWAYS);
            Button browseExe = new Button("浏览…");
            browseExe.getStyleClass().add("btn-ghost");
            browseExe.setOnAction(e -> {
                javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                fc.setTitle("选择浏览器程序");
                fc.getExtensionFilters().addAll(
                        new javafx.stage.FileChooser.ExtensionFilter("可执行文件", "*.exe"),
                        new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*"));
                // 当前值若是存在的文件，初始定位到其父目录
                String cur = browserPath.getText().trim();
                java.io.File f = new java.io.File(cur);
                if (f.isFile() && f.getParentFile() != null && f.getParentFile().isDirectory()) {
                    fc.setInitialDirectory(f.getParentFile());
                }
                java.io.File file = fc.showOpenDialog(owner);
                if (file != null) browserPath.setText(file.getAbsolutePath());
            });
            browserPathBox.getChildren().addAll(browserPath, browseExe);
```

并把 rows 中的：

```java
                    row("browser.path:", browserPath),
```

改为：

```java
                    row("browser.path:", browserPathBox),
```

- [ ] **Step 2: 编译 + 全量测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: 编译过、全 PASS

- [ ] **Step 3: 手工验证**

`minion.bat` → ⚙ 设置：browser.path 行点「浏览…」弹出文件选择器（过滤 *.exe），选定 chrome.exe 后填入路径；当前值已是存在的文件时选择器初始定位其父目录；点「应用」保存。

- [ ] **Step 4: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`feat: 基础设置 browser.path 支持文件选择器浏览选取`），然后：

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 7: 会话列表横向滚动条消除（省略号截断）

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/SessionListView.java`（SessionCell.updateItem）

**Interfaces:**
- Consumes: 无
- Produces: 无

- [ ] **Step 1: 修改 SessionCell.updateItem**

`src/main/java/com/minion/gui/sidebar/SessionListView.java`：

a) import 区加：

```java
import javafx.scene.control.OverrunStyle;
```

b) `SessionCell.updateItem` 中，标题 Label 创建处（`name.getStyleClass().add("cell-text");` 之后）加：

```java
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            name.setMinWidth(0); // 允许收缩至省略号：长标题不再撑出横向滚动条
```

c) 摘要 Label 创建处（`sum.getStyleClass().add("section-title");` 之后）加：

```java
                sum.setTextOverrun(OverrunStyle.ELLIPSIS);
                sum.setMinWidth(0);
```

d) `cellBox` 创建处（`VBox cellBox = new VBox(2);` 之后）加：

```java
            cellBox.maxWidthProperty().bind(widthProperty().subtract(4)); // 绑定 cell 宽：内容超出即截断，根除横向滚动条
```

- [ ] **Step 2: 编译 + 全量测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: 编译过、全 PASS

- [ ] **Step 3: 手工验证**

`minion.bat`：重命名一个会话为超长标题、发送一条超长消息（摘要超长），确认列表显示省略号截断、无横向滚动条；纵向滚动正常；悬停时 ✎/✕ 按钮可见可点、不被截断。

- [ ] **Step 4: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`fix: 会话列表长标题/摘要省略号截断，消除横向滚动条`），然后：

```bash
git add src/main/java/com/minion/gui/sidebar/SessionListView.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 8: 文档同步 + 全量验收

**Files:**
- Modify: `README.md`（配置说明 + 快捷操作）
- Modify: `docs/ARCHITECTURE.md`（受影响类的职责描述）

- [ ] **Step 1: 更新 README.md**

a) 第 20 行 config.properties 行末尾（"skills.dir 可用目录选择器浏览选取"之后）追加：

```
；browser.path 可用文件选择器浏览选取
```

b) 第 25 行设置窗条目末尾追加：

```
；基础设置页底部按钮栏「应用」（保存不关窗）与「关闭」
```

c) 第 27 行替换为：

```
- 消息区发送消息自动滚到底部并随新内容自动滚动（距底半屏内持续跟随），上翻离开底部暂停；拖回底部恢复
```

d) 快捷操作列表追加一条（放在滚动条目之后）：

```
- 每轮回复结束显示 token 统计行（⏱ 耗时 · in/out/thinking 会话累计 · ctx 上下文占比）
```

- [ ] **Step 2: 更新 docs/ARCHITECTURE.md**

a) `gui/` 表 MainWindow 行："SplitPane 1:3（左侧会话/工作空间，右侧消息区+输入区）" → "GridPane 25%/75% 固定比例（无分隔线、不可拖拽，左侧会话/工作空间，右侧消息区+输入区）"

b) sidebar/SessionListView、WorkspaceListView 行末尾追加："；会话项长标题/摘要省略号截断（无横向滚动条）"

c) dialog/SettingsDialog、ConfirmDialog 行末尾追加："；基础页按钮栏「应用」（保存不关窗）与 browser.path 文件浏览"

d) AutoScrollPolicy 行整体替换为：

```
| session/AutoScrollPolicy | 消息区自动滚动贴底策略（纯逻辑，无 JavaFX 依赖）：sync(vvalue,vmax) 滚动位置变化重算贴底（距底半屏容差），onVmaxChanged(vvalue,prev,cur) 内容增长重算（增长前贴底则保持跟随，根因修复），forceFollow() 用户发消息强制贴底；MainWindow vvalue/vmax 双监听配合，vmax 变化后 runLater 内重读 getVmax() 并二次确认贴底 |
```

e) `core/agent/` 表 AgentLoop 行末尾追加："；每轮结束经 ui.onStatsLine 发射统计行（StatsLine 格式化，正常/错误/中断路径均发射）"，并在表末新增一行：

```
| StatsLine | 统计行格式化：⏱ 耗时 · in/out/thinking（UsageTracker 会话累计）· ctx 上下文占比；formatTokens 缩写（<1000 原样/整千 "900k"/≥10 万整 k/其余 "7.8k"） |
```

- [ ] **Step 3: 全量构建与测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn clean package`
Expected: BUILD SUCCESS，产物 target/minion-0.1.0.jar

- [ ] **Step 4: 手工验收清单（全部过才算完成）**

`minion.bat` 启动，逐项过设计文档「手工验证清单」：

1. 设置窗：底部按钮栏从左到右「应用」「关闭」；修改任意项点"应用"后窗口不关闭，重启后生效；port 填非法值点"应用"弹错且该项未写入。
2. 会话列表：长标题/长摘要会话截断显示省略号，无横向滚动条；纵向滚动正常；悬停按钮（✎/✕）仍可见可点。
3. 基础设置 browser.path 行点「浏览…」弹出文件选择器（exe 过滤），选定填入；当前值有效时初始定位父目录。
4. 左右无分隔线；窗口缩放时侧栏始终占 1/4；侧栏与消息区之间不可拖拽。
5. 发送一条消息，回复结束后消息区末尾出现 `⏱ x.xs · in … · out … · thinking … · ctx …`；工具轮次/中断也有该行；切换历史会话不出现统计行。
6. 长对话：发送后自动滚到底并随流式内容持续跟随；向上翻半屏以上后回复不再拽回；翻回底半屏内恢复跟随。

- [ ] **Step 5: Commit**

用 Write 工具写 `.git/COMMIT_MSG.txt`（内容：`docs: 同步 UI 修复第四轮说明（应用按钮/滚动条/统计行/滚动策略/布局）`），然后：

```bash
git add README.md docs/ARCHITECTURE.md && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```
