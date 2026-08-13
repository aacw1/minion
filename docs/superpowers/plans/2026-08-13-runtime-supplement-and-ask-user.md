# 运行中补充 + ask_user 工具 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 运行中用户可点补充将内容注入进行中的对话（工具结果后、带标识）；新增 ask_user 工具让模型挂起等待用户回答后继续本轮。

**Architecture:** 「检查点注入」+ Claude Code 式 ask_user。补充进 `Session.pendingSupplements` 挂起队列（随会话落盘），在 AgentLoop 每轮工具结果入历史后的检查点批量注入为 `supplement=true` 的 user 消息；ask_user 工具阻塞在 CompletableFuture 上等待 `answerAskUser`，回答作为 TOOL 消息回传。UI 侧单图标按钮状态机 + USER_SUPPLEMENT 事件渲染标识气泡。

**Tech Stack:** JDK 8、JavaFX 8（SVGPath 图标）、gson、okhttp 3.14、junit4。

**Spec:** docs/superpowers/specs/2026-08-13-runtime-supplement-and-ask-user-design.md

## Global Constraints

- JDK 8 兼容；新代码不用 Java 9+ API（可用 lambda、CompletableFuture）
- 构建/测试命令（bash）：`JAVA_HOME="E:/javame/jdk8" mvn test`、`JAVA_HOME="E:/javame/jdk8" mvn clean package`
- API 契约防回归：reasoning_content 原样回传；tool_call↔tool 消息完整配对（assistant 含 tool_calls 的消息后必须紧跟对应 tool 消息，中间不得插入 user 消息）
- `Message.toApiJson` 不输出 supplement 字段（标识只在本地历史与 UI 层，API 零污染）
- 文档、注释、commit 用中文；commit 用 conventional 格式
- **commit 中文消息必须经文件提交**：先用 Write 工具写 `.git/COMMIT_MSG_TMP.txt`（含正文 + `Co-Authored-By: Claude <noreply@anthropic.com>` 结尾行），再 `git commit -F .git/COMMIT_MSG_TMP.txt`，最后删除该文件（`git commit -m` 中文会触发 bash wrapper 崩溃）
- 每任务结束运行相关测试确认通过后再提交

---

### Task 1: Message 补充标识（supplement 字段）

**Files:**
- Modify: `src/main/java/com/minion/core/llm/Message.java`
- Test: `src/test/java/com/minion/core/llm/MessageTest.java`

**Interfaces:**
- Produces: `Message.userSupplement(String): Message`（USER 角色 + supplement=true）；`Message.supplement` 布尔字段（Gson 落盘，缺失=false 兼容旧数据）

- [ ] **Step 1: 写失败测试**

在 `MessageTest.java` 末尾（类内）追加：

```java
    /** 运行中补充消息：supplement=true；toApiJson 不输出该字段（API 零污染）；Gson 往返保留 */
    @Test
    public void userSupplement_flagsAndRoundTrips() {
        Message m = Message.userSupplement("补充内容");
        assertTrue(m.supplement);
        assertEquals(Message.Role.USER, m.role);
        JsonObject api = m.toApiJson();
        assertFalse("supplement 不得进入 API 请求体", api.has("supplement"));
        assertEquals("补充内容", api.get("content").getAsString());

        String json = gson.toJson(m);
        Message back = gson.fromJson(json, Message.class);
        assertTrue(back.supplement);
    }

    /** 普通 user 消息 supplement=false */
    @Test
    public void userMessage_supplementFalseByDefault() {
        assertFalse(Message.user("普通").supplement);
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=MessageTest`
Expected: FAIL，`userSupplement` 未定义编译错误

- [ ] **Step 3: 实现**

`Message.java`：在 `public boolean summary;` 行后加字段；在 `user(String)` 工厂后加新工厂：

```java
    /** true = 运行中用户补充消息（仅 user 角色使用）。标识只在本地历史与 UI 层，
     *  toApiJson 不输出——API content 原样发送，模型输入零污染 */
    public boolean supplement;
```

```java
    /** 运行中用户补充：USER 角色 + supplement=true（检查点注入/下次发送合并时使用） */
    public static Message userSupplement(String content) {
        Message m = user(content);
        m.supplement = true;
        return m;
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=MessageTest`
Expected: PASS（全部用例）

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: Message 增加运行中补充标识（supplement 字段，API 零污染）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/llm/Message.java src/test/java/com/minion/core/llm/MessageTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 2: Session 挂起补充队列（随会话落盘）

**Files:**
- Modify: `src/main/java/com/minion/core/agent/Session.java`
- Test: `src/test/java/com/minion/core/storage/SessionStoreTest.java`

**Interfaces:**
- Produces: `Session.pendingSupplements: List<String>`——字段初始化器 `= new ArrayList<String>()`（Gson 反序列化旧文件时初始化器生效，天然非 null）

- [ ] **Step 1: 写失败测试**

在 `SessionStoreTest.java` 的 `saveLoad_roundTrip` 测试后追加：

```java
    /** 挂起补充队列 + supplement 标识消息随会话落盘往返（重启不丢） */
    @Test
    public void saveLoad_pendingSupplementsRoundTrip() throws Exception {
        SessionStore store = new SessionStore(tmp.getRoot().toPath().resolve("sessions2"));
        Session s = makeSession();
        s.messages.add(Message.userSupplement("已注入的补充"));
        s.pendingSupplements.add("补充A");
        s.pendingSupplements.add("补充B");
        store.save(s);

        Session loaded = store.load(s.id);
        assertEquals(2, loaded.pendingSupplements.size());
        assertEquals("补充A", loaded.pendingSupplements.get(0));
        assertEquals("补充B", loaded.pendingSupplements.get(1));
        Message last = loaded.messages.get(loaded.messages.size() - 1);
        assertTrue("supplement 标识随消息落盘", last.supplement);
        assertEquals("已注入的补充", last.content);
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionStoreTest`
Expected: FAIL，`pendingSupplements` 未定义编译错误

- [ ] **Step 3: 实现**

`Session.java`：在 `public UsageTracker usage = new UsageTracker();` 行后加：

```java
    /** 运行中用户补充的挂起队列（尚未入 messages 历史；检查点/下次发送时批量注入后清空）。
     *  随会话落盘：应用退出时未注入的补充不丢。初始化器保证旧文件反序列化后非 null */
    public List<String> pendingSupplements = new ArrayList<String>();
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionStoreTest`
Expected: PASS

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: Session 增加挂起补充队列 pendingSupplements（随会话落盘）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/agent/Session.java src/test/java/com/minion/core/storage/SessionStoreTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 3: 事件管道（AgentUi 回调 + USER_SUPPLEMENT + SessionController 转发）

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentUi.java`
- Modify: `src/main/java/com/minion/gui/session/EventList.java`
- Modify: `src/main/java/com/minion/gui/session/SessionController.java`
- Modify: `src/test/java/com/minion/core/agent/RecordingUi.java`（测试基建：记录新回调）
- Test: `src/test/java/com/minion/gui/session/SessionControllerTest.java`

**Interfaces:**
- Consumes: `Message.supplement` / `Message.userSupplement`（Task 1）
- Produces:
  - `AgentUi.onUserSupplement(String text)` / `onAskUserStart(String question)` / `onAskUserDone(String answer)`（default 空实现，不破坏既有实现类）
  - `EventList.Kind.USER_SUPPLEMENT`
  - `SessionController.setAskStateListener(java.util.function.Consumer<String>)`——回调参数：非 null=开始挂起并携带问题文本；null=回答完成
  - `RecordingUi.asksStarted / asksDone / supplements` 记录列表

- [ ] **Step 1: 写失败测试**

在 `SessionControllerTest.java` 追加（`java.util.function.Consumer` 用匿名类，无需新 import）：

```java
    @Test
    public void onUserSupplement_emitsSupplementEvent() {
        SessionController c = new SessionController();
        c.onUserSupplement("补充内容");
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("补充内容", evs.get(0).text);
    }

    /** 历史回放：supplement=true 的 USER 消息 → USER_SUPPLEMENT 事件 */
    @Test
    public void replayHistory_userSupplement_emitsSupplementEvent() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.userSupplement("历史补充"));
        msgs.add(Message.user("普通消息"));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("历史补充", evs.get(0).text);
        assertEquals(EventList.Kind.USER_MESSAGE, evs.get(1).kind);
    }

    /** ask_user 状态转发：开始（带问题）→ 完成（null） */
    @Test
    public void askStateListener_startAndDone() {
        SessionController c = new SessionController();
        final List<String> states = new ArrayList<String>();
        c.setAskStateListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String question) { states.add(question); }
        });
        c.onAskUserStart("选哪个？");
        c.onAskUserDone("方案B");
        assertEquals(2, states.size());
        assertEquals("选哪个？", states.get(0));
        assertNull(states.get(1));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionControllerTest`
Expected: FAIL，`USER_SUPPLEMENT`/`onUserSupplement` 未定义编译错误

- [ ] **Step 3: 实现**

`AgentUi.java` 接口内（`onWarning` 后）追加三个 default 方法：

```java
    /** 运行中用户补充（UI 事件在点击时发一次；历史注入不再发） */
    default void onUserSupplement(String text) { }
    /** ask_user 工具开始挂起等待回答 */
    default void onAskUserStart(String question) { }
    /** ask_user 收到回答（answer 为回答文本；中断路径不回调，由运行态复位兜底） */
    default void onAskUserDone(String answer) { }
```

`EventList.java` 的 `Kind` 枚举（现为 4 行）在 `USER_MESSAGE,` 后插入 `USER_SUPPLEMENT,`：

```java
    public enum Kind {
        USER_MESSAGE, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT,
        SUB_AGENT_START, SUB_AGENT_DELTA, SUB_AGENT_DONE, STATS, ERROR, WARNING
    }
```

改为：

```java
    public enum Kind {
        USER_MESSAGE, USER_SUPPLEMENT, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT,
        SUB_AGENT_START, SUB_AGENT_DELTA, SUB_AGENT_DONE, STATS, ERROR, WARNING
    }
```

`SessionController.java`：字段区（`private final EventList events` 后）加：

```java
    /** ask_user 挂起状态回调（非 null=开始挂起并携带问题；null=回答完成），SessionManager 注入 */
    private volatile java.util.function.Consumer<String> askStateListener;

    public void setAskStateListener(java.util.function.Consumer<String> l) { this.askStateListener = l; }
```

`replayHistory` 的 USER 分支改为：

```java
            if (m.role == Message.Role.USER) {
                events.add(new EventList.Ev(m.supplement
                        ? EventList.Kind.USER_SUPPLEMENT : EventList.Kind.USER_MESSAGE,
                        m.content, null));
            }
```

末尾追加三个实现：

```java
    @Override public void onUserSupplement(String text) {
        events.add(new EventList.Ev(EventList.Kind.USER_SUPPLEMENT, text, null));
    }
    @Override public void onAskUserStart(String question) {
        if (askStateListener != null) askStateListener.accept(question);
    }
    @Override public void onAskUserDone(String answer) {
        if (askStateListener != null) askStateListener.accept(null);
    }
```

`RecordingUi.java`（测试基建）追加记录字段与实现：

```java
    public final List<String> asksStarted = new ArrayList<String>();
    public final List<String> asksDone = new ArrayList<String>();
    public final List<String> supplements = new ArrayList<String>();
```

```java
    @Override public synchronized void onAskUserStart(String question) { asksStarted.add(question); }
    @Override public synchronized void onAskUserDone(String answer) { asksDone.add(answer); }
    @Override public synchronized void onUserSupplement(String text) { supplements.add(text); }
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: 补充与提问事件管道（AgentUi 回调+USER_SUPPLEMENT 事件+状态转发）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/agent/AgentUi.java src/main/java/com/minion/gui/session/EventList.java src/main/java/com/minion/gui/session/SessionController.java src/test/java/com/minion/core/agent/RecordingUi.java src/test/java/com/minion/gui/session/SessionControllerTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 4: AskUserTool（挂起等待回答）

**Files:**
- Create: `src/main/java/com/minion/core/tools/AskUserTool.java`
- Test: `src/test/java/com/minion/core/tools/AskUserToolTest.java`

**Interfaces:**
- Consumes: `AgentUi.onAskUserStart/onAskUserDone`（Task 3）
- Produces: `AskUserTool(AgentUi ui)` 构造器；`complete(String answer): boolean`（无挂起时返回 false）；工具名 `ask_user`；`execute` 阻塞到 complete 或线程中断（中断抛 InterruptedException 传播）

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/com/minion/core/tools/AskUserToolTest.java`：

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.RecordingUi;
import org.junit.Test;

import static org.junit.Assert.*;

/** ask_user 工具：挂起等待回答；无挂起时 complete 忽略；缺 question 回退默认文案 */
public class AskUserToolTest {

    @Test
    public void complete_withoutPending_returnsFalse() {
        AskUserTool tool = new AskUserTool(new RecordingUi());
        assertFalse(tool.complete("无人等待"));
    }

    @Test
    public void execute_blocksUntilAnswered() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserTool tool = new AskUserTool(ui);
        final ToolResult[] result = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JsonObject args = JsonParser.parseString(
                            "{\"question\":\"选哪个？\"}").getAsJsonObject();
                    result[0] = tool.execute(args);
                } catch (Exception e) {
                    result[0] = ToolResult.error("异常: " + e.getMessage());
                }
            }
        });
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue("未进入挂起（onAskUserStart 未回调）", ui.asksStarted.size() == 1);
        assertEquals("选哪个？", ui.asksStarted.get(0));
        assertTrue(tool.complete("方案B"));
        t.join(5000);
        assertFalse(t.isAlive());
        assertNotNull(result[0]);
        assertTrue(result[0].ok);
        assertEquals("方案B", result[0].output);
        assertEquals(1, ui.asksDone.size());
        assertEquals("方案B", ui.asksDone.get(0));
        // 完成后 pending 清空：再次 complete 无效
        assertFalse(tool.complete("再来一次"));
    }

    @Test
    public void execute_missingQuestion_usesFallbackText() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserTool tool = new AskUserTool(ui);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { tool.execute(new JsonObject()); } catch (Exception ignored) { }
            }
        });
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        t.interrupt(); // 不回答，直接打断（验证中断可解除阻塞）
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(1, ui.asksStarted.size());
        assertFalse("缺少 question 应回退默认文案", ui.asksStarted.get(0).isEmpty());
    }

    /** schema 契约：question 必填；options 数组；multiSelect 布尔 */
    @Test
    public void schema_hasQuestionRequiredAndOptionsArray() {
        JsonObject schema = new AskUserTool(new RecordingUi()).schema();
        assertEquals("object", schema.get("type").getAsString());
        assertEquals(1, schema.getAsJsonArray("required").size());
        assertEquals("question", schema.getAsJsonArray("required").get(0).getAsString());
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("question"));
        assertTrue(props.has("header"));
        assertTrue(props.has("options"));
        assertEquals("array", props.getAsJsonObject("options").get("type").getAsString());
        assertEquals("boolean", props.getAsJsonObject("multiSelect").get("type").getAsString());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AskUserToolTest`
Expected: FAIL，`AskUserTool` 类不存在编译错误

- [ ] **Step 3: 实现**

新建 `src/main/java/com/minion/core/tools/AskUserTool.java`：

```java
package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;

import java.util.concurrent.CompletableFuture;

/** 向用户提问：execute 挂起等待回答（无超时），回答经 complete() 送达后作为工具结果返回。
 *  参照 Claude Code AskUserQuestion；子 agent 禁用（SubAgentLoop 过滤 schema + 同名防御）。 */
public class AskUserTool implements Tool {

    private final AgentUi ui;
    /** 当前挂起的等待（单槽：同轮多次调用共享同一回答；null=未挂起） */
    private volatile CompletableFuture<String> pending;

    public AskUserTool(AgentUi ui) { this.ui = ui; }

    @Override
    public String name() { return "ask_user"; }

    @Override
    public String description() {
        return "向用户提问或请求选择。当缺少完成任务所需信息、需要用户确认方案或做出选择时调用。"
                + "参数 question 为必填问题文本；header 为可选简短标题；options 为可选答案列表"
                + "（2-4 个，每项含 label/description）；multiSelect 表示是否可多选。"
                + "调用后将挂起等待用户回答，回答会作为工具结果返回。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "向用户提问（缺少信息/需确认方案/需选择时）");
        JsonObject props = new JsonObject();
        JsonObject question = new JsonObject();
        question.addProperty("type", "string");
        question.addProperty("description", "要问用户的问题");
        JsonObject header = new JsonObject();
        header.addProperty("type", "string");
        header.addProperty("description", "简短标题（可选）");
        JsonObject options = new JsonObject();
        options.addProperty("type", "array");
        options.addProperty("description", "可选答案列表，2-4 个");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        JsonObject label = new JsonObject();
        label.addProperty("type", "string");
        label.addProperty("description", "选项文本");
        JsonObject desc = new JsonObject();
        desc.addProperty("type", "string");
        desc.addProperty("description", "选项说明");
        itemProps.add("label", label);
        itemProps.add("description", desc);
        items.add("properties", itemProps);
        options.add("items", items);
        JsonObject multiSelect = new JsonObject();
        multiSelect.addProperty("type", "boolean");
        multiSelect.addProperty("description", "是否可多选（可选）");
        props.add("question", question);
        props.add("header", header);
        props.add("options", options);
        props.add("multiSelect", multiSelect);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("question");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        String question = args.has("question") && !args.get("question").isJsonNull()
                ? args.get("question").getAsString()
                : "请提供完成任务所需的信息";
        CompletableFuture<String> fut = new CompletableFuture<String>();
        this.pending = fut;
        ui.onAskUserStart(question);
        try {
            String answer = fut.get(); // 阻塞到 answerAskUser 或线程中断（终止）
            ui.onAskUserDone(answer);
            return ToolResult.success(answer);
        } finally {
            this.pending = null;
        }
    }

    /** 用户回答入口（AgentLoop.answerAskUser 转发）；无挂起时忽略并返回 false */
    public boolean complete(String answer) {
        CompletableFuture<String> fut = pending;
        if (fut == null) return false;
        fut.complete(answer);
        return true;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AskUserToolTest`
Expected: PASS

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: 新增 ask_user 工具（挂起等待用户回答，回答作为工具结果回传）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/tools/AskUserTool.java src/test/java/com/minion/core/tools/AskUserToolTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 5: AgentLoop 补充注入（offerSupplement / 检查点 / 开头合并）

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: `Message.userSupplement`（Task 1）、`Session.pendingSupplements`（Task 2）
- Produces: `AgentLoop.offerSupplement(String text)`（入挂起队列；空文本忽略）；私有 `drainSupplements()`；行为：工具结果后检查点注入（interrupted 时不注入）、runUserTurn 开头合并遗留补充

- [ ] **Step 1: 写失败测试**

`AgentLoopTest.java` 追加一个闸门工具类（在 `BlockingTool` 附近）与 3 个测试：

```java
    /** 闸门工具：execute 阻塞直到 release 后成功返回（供「运行中补充」时序测试） */
    public static class GateTool implements Tool {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
        @Override public String name() { return "gate"; }
        @Override public String description() { return "闸门测试工具"; }
        @Override public JsonObject schema() {
            return com.minion.core.tools.SchemaGenerator.objectSchema("闸门", new String[0], new String[0]);
        }
        @Override public ToolResult execute(JsonObject args) throws Exception {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return ToolResult.success("gate-opened");
        }
    }

    /** 需求1/2：运行中补充在工具结果后注入（supplement=true），第二轮请求可见 */
    @Test
    public void supplement_injectedAfterToolResults() throws Exception {
        GateTool gate = new GateTool();
        registry.register(gate);
        ToolCall tc = new ToolCall();
        tc.id = "g1";
        tc.name = "gate";
        tc.arguments = "{}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("收到补充");
        final AgentLoop loop = newLoop();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop.runUserTurn("开始干活"); }
        });
        t.start();
        assertTrue("工具未进入执行", gate.entered.await(5, TimeUnit.SECONDS));
        loop.offerSupplement("注意边界条件");
        gate.release.countDown();
        t.join(5000);
        assertFalse(t.isAlive());
        // 0:user 1:assistant(toolCalls) 2:tool 3:user(补充) 4:assistant(最终)
        List<Message> msgs = loop.messages();
        assertEquals(5, msgs.size());
        assertEquals(Message.Role.TOOL, msgs.get(2).role);
        assertEquals(Message.Role.USER, msgs.get(3).role);
        assertTrue(msgs.get(3).supplement);
        assertEquals("注意边界条件", msgs.get(3).content);
        assertEquals("收到补充", msgs.get(4).content);
        // 第二轮请求：补充是最后一条（检查点注入后、请求构建前；请求首条为 system）
        List<Message> req2 = llm.requests.get(1).messages;
        assertEquals("注意边界条件", req2.get(req2.size() - 1).content);
        assertTrue(req2.get(req2.size() - 1).supplement);
    }

    /** 需求4：回合自然结束时未注入的补充挂起，下次发送开头合并（顺序：补充→用户输入） */
    @Test
    public void supplement_pendingMergesAtNextSend() {
        llm.addTurn("好的");
        AgentLoop loop = newLoop();
        loop.offerSupplement("补充A");
        loop.offerSupplement("补充B");
        loop.runUserTurn("真实回答");
        List<Message> msgs = loop.messages();
        assertEquals(4, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(0).role);
        assertTrue(msgs.get(0).supplement);
        assertEquals("补充A", msgs.get(0).content);
        assertTrue(msgs.get(1).supplement);
        assertEquals("补充B", msgs.get(1).content);
        assertEquals(Message.Role.USER, msgs.get(2).role);
        assertFalse(msgs.get(2).supplement);
        assertEquals("真实回答", msgs.get(2).content);
        assertEquals("好的", msgs.get(3).content);
    }

    /** 中断不注入（防半轮 tool_call 未配对时插入 user 消息导致 400），挂起保留到下次发送合并 */
    @Test
    public void supplement_interruptKeepsPending() throws Exception {
        GateTool gate = new GateTool();
        registry.register(gate);
        ToolCall tc = new ToolCall();
        tc.id = "g1";
        tc.name = "gate";
        tc.arguments = "{}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("接着来");
        final AgentLoop loop = newLoop();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop.runUserTurn("开始干活"); }
        });
        t.start();
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        loop.offerSupplement("补充A");
        loop.interrupt();
        gate.release.countDown();
        t.join(5000);
        assertFalse(t.isAlive());
        // 中断路径：补充未注入（留在挂起队列）
        for (Message m : loop.messages()) {
            assertFalse("中断轮不应注入补充", m.supplement);
        }
        loop.runUserTurn("接着来");
        List<Message> msgs = loop.messages();
        // 0:user(开始干活) 1:补充A 2:接着来 3:assistant
        assertEquals(4, msgs.size());
        assertTrue(msgs.get(1).supplement);
        assertEquals("补充A", msgs.get(1).content);
        assertEquals("接着来", msgs.get(2).content);
        assertFalse(msgs.get(2).supplement);
    }
```

（`GateTool` 与既有 `BlockingTool` 的区别：可被 release 释放并正常返回，用于「注入后继续下一轮」的断言。）

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AgentLoopTest`
Expected: FAIL，`offerSupplement` 未定义编译错误

- [ ] **Step 3: 实现**

`AgentLoop.java` 三处修改：

① 在 `interrupt()` 方法后新增两个方法：

```java
    /** 运行中补充：入挂起队列（随会话落盘），检查点或下次发送时入历史 */
    public void offerSupplement(String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (session.pendingSupplements) {
            session.pendingSupplements.add(text);
        }
    }

    /** 挂起补充全部入历史并清空队列（UI 事件在点击时已发，此处不再发） */
    private void drainSupplements() {
        List<String> drain;
        synchronized (session.pendingSupplements) {
            if (session.pendingSupplements.isEmpty()) return;
            drain = new ArrayList<String>(session.pendingSupplements);
            session.pendingSupplements.clear();
        }
        for (String s : drain) session.messages.add(Message.userSupplement(s));
    }
```

② `runUserTurn` 开头（`interrupted = false;` 之后、`ui.onUserMessage(input);` 之前）插入：

```java
        // 上次回合遗留的挂起补充先入历史（模型提问自然收尾/中断遗留），与本次输入拼接发送
        drainSupplements();
```

③ 工具结果 for 循环的 `finally { synchronized (inFlight) { inFlight.clear(); } }` 之后、`// 卡住止损` 注释之前插入：

```java
                // 运行中补充注入检查点：工具结果全部入历史后、下一轮请求前；
                // ask_user 挂起时补充等回答的 TOOL 消息入历史后同请求发出；
                // interrupted 不注入——半轮 tool_call 未配对时插入 user 消息会破坏契约（400）
                if (!interrupted) drainSupplements();
```

④ `restoreSession` 中 `workspace.restore(s.cwd);` 之后加：

```java
        // 挂起补充随会话恢复（旧文件缺字段时 Gson 初始化器已兜底，此处再防御一次）
        session.pendingSupplements = s.pendingSupplements != null
                ? s.pendingSupplements : new ArrayList<String>();
```

⑤ `startNewSession` 中 `session.messages.clear();` 之后加：

```java
        session.pendingSupplements.clear();
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AgentLoopTest`
Expected: PASS（含既有全部用例，确认无回归）

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: AgentLoop 运行中补充注入（工具结果后检查点+下次发送合并）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 6: AgentLoop 接线 ask_user（answerAskUser / 挂起回答 / 中断清洗）

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: `AskUserTool`（Task 4）、`RecordingUi.asksStarted`（Task 3）
- Produces: `AgentLoop.answerAskUser(String answer): boolean`；构造器自动注册 ask_user 工具

- [ ] **Step 1: 写失败测试**

`AgentLoopTest.java` 追加轮询等待辅助方法与 3 个测试：

```java
    /** 轮询等待条件（挂起回调无 latch 可挂时用） */
    private static void waitFor(java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.call()) return;
            Thread.sleep(20);
        }
        fail("条件等待超时");
    }

    /** ask_user 工具随 AgentLoop 构造自动注册 */
    @Test
    public void agentLoop_autoRegistersAskUserTool() {
        AgentLoop loop = newLoop();
        assertNotNull(registry.get("ask_user"));
        assertEquals("ask_user", registry.get("ask_user").name());
    }

    /** 需求3/6：ask_user 挂起 → answerAskUser → 回答作为 TOOL 消息入历史继续本轮 */
    @Test
    public void askUser_suspendThenAnswer_continuesTurn() throws Exception {
        ToolCall q = new ToolCall();
        q.id = "q1";
        q.name = "ask_user";
        q.arguments = "{\"question\":\"选哪个方案？\"}";
        llm.addTurnWithTools(Collections.singletonList(q), null);
        llm.addTurn("按方案B执行");
        final AgentLoop loop = newLoop();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop.runUserTurn("帮我选一下"); }
        });
        t.start();
        waitFor(new java.util.concurrent.Callable<Boolean>() {
            @Override public Boolean call() { return !ui.asksStarted.isEmpty(); }
        });
        assertTrue(loop.answerAskUser("方案B"));
        t.join(5000);
        assertFalse(t.isAlive());
        // 0:user 1:assistant(toolCalls) 2:tool(回答) 3:assistant(最终)
        List<Message> msgs = loop.messages();
        assertEquals(4, msgs.size());
        assertEquals(Message.Role.TOOL, msgs.get(2).role);
        assertEquals("q1", msgs.get(2).toolCallId);
        assertTrue(msgs.get(2).content.contains("方案B"));
        assertEquals("按方案B执行", msgs.get(3).content);
        // 第二轮请求：tool 消息前紧跟含对应 tool_call_id 的 assistant 消息（API 契约）
        List<Message> req2 = llm.requests.get(1).messages;
        assertEquals(Message.Role.ASSISTANT, req2.get(req2.size() - 2).role);
        assertEquals(Message.Role.TOOL, req2.get(req2.size() - 1).role);
    }

    /** 需求6：ask_user 挂起时中断 → 退出 + 半轮 tool_call 被清洗（不留未配对残留） */
    @Test
    public void askUser_interruptWhileWaiting_scrubsHalfTurn() throws Exception {
        ToolCall q = new ToolCall();
        q.id = "q2";
        q.name = "ask_user";
        q.arguments = "{\"question\":\"选哪个？\"}";
        llm.addTurnWithTools(Collections.singletonList(q), null);
        final AgentLoop loop = newLoop();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop.runUserTurn("帮我选一下"); }
        });
        t.start();
        waitFor(new java.util.concurrent.Callable<Boolean>() {
            @Override public Boolean call() { return !ui.asksStarted.isEmpty(); }
        });
        loop.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        for (Message m : loop.messages()) {
            assertTrue("残留未配对 toolCalls", m.toolCalls == null || m.toolCalls.isEmpty());
        }
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AgentLoopTest`
Expected: FAIL，`answerAskUser` 未定义 / registry 无 ask_user

- [ ] **Step 3: 实现**

`AgentLoop.java`：

① 字段区（`private final ExecutorService pool;` 附近）加：

```java
    /** ask_user 工具实例（构造注册；answerAskUser 经其送达回答） */
    private final com.minion.core.tools.AskUserTool askUserTool;
```

② 构造器（`registry.register(new TodoWriteTool(...));` 之后）加：

```java
        this.askUserTool = new com.minion.core.tools.AskUserTool(ui);
        registry.register(askUserTool);
```

③ `setSubAgentRunner` 附近加公共方法：

```java
    /** 回答 ask_user（SessionManager.sendAnswer 转发）；无挂起时忽略 */
    public boolean answerAskUser(String answer) {
        return askUserTool.complete(answer);
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=AgentLoopTest,AskUserToolTest`
Expected: PASS（AgentLoopTest 既有用例 + 新增 3 例；ask_user 挂起时终止的清洗由既有 scrubHalfTurn 完成）

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: AgentLoop 接线 ask_user（自动注册+answerAskUser 挂起回答）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 7: 子 agent 屏蔽 ask_user + 系统提示词指引

**Files:**
- Modify: `src/main/java/com/minion/core/agent/SubAgentLoop.java`
- Modify: `src/main/java/com/minion/core/agent/SystemPromptBuilder.java`
- Test: `src/test/java/com/minion/core/agent/SubAgentLoopTest.java`
- Test: `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java`

**Interfaces:**
- Consumes: `AskUserTool`（Task 4，测试注册用）
- Produces: 子 agent 请求 schema 不含 ask_user；违规调用返回错误不挂起；系统提示词规则 2/7 提及 ask_user 工具（保留既有测试断言的短语「不要猜测用户意图」「停止调用工具」「不要反复重试同一方法」）

- [ ] **Step 1: 写失败测试**

`SubAgentLoopTest.java` 追加：

```java
    /** 子 agent 工具集剔除 ask_user（防嵌套挂起）；违规调用返回错误不挂起 */
    @Test
    public void subAgent_excludesAskUserTool() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        registry.register(new com.minion.core.tools.AskUserTool(new RecordingUi()));
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();

        com.minion.core.llm.ToolCall tc = new com.minion.core.llm.ToolCall();
        tc.id = "s1";
        tc.name = "ask_user";
        tc.arguments = "{\"question\":\"问？\"}";
        llm.addTurnWithTools(java.util.Collections.singletonList(tc), null);
        llm.addTurn("子任务完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.run();
        // schema 已剔除（模型不可见）
        for (com.google.gson.JsonObject s : llm.requests.get(0).tools) {
            String name = s.getAsJsonObject("function").get("name").getAsString();
            assertFalse("子 agent 不得暴露 ask_user", "ask_user".equals(name));
        }
        // 防御：即使模型违规调用，也返回错误、不挂起
        assertTrue(ui.toolResults.contains("ask_user"));
        assertTrue(ui.asksStarted.isEmpty());
    }
```

`SystemPromptBuilderTest.java` 追加：

```java
    /** 规则指引模型用 ask_user 工具提问（替代纯文本提问等待） */
    @Test
    public void build_mentionsAskUserTool() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md").build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList(),
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertTrue(prompt.contains("ask_user"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SubAgentLoopTest,SystemPromptBuilderTest`
Expected: FAIL，`subAgent_excludesAskUserTool` 断言失败（ask_user 未过滤）+ `build_mentionsAskUserTool` 断言失败

- [ ] **Step 3: 实现**

`SubAgentLoop.java` 的 `subAgentTools()`：

```java
        for (JsonObject s : registry.schemas()) {
            if ("task".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("ask_user".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            list.add(s);
        }
```

`runOneTool` 中 task 防御之后加：

```java
            if ("ask_user".equals(call.name)) {
                // 防御：子 agent 不得挂起询问用户（ask_user 已从 schema 剔除；防模型幻觉调用）
                return ToolResult.error("子 agent 不可询问用户（ask_user 工具已禁用）");
            }
```

`SystemPromptBuilder.java` 的规则 2 与规则 7（必须保留既有测试断言的短语）：

```java
          + "2. 用户指令不明确、信息不足或存在多种可能理解时，先列出需要补充的问题，调用 ask_user 工具向用户提问，等待用户回答后再行动；不要猜测用户意图。\n"
```

```java
          + "7. 当工具连续失败、或发现缺少完成任务所必需的信息/权限时，停止调用工具；向用户说明已尝试的方案、失败原因，并列出需要用户补充的信息或需要用户选择的方案（可调用 ask_user 工具提问），等待用户回复。不要反复重试同一方法。";
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SubAgentLoopTest,SystemPromptBuilderTest`
Expected: PASS

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: 子 agent 屏蔽 ask_user+系统提示词指引模型用 ask_user 提问

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/core/agent/SubAgentLoop.java src/main/java/com/minion/core/agent/SystemPromptBuilder.java src/test/java/com/minion/core/agent/SubAgentLoopTest.java src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 8: SessionManager 补充/回答入口与提问状态通知

**Files:**
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`
- Modify: `src/main/java/com/minion/gui/session/SessionHandle.java`
- Test: `src/test/java/com/minion/gui/session/SessionManagerTest.java`

**Interfaces:**
- Consumes: `AgentLoop.offerSupplement/answerAskUser`（Task 5/6）、`SessionController.onUserSupplement/setAskStateListener`（Task 3）
- Produces:
  - `SessionManager.sendSupplement(SessionHandle h, String text)`、`sendAnswer(SessionHandle h, String text)`
  - `SessionManager.Listener.onSessionAskChanged(SessionHandle h, boolean asking, String question)`——**default 空实现**（既有匿名实现类零破坏）
  - `SessionHandle.askPending` / `askQuestion` volatile 字段

- [ ] **Step 1: 写失败测试**

`SessionManagerTest.java` 追加 2 个测试：

```java
    /** 补充：入挂起队列 + 发 USER_SUPPLEMENT 事件（不触网，真实 SessionManager 即可） */
    @Test
    public void sendSupplement_queuesAndEmitsEvent() throws Exception {
        SessionManager m = newManager();
        SessionHandle h = m.createSession(null);
        m.sendSupplement(h, "补充内容");
        assertEquals(1, h.session.pendingSupplements.size());
        assertEquals("补充内容", h.session.pendingSupplements.get(0));
        List<EventList.Ev> evs = h.controller.eventList().snapshot();
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
    }

    /** ask_user 挂起 → sendAnswer → 回答入历史继续本轮；ask 状态通知与复位 */
    @Test
    public void sendAnswer_resumesAskUserAndResetsState() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        final List<Boolean> askStates = new ArrayList<Boolean>();
        final CountDownLatch askStarted = new CountDownLatch(1);
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { }
            @Override public void onSessionAskChanged(SessionHandle h, boolean asking, String question) {
                if (asking) { askStates.add(true); askStarted.countDown(); }
                else askStates.add(false);
            }
        });
        SessionHandle h = m.createSession(null);
        FakeLlmClient llm = m.created.get(0);
        com.minion.core.llm.ToolCall q = new com.minion.core.llm.ToolCall();
        q.id = "q1";
        q.name = "ask_user";
        q.arguments = "{\"question\":\"选哪个？\"}";
        llm.addTurnWithTools(java.util.Collections.singletonList(q), null);
        llm.addTurn("按你的选择执行");
        m.send(h, "帮我选一下");
        assertTrue("ask_user 未挂起", askStarted.await(5, TimeUnit.SECONDS));
        assertTrue(h.askPending);
        assertEquals("选哪个？", h.askQuestion);
        m.sendAnswer(h, "方案B");
        long deadline = System.currentTimeMillis() + 5000;
        while (h.running && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertFalse(h.running);
        assertFalse(h.askPending);
        // 回答作为 TOOL 消息入历史并继续本轮
        Message answer = h.session.messages.get(2);
        assertEquals(Message.Role.TOOL, answer.role);
        assertTrue(answer.content.contains("方案B"));
        assertEquals("按你的选择执行", h.session.messages.get(3).content);
    }
```

（`Message`、`CountDownLatch`、`TimeUnit` 该文件已 import；`ToolCall` 未导入须用全限定名；`EventList` 与测试同包无需导入。）

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionManagerTest`
Expected: FAIL，`sendSupplement`/`sendAnswer`/`onSessionAskChanged`/`askPending` 未定义编译错误

- [ ] **Step 3: 实现**

`SessionHandle.java` 在 `deleted` 字段后加：

```java
    /** ask_user 挂起中（输入框进入回答模式；回答/中断/回合结束复位） */
    public volatile boolean askPending;
    /** ask_user 问题文本（回答模式占位提示用） */
    public volatile String askQuestion;
```

`SessionManager.java` 五处修改：

① `Listener` 接口内（`onError` 后）加 default 方法：

```java
        /** ask_user 挂起状态变化（asking=true 且 question 非空=开始挂起；asking=false=复位） */
        default void onSessionAskChanged(SessionHandle h, boolean asking, String question) { }
```

② `notifyWorkspaceChanged()` 附近加通知方法：

```java
    private void notifyAskChanged(SessionHandle h, boolean asking) {
        for (Listener l : listeners) l.onSessionAskChanged(h, asking, asking ? h.askQuestion : null);
    }
```

③ `restoreSessions` 中 `ctx.sessions.add(new SessionHandle(...));` 重构为（先建句柄再接线）：

```java
                SessionHandle h = new SessionHandle(s.id, ctx.name, s, loop, controller,
                        s.title, false, llm);
                controller.setAskStateListener(new java.util.function.Consumer<String>() {
                    @Override public void accept(String question) {
                        h.askQuestion = question;
                        h.askPending = question != null;
                        notifyAskChanged(h, question != null);
                    }
                });
                ctx.sessions.add(h);
```

④ `createSession` 中 `ctx.sessions.add(h);` 之前加同样的接线：

```java
        controller.setAskStateListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String question) {
                h.askQuestion = question;
                h.askPending = question != null;
                notifyAskChanged(h, question != null);
            }
        });
```

⑤ `send` 中 `h.running = false;` 的两处（finally 正常路径与 catch 路径）之后各加：

```java
                        if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                            h.askPending = false;
                            h.askQuestion = null;
                            notifyAskChanged(h, false);
                        }
```

并在 `stop` 方法之后新增两个公共方法：

```java
    /** 运行中补充：入 AgentLoop 挂起队列 + 发聊天标识事件（UI 事件仅在点击时发一次，注入不重发） */
    public void sendSupplement(final SessionHandle h, final String text) {
        if (h == null || text == null || text.trim().isEmpty()) return;
        h.loop.offerSupplement(text);
        h.controller.onUserSupplement(text);
    }

    /** 回答 ask_user：完成挂起的等待（未挂起时忽略）；回答作为工具结果回传继续本轮 */
    public void sendAnswer(final SessionHandle h, final String text) {
        if (h == null || !h.running) return;
        h.loop.answerAskUser(text);
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SessionManagerTest`
Expected: PASS（含既有全部用例）

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: SessionManager 补充/回答入口与 ask_user 提问状态通知

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/gui/session/SessionManager.java src/main/java/com/minion/gui/session/SessionHandle.java src/test/java/com/minion/gui/session/SessionManagerTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 9: InputView 图标按钮与状态机

**Files:**
- Modify: `src/main/java/com/minion/gui/input/InputView.java`
- Create: `src/test/java/com/minion/gui/input/InputViewButtonTest.java`

**Interfaces:**
- Consumes: `SessionManager.sendSupplement/sendAnswer/stop`（Task 8）
- Produces:
  - `InputView.onAskChanged(SessionHandle h, boolean asking, String question)`（MainWindow 在 Task 10 转发调用）
  - 包级静态 `InputView.buttonMode(boolean running, boolean askPending, boolean hasContent)` 与 `InputView.BtnMode` 枚举（供纯函数单测）

- [ ] **Step 1: 写失败测试**

新建 `src/test/java/com/minion/gui/input/InputViewButtonTest.java`（只测静态纯函数，不实例化 JavaFX 控件、无需 toolkit）：

```java
package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;

/** 按钮状态机纯函数测试：图标/透明度/背景类/动作的判定依据 */
public class InputViewButtonTest {

    @Test
    public void idle_withContent_send() {
        assertEquals(InputView.BtnMode.SEND, InputView.buttonMode(false, false, true));
    }

    @Test
    public void idle_empty_sendDim() {
        assertEquals(InputView.BtnMode.SEND_DIM, InputView.buttonMode(false, false, false));
    }

    @Test
    public void running_empty_stop() {
        assertEquals(InputView.BtnMode.STOP, InputView.buttonMode(true, false, false));
    }

    @Test
    public void running_withContent_supplement() {
        assertEquals(InputView.BtnMode.SUPPLEMENT, InputView.buttonMode(true, false, true));
    }

    @Test
    public void asking_empty_stop() {
        assertEquals(InputView.BtnMode.STOP, InputView.buttonMode(true, true, false));
    }

    @Test
    public void asking_withContent_answer() {
        assertEquals(InputView.BtnMode.ANSWER, InputView.buttonMode(true, true, true));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=InputViewButtonTest`
Expected: FAIL，`BtnMode`/`buttonMode` 未定义编译错误

- [ ] **Step 3: 实现**

重写 `InputView.java`（整文件替换，保留 `onSend` 的自动建会话逻辑）：

```java
package com.minion.gui.input;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** 底部输入区：多行 TextArea + 单图标按钮（上箭头=发送/补充/回答、方块=终止、变淡箭头=空输入）。
 *  运行中 + 有内容 → 补充；等待回答 + 有内容 → 回答；运行中 + 空 → 终止。 */
public class InputView extends VBox {

    /** 按钮模式：图标/透明度/背景类/动作的判定依据 */
    enum BtnMode { SEND, SEND_DIM, SUPPLEMENT, ANSWER, STOP }

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button();
    private final SVGPath arrowIcon = new SVGPath();
    private final SVGPath stopIcon = new SVGPath();
    private volatile SessionHandle current;
    // FX 线程缓存的状态（bindSession/onRunningChanged/onAskChanged 维护）
    private boolean running;
    private boolean askPending;
    private String askQuestion;

    public InputView(final SessionManager manager) {
        this.manager = manager;
        getStyleClass().add("panel-dark");
        setSpacing(8);
        setStyle("-fx-padding: 12 16 12 16;");

        input.getStyleClass().add("input-area");
        input.setWrapText(true);
        input.setPromptText("输入消息…  (Ctrl+Enter 发送)");
        input.setPrefRowCount(2);
        input.setMaxHeight(6 * 24);
        input.textProperty().addListener((obs, ov, nv) -> updateButton());

        // 上箭头（Claude Code 同款语义：可发送）；方块 = 终止
        arrowIcon.setContent("M12 4 L20 13 L15 13 L15 21 L9 21 L9 13 L4 13 Z");
        arrowIcon.getStyleClass().add("icon-send");
        stopIcon.setContent("M7 7 L17 7 L17 17 L7 17 Z");
        stopIcon.getStyleClass().add("icon-stop");

        sendButton.setMinSize(36, 36);
        sendButton.setPrefSize(36, 36);
        sendButton.setOnAction(e -> onAction());
        updateButton();

        input.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onAction();
            }
        });

        HBox buttonRow = new HBox(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonRow.getChildren().addAll(spacer, sendButton);
        VBox.setVgrow(input, Priority.ALWAYS);
        getChildren().addAll(input, buttonRow);
    }

    /** 纯静态判定（可脱离 JavaFX 单测）：运行/提问挂起/有内容 → 按钮模式 */
    static BtnMode buttonMode(boolean running, boolean askPending, boolean hasContent) {
        if (!running) return hasContent ? BtnMode.SEND : BtnMode.SEND_DIM;
        if (askPending) return hasContent ? BtnMode.ANSWER : BtnMode.STOP;
        return hasContent ? BtnMode.SUPPLEMENT : BtnMode.STOP;
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        Platform.runLater(() -> {
            running = h != null && h.running;
            askPending = h != null && h.askPending;
            askQuestion = h == null ? null : h.askQuestion;
            updateButton();
            updatePrompt();
        });
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.running = running;
            updateButton();
        });
    }

    /** ask_user 挂起状态变化（MainWindow 转发自 SessionManager 监听） */
    public void onAskChanged(SessionHandle h, boolean asking, String question) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.askPending = asking;
            this.askQuestion = question;
            updateButton();
            updatePrompt();
        });
    }

    private boolean hasContent() {
        return input.getText() != null && !input.getText().trim().isEmpty();
    }

    private void updatePrompt() {
        if (askPending) {
            String q = askQuestion == null ? "" : askQuestion;
            input.setPromptText("回答: " + (q.length() > 40 ? q.substring(0, 40) + "…" : q));
        } else {
            input.setPromptText("输入消息…  (Ctrl+Enter 发送)");
        }
    }

    private void updateButton() {
        switch (buttonMode(running, askPending, hasContent())) {
            case SEND:       applyStyle(arrowIcon, "btn-primary", 1.0, "发送 (Ctrl+Enter)"); break;
            case SEND_DIM:   applyStyle(arrowIcon, "btn-primary", 0.35, "输入消息后发送 (Ctrl+Enter)"); break;
            case SUPPLEMENT: applyStyle(arrowIcon, "btn-primary", 1.0, "补充信息给正在运行的模型 (Ctrl+Enter)"); break;
            case ANSWER:     applyStyle(arrowIcon, "btn-primary", 1.0, "回答模型的提问 (Ctrl+Enter)"); break;
            case STOP:       applyStyle(stopIcon, "btn-danger", 1.0, "终止当前运行"); break;
        }
    }

    private void applyStyle(SVGPath graphic, String styleClass, double opacity, String tip) {
        sendButton.setGraphic(graphic);
        sendButton.getStyleClass().removeAll("btn-primary", "btn-danger");
        sendButton.getStyleClass().add(styleClass);
        sendButton.setOpacity(opacity);
        sendButton.setTooltip(new Tooltip(tip));
    }

    /** Ctrl+Enter / 按钮点击统一入口：按当前模式分发 */
    private void onAction() {
        switch (buttonMode(running, askPending, hasContent())) {
            case SEND:
                onSend();
                break;
            case SUPPLEMENT: {
                String text = input.getText();
                if (text == null || text.trim().isEmpty()) return;
                input.clear();
                if (current != null) manager.sendSupplement(current, text);
                break;
            }
            case ANSWER: {
                String text = input.getText();
                if (text == null || text.trim().isEmpty()) return;
                input.clear();
                if (current != null) manager.sendAnswer(current, text);
                break;
            }
            case STOP:
                if (current != null) manager.stop(current);
                break;
            case SEND_DIM:
                break;
        }
    }

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.clear();
        SessionHandle target = current;
        if (target == null) {
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        manager.send(target, text);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=InputViewButtonTest`
Expected: PASS

- [ ] **Step 5: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: 输入框图标按钮与状态机（箭头发送/补充/回答，方块终止，空输入变淡）

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/gui/input/InputView.java src/test/java/com/minion/gui/input/InputViewButtonTest.java
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

### Task 10: MainWindow 转发 + ChatView 渲染 + 主题样式

**Files:**
- Modify: `src/main/java/com/minion/gui/MainWindow.java`
- Modify: `src/main/java/com/minion/gui/chat/ChatView.java`
- Modify: `src/resource/theme/theme.css`

**Interfaces:**
- Consumes: `EventList.Kind.USER_SUPPLEMENT`（Task 3）、`InputView.onAskChanged`（Task 9）、`SessionManager.Listener.onSessionAskChanged`（Task 8）

- [ ] **Step 1: MainWindow 转发**

在 `MainWindow.java` 的 manager 监听匿名类中 `onSessionRunningChanged` 覆写之后加：

```java
            @Override public void onSessionAskChanged(SessionHandle h, boolean asking, String question) {
                if (inputView != null) inputView.onAskChanged(h, asking, question);
            }
```

- [ ] **Step 2: ChatView 渲染**

`ChatView.java` 的 `onEventFx` switch 中：

① `USER_MESSAGE` 分支后加：

```java
            case USER_SUPPLEMENT: {
                VBox box = new VBox(2);
                Label tag = new Label("⤒ 运行中补充");
                tag.getStyleClass().add("supplement-tag");
                Label l = new Label(e.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-user");
                box.getChildren().addAll(tag, l);
                getChildren().add(box);
                break;
            }
```

② `TOOL_CALL` 分支改为（ask_user 特判渲染问题卡片）：

```java
            case TOOL_CALL: {
                if ("ask_user".equals(e.text)) {
                    VBox card = new VBox(4);
                    card.getStyleClass().add("card");
                    Label name = new Label("❓ 模型向你提问");
                    name.getStyleClass().add("msg-thinking");
                    Label q = new Label(askQuestionOf(e.data));
                    q.setWrapText(true);
                    q.getStyleClass().add("msg-thinking");
                    card.getChildren().addAll(name, q);
                    getChildren().add(card);
                } else {
                    VBox card = new VBox(4);
                    card.getStyleClass().add("card");
                    Label name = new Label("🔧 " + e.text);
                    name.getStyleClass().add("msg-thinking");
                    Label detail = new Label(shorten(e.data == null ? "{}" : e.data.toString(), 120));
                    detail.getStyleClass().add("msg-thinking");
                    card.getChildren().addAll(name, detail);
                    getChildren().add(card);
                }
                break;
            }
```

③ 类内（`shorten` 方法旁）加解析辅助（需要 import `com.google.gson.JsonObject` 与 `com.google.gson.JsonParser`）：

```java
    /** ask_user 工具调用的 question 参数（解析失败回空串） */
    private static String askQuestionOf(Object data) {
        try {
            JsonObject o = JsonParser.parseString(data == null ? "{}" : data.toString())
                    .getAsJsonObject();
            return o.has("question") ? o.get("question").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }
```

- [ ] **Step 3: theme.css 样式**

`theme.css` 的 `/* 按钮 */` 区块末尾加图标填充色，`/* 消息 */` 区块加补充徽标：

```css
/* 图标按钮（输入区发送/终止；SVGPath 填充不继承 -fx-text-fill，须显式指定） */
.icon-send { -fx-fill: white; }
.icon-stop { -fx-fill: white; }
```

```css
/* 运行中补充气泡的小徽标 */
.supplement-tag { -fx-text-fill: #8ab4ff; -fx-font-size: 11px; -fx-font-weight: bold; }
```

- [ ] **Step 4: 全量测试 + 构建**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: PASS（全部既有 + 新增）

Run: `JAVA_HOME="E:/javame/jdk8" mvn clean package`
Expected: BUILD SUCCESS

- [ ] **Step 5: 手工验证（minion.bat 启动 GUI）**

1. 空闲 + 空输入：按钮为变淡箭头；输入文字后变亮；Ctrl+Enter 发送正常
2. 运行中 + 空输入：按钮为红色方块，点击终止正常
3. 运行中 + 输入文字：按钮变箭头（tooltip「补充信息给正在运行的模型」），点击后聊天区出现「⤒ 运行中补充」气泡，模型继续工作且后续回复体现补充内容
4. 向模型提问一个需要选择的问题（如「帮我选方案，先问我」），模型调用 ask_user 后：聊天区出现「❓ 模型向你提问」卡片，输入框占位变「回答: …」，按钮箭头 tooltip「回答模型的提问」；输入回答回车后本轮继续
5. ask_user 挂起时点击方块终止：运行结束、无残留错误
6. 运行中补充后立刻终止：聊天区气泡保留，下次发送时补充与输入合并生效（模型回复体现两条信息）
7. 关闭应用重启：历史会话中补充气泡与标识仍在（落盘回放）

- [ ] **Step 6: Commit**

Write `.git/COMMIT_MSG_TMP.txt`：

```
feat: 聊天区补充标识与提问卡片渲染+图标按钮主题样式

Co-Authored-By: Claude <noreply@anthropic.com>
```

```bash
git add src/main/java/com/minion/gui/MainWindow.java src/main/java/com/minion/gui/chat/ChatView.java src/resource/theme/theme.css
git commit -F .git/COMMIT_MSG_TMP.txt && rm .git/COMMIT_MSG_TMP.txt
```

---

## 完成前自查（全部任务后）

- [ ] `JAVA_HOME="E:/javame/jdk8" mvn test` 全绿
- [ ] `JAVA_HOME="E:/javame/jdk8" mvn clean package` 成功
- [ ] README 使用说明补充：运行中补充按钮与 ask_user 提问机制（一段话即可）
- [ ] 设计文档与计划文档同步（实现如有偏差回写 design 文档）
