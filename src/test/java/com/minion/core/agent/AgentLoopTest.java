package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
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
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
                throws LlmException {
            entered.countDown();
            try { Thread.sleep(300); } catch (InterruptedException e) { }
            super.streamChat(messages, tools, handler);
        }
    }

    /** 需求 15：流式中断——第一轮进入后阻塞等 interrupt()，先回调部分内容再抛异常模拟取消。
     *  直接实现 LlmClient 而非继承 FakeLlmClient：FakeLlmClient 支持脚本化 throw 后覆写可正常抛异常 */
    public static class InterruptibleStreamLlm implements LlmClient {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch cancelSignal = new CountDownLatch(1);
        public List<Message> lastRequestMessages = new ArrayList<Message>();
        private final List<String> turns = new ArrayList<String>();
        private int cursor = 0;

        public void addTurn(String content) { turns.add(content); }

        @Override public void cancel() { cancelSignal.countDown(); }

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
                throws LlmException {
            lastRequestMessages = new ArrayList<Message>(messages);
            if (entered.getCount() > 0) { // 仅第一轮：阻塞等中断信号
                entered.countDown();
                try {
                    cancelSignal.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                handler.onThinking("已经分析到一半");
                handler.onContent("部分回复内容");
                throw new LlmException(LlmException.Type.OTHER, "模拟中断", false);
            }
            // 第二轮起正常回放（与 FakeLlmClient 出牌语义一致）
            String turn = turns.get(Math.min(cursor, turns.size() - 1));
            cursor++;
            Usage u = new Usage();
            u.inputTokens = 10;
            u.outputTokens = 5;
            handler.onContent(turn);
            handler.onFinish("stop", u, new ArrayList<ToolCall>());
        }

        @Override
        public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            return null; // 测试未启用上下文压缩，不会走到
        }
    }

    /** 需求 15：流式中断后，中断前收到的部分回复（含思考）进入历史，且下次请求携带 */
    @Test
    public void interrupt_partialReplyKeptForNextTurn() throws Exception {
        InterruptibleStreamLlm p = new InterruptibleStreamLlm();
        AgentLoop loop = new AgentLoop(p, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        Thread t = new Thread(() -> loop.runUserTurn("长任务"));
        t.start();
        assertTrue("streamChat 未进入", p.entered.await(5, TimeUnit.SECONDS));
        loop.interrupt(); // cancel → cancelSignal 打开 → 流抛异常走中断路径
        t.join(5000);
        assertFalse(t.isAlive());
        // 中断前收到的部分回复与思考进入历史（不含 toolCalls——切断的 tool_calls 流不可信）
        assertEquals(2, loop.messages().size());
        Message a = loop.messages().get(1);
        assertEquals(Message.Role.ASSISTANT, a.role);
        assertEquals("部分回复内容", a.content);
        assertEquals("已经分析到一半", a.reasoningContent);
        assertTrue(a.toolCalls == null || a.toolCalls.isEmpty());
        // 下一次请求携带部分回复（截断前的模型回复进入后续上下文）
        p.addTurn("继续处理");
        loop.runUserTurn("继续");
        boolean found = false;
        for (Message m : p.lastRequestMessages) {
            if (m.role == Message.Role.ASSISTANT && "部分回复内容".equals(m.content)) {
                found = true;
                break;
            }
        }
        assertTrue("部分回复应出现在后续请求中", found);
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

    /** M3：工具阶段中断且带思考——scrub 剥离 toolCalls 后，仅思考无正文的 assistant 空壳
     *  必须整条移除（DeepSeek 思考模式硬性要求 assistant 消息带 content 或 tool_calls，
     *  仅 reasoning_content 回传会 400「content or tool_calls must be set」） */
    @Test
    public void interrupt_duringToolExecution_thinkingOnlyShellRemoved() throws Exception {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "blocker";
        tc.arguments = "{}";
        llm.addTurnWithTools(Collections.singletonList(tc), null, "思考中");
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
        // 仅思考的 assistant 空壳不得留在历史（否则下次发送 400）
        assertEquals(1, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
    }

    /** M3：流式中断时思考已到、正文未到——不得存储仅思考的 assistant 消息（下次发送 400） */
    @Test
    public void interrupt_thinkingOnlyPartial_notStored() throws Exception {
        ThinkingOnlyStreamLlm p = new ThinkingOnlyStreamLlm();
        AgentLoop loop = new AgentLoop(p, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        Thread t = new Thread(() -> loop.runUserTurn("长任务"));
        t.start();
        assertTrue("streamChat 未进入", p.entered.await(5, TimeUnit.SECONDS));
        loop.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        // 仅思考的部分回复不得入历史
        assertEquals(1, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
    }

    /** M3：恢复历史会话时，落盘文件里的仅思考 assistant 空壳（旧版本 bug 产物）一并清洗 */
    @Test
    public void restoreSession_removesThinkingOnlyShell() {
        Session saved = Session.create(tmp.getRoot().getPath(), "test-model");
        saved.messages.add(Message.user("任务"));
        Message shell = Message.assistant(null);
        shell.reasoningContent = "思考了一半";
        saved.messages.add(shell);
        AgentLoop loop = newLoop();
        loop.restoreSession(saved);
        assertEquals(1, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
    }

    /** M3：正常路径空回复（无正文无工具调用）不得入历史（同样触发 400 形状） */
    @Test
    public void emptyAssistantReply_notStored() {
        llm.addTurn("");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        assertEquals(1, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
    }

    /** M3 测试桩：进入后阻塞等中断，仅回调思考（无正文）再抛异常模拟取消 */
    public static class ThinkingOnlyStreamLlm implements LlmClient {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch cancelSignal = new CountDownLatch(1);

        @Override public void cancel() { cancelSignal.countDown(); }

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
                throws LlmException {
            entered.countDown();
            try {
                cancelSignal.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handler.onThinking("已经分析到一半");
            throw new LlmException(LlmException.Type.OTHER, "模拟中断", false);
        }

        @Override
        public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            return null; // 测试未启用上下文压缩，不会走到
        }
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

    /** 技能加载入队（/skill 手动加载路径）：offerSkillLoad → 下轮请求生效（<skill> 消息 pinned 入历史） */
    @Test
    public void offerSkillLoad_injectsSkillMessageNextTurn() {
        AgentLoop loop = newLoop();
        loop.offerSkillLoad(new Skill("brainstorming", "头脑风暴", "正文：先澄清需求", "SKILL.md"));
        llm.addTurn("好的");
        loop.runUserTurn("开始");
        // 0:user(技能) 1:user(输入) 2:assistant
        List<Message> msgs = loop.messages();
        assertEquals(3, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(0).role);
        assertTrue(msgs.get(0).pinned);
        assertTrue(msgs.get(0).content.contains("<skill name=\"brainstorming\">"));
        assertTrue(msgs.get(0).content.contains("正文：先澄清需求"));
        // 第一轮请求即携带技能正文（runUserTurn 开头 drain；请求 = system + 全部历史）
        assertEquals(3, llm.requests.get(0).messages.size());
        assertTrue(llm.requests.get(0).messages.get(1).content.contains("正文：先澄清需求"));
    }

    /** 同一技能重复入队：同轮仅注入一次 */
    @Test
    public void offerSkillLoad_deduplicatesPendingQueue() {
        AgentLoop loop = newLoop();
        loop.offerSkillLoad(new Skill("review", "审查", "审查正文", "SKILL.md"));
        loop.offerSkillLoad(new Skill("review", "另一个描述", "审查正文 2", "SKILL.md"));
        llm.addTurn("好的");
        loop.runUserTurn("开始");
        long pinnedCount = loop.messages().stream()
                .filter(m -> m.pinned && m.content.contains("<skill name=\"review\">")).count();
        assertEquals(1, pinnedCount);
    }

    /** 回归：同名技能跨轮重复加载（/skill 命令反复执行或失败后重试）——历史级幂等，
     *  技能正文（pinned、压缩豁免）不得重复注入。此前 offerSkillLoad 只做队列级去重，
     *  drain 后队列清空，再次加载会重新注入一条技能正文 → 上下文永久叠加 */
    @Test
    public void offerSkillLoad_sameSkillAcrossTurns_doesNotDuplicate() {
        Skill skill = new Skill("brainstorming", "头脑风暴", "正文：先澄清需求", "SKILL.md");
        AgentLoop loop = newLoop();
        loop.offerSkillLoad(skill);
        llm.addTurn("好的");
        loop.runUserTurn("第一次");
        long c1 = loop.messages().stream()
                .filter(m -> m.pinned && m.content.contains("<skill name=\"brainstorming\">")).count();
        assertEquals(1, c1);
        // 第二次加载同一技能（模拟 /skill 重复执行）：不得再次注入
        loop.offerSkillLoad(skill);
        llm.addTurn("好的");
        loop.runUserTurn("第二次");
        long c2 = loop.messages().stream()
                .filter(m -> m.pinned && m.content.contains("<skill name=\"brainstorming\">")).count();
        assertEquals("同名技能跨轮重复加载不得重复注入历史", 1, c2);
    }

    /** 回归：接口持续失败（如 401/网络错误）时历史只增用户输入，不得有任何叠加 */
    @Test
    public void interfaceFailure_repeatedTurns_noAccumulation() {
        for (int i = 0; i < 3; i++) {
            llm.addTurnError("401: 认证失败");
        }
        AgentLoop loop = newLoop();
        for (int i = 0; i < 3; i++) {
            loop.runUserTurn("尝试" + i);
        }
        // 3 次失败 = 3 条用户消息，无 assistant/tool/技能消息叠加
        assertEquals(3, loop.messages().size());
        for (Message m : loop.messages()) {
            assertEquals(Message.Role.USER, m.role);
            assertFalse(m.pinned);
        }
        assertEquals(3, ui.errors.size());
    }

    /** 真实网络错误路径（streamChat 抛 retryable 异常，与 DeepSeekClient 断线一致）：
     *  模型每轮先调工具、下一轮网络失败 → 用户重发 → 网络再失败。
     *  验证失败轮的工具结果只入历史一次，无重复叠加。 */
    @Test
    public void networkFailure_toolTurnThenFail_repeatedTurns_noAccumulation() {
        ScriptedNetFailLlm net = new ScriptedNetFailLlm(Arrays.asList(
                "TOOL", "NETWORK", "NETWORK",   // runUserTurn#1：调工具 → 网络失败 → 重试失败 → break
                "TOOL", "NETWORK", "NETWORK")); // runUserTurn#2：同上
        AgentLoop loop = new AgentLoop(net, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        loop.runUserTurn("任务一");
        loop.runUserTurn("任务二");
        // 每轮：user + assistant(tool_calls) + tool(result) = 3 条；2 轮共 6 条，无叠加
        List<Message> msgs = loop.messages();
        assertEquals("失败轮工具结果不得重复入历史", 6, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(0).role);
        assertEquals(Message.Role.ASSISTANT, msgs.get(1).role);
        assertEquals(Message.Role.TOOL, msgs.get(2).role);
        assertEquals(Message.Role.USER, msgs.get(3).role);
        assertEquals(Message.Role.ASSISTANT, msgs.get(4).role);
        assertEquals(Message.Role.TOOL, msgs.get(5).role);
        // 每次 runUserTurn 首请求失败后重试一次（共 2 次请求），随后 break
        assertEquals(6, net.calls);
        assertEquals(2, ui.errors.size());
    }

    /** 网络失败发生在流式中途（已回调部分 content）：半截内容不得入历史（非用户中断），
     *  重试成功后仅一条完整 assistant 消息 */
    @Test
    public void networkFailure_midStreamPartialContent_notDuplicated() throws Exception {
        MidStreamFailLlm net = new MidStreamFailLlm();
        AgentLoop loop = new AgentLoop(net, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        loop.runUserTurn("任务");
        // 第一次请求：回调半截后抛网络异常 → 重试成功
        // 历史 = user + assistant(完整回复)，半截不入历史
        assertEquals(2, loop.messages().size());
        assertEquals(Message.Role.ASSISTANT, loop.messages().get(1).role);
        assertEquals("完整回复", loop.messages().get(1).content);
        assertEquals(2, net.calls);
        assertEquals(1, ui.warnings.size()); // 仅重试提示
    }

    /** 脚本化网络错误客户端：TOOL=输出工具调用；NETWORK=抛 retryable 网络异常；OK=正常回复 */
    public static class ScriptedNetFailLlm implements LlmClient {
        private final List<String> script;
        private int cursor = 0;
        public int calls = 0;

        public ScriptedNetFailLlm(List<String> script) { this.script = script; }

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
                throws LlmException {
            calls++;
            String step = script.get(Math.min(cursor, script.size() - 1));
            cursor++;
            if ("NETWORK".equals(step)) {
                throw new LlmException(LlmException.Type.NETWORK, "模拟网络错误", true);
            }
            Usage u = new Usage();
            u.inputTokens = 10;
            u.outputTokens = 5;
            if ("TOOL".equals(step)) {
                ToolCall tc = new ToolCall();
                tc.id = "c" + cursor;
                tc.name = "example";
                tc.arguments = "{\"text\":\"x\"}";
                handler.onFinish("tool_calls", u, Collections.singletonList(tc));
                return;
            }
            handler.onContent("最终回复");
            handler.onFinish("stop", u, new ArrayList<ToolCall>());
        }

        @Override
        public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            return "【摘要】";
        }
    }

    /** 流式中途网络失败客户端：首次请求回调半截 content 后抛网络异常（重试成功） */
    public static class MidStreamFailLlm implements LlmClient {
        public int calls = 0;
        public List<Message> lastRequest = new ArrayList<Message>();

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
                throws LlmException {
            lastRequest = new ArrayList<Message>(messages);
            calls++;
            if (calls == 1) {
                handler.onThinking("思考中");
                handler.onContent("半截内");
                throw new LlmException(LlmException.Type.NETWORK, "模拟中途断线", true);
            }
            Usage u = new Usage();
            u.inputTokens = 10;
            u.outputTokens = 5;
            handler.onContent("完整回复");
            handler.onFinish("stop", u, new ArrayList<ToolCall>());
        }

        @Override
        public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
            return "【摘要】";
        }
    }

    /** 技能带参数加载：args 以「用户参数: 」附加在技能正文后注入，下轮请求生效 */
    @Test
    public void offerSkillLoad_withArgs_injectsArgsIntoSkillMessage() {
        AgentLoop loop = newLoop();
        loop.offerSkillLoad(new Skill("brainstorming", "头脑风暴", "正文：先澄清需求", "SKILL.md"), "帮我设计一个设置页");
        llm.addTurn("好的");
        loop.runUserTurn("开始");
        List<Message> msgs = loop.messages();
        assertEquals(3, msgs.size());
        assertTrue(msgs.get(0).content.contains("<skill name=\"brainstorming\">"));
        assertTrue(msgs.get(0).content.contains("正文：先澄清需求"));
        assertTrue(msgs.get(0).content.contains("用户参数: 帮我设计一个设置页"));
        // 参数在 <skill> 标签外（闭合之后），标签内严格等于技能正文
        assertTrue(msgs.get(0).content.indexOf("</skill>") < msgs.get(0).content.indexOf("用户参数:"));
        // 首轮请求即携带技能正文与参数（请求 = system + 全部历史）
        assertEquals(3, llm.requests.get(0).messages.size());
        assertTrue(llm.requests.get(0).messages.get(1).content.contains("用户参数: 帮我设计一个设置页"));
    }

    /** 同名不同参连续入队：仍按技能名去重（参数以第一次为准） */
    @Test
    public void offerSkillLoad_deduplicatesByNameEvenWithDifferentArgs() {
        AgentLoop loop = newLoop();
        loop.offerSkillLoad(new Skill("review", "审查", "审查正文", "SKILL.md"), "参数A");
        loop.offerSkillLoad(new Skill("review", "审查", "审查正文", "SKILL.md"), "参数B");
        llm.addTurn("好的");
        loop.runUserTurn("开始");
        long pinnedCount = loop.messages().stream()
                .filter(m -> m.pinned && m.content.contains("<skill name=\"review\">")).count();
        assertEquals(1, pinnedCount);
        assertTrue(loop.messages().get(0).content.contains("用户参数: 参数A"));
        assertFalse(loop.messages().get(0).content.contains("参数B"));
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

    /** 补充带图：pendingSupplementImages 与文本同步入队，合并后 user 消息 images 保留 */
    @Test
    public void supplement_withImages_keepsImagesInMessage() {
        llm.addTurn("好的");
        AgentLoop loop = newLoop();
        ImagePart img = new ImagePart();
        img.mime = "image/png"; img.base64 = "QUJD"; img.name = "截图.png";
        loop.offerSupplement("看图", Collections.singletonList(img));
        loop.runUserTurn("继续");
        List<Message> msgs = loop.messages();
        // 0:user(补充带图) 1:user(输入) 2:assistant
        assertEquals(3, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(0).role);
        assertTrue(msgs.get(0).supplement);
        assertEquals(1, msgs.get(0).images.size());
        assertEquals("截图.png", msgs.get(0).images.get(0).name);
        assertNull(msgs.get(1).images); // 无图输入不产生 images
    }

    /** 带图请求失败（如 DeepSeek 400 不支持 image_url）：自动移除图片降级为纯文本重试，会话恢复可用 */
    @Test
    public void imageFailure_degradesToTextAndRetries() {
        llm.addTurnError("400: 不支持 image_url");
        llm.addTurn("好的，已按文字处理");
        AgentLoop loop = newLoop();
        ImagePart img = new ImagePart();
        img.mime = "image/png"; img.base64 = "QUJD"; img.name = "截图.png";
        loop.runUserTurn("看图", Collections.singletonList(img));
        // 两次请求：第一次带图失败，第二次降级纯文本成功
        assertEquals(2, llm.requests.size());
        // 历史中带图消息 images 已清空（下轮请求不会再触发 400）
        Message user = loop.messages().get(0);
        assertEquals(Message.Role.USER, user.role);
        assertNull(user.images);
        // 降级成功不显示错误，仅提示一次
        assertEquals(0, ui.errors.size());
        assertEquals(1, ui.warnings.size());
        assertTrue(ui.warnings.get(0).contains("图片"));
        // 正常完成：assistant 回复入历史
        assertEquals("好的，已按文字处理", loop.messages().get(1).content);
    }

    /** 降级后仍失败：不无限重试，正常报错退出 */
    @Test
    public void imageFailure_degradeRetryFailsAgain_stopsWithError() {
        llm.addTurnError("400: 不支持 image_url");
        llm.addTurnError("500: 服务错误");
        AgentLoop loop = newLoop();
        ImagePart img = new ImagePart();
        img.mime = "image/png"; img.base64 = "QUJD"; img.name = "截图.png";
        loop.runUserTurn("看图", Collections.singletonList(img));
        // 原请求 + 降级重试各一次，不无限循环
        assertEquals(2, llm.requests.size());
        assertEquals(1, ui.errors.size());
        assertEquals("500: 服务错误", ui.errors.get(0));
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

    /** 轮询等待条件（挂起回调无 latch 可挂时用） */
    private static void waitFor(java.util.concurrent.Callable<Boolean> cond) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (cond.call()) return;
            Thread.sleep(20);
        }
        fail("条件等待超时");
    }

    /** AskUserQuestion 工具随 AgentLoop 构造自动注册 */
    @Test
    public void agentLoop_autoRegistersAskUserQuestionTool() {
        AgentLoop loop = newLoop();
        assertNotNull(registry.get("AskUserQuestion"));
        assertEquals("AskUserQuestion", registry.get("AskUserQuestion").name());
    }

    /** 需求3/6：AskUserQuestion 挂起 → answerAskUser → 回答作为 TOOL 消息入历史继续本轮 */
    @Test
    public void askUser_suspendThenAnswer_continuesTurn() throws Exception {
        ToolCall q = new ToolCall();
        q.id = "q1";
        q.name = "AskUserQuestion";
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

    /** 需求6：AskUserQuestion 挂起时中断 → 退出 + 半轮 tool_call 被清洗（不留未配对残留） */
    @Test
    public void askUser_interruptWhileWaiting_scrubsHalfTurn() throws Exception {
        ToolCall q = new ToolCall();
        q.id = "q2";
        q.name = "AskUserQuestion";
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

    /** Skill 工具调用 → 工具结果入历史 → 检查点注入 <skill> 消息 → 下一轮请求携带（同轮生效，链式衔接） */
    @Test
    public void skillTool_sameTurnEffect() {
        Skill skill = new Skill("brainstorming", "头脑风暴", "正文：先澄清需求", "SKILL.md");
        AgentLoop loop = newLoop();
        loop.setAllSkills(Collections.singletonList(skill));
        ToolCall tc = new ToolCall();
        tc.id = "s1";
        tc.name = "Skill";
        tc.arguments = "{\"name\":\"brainstorming\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("按技能继续");
        loop.runUserTurn("开始");
        // 0:user 1:assistant(tool_calls) 2:tool(已加载) 3:user(skill pinned) 4:assistant(最终)
        List<Message> msgs = loop.messages();
        assertEquals(5, msgs.size());
        assertEquals(Message.Role.USER, msgs.get(3).role);
        assertTrue(msgs.get(3).pinned);
        assertTrue(msgs.get(3).content.contains("<skill name=\"brainstorming\">"));
        // 第二轮请求已携带技能正文（最后一条消息）
        List<Message> req2 = llm.requests.get(1).messages;
        assertTrue(req2.get(req2.size() - 1).content.contains("正文：先澄清需求"));
    }

    /** setAllSkills 接线：技能目录进 loop（Skill 工具可访问、子 agent 系统提示词含目录段） */
    @Test
    public void setAllSkills_catalogVisibleInSystemPrompt() {
        AgentLoop loop = newLoop();
        assertEquals(0, loop.allSkills().size());
        loop.setAllSkills(Collections.singletonList(
                new Skill("brainstorming", "头脑风暴", "正文", "SKILL.md")));
        assertEquals(1, loop.allSkills().size());
        // 子 agent 复用 buildSystemPrompt：目录段可见
        assertTrue(loop.buildSystemPrompt().contains("brainstorming — 头脑风暴"));
    }

    /** I-1 回归：技能注入时发 UI 事件——live 会话消息区照常渲染为一条用户消息（透明可审计）。
     *  与 runUserTurn 发用户输入同一语义：整条 <skill> 正文内容渲染，不加前缀后缀 */
    @Test
    public void skillInjection_emitsUiEvent() {
        AgentLoop loop = newLoop();
        loop.offerSkillLoad(new Skill("brainstorming", "头脑风暴", "正文：先澄清需求", "SKILL.md"));
        llm.addTurn("好的");
        loop.runUserTurn("开始");
        assertTrue("技能注入未发 UI 事件",
                ui.userMessages.stream().anyMatch(m ->
                        m.startsWith("<skill name=\"brainstorming\">")
                                && m.contains("正文：先澄清需求")));
    }

    /** M-9 回归：会话预览跳过 pinned 技能消息——技能消息在用户输入之后注入（轮内检查点），
     *  不得污染预览；预览仍取最后一条真实用户消息 */
    @Test
    public void preview_skipsPinnedSkillMessage() {
        Session s = Session.create(tmp.getRoot().getPath(), "test-model");
        s.messages.add(Message.user("真正的用户消息"));
        s.messages.add(Message.skill("<skill name=\"review\">\n审查正文\n</skill>"));
        assertTrue(s.preview().contains("真正的用户消息"));
        assertFalse(s.preview().contains("审查正文"));
    }

    /** 429 限流长重试：重试进度经 onRetryProgress 进左下角指示器，消息区仅"已恢复"一条，无刷屏无报错 */
    @Test
    public void rateLimit_retryThenSuccess_silentRecovery() {
        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));
        llm.addTurn("最终回复");
        AgentLoop loop = newLoop();
        loop.retryPolicy429 = new RetryPolicy(10, 10, 100, 60000); // 测试短退避
        loop.runUserTurn("任务");
        assertEquals(2, loop.messages().size()); // user + assistant
        assertEquals("最终回复", loop.messages().get(1).content);
        assertEquals(2, llm.requests.size()); // 原始请求 + 1 次重试
        assertEquals(1, ui.warnings.size());  // 仅"已恢复"；重试提示在指示器不进消息区
        assertTrue(ui.warnings.get(0).contains("已恢复"));
        // 进度序列：进入重试（第1次）→ 成功退出（0）
        assertEquals(Arrays.asList(1, 0), ui.retryProgress);
        assertTrue(ui.errors.isEmpty());
    }

    /** 429 持续失败：超总时长后一次性总结并停止，不无限重试 */
    @Test
    public void rateLimit_exhausted_stopsWithSummary() {
        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));
        AgentLoop loop = newLoop();
        loop.retryPolicy429 = new RetryPolicy(10, 10, 20, 50); // 10+20+20=50ms 耗尽
        long start = System.currentTimeMillis();
        loop.runUserTurn("任务");
        assertTrue("应在数百毫秒内停止", System.currentTimeMillis() - start < 5000);
        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("重试了"));
        assertTrue(ui.errors.get(0).contains("仍失败"));
        // 请求数有限：1 原始 + 重试（10ms、20ms、20ms 后耗尽 → 2 次重试）
        assertTrue(llm.requests.size() >= 2 && llm.requests.size() <= 5);
        // 进度序列：1、2（两次重试）… 末位 0（退出重试态复位）
        assertTrue(!ui.retryProgress.isEmpty());
        assertEquals(Integer.valueOf(0), ui.retryProgress.get(ui.retryProgress.size() - 1));
        assertTrue(ui.retryProgress.get(0) >= 1);
    }

    /** 429 重试等待中 interrupt()：小片轮询，不等待完整间隔，立即退出 */
    @Test
    public void rateLimit_interruptedDuringBackoff_stopsPromptly() throws Exception {
        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));
        AgentLoop loop = newLoop();
        loop.retryPolicy429 = new RetryPolicy(60000, 60000, 60000, 3600000L); // 长退避
        Thread t = new Thread(() -> loop.runUserTurn("任务"));
        t.start();
        Thread.sleep(200); // 等首次失败进入退避等待
        long start = System.currentTimeMillis();
        loop.interrupt();
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue("中断应 ≤1s 响应", System.currentTimeMillis() - start < 1000);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("中断")));
    }
}
