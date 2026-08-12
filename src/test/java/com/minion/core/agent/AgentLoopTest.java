package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.StreamHandler;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class AgentLoopTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Config config;
    private FakeLlmClient llm;
    private ToolRegistry registry;
    private RecordingUi ui;
    private ConfirmGate confirm;

    @org.junit.Before
    public void setup() throws Exception {
        config = Config.load(tmp.getRoot().toPath());
        llm = new FakeLlmClient();
        registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        registry.register(new com.minion.core.tools.BashTool(
                new com.minion.core.tools.Workspace(tmp.getRoot().getPath())));
        ui = new RecordingUi();
        confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
    }

    private AgentLoop newLoop() {
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10; // 测试用
        return loop;
    }

    @Test
    public void singleTurn_noTools() {
        llm.addTurn("好的");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        // 0:user 1:assistant
        assertEquals(2, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
        assertEquals("你好", loop.messages().get(0).content);
        assertEquals(Message.Role.ASSISTANT, loop.messages().get(1).role);
        assertEquals("好的", loop.messages().get(1).content);
        // 请求 = system + 全部历史
        assertEquals(Message.Role.SYSTEM, llm.lastRequestMessages.get(0).role);
        assertEquals(2, llm.lastRequestMessages.size());
        assertEquals(1, ui.contentParts.size());
    }

    @Test
    public void toolLoop_executesAndReturns() {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"hi\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("处理完成");
        AgentLoop loop = newLoop();
        loop.runUserTurn("调用一下");
        // 0:user 1:assistant(tool_calls) 2:tool(result) 3:assistant(final)
        List<Message> msgs = loop.messages();
        assertEquals(4, msgs.size());
        assertEquals(Message.Role.TOOL, msgs.get(2).role);
        assertTrue(msgs.get(2).content.contains("echo: hi"));
        assertEquals("处理完成", msgs.get(3).content);
        assertEquals(1, ui.toolCalls.size());
        assertEquals("example", ui.toolCalls.get(0));
        assertEquals(1, ui.toolResults.size());
    }

    @Test
    public void roundLimit_stopsLoop() {
        for (int i = 0; i < 5; i++) {
            ToolCall tc = new ToolCall();
            tc.id = "c" + i;
            tc.name = "example";
            tc.arguments = "{\"text\":\"x\"}";
            llm.addTurnWithTools(Collections.singletonList(tc), null);
        }
        AgentLoop loop = newLoop();
        loop.roundLimit = 3;
        loop.runUserTurn("循环");
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("工具轮数上限")));
        // 1 user + 3 × (assistant工具调用 + tool结果)
        assertEquals(7, loop.messages().size());
        assertEquals(Message.Role.TOOL, loop.messages().get(loop.messages().size() - 1).role);
    }

    @Test
    public void parallelTools_bothExecuted() {
        ToolCall tc1 = new ToolCall();
        tc1.id = "a1";
        tc1.name = "example";
        tc1.arguments = "{\"text\":\"one\"}";
        ToolCall tc2 = new ToolCall();
        tc2.id = "a2";
        tc2.name = "example";
        tc2.arguments = "{\"text\":\"two\"}";
        llm.addTurnWithTools(Arrays.asList(tc1, tc2), null);
        llm.addTurn("完成");
        AgentLoop loop = newLoop();
        loop.runUserTurn("并行");
        Message tool1 = loop.messages().get(2);
        Message tool2 = loop.messages().get(3);
        assertEquals(Message.Role.TOOL, tool1.role);
        assertEquals(Message.Role.TOOL, tool2.role);
        assertTrue(tool1.content.contains("one"));
        assertTrue(tool2.content.contains("two"));
        assertEquals(2, ui.toolCalls.size());
    }

    @Test
    public void confirmReject_toolReturnsRejected() {
        FakeConfirmUi rejectUi = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        // 用 Bash 危险命令触发确认
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "Bash";
        tc.arguments = "{\"command\":\"rm -rf x\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("好，换个方案");
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, rejectUi), ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        loop.runUserTurn("删掉");
        Message tool = loop.messages().get(2);
        assertTrue(tool.content.contains("拒绝"));
        assertTrue(tool.content.contains("rm"));
    }

    @Test
    public void interrupt_cancelsInFlightTurn() throws Exception {
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
        assertTrue(blocking.cancelled);
        t.join(5000);
        assertFalse(t.isAlive());
        // 0:user 1:assistant（打断前已收到的回复）
        assertEquals(2, loop.messages().size());
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("中断")));
    }

    /** 可阻塞的测试客户端：进入请求后等待 interrupt 触发 cancel */
    public static class BlockingLlmClient extends FakeLlmClient {
        public final CountDownLatch entered = new CountDownLatch(1);
        public volatile boolean cancelled = false;

        @Override
        public void cancel() { cancelled = true; }

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) {
            entered.countDown();
            try { Thread.sleep(300); } catch (InterruptedException e) { }
            super.streamChat(messages, tools, handler);
        }
    }

    @Test
    public void usage_recorded() {
        llm.addTurn("x");
        AgentLoop loop = newLoop();
        loop.runUserTurn("统计");
        assertEquals(15, loop.usage().sessionTotal()); // Fake: input 10 + output 5
    }

    @Test
    public void thinking_reasoningContentInHistory() {
        // 带 thinking 的 turn：assistant 消息 reasoningContent 必须入历史并随请求回传（DeepSeek 硬性要求）
        ThinkingLlmClient thinkingLlm = new ThinkingLlmClient();
        AgentLoop loop = new AgentLoop(thinkingLlm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        loop.runUserTurn("思考题");
        assertEquals(2, loop.messages().size());
        Message assistant = loop.messages().get(1);
        assertEquals(Message.Role.ASSISTANT, assistant.role);
        assertEquals("思考中...", assistant.reasoningContent);
        assertEquals(1, ui.thinking.size());
        // 回传链路：toApiJson 必须带 reasoning_content
        assertTrue(assistant.toApiJson().has("reasoning_content"));
        assertEquals("思考中...", assistant.toApiJson().get("reasoning_content").getAsString());
    }

    /** M2：工具执行阶段中断 → 半轮残留（assistant 带 toolCalls 无完整 tool 结果）不得留在历史 */
    @Test
    public void interrupt_duringToolExecution_leavesNoToolCallsResidue() throws Exception {
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
        Thread.sleep(200); // 确保 inFlight 已注册完成再中断
        loop.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        // 无半轮残留：任何 assistant 消息都不得带 toolCalls（空壳整条移除）
        for (Message m : loop.messages()) {
            assertTrue("残留 toolCalls: " + m.role, m.toolCalls == null || m.toolCalls.isEmpty());
        }
        assertEquals(Message.Role.USER, loop.messages().get(loop.messages().size() - 1).role);
    }

    /** M2：restoreSession 对恢复历史做半轮清洗（末条 assistant 含 toolCalls 且无后续 TOOL 结果 → 剥离） */
    @Test
    public void restoreSession_scrubsHalfTurnResidue() {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "example";
        tc.arguments = "{}";
        // 纯工具调用空壳：整条移除
        Session saved = Session.create(tmp.getRoot().getPath(), "test-model");
        saved.messages.add(Message.user("任务"));
        Message half = Message.assistant(null);
        half.toolCalls = Collections.singletonList(tc);
        saved.messages.add(half);
        AgentLoop loop = newLoop();
        loop.restoreSession(saved);
        assertEquals(1, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
        // 带正文的 assistant：保留正文、剥离 toolCalls
        Session saved2 = Session.create(tmp.getRoot().getPath(), "test-model");
        saved2.messages.add(Message.user("任务2"));
        Message half2 = Message.assistant("已分析");
        half2.toolCalls = Collections.singletonList(tc);
        saved2.messages.add(half2);
        AgentLoop loop2 = newLoop();
        loop2.restoreSession(saved2);
        assertEquals(2, loop2.messages().size());
        Message assistant = loop2.messages().get(1);
        assertEquals("已分析", assistant.content);
        assertTrue(assistant.toolCalls == null || assistant.toolCalls.isEmpty());
    }

    /** S3：loadSkill 按 name 判重，重复加载不重复注入系统提示词 */
    @Test
    public void loadSkill_deduplicatesByName() {
        AgentLoop loop = newLoop();
        loop.loadSkill(new Skill("review", "审查", "执行审查", "review.skill.md"));
        loop.loadSkill(new Skill("review", "另一个描述", "执行审查 2", "review2.skill.md"));
        assertEquals(1, loop.loadedSkills().size());
        loop.loadSkill(new Skill("deploy", "部署", "发布", "deploy.skill.md"));
        assertEquals(2, loop.loadedSkills().size());
    }

    /** S5：restoreSession 恢复 usage 与 todos（T21 M6） */
    @Test
    public void restoreSession_restoresUsageAndTodos() {
        Session saved = Session.create(tmp.getRoot().getPath(), "test-model");
        Usage u = new Usage();
        u.inputTokens = 30;
        u.outputTokens = 20;
        u.reasoningTokens = 5;
        saved.usage.record(u);
        saved.todos.replace(Collections.singletonList(new TodoList.TodoItem("写文档", false)));
        AgentLoop loop = newLoop();
        loop.restoreSession(saved);
        assertEquals(50, loop.usage().sessionTotal());
        assertEquals(5, loop.usage().sessionThinking());
        assertEquals(1, loop.session().todos.items.size());
        assertEquals("写文档", loop.session().todos.items.get(0).text);
    }

    /** T4：restoreSession 恢复会话 cwd（有效路径 → workspace 跟随） */
    @Test
    public void restoreSession_restoresCwd() throws Exception {
        Path sub = tmp.newFolder("sub").toPath();
        Session saved = Session.create(tmp.getRoot().getPath(), "test-model");
        saved.cwd = sub.toString();
        Workspace ws = new Workspace(tmp.getRoot().toPath().toString());
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null, ws,
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.restoreSession(saved);
        assertEquals(sub, ws.cwd());
    }

    /** T4：startNewSession 清空会话内容（消息/任务/统计）并回到工作区根 */
    @Test
    public void startNewSession_clearsSessionAndResetsCwd() throws Exception {
        Workspace ws = new Workspace(tmp.getRoot().toPath().toString());
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null, ws,
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.session().messages.add(Message.user("任务"));
        loop.session().todos.replace(Collections.singletonList(
                new TodoList.TodoItem("写文档", false)));
        Usage u = new Usage();
        u.inputTokens = 10;
        loop.session().usage.record(u);
        Path sub = tmp.newFolder("sub").toPath();
        ws.cd(sub.toString());
        assertEquals(sub, ws.cwd());
        loop.startNewSession();
        assertEquals(0, loop.messages().size());
        assertEquals(0, loop.session().todos.items.size());
        assertEquals(0, loop.usage().sessionTotal());
        assertEquals(tmp.getRoot().toPath(), ws.cwd());
    }

    /** T4:persistSession 落盘前快照当前 cwd。此前会话文件 cwd 恒为 null(所有保存入口
     *  只序列化 session 自身),/resume 后 cd 跨会话持久化静默失效 */
    @Test
    public void persistSession_serializesCurrentCwd() throws Exception {
        Path sub = tmp.newFolder("sub").toPath();
        Path storeDir = tmp.newFolder("sessions").toPath();
        SessionStore store = new SessionStore(storeDir);
        Workspace ws = new Workspace(tmp.getRoot().toPath().toString());
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null, ws,
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.setSessionStore(store);
        ws.cd(sub.toString());
        loop.persistSession();
        // 断言基于落盘文件内容而非内存对象:cd 后会话文件 cwd 必须是子目录绝对路径
        String json = new String(Files.readAllBytes(storeDir.resolve(loop.session().id + ".json")),
                StandardCharsets.UTF_8);
        JsonObject saved = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(sub.toAbsolutePath().normalize().toString(),
                saved.get("cwd").getAsString());
    }

    /** Task 6 回归：startNewSession 重新生成 id/createdAt。旧 id 会话已随 /new 落盘，
     *  新会话若沿用旧 id，后续自动落盘会覆盖上一个会话文件 */
    @Test
    public void startNewSession_regeneratesSessionId() throws Exception {
        AgentLoop loop = newLoop();
        String oldId = loop.session().id;
        String oldCreatedAt = loop.session().createdAt;
        loop.startNewSession();
        assertNotEquals(oldId, loop.session().id);
        assertNotEquals(oldCreatedAt, loop.session().createdAt);
        assertEquals(loop.session().id, loop.session().createdAt); // 与 Session.create 同机制
    }

    /** T4 回归：startNewSession 原地清空 todo/usage，Main 注册的 TodoWriteTool
     *  捕获的实例引用在 /new 后仍指向会话清单（换新实例会导致任务状态丢失） */
    @Test
    public void startNewSession_keepsCapturedTodoWriteToolReferenceValid() throws Exception {
        AgentLoop loop = newLoop();
        // 模拟 Main.java 的接线：TodoWriteTool 捕获 session.todos 的实例引用
        com.minion.core.tools.TodoWriteTool tool =
                new com.minion.core.tools.TodoWriteTool(loop.session().todos);
        loop.session().todos.replace(Collections.singletonList(
                new TodoList.TodoItem("写文档", false)));
        loop.startNewSession();
        assertEquals(0, loop.session().todos.items.size());

        JsonObject args = new JsonObject();
        args.addProperty("action", "update");
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("text", "新任务");
        items.add(item);
        args.add("items", items);
        tool.execute(args);
        assertEquals(1, loop.session().todos.items.size());
        assertEquals("新任务", loop.session().todos.items.get(0).text);
    }

    /** T4 回归：restoreSession 原地恢复 todo/usage，Main 注册的 TodoWriteTool
     *  捕获的实例引用在 /resume 后仍指向会话清单（换新实例会导致任务状态丢失） */
    @Test
    public void restoreSession_keepsCapturedTodoWriteToolReferenceValid() {
        AgentLoop loop = newLoop();
        // 模拟 Main.java 的接线：TodoWriteTool 捕获 session.todos 的实例引用
        com.minion.core.tools.TodoWriteTool tool =
                new com.minion.core.tools.TodoWriteTool(loop.session().todos);
        com.minion.core.llm.UsageTracker captured = loop.usage();
        // 保存的会话带任务与统计
        Session saved = Session.create(tmp.getRoot().getPath(), "test-model");
        saved.todos.replace(Collections.singletonList(
                new TodoList.TodoItem("写文档", false)));
        Usage u = new Usage();
        u.inputTokens = 30;
        u.outputTokens = 20;
        u.reasoningTokens = 5;
        saved.usage.record(u);
        loop.restoreSession(saved);
        // 恢复后通过恢复前捕获的引用写入必须落到会话清单（换实例会写入已废弃清单）
        JsonObject args = new JsonObject();
        args.addProperty("action", "update");
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("text", "恢复后的新任务");
        items.add(item);
        args.add("items", items);
        tool.execute(args);
        assertEquals(1, loop.session().todos.items.size());
        assertEquals("恢复后的新任务", loop.session().todos.items.get(0).text);
        // usage 统计同样原地复制：恢复前捕获的引用能看到恢复后的统计（换实例则为 0）
        assertEquals(50, captured.sessionTotal());
        assertEquals(5, captured.sessionThinking());
    }

    /** 阻塞工具：进入 execute 后吞掉中断持续阻塞，保证工具执行阶段的稳定中断路径（M2） */
    public static class BlockingTool implements Tool {
        public final CountDownLatch entered = new CountDownLatch(1);
        @Override public String name() { return "blocker"; }
        @Override public String description() { return "阻塞测试工具"; }
        @Override public JsonObject schema() {
            return com.minion.core.tools.SchemaGenerator.objectSchema("阻塞", new String[0], new String[0]);
        }
        @Override public ToolResult execute(JsonObject args) throws Exception {
            entered.countDown();
            long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException e) { /* 吞掉中断保持阻塞 */ }
            }
            return ToolResult.success("完成");
        }
    }

    /** 带思考的测试客户端：onThinking + onContent + onFinish */
    public static class ThinkingLlmClient extends FakeLlmClient {
        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) {
            lastRequestMessages = new ArrayList<Message>(messages);
            handler.onThinking("思考中...");
            handler.onContent("回答完毕");
            Usage u = new Usage();
            u.inputTokens = 10;
            u.outputTokens = 5;
            handler.onFinish("stop", u, new ArrayList<ToolCall>());
        }
    }

    private static long stuckHints(List<Message> msgs) {
        return msgs.stream().filter(m ->
                m.role == Message.Role.USER && m.content != null && m.content.contains("[系统提醒]")).count();
    }

    private static ToolCall failingCall(int i) {
        ToolCall tc = new ToolCall();
        tc.id = "f" + i;
        tc.name = "failing";
        tc.arguments = "{}";
        return tc;
    }

    @Test
    public void defaultRoundLimit_is1000() {
        assertEquals(1000, AgentLoop.DEFAULT_ROUND_LIMIT);
    }

    @Test
    public void stuck_30ConsecutiveFailures_injectsReminder() {
        registry.register(new FailingTool());
        for (int i = 0; i < 30; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        llm.addTurn("好的，我需要用户补充信息");
        AgentLoop loop = newLoop();
        loop.roundLimit = 40;
        loop.runUserTurn("任务");
        assertEquals(1, stuckHints(loop.messages()));
        Message hint = loop.messages().stream().filter(m ->
                m.role == Message.Role.USER && m.content != null && m.content.contains("[系统提醒]"))
                .findFirst().get();
        assertTrue(hint.content.contains("30 次"));
        assertTrue(loop.messages().get(loop.messages().size() - 1).role == Message.Role.ASSISTANT);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("工具连续失败")));
    }

    @Test
    public void stuck_29Failures_noReminder() {
        registry.register(new FailingTool());
        for (int i = 0; i < 29; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        llm.addTurn("结束");
        AgentLoop loop = newLoop();
        loop.roundLimit = 40;
        loop.runUserTurn("任务");
        assertEquals(0, stuckHints(loop.messages()));
    }

    @Test
    public void stuck_successResetsCounter() {
        registry.register(new FailingTool());
        for (int i = 0; i < 10; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        ToolCall ok = new ToolCall();
        ok.id = "ok";
        ok.name = "example";
        ok.arguments = "{\"text\":\"x\"}";
        llm.addTurnWithTools(Collections.singletonList(ok), null);
        for (int i = 0; i < 29; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(100 + i)), null);
        }
        llm.addTurn("结束");
        AgentLoop loop = newLoop();
        loop.roundLimit = 60;
        loop.runUserTurn("任务");
        // 成功清零后仅 29 次连续失败，不注入
        assertEquals(0, stuckHints(loop.messages()));
    }

    @Test
    public void stuck_injectionResetsCounter_second30InjectsAgain() {
        registry.register(new FailingTool());
        for (int i = 0; i < 60; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        llm.addTurn("结束");
        AgentLoop loop = newLoop();
        loop.roundLimit = 70;
        loop.runUserTurn("任务");
        // 注入后计数重置：60 次失败 → 两次提醒
        assertEquals(2, stuckHints(loop.messages()));
    }

    /** 总是失败的测试工具：驱动连续失败计数 */
    public static class FailingTool implements Tool {
        @Override public String name() { return "failing"; }
        @Override public String description() { return "总是失败的测试工具"; }
        @Override public JsonObject schema() {
            return com.minion.core.tools.SchemaGenerator.objectSchema("失败", new String[0], new String[0]);
        }
        @Override public ToolResult execute(JsonObject args) { return ToolResult.error("模拟失败"); }
    }
}
