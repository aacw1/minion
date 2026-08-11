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
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
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
                new com.minion.core.tools.Workspace(config.workDir())));
        ui = new RecordingUi();
        confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
    }

    private AgentLoop newLoop() {
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, ui);
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
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config),
                new ConfirmGate(config, rejectUi), ui);
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
        AgentLoop loop = new AgentLoop(config, blocking, registry,
                new SystemPromptBuilder(config), confirm, ui);
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
        AgentLoop loop = new AgentLoop(config, thinkingLlm, registry,
                new SystemPromptBuilder(config), confirm, ui);
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
        Session saved = Session.create(config);
        saved.messages.add(Message.user("任务"));
        Message half = Message.assistant(null);
        half.toolCalls = Collections.singletonList(tc);
        saved.messages.add(half);
        AgentLoop loop = newLoop();
        loop.restoreSession(saved);
        assertEquals(1, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
        // 带正文的 assistant：保留正文、剥离 toolCalls
        Session saved2 = Session.create(config);
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
        Session saved = Session.create(config);
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
}
