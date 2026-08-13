# UI 修复与增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 minion GUI 的 10 项问题（会话切换/历史渲染/消息颜色/设置窗/拖拽排序/自动滚动/页签溢出/悬停按钮/消息时间/project.md 浏览）。

**Architecture:** 纯逻辑改动（Message 时间戳、历史消息灌入事件流、自动滚动策略、工作空间排序、时间格式化）先行并带单测；JavaFX 界面改动（MainWindow 页签激活/补齐、设置窗、侧栏交互、CSS）逐文件实施，用 GUI 手工验证清单验收。历史渲染走"恢复时灌入 EventList"单一路径；时间戳走 Message.ts 字段（向后兼容）；自动滚动抽 AutoScrollPolicy 纯逻辑类。

**Tech Stack:** JDK 8、JavaFX 8（JDK 自带 jfxrt）、gson、junit4。构建：`JAVA_HOME="E:/javame/jdk8" mvn clean package`。启动 GUI：`minion.bat`。

## Global Constraints

- JDK 8 兼容；不新增依赖。
- 所有 commit 用中文、conventional 格式。
- Message 落盘 JSON 向后兼容：新字段默认值语义不破坏旧文件加载。
- 历史会话渲染只重演对话内容（USER/ASSISTANT），不重演工具过程。
- 会话列表时间/摘要在 `refresh()` 时更新，运行中不实时刷新（与现状一致）。
- 工作空间拖拽排序不得清空右侧聊天区（不得触发 `notifyWorkspaceChanged` 的 `clearChatPane`）。
- 设计文档：docs/superpowers/specs/2026-08-13-ui-fixes-enhancements-design.md（任务涉及的行为偏差需同步该文档）。

---

### Task 1: Message.ts 消息创建时间戳

**Files:**
- Modify: `src/main/java/com/minion/core/llm/Message.java`（工厂方法 4 处）
- Test: `src/test/java/com/minion/core/llm/MessageTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `Message.ts`（`public long`，毫秒，默认 0）；工厂 `Message.user/assistant/toolResult/system` 创建时打点。Task 3（TimeFormatter）与 Task 11（SessionCell）依赖。

- [ ] **Step 1: 写失败测试**（追加到 MessageTest）

```java
/** 消息创建时间戳：工厂打点 ts>0；默认 0（旧数据兼容） */
@Test
public void factory_stampsCreationTimestamp() {
    assertTrue(Message.user("u").ts > 0);
    assertTrue(Message.assistant("a").ts > 0);
    assertTrue(Message.toolResult("tc", "ReadTool", "ok").ts > 0);
    assertTrue(Message.system("s").ts > 0);
    Message plain = new Message();
    assertEquals(0L, plain.ts);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=MessageTest`
Expected: FAIL（`plain.ts` 编译失败——字段不存在）。

- [ ] **Step 3: 实现**

`Message.java` 字段区（`summary` 行后）加：

```java
/** 消息创建时间戳（毫秒；0 = 旧数据未打点） */
public long ts;
```

四个工厂方法 `m` 创建后加：`m.ts = System.currentTimeMillis();`（user/assistant/toolResult/system 各一处，共 4 处）。

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=MessageTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/llm/Message.java src/test/java/com/minion/core/llm/MessageTest.java
git commit -m "feat: Message 增加创建时间戳 ts（工厂打点，旧数据兼容）"
```

---

### Task 2: 历史消息灌入事件流

**Files:**
- Modify: `src/main/java/com/minion/gui/session/SessionController.java`（加 `replayHistory`）
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java:161-168`（restoreSessions 调用）
- Create: `src/test/java/com/minion/gui/session/SessionControllerTest.java`
- Modify: `src/test/java/com/minion/gui/session/SessionManagerTest.java`（加恢复集成测试）

**Interfaces:**
- Consumes: `Message`（role/content/ts）、`EventList`（Kind/Ev/add/snapshot）、`SessionStore.save`。
- Produces: `SessionController.replayHistory(List<Message>)`——把 USER→`USER_MESSAGE`、ASSISTANT（content 非空）→`CONTENT` 灌入 events，SYSTEM/TOOL/toolCalls/role==null 跳过。Task 6/11 不直接依赖，但页签激活后 ChatView.bind 重放依赖此数据。

- [ ] **Step 1: 写失败测试**

Create `SessionControllerTest.java`:

```java
package com.minion.gui.session;

import com.minion.core.llm.Message;
import com.minion.gui.session.EventList.Ev;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 历史消息重演：USER→USER_MESSAGE、ASSISTANT content→CONTENT，工具过程跳过 */
public class SessionControllerTest {

    @Test
    public void replayHistory_convertsUserAndAssistant() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("你好"));
        msgs.add(Message.assistant("你好，我是助手"));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.USER_MESSAGE, evs.get(0).kind);
        assertEquals("你好", evs.get(0).text);
        assertEquals(EventList.Kind.CONTENT, evs.get(1).kind);
        assertEquals("你好，我是助手", evs.get(1).text);
    }

    /** 工具消息/系统消息/空 content/assistant 工具调用 全部跳过 */
    @Test
    public void replayHistory_skipsToolAndSystemAndEmpty() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("system prompt"));
        msgs.add(Message.toolResult("tc1", "ReadTool", "file content"));
        Message withCall = Message.assistant(null);
        withCall.toolCalls = new ArrayList<Message.ToolCall>(); // 仅工具调用无 content
        msgs.add(withCall);
        msgs.add(Message.assistant(""));
        c.replayHistory(msgs);
        assertEquals(0, c.eventList().size());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionControllerTest`
Expected: FAIL（编译错误：`replayHistory` 不存在）。

- [ ] **Step 3: 实现**

`SessionController.java` 加方法（import `java.util.List` 与 `com.minion.core.llm.Message`）：

```java
/** 恢复会话时把历史消息灌入事件流：USER→USER_MESSAGE、ASSISTANT(content 非空)→CONTENT；
 *  SYSTEM/TOOL/纯工具调用消息跳过——历史只重演对话内容，不重演工具过程 */
public void replayHistory(List<Message> messages) {
    for (Message m : messages) {
        if (m == null || m.role == null) continue;
        if (m.role == Message.Role.USER) {
            events.add(new EventList.Ev(EventList.Kind.USER_MESSAGE, m.content, null));
        } else if (m.role == Message.Role.ASSISTANT
                && m.content != null && !m.content.trim().isEmpty()) {
            events.add(new EventList.Ev(EventList.Kind.CONTENT, m.content, null));
        }
    }
}
```

`SessionManager.restoreSessions` 中构造 SessionHandle 前加一行（在 `SessionController controller = new SessionController();` 之后）：

```java
controller.replayHistory(s.messages); // 历史消息灌入事件流：点击会话即可重放显示
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionControllerTest,SessionManagerTest`
Expected: PASS。

- [ ] **Step 5: 恢复路径集成测试**（追加到 SessionManagerTest）

```java
/** 恢复会话：历史消息灌入 EventList（点击即可重放显示；TOOL 跳过） */
@Test
public void restore_replaysHistoryIntoEventList() throws Exception {
    Path jar = tmp.newFolder("jar").toPath();
    Config config = Config.load(jar);
    WorkspaceManager ws = WorkspaceManager.load(jar);
    Session s = Session.create(".", "deepseek");
    s.title = "历史会话";
    s.messages.add(Message.user("你好"));
    s.messages.add(Message.assistant("你好，我是助手"));
    s.messages.add(Message.toolResult("tc1", "ReadTool", "file content"));
    Path sdir = WorkspaceManager.sessionDirFor(jar, "default");
    Files.createDirectories(sdir);
    new SessionStore(sdir).save(s);
    SessionManager m = new SessionManager(FAKE_UI, config, jar, ws,
            ModelManager.load(jar), new ArrayList<Skill>(), null);
    assertEquals(1, m.sessions().size());
    SessionHandle h = m.sessions().get(0);
    List<EventList.Ev> evs = h.controller.eventList().snapshot();
    assertEquals(2, evs.size());
    assertEquals(EventList.Kind.USER_MESSAGE, evs.get(0).kind);
    assertEquals(EventList.Kind.CONTENT, evs.get(1).kind);
}
```

需要 import：`com.minion.core.llm.Message`、`com.minion.core.storage.SessionStore`、`java.nio.file.Files`、`java.nio.file.Path`（均可能已存在，检查后补充）。

- [ ] **Step 6: 运行集成测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionManagerTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/minion/gui/session/SessionController.java src/main/java/com/minion/gui/session/SessionManager.java src/test/java/com/minion/gui/session/
git commit -m "feat: 恢复会话时历史消息灌入事件流（replayHistory），点击会话即可重放显示历史"
```

---

### Task 3: TimeFormatter 消息时间格式化

**Files:**
- Create: `src/main/java/com/minion/gui/sidebar/TimeFormatter.java`
- Create: `src/test/java/com/minion/gui/sidebar/TimeFormatterTest.java`

**Interfaces:**
- Consumes: `Message.ts`（Task 1）。
- Produces: `TimeFormatter.format(long ts, long now)` → `String` 或 null（ts<=0）。规则：`<1min→"1m"`；`<60min→"Nm"`（向下取整）；`<24h→"Nh"`；`≥24h→"Nd"`；ts<=0→null。Task 11 依赖。

- [ ] **Step 1: 写失败测试**

```java
package com.minion.gui.sidebar;

import org.junit.Test;

import static org.junit.Assert.*;

/** 消息时间显示规则：1m/5m/3h/2d；旧数据 ts<=0 不显示 */
public class TimeFormatterTest {

    private static final long NOW = 1_000_000_000_000L;

    @Test
    public void format_underOneMinuteShows1m() {
        assertEquals("1m", TimeFormatter.format(NOW - 30_000L, NOW));
        assertEquals("1m", TimeFormatter.format(NOW, NOW));
    }

    @Test
    public void format_minutesFloor() {
        assertEquals("5m", TimeFormatter.format(NOW - 5 * 60_000L - 30_000L, NOW));
        assertEquals("59m", TimeFormatter.format(NOW - 59 * 60_000L, NOW));
    }

    @Test
    public void format_hoursFloor() {
        assertEquals("1h", TimeFormatter.format(NOW - 3_600_000L, NOW));
        assertEquals("3h", TimeFormatter.format(NOW - 3 * 3_600_000L - 59 * 60_000L, NOW));
        assertEquals("23h", TimeFormatter.format(NOW - 23 * 3_600_000L, NOW));
    }

    @Test
    public void format_daysFloor() {
        assertEquals("1d", TimeFormatter.format(NOW - 24 * 3_600_000L, NOW));
        assertEquals("2d", TimeFormatter.format(NOW - 2 * 86_400_000L - 1L, NOW));
    }

    @Test
    public void format_oldDataReturnsNull() {
        assertNull(TimeFormatter.format(0L, NOW));
        assertNull(TimeFormatter.format(-1L, NOW));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=TimeFormatterTest`
Expected: FAIL（编译错误：类不存在）。

- [ ] **Step 3: 实现**

```java
package com.minion.gui.sidebar;

/** 会话列表时间显示：消息创建时间 → 相对距离（5m/3h/2d）；ts<=0（旧数据）→ null 不显示 */
public class TimeFormatter {

    public static String format(long ts, long now) {
        if (ts <= 0) return null;
        long diff = Math.max(0L, now - ts);
        if (diff < 60_000L) return "1m";
        long minutes = diff / 60_000L;
        if (minutes < 60) return minutes + "m";
        long hours = diff / 3_600_000L;
        if (hours < 24) return hours + "h";
        return (diff / 86_400_000L) + "d";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=TimeFormatterTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/sidebar/TimeFormatter.java src/test/java/com/minion/gui/sidebar/TimeFormatterTest.java
git commit -m "feat: 会话列表消息时间格式化（1m/5m/3h/2d，旧数据不显示）"
```

---

### Task 4: AutoScrollPolicy 自动滚动策略

**Files:**
- Create: `src/main/java/com/minion/gui/session/AutoScrollPolicy.java`
- Create: `src/test/java/com/minion/gui/session/AutoScrollPolicyTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `AutoScrollPolicy.onScroll(double vvalue, double vmax)`、`boolean shouldFollow()`。Task 6（MainWindow.setupAutoScroll）依赖。

- [ ] **Step 1: 写失败测试**

```java
package com.minion.gui.session;

import org.junit.Test;

import static org.junit.Assert.*;

/** 贴底判定：贴底/离开/回到底部；初始视为贴底（内容未超一屏时 vvalue==vmax==0） */
public class AutoScrollPolicyTest {

    @Test
    public void initiallyPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        assertTrue(p.shouldFollow());
    }

    @Test
    public void atBottom_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(100.0, 100.0);
        assertTrue(p.shouldFollow());
    }

    @Test
    public void scrolledUp_isNotPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(100.0, 100.0);
        p.onScroll(50.0, 100.0);
        assertFalse(p.shouldFollow());
    }

    @Test
    public void backToBottom_pinsAgain() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(50.0, 100.0);
        p.onScroll(99.9995, 100.0); // 距底 0.0005（视口单位）→ 视为贴底
        assertTrue(p.shouldFollow());
    }

    @Test
    public void contentFits_noScroll_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(0.0, 0.0);
        assertTrue(p.shouldFollow());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AutoScrollPolicyTest`
Expected: FAIL（编译错误：类不存在）。

- [ ] **Step 3: 实现**

```java
package com.minion.gui.session;

/** 消息区自动滚动策略（纯逻辑）：贴底判定与内容增长跟随；离开底部即暂停，拖回底部恢复 */
public class AutoScrollPolicy {

    /** 贴底阈值（vvalue 视口单位）：距底小于该值视为贴底 */
    private static final double EPSILON = 0.001;

    private boolean pinned = true; // 初始视为贴底：内容未超一屏时 vvalue==vmax==0

    /** 滚动位置变化时更新贴底状态 */
    public void onScroll(double vvalue, double vmax) {
        pinned = vvalue >= vmax - EPSILON;
    }

    /** 内容增长后是否应跟随滚动到底（贴底时 true） */
    public boolean shouldFollow() {
        return pinned;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AutoScrollPolicyTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/session/AutoScrollPolicy.java src/test/java/com/minion/gui/session/AutoScrollPolicyTest.java
git commit -m "feat: AutoScrollPolicy 贴底跟随策略（纯逻辑可单测）"
```

---

### Task 5: 工作空间排序（WorkspaceManager.move + SessionManager.moveWorkspace）

**Files:**
- Modify: `src/main/java/com/minion/core/config/WorkspaceManager.java`（加 `move`）
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（加 `moveWorkspace`）
- Modify: `src/test/java/com/minion/core/config/WorkspaceManagerTest.java`
- Modify: `src/test/java/com/minion/gui/session/SessionManagerTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `WorkspaceManager.move(String name, int newIndex)` → boolean（越界/不存在 false；位置不变 true 不落盘）；`SessionManager.moveWorkspace(String name, int newIndex)` → boolean（只转发，**不发通知**）。Task 10（WorkspaceListView 拖拽）依赖。

- [ ] **Step 1: 写失败测试**（追加到 WorkspaceManagerTest）

```java
/** 移动顺序：列表重排 + 落盘持久化 */
@Test
public void move_reordersAndPersists() throws IOException {
    Path dir = jarDir();
    WorkspaceManager m = WorkspaceManager.load(dir);
    m.add("projA", "d:/a", "");
    m.add("projB", "d:/b", "");
    assertTrue(m.move("default", 2)); // 移到末尾
    assertEquals("projA", m.list().get(0).workSpaceName);
    assertEquals("projB", m.list().get(1).workSpaceName);
    assertEquals("default", m.list().get(2).workSpaceName);
    WorkspaceManager m2 = WorkspaceManager.load(dir);
    assertEquals("projA", m2.list().get(0).workSpaceName);
    assertEquals("default", m2.list().get(2).workSpaceName);
}

/** 越界/不存在返回 false；位置不变返回 true 且列表不变 */
@Test
public void move_rejectsInvalidIndexAndMissingName() throws IOException {
    WorkspaceManager m = WorkspaceManager.load(jarDir());
    m.add("projA", "d:/a", "");
    assertFalse(m.move("nope", 0));     // 不存在
    assertFalse(m.move("projA", -1));   // 越界
    assertFalse(m.move("projA", 3));    // 越界
    assertTrue(m.move("projA", 1));     // 同位置
    assertEquals(2, m.list().size());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=WorkspaceManagerTest`
Expected: FAIL（编译错误：`move` 不存在）。

- [ ] **Step 3: 实现**

`WorkspaceManager.java`（`setCurrent` 方法后加）：

```java
/** 移动工作空间顺序（UI 拖拽排序）；名字不存在或索引越界返回 false；位置不变视为成功不落盘 */
public boolean move(String name, int newIndex) {
    WorkspaceConfig w = get(name);
    if (w == null) return false;
    if (newIndex < 0 || newIndex >= workspaces.size()) return false;
    int from = workspaces.indexOf(w);
    if (from == newIndex) return true;
    workspaces.remove(from);
    workspaces.add(newIndex, w);
    save();
    return true;
}
```

`SessionManager.java`（`updateWorkspace` 方法后加）：

```java
/**
 * 工作空间拖拽排序：转发 WorkspaceManager（不发通知——notifyWorkspaceChanged 会触发
 * MainWindow 的 clearChatPane 清空右侧聊天区，拖拽排序不应清内容；UI 侧 drop 后自行 refresh）。
 */
public boolean moveWorkspace(String name, int newIndex) {
    return workspaces.move(name, newIndex);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=WorkspaceManagerTest,SessionManagerTest`
Expected: PASS。

- [ ] **Step 5: 转发测试**（追加到 SessionManagerTest）

```java
/** 工作空间排序转发：顺序变化可查；不发通知（拖拽排序不清空右侧） */
@Test
public void moveWorkspace_reordersWithoutNotify() throws Exception {
    SessionManager m = newManager();
    m.addWorkspace("projA", "d:/a", "");
    final int[] notified = new int[] { 0 };
    m.addListener(new SessionManager.Listener() {
        @Override public void onSessionTitleChanged(SessionHandle h) { }
        @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
        @Override public void onSessionActivated(SessionHandle h) { }
        @Override public void onWorkspaceChanged() { notified[0]++; }
        @Override public void onError(String message) { fail("不应有错误: " + message); }
    });
    assertTrue(m.moveWorkspace("projA", 0));
    assertEquals("projA", m.workspaces().list().get(0).workSpaceName);
    assertEquals(0, notified[0]); // 不触发 onWorkspaceChanged
    assertFalse(m.moveWorkspace("nope", 0));
}
```

注意：现有 `moveWorkspace_reordersWithoutNotify` 中 listener 匿名类需实现全部 5 个方法——与现有测试的 Listener 写法一致。

- [ ] **Step 6: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionManagerTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/minion/core/config/WorkspaceManager.java src/main/java/com/minion/gui/session/SessionManager.java src/test/java/com/minion/core/config/WorkspaceManagerTest.java src/test/java/com/minion/gui/session/SessionManagerTest.java
git commit -m "feat: 工作空间排序 move/moveWorkspace（拖拽排序持久化，不发清空通知）"
```

---

### Task 6: MainWindow 会话页签激活/补齐与自动滚动

**Files:**
- Modify: `src/main/java/com/minion/gui/MainWindow.java`
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java:307-314`（activateSession 幂等 guard）

**Interfaces:**
- Consumes: `AutoScrollPolicy`（Task 4）、`SessionManager.activateSession/currentSession/sessions`。
- Produces: 无新接口。行为：页签选中→激活会话；启动补齐历史页签（不自动激活）；流式贴底自动跟随。

- [ ] **Step 1: 页签选中激活 + 启动补齐**

`MainWindow.java`：

a) 类字段加：`private boolean suppressingTabSelect; // rebuildTabs 期间不触发页签选中激活`

b) `show()` 中 `tabs` 创建后加选中监听（在 `new TitleBar(...)` 之前）：

```java
// 需求：标题栏页签点击激活对应会话（userData 存会话 id；删除竞态找不到则忽略）
tabs.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
    if (nv == null) return;
    Object id = nv.getUserData();
    if (id == null) return;
    for (SessionHandle h : manager.sessions()) {
        if (h.id.equals(id)) {
            manager.activateSession(h);
            return;
        }
    }
});
```

c) `addTab` 中选中行为改为受抑制标志控制：

```java
tabs.getTabs().add(t);
if (!suppressingTabSelect) tabs.getSelectionModel().select(t);
```

d) `rebuildTabs` 改为：

```java
private void rebuildTabs() {
    for (Tab t : tabs.getTabs()) StatusDot.stopPulseIn(t.getGraphic()); // 回收呼吸动画
    tabs.getTabs().clear();
    suppressingTabSelect = true; // 启动/切空间补齐不触发激活；结束后恢复当前会话选中
    for (SessionHandle h : manager.sessions()) {
        if (h.title != null) addTab(h);
    }
    suppressingTabSelect = false;
    if (manager.currentSession() != null) selectTab(manager.currentSession());
}
```

e) `show()` 末尾（`stage.setOnCloseRequest` 之前、`ResizeHelper.attach` 之后）加启动补齐：

```java
rebuildTabs(); // 启动补齐历史会话页签（需求 2：启动后历史会话即有页签）
```

- [ ] **Step 2: activateSession 幂等 guard**

`SessionManager.activateSession` 开头（`if (!currentWorkspaceName.equals(h.workspaceName)) return;` 之后）加：

```java
if (currentSession == h) return; // 重复激活（页签选中/左侧点击重叠）幂等跳过，避免重放闪烁
```

- [ ] **Step 3: 自动滚动改用 AutoScrollPolicy**

`setupAutoScroll` 整体替换为：

```java
/** 需求：消息区自动滚动——贴底时随新内容滚到底，离开底部即暂停，拖回底部恢复。
 *  内容增长后 runLater 延迟设置 vvalue，避免布局未完成时 setVvalue 被旧 vmax clamp 吞掉 */
private void setupAutoScroll() {
    final AutoScrollPolicy policy = new AutoScrollPolicy();
    chatScroll.vvalueProperty().addListener((obs, ov, nv) ->
            policy.onScroll(nv.doubleValue(), chatScroll.getVmax()));
    chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
        if (policy.shouldFollow()) {
            final double target = nv.doubleValue();
            Platform.runLater(() -> chatScroll.setVvalue(target));
        }
    });
}
```

（删除旧实现 `final boolean[] pinned` 版本。）

- [ ] **Step 4: 编译**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 手工验证（GUI）**

Run: `minion.bat`（或构建后运行 target/minion-0.1.0.jar）

1. 启动后：标题栏出现历史会话页签（如有），右侧保持占位不自动激活。
2. 点击标题栏页签 → 右侧切换为该会话，历史消息完整显示（含 Markdown 渲染）。
3. 点击左侧列表项 → 同样切换；再点页签 → 无闪烁（activateSession 幂等）。
4. 新建会话发消息 → 页签自动出现并被选中。
5. 发送长回复（>一屏）过程中：贴底自动跟随；滚到中间暂停；拖回底部恢复跟随。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/minion/gui/MainWindow.java src/main/java/com/minion/gui/session/SessionManager.java
git commit -m "feat: 页签点击激活会话、启动补齐历史页签、自动滚动改用 AutoScrollPolicy"
```

---

### Task 7: BlockNodeFactory 消息文字颜色

**Files:**
- Modify: `src/main/java/com/minion/gui/chat/BlockNodeFactory.java`

**Interfaces:**
- Consumes: 无。
- Produces: 无。行为：TextFlow 内 Text 显式 fill（正文 `#f0f2f6`、行内代码 `#79c0ff`、表格 `#c9d1d9`）。

- [ ] **Step 1: 实现**

`BlockNodeFactory.java` 类顶部加常量：

```java
/** 正文/表格文字颜色：Text 节点响应 -fx-fill（-fx-text-fill 仅对 Label 生效，根因在此） */
private static final String TEXT_FILL = "#f0f2f6";
private static final String TABLE_FILL = "#c9d1d9";
private static final String CODE_FILL = "#79c0ff";
```

`spanText` 的 code 分支改为：

```java
if (s.style.contains("code")) {
    t.setFill(javafx.scene.paint.Color.web(CODE_FILL));
} else {
    t.setFill(javafx.scene.paint.Color.web(TEXT_FILL));
}
```

（删除原 `if (s.style.contains("code")) t.setStyle("-fx-font-family: Consolas; -fx-fill: #79c0ff;");`——保留 font-family 设置：改为 `t.setStyle("-fx-font-family: Consolas;");` + setFill。）

表格单元格（`grid.add(t, c, rowIdx);` 之前）加：

```java
t.setFill(javafx.scene.paint.Color.web(TABLE_FILL));
```

- [ ] **Step 2: 编译**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 手工验证（GUI）**

Run: `minion.bat`。新建会话发送含段落（**正文**）、`行内代码`、代码块、表格的回复；确认消息块内文字浅色可读、行内代码蓝色、表格文字浅灰。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/minion/gui/chat/BlockNodeFactory.java
git commit -m "fix: 消息块 Text 显式 setFill——修复黑底黑字（Text 不响应 -fx-text-fill）"
```

---

### Task 8: SettingsDialog 页签横排与基础设置列布局

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`

**Interfaces:**
- Consumes: 无。
- Produces: 无。行为：设置窗页签顶部横排（tabMinWidth 90）；基础设置 GridPane 列约束（标签列不收缩、输入列铺满）。

- [ ] **Step 1: 页签横排**

`show()` 中：

```java
tp.setSide(javafx.geometry.Side.TOP); // 页签顶部横排（原 LEFT 竖排文字）
tp.setTabMinWidth(90);                 // 页签栏加宽
```

（删除 `tp.setSide(javafx.geometry.Side.LEFT);` 与 `tp.setTabMinWidth(100);`。）

- [ ] **Step 2: 基础设置列约束**

`basicTab` 中 GridPane 创建后加：

```java
// 列约束：标签列不收缩（完整显示，修复省略号截断）；输入列铺满剩余宽度
javafx.scene.layout.ColumnConstraints labelCol = new javafx.scene.layout.ColumnConstraints();
labelCol.setHgrow(javafx.scene.layout.Priority.NEVER);
javafx.scene.layout.ColumnConstraints inputCol = new javafx.scene.layout.ColumnConstraints();
inputCol.setHgrow(javafx.scene.layout.Priority.ALWAYS);
inputCol.setFillWidth(true);
grid.getColumnConstraints().addAll(labelCol, inputCol);
```

删除 `skillsDir.setPrefWidth(320);`。对基础设置页全部输入控件（skillsDir、toolWhitelist、cmdWhitelist、browserPath、browserPort、browserUserData、browserTimeout）加 `xxx.setMaxWidth(Double.MAX_VALUE);`。

import 处理：`javafx.scene.layout.ColumnConstraints`、`javafx.scene.layout.Priority` 可用全限定名（如上面代码）避免 import 冲突；`Double.MAX_VALUE` 直接写。

- [ ] **Step 3: 编译 + 现有测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SettingsDialogTest`
Expected: PASS（setInt 逻辑不受影响）。

- [ ] **Step 4: 手工验证（GUI）**

Run: `minion.bat` → ⚙ 设置：
1. 页签"模型/基础设置/关于"横排在顶部，文字正立。
2. 基础设置页："技能目录 skills.dir:"等标签完整显示（无"..."），输入框铺满右侧。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "fix: 设置窗页签改顶部横排、基础设置列约束——修复标签省略号截断"
```

---

### Task 9: theme.css 页签溢出裁剪与侧栏小按钮样式

**Files:**
- Modify: `src/resource/theme/theme.css`

**Interfaces:**
- Consumes: 无。
- Produces: `.btn-cell`（Task 10/11 按钮）、`.cell-time`（Task 11 时间）、页签溢出裁剪（Task 6 页签）。均仅 CSS 类。

- [ ] **Step 1: 实现**

页签段（`.tab-pane .tab` 规则后）追加：

```css
/* 页签溢出：隐藏控制按钮（下拉箭头/滚动），超出宽度由 header 裁剪，不显示滚动条 */
.tab-pane > .tab-header-area > .control-buttons-tab { -fx-visibility: hidden; }
.tab-pane .tab { -fx-min-width: 0; }
```

侧栏小按钮与时间样式（`.section-title` 规则后）追加：

```css
/* 侧栏行内悬停小按钮（重命名/修改/删除） */
.btn-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #a8b0bb;
    -fx-background-radius: 4;
    -fx-padding: 2 6 2 6;
    -fx-cursor: hand;
}
.btn-cell:hover { -fx-text-fill: #f0f2f6; -fx-background-color: #2a2f3a; }

/* 侧栏消息时间（5m/3h/2d） */
.cell-time { -fx-text-fill: #7a828e; -fx-font-size: 11px; }
```

注意：`.tab-pane .tab` 已有规则 `-fx-background-color: #1a1d24; -fx-background-radius: 8 8 0 0; -fx-padding: 4 12;`——追加 `-fx-min-width: 0` 需在同一选择器上（合并或第二条规则均可，第二条覆盖 min-width，其余属性保留第一条）。

- [ ] **Step 2: 手工验证（GUI）**

Run: `minion.bat`：
1. 新建 10+ 个会话 → 标题栏页签溢出：无下拉箭头/滚动条，超出部分不可见。
2. （Task 10/11 完成后验证）侧栏悬停显示小按钮、会话时间灰字。

- [ ] **Step 3: 提交**

```bash
git add src/resource/theme/theme.css
git commit -m "fix: 页签溢出裁剪不显示滚动条；新增 btn-cell/cell-time 样式"
```

---

### Task 10: WorkspaceListView 拖拽排序、悬停按钮、project.md 浏览

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java:248-293`（onNewWorkspace project.md 浏览）

**Interfaces:**
- Consumes: `WorkspaceManager.move`/`SessionManager.moveWorkspace`（Task 5）。
- Produces: 无。行为：cell 拖拽排序（drop 后 `refresh`）；悬停显示 重命名/修改/删除 小按钮；project.md 文件选择。

- [ ] **Step 1: cell 图形重构（悬停按钮 + 拖拽）**

`WsCell.updateItem` 的 `setText(name)` 段整体替换为：

```java
Label nameLabel = new Label(name + (name.equals(workspaces.currentName()) ? "  ●" : ""));
Region spacer = new Region();
HBox.setHgrow(spacer, Priority.ALWAYS);

// 悬停操作按钮（需求：不用右键，鼠标放上去才显示）
HBox btns = new HBox(4);
Button renameBtn = new Button("✎");
renameBtn.getStyleClass().add("btn-cell");
renameBtn.setTooltip(new Tooltip("重命名"));
renameBtn.setOnAction(e -> doRename(name));
Button editBtn = new Button("⚙");
editBtn.getStyleClass().add("btn-cell");
editBtn.setTooltip(new Tooltip("修改"));
editBtn.setOnAction(e -> doEdit(name));
Button delBtn = new Button("✕");
delBtn.getStyleClass().add("btn-cell");
delBtn.setTooltip(new Tooltip("删除"));
delBtn.setOnAction(e -> doDelete(name));
btns.getChildren().addAll(renameBtn, editBtn, delBtn);
btns.setVisible(false);
btns.setManaged(false);
setOnMouseEntered(e -> { btns.setVisible(true); btns.setManaged(true); });
setOnMouseExited(e -> { btns.setVisible(false); btns.setManaged(false); });

HBox row = new HBox(6);
row.getChildren().addAll(nameLabel, spacer, btns);
setGraphic(row);

// 拖拽排序：拖起携带工作空间名，drop 到目标 cell 位置
setOnDragDetected(e -> {
    if (isEmpty()) return;
    javafx.scene.input.Dragboard db = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
    javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
    cc.putString(name);
    db.setContent(cc);
    e.consume();
});
setOnDragOver(e -> {
    if (e.getGestureSource() != this) return;
    e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
    e.consume();
});
setOnDragDropped(e -> {
    String dragged = e.getDragboard().getString();
    if (dragged != null && !dragged.equals(name)) {
        manager.moveWorkspace(dragged, getIndex()); // 排序持久化；不触发内容切换通知
        refresh();
    }
    e.setDropHandled(true);
    e.consume();
});
```

删除原 ContextMenu 段（`ContextMenu menu = ...; setContextMenu(menu);` 整体移除），import 同步清理（ContextMenu/MenuItem 不再使用；新增 Button/Tooltip/HBox/Priority/Region）。

- [ ] **Step 2: doEdit 的 project.md 浏览**

`doEdit` 中 projectMd 段替换为：

```java
HBox pmBox = new HBox(6);
TextField projectMd = new TextField(w.projectMd == null ? "" : w.projectMd);
HBox.setHgrow(projectMd, Priority.ALWAYS);
Button pmBrowse = new Button("浏览…");
pmBrowse.getStyleClass().add("btn-ghost");
pmBrowse.setOnAction(e -> {
    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
    fc.setTitle("选择 project.md");
    fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"));
    String cur = projectMd.getText().trim();
    if (!cur.isEmpty()) {
        java.io.File f = new java.io.File(cur);
        if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
            fc.setInitialDirectory(f.getParentFile());
        }
    }
    java.io.File file = fc.showOpenDialog(d.getOwner());
    if (file != null) projectMd.setText(file.getAbsolutePath());
});
pmBox.getChildren().addAll(projectMd, pmBrowse);
```

`grid.addRow(1, new Label("project.md:"), projectMd);` → `grid.addRow(1, new Label("project.md:"), pmBox);`

- [ ] **Step 3: onNewWorkspace 的 project.md 浏览**（MainWindow.java）

`onNewWorkspace` 中 `TextField pm = new TextField();` 段替换为：

```java
HBox pmBox = new HBox(6);
TextField pm = new TextField();
pm.setPromptText("project.md（可空）");
HBox.setHgrow(pm, Priority.ALWAYS);
Button pmBrowse = new Button("浏览…");
pmBrowse.getStyleClass().add("btn-ghost");
pmBrowse.setOnAction(e -> {
    javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
    fc.setTitle("选择 project.md");
    fc.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"));
    java.io.File file = fc.showOpenDialog(d.getOwner());
    if (file != null) pm.setText(file.getAbsolutePath());
});
pmBox.getChildren().addAll(pm, pmBrowse);
```

`g.addRow(2, new Label("project.md:"), pm);` → `g.addRow(2, new Label("project.md:"), pmBox);`

- [ ] **Step 4: 编译**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 手工验证（GUI）**

Run: `minion.bat`：
1. 多个工作空间上下拖动 → 顺序变化；重启后顺序保持。
2. 悬停工作空间项 → 右侧出现 ✎/⚙/✕ 小按钮；移开消失；点击分别弹出重命名/修改/删除（右键不再有菜单）。
3. 新建工作空间/修改弹窗：project.md 行有"浏览…"，可打开文件选择器选 .md 文件填入。
4. 拖拽过程中右侧聊天区内容不被清空。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/minion/gui/sidebar/WorkspaceListView.java src/main/java/com/minion/gui/MainWindow.java
git commit -m "feat: 工作空间拖拽排序、悬停操作按钮、project.md 文件选择"
```

---

### Task 11: SessionListView 悬停按钮与消息时间

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/SessionListView.java`

**Interfaces:**
- Consumes: `TimeFormatter.format`（Task 3）、`Message.ts`（Task 1）。
- Produces: 无。行为：悬停显示 重命名/删除 小按钮；非悬停显示最后消息时间。

- [ ] **Step 1: cell 图形重构**

`SessionCell.updateItem` 中 `Label name` 之后的构建段替换为：

```java
Circle dot = StatusDot.create(h.running);
Label name = new Label(label);
Region spacer = new Region();
HBox.setHgrow(spacer, Priority.ALWAYS);

// 右区双态：非悬停显示最近消息时间（5m/3h/2d）；悬停切换为操作按钮
Label timeLabel = new Label();
timeLabel.getStyleClass().add("cell-time");
String t = TimeFormatter.format(lastMessageTs(h), System.currentTimeMillis());
if (t != null) timeLabel.setText(t);
Button renameBtn = new Button("✎");
renameBtn.getStyleClass().add("btn-cell");
renameBtn.setTooltip(new Tooltip("重命名"));
renameBtn.setOnAction(e -> doRename(h));
Button delBtn = new Button("✕");
delBtn.getStyleClass().add("btn-cell");
delBtn.setTooltip(new Tooltip("删除"));
delBtn.setOnAction(e -> doDelete(h));
renameBtn.setVisible(false);
renameBtn.setManaged(false);
delBtn.setVisible(false);
delBtn.setManaged(false);
setOnMouseEntered(e -> {
    timeLabel.setVisible(false);
    timeLabel.setManaged(false);
    renameBtn.setVisible(true);
    renameBtn.setManaged(true);
    delBtn.setVisible(true);
    delBtn.setManaged(true);
});
setOnMouseExited(e -> {
    timeLabel.setVisible(true);
    timeLabel.setManaged(true);
    renameBtn.setVisible(false);
    renameBtn.setManaged(false);
    delBtn.setVisible(false);
    delBtn.setManaged(false);
});

HBox box = new HBox(6);
box.getChildren().addAll(dot, name, spacer, timeLabel, renameBtn, delBtn);
```

删除原 `ContextMenu menu = ...` 段（`setContextMenu(menu)` 整体移除）。

`doRename(SessionHandle h)` 与 `doDelete(SessionHandle h)` 新方法（复用原 ContextMenu 内的弹窗逻辑）：

```java
private void doRename(SessionHandle h) {
    TextInputDialog d = new TextInputDialog(h.title);
    d.setTitle("重命名会话");
    d.setHeaderText("输入新标题");
    Theme.style(d); // 弹窗深色
    d.showAndWait().ifPresent(t -> manager.renameSession(h, t));
}

private void doDelete(SessionHandle h) {
    Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "删除会话「" + (h.title == null ? h.id : h.title) + "」？",
            ButtonType.OK, ButtonType.CANCEL);
    a.setTitle("删除会话");
    Theme.style(a); // 弹窗深色
    a.showAndWait().ifPresent(bt -> {
        if (bt == ButtonType.OK) {
            manager.deleteSession(h);
            onDeleted.accept(h); // 页签联动清理（MainWindow.removeTabById）
            refresh();
        }
    });
}
```

- [ ] **Step 2: lastMessageTs 方法**（类内 `lastSummary` 附近加）

```java
/** 最后一条非 TOOL 消息的创建时间戳（毫秒；无消息/全 TOOL/旧数据 → 0） */
private long lastMessageTs(SessionHandle h) {
    if (h.session == null || h.session.messages == null) return 0L;
    List<Message> msgs = new ArrayList<Message>(h.session.messages); // 防御性拷贝（同 lastSummary）
    for (int i = msgs.size() - 1; i >= 0; i--) {
        Message m = msgs.get(i);
        if (m.role == Message.Role.TOOL) continue;
        return m.ts;
    }
    return 0L;
}
```

- [ ] **Step 3: 编译**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 手工验证（GUI）**

Run: `minion.bat`：
1. 会话项非悬停：最右侧显示时间（刚刚的消息 → 1m；1 小时前 → 1h 等），旧数据会话不显示时间。
2. 悬停会话项：时间消失，显示 ✎/✕ 小按钮；点击重命名/删除正常（右键不再有菜单）。
3. 运行中会话状态点呼吸动画正常（StatusDot 未被破坏）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/sidebar/SessionListView.java
git commit -m "feat: 会话悬停操作按钮 + 最近消息时间显示（5m/3h/2d）"
```

---

### Task 12: 文档同步与全量验证

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/superpowers/specs/2026-08-13-ui-fixes-enhancements-design.md`（如实施产生偏差）

**Interfaces:**
- Consumes: 全部已完成任务。
- Produces: 无。

- [ ] **Step 1: 同步 README.md**

在侧栏/界面相关说明处补充：工作空间可拖拽排序；会话/工作空间项悬停显示操作按钮（重命名/修改/删除）；会话项非悬停显示最近消息时间（1m/5m/3h/2d）；新建/修改工作空间弹窗 project.md 支持文件选择器。语句风格与现有 README 一致（中文、简洁）。

- [ ] **Step 2: 同步 docs/ARCHITECTURE.md**

补充组件：`gui/session/AutoScrollPolicy`（自动滚动贴底策略）、`gui/sidebar/TimeFormatter`（消息时间格式化）、`SessionController.replayHistory`（历史消息灌入事件流）、`Message.ts`（创建时间戳）、`WorkspaceManager.move`（排序持久化）。

- [ ] **Step 3: 全量构建 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn clean package`
Expected: BUILD SUCCESS，全部测试 PASS。

- [ ] **Step 4: 手工回归清单（完整跑一遍）**

Run: `minion.bat`：

1. 启动 → 历史会话有页签；点页签/列表 → 右侧显示历史消息。
2. 新建会话发消息 → 页签自动出现。
3. 流式回复末尾消息块文字浅色可读。
4. 设置窗页签横排；基础设置标签完整显示。
5. 工作空间拖拽排序，重启保持。
6. 流式输出滚到底部自动跟随。
7. 页签溢出无滚动条/箭头。
8. 悬停会话/工作空间显示按钮；会话非悬停显示时间。
9. project.md 可文件选择。
10. 删除会话/工作空间、退出确认、深色弹窗正常（回归）。

- [ ] **Step 5: 提交**

```bash
git add README.md docs/ARCHITECTURE.md docs/superpowers/specs/2026-08-13-ui-fixes-enhancements-design.md
git commit -m "docs: 同步 UI 增强说明（悬停按钮/消息时间/拖拽排序/project.md 浏览/新组件）"
```

---

## 手工验证注意事项

- GUI 无法自动测试（无 TestFX）：Task 6-11 以 `minion.bat` 手工验证为准，验证步骤须逐条执行。
- 每次手工验证前先 `mvn clean package` 确认构建产物最新。
- 若某步 GUI 行为与预期不符：记录现象回到对应 Task 排查，不要跳过验证步骤。
