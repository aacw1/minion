package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SubAgentLoopTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void subAgent_runsOwnLoop_returnsFinalText() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        // 子 agent 无 task 工具
        assertNull(registry.get("task"));
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();

        // 子 agent 内部：工具调用一轮（带 thinking）→ 总结
        ToolCall tc = new ToolCall();
        tc.id = "s1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"子任务\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null, "子agent思考");
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui, null, 1);
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        // 子 agent 请求 = [system, user(任务描述)]
        assertEquals(Message.Role.SYSTEM, llm.lastRequestMessages.get(0).role);
        assertTrue(llm.lastRequestMessages.get(1).content.contains("调研一下"));
        // tool 结果已进入子 agent 自己的消息
        assertTrue(ui.subToolCalls.contains("1:example"));
        // 思考/正文增量必须走子代理通道（编号透传；回归到主通道 onThinking/onContent 时本断言必红）
        assertTrue("子代理思考须走子代理通道: " + ui.subThinking,
                ui.subThinking.contains("子agent思考"));
        assertTrue("子代理正文须走子代理通道: " + ui.subDeltas,
                ui.subDeltas.contains("子任务结果：完成"));
        assertTrue("起始/完成事件带编号: " + ui.subStarts + " / " + ui.subDones,
                ui.subStarts.contains("1:任务: 调研一下") && ui.subDones.contains("1:子任务结果：完成"));
        // 事件走子代理通道；主通道零调用（未串台，含主代理思考/正文通道）
        assertTrue("主通道零调用", ui.toolCalls.isEmpty() && ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
        // C1 契约：第二轮请求中，tool 消息前必须有含对应 tool_call_id 的 assistant tool_calls 消息
        List<Message> round2 = llm.requests.get(1).messages;
        assertEquals(4, round2.size());
        assertEquals(Message.Role.ASSISTANT, round2.get(2).role);
        assertNotNull(round2.get(2).toolCalls);
        assertEquals("s1", round2.get(2).toolCalls.get(0).id);
        assertEquals(Message.Role.TOOL, round2.get(3).role);
        // M3 契约：子 agent 与主循环一致，reasoningContent 必须原样回传（思考模式 + 工具调用否则 400）
        assertEquals("子agent思考", round2.get(2).reasoningContent);
        assertTrue(round2.get(2).toApiJson().has("reasoning_content"));
        assertEquals("子agent思考", round2.get(2).toApiJson().get("reasoning_content").getAsString());
    }

    /** I4-① 构造 AgentLoop 后 task 工具自动注册 */
    @Test
    public void agentLoop_autoRegistersTaskTool() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        AgentLoop loop = new AgentLoop(new FakeLlmClient(), registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi(), null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        assertNotNull(loop);
        assertNotNull(registry.get("task"));
        assertEquals("task", registry.get("task").name());
    }

    /** I4-②③ 生产装配链端到端：构造自动注册 → 默认 runner → buildSystemPrompt → SubAgentLoop → 结果回注主会话 */
    @Test
    public void taskTool_endToEnd_defaultRunner() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        // 主 agent 出 task 调用牌 → 默认 runner 派发子 agent（子 agent 消费第 2 张牌）
        ToolCall tc = new ToolCall();
        tc.id = "t1";
        tc.name = "task";
        tc.arguments = "{\"description\":\"子任务甲\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("子任务甲完成");
        llm.addTurn("总结");
        loop.runUserTurn("派发");

        // 0:user 1:assistant(tool_calls) 2:tool(子agent结果) 3:assistant(最终)
        List<Message> msgs = loop.messages();
        assertEquals(4, msgs.size());
        assertEquals(Message.Role.TOOL, msgs.get(2).role);
        assertTrue(msgs.get(2).content.contains("子任务甲完成"));
        assertEquals("总结", msgs.get(3).content);

        // 请求序列：0=主agent第1轮（含 task schema），1=子agent（不含 task），2=主agent第2轮
        assertEquals(3, llm.requests.size());
        assertTrue(llm.requests.get(0).tools.stream()
                .anyMatch(t -> "task".equals(t.getAsJsonObject("function").get("name").getAsString())));
        assertTrue(llm.requests.get(1).tools.stream()
                .noneMatch(t -> "task".equals(t.getAsJsonObject("function").get("name").getAsString())));
        assertTrue(llm.requests.get(1).tools.stream()
                .anyMatch(t -> "example".equals(t.getAsJsonObject("function").get("name").getAsString())));
        // 子 agent 请求 = [system, user(任务描述)]，system 包含任务说明
        assertEquals(2, llm.requests.get(1).messages.size());
        assertTrue(llm.requests.get(1).messages.get(1).content.contains("子任务甲"));
    }

    /** I4-④ 子 agent 内 task 调用被防御拦截：错误 tool 结果，不派发嵌套子 agent */
    @Test
    public void subAgent_rejectsTaskToolCall() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        class CapturingUi extends RecordingUi {
            final List<com.minion.core.tools.ToolResult> results = new ArrayList<com.minion.core.tools.ToolResult>();
            @Override public synchronized void onSubAgentToolResult(int no, String name,
                                                                    com.minion.core.tools.ToolResult result) {
                super.onSubAgentToolResult(no, name, result);
                results.add(result);
            }
        }
        CapturingUi ui = new CapturingUi();
        // 构造 AgentLoop 让 registry 真实含 task（生产中即此状态）
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;

        ToolCall tc = new ToolCall();
        tc.id = "s1";
        tc.name = "task";
        tc.arguments = "{\"description\":\"再派发\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("只做了自己的事");
        SubAgentLoop sub = new SubAgentLoop("sys", "任务", tmp.getRoot().getPath(), llm, registry,
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)), ui, null, 1);
        assertEquals("只做了自己的事", sub.run());
        // 防御拦截：task 调用作为错误 tool 结果回传（子代理通道）
        assertTrue(ui.subToolResults.contains("1:task"));
        com.minion.core.tools.ToolResult err = ui.results.get(0);
        assertFalse(err.ok);
        assertTrue(err.output.contains("task"));
        // 未派发嵌套子 agent：请求仅 2 次（工具轮 + 总结轮），无第三次派发请求
        assertEquals(2, llm.requests.size());
        assertTrue("主通道零调用", ui.toolCalls.isEmpty() && ui.errors.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    @Test
    public void subAgent_loopStopsWhenNoMoreTools() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        llm.addTurn("直接回答");
        SubAgentLoop sub = new SubAgentLoop("sys", "任务", tmp.getRoot().getPath(), llm, registry,
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi());
        assertEquals("直接回答", sub.run());
    }

    @Test
    public void taskTool_dispatches() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.setSubAgentRunner(args ->
                new SubAgentLoop("sys", args.get("description").getAsString(),
                        tmp.getRoot().getPath(), llm, registry, confirm, ui).run());

        com.minion.core.tools.TaskTool task = new com.minion.core.tools.TaskTool(loop);
        llm.addTurn("子agent结果");
        JsonObject args = JsonParser.parseString("{\"description\":\"完成子任务\"}").getAsJsonObject();
        com.minion.core.tools.ToolResult r = task.execute(args);
        assertTrue(r.ok);
        assertEquals("子agent结果", r.output);
    }

    /** 子 agent 工具集剔除 AskUserQuestion（防嵌套挂起）；违规调用返回错误不挂起 */
    @Test
    public void subAgent_excludesAskUserQuestionTool() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        registry.register(new com.minion.core.tools.AskUserQuestionTool(new RecordingUi()));
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();

        com.minion.core.llm.ToolCall tc = new com.minion.core.llm.ToolCall();
        tc.id = "s1";
        tc.name = "AskUserQuestion";
        tc.arguments = "{\"question\":\"问？\"}";
        llm.addTurnWithTools(java.util.Collections.singletonList(tc), null);
        llm.addTurn("子任务完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui, null, 1);
        sub.run();
        // schema 已剔除（模型不可见）
        for (com.google.gson.JsonObject s : llm.requests.get(0).tools) {
            String name = s.getAsJsonObject("function").get("name").getAsString();
            assertFalse("子 agent 不得暴露 AskUserQuestion", "AskUserQuestion".equals(name));
        }
        // 防御：即使模型违规调用，也返回错误、不挂起（子代理通道）
        assertTrue(ui.subToolResults.contains("1:AskUserQuestion"));
        assertTrue(ui.asksStarted.isEmpty());
        assertTrue("主通道零调用", ui.toolCalls.isEmpty() && ui.errors.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 工具集剔除 Skill（防正文注入主会话）；违规调用返回错误 */
    @Test
    public void subAgent_excludesSkillTool() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();
        // 构造 AgentLoop 让 registry 真实含 Skill（生产中即此状态）
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));

        com.minion.core.llm.ToolCall tc = new com.minion.core.llm.ToolCall();
        tc.id = "s1";
        tc.name = "Skill";
        tc.arguments = "{\"name\":\"brainstorming\"}";
        llm.addTurnWithTools(java.util.Collections.singletonList(tc), null);
        llm.addTurn("子任务完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui, null, 1);
        sub.run();
        // schema 已剔除（模型不可见）
        for (com.google.gson.JsonObject s : llm.requests.get(0).tools) {
            String name = s.getAsJsonObject("function").get("name").getAsString();
            assertFalse("子 agent 不得暴露 Skill", "Skill".equals(name));
        }
        // 防御：即使模型违规调用，也返回错误、不注入主会话（子代理通道）
        assertTrue(ui.subToolResults.contains("1:Skill"));
        for (com.minion.core.llm.Message m : loop.messages()) {
            assertFalse("技能正文不得注入主会话", m.pinned);
        }
        assertTrue("主通道零调用", ui.toolCalls.isEmpty() && ui.errors.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /**
     * 回归：模型热切换后子 agent 必须与主 agent 用同一客户端。
     * 根因——默认 subAgentRunner 的 lambda 写在构造器内，简单名 llm 捕获的是构造器形参
     * （旧客户端引用）而非可变字段 this.llm，导致 setLlm 切模型后主 agent 用新模型、
     * 子 agent 仍用旧模型（实际事故：切 qwen 后子 agent 继续烧 deepseek 额度，402 余额不足）。
     */
    @Test
    public void subAgent_usesLlmClient_afterSetLlm() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient oldLlm = new FakeLlmClient();  // 会话创建时的模型（事故中的 deepseek）
        FakeLlmClient newLlm = new FakeLlmClient();  // setLlm 切换后的模型（事故中的 qwen）
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();
        AgentLoop loop = new AgentLoop(oldLlm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        loop.setLlm(newLlm); // 模拟设置窗切换模型（SessionManager.applyModelChanged 同路径）

        ToolCall tc = new ToolCall();
        tc.id = "t1";
        tc.name = "task";
        tc.arguments = "{\"description\":\"派发子任务\"}";
        // 切换后主 agent 用 newLlm：第 1 轮发 task → 子 agent 一轮 → 第 2 轮总结
        newLlm.addTurnWithTools(Collections.singletonList(tc), null);
        newLlm.addTurn("子agent结果");
        newLlm.addTurn("主agent总结");
        oldLlm.addTurn("旧模型回答"); // 旧客户端的牌：仅 bug 版本会被子 agent 取走

        loop.runUserTurn("派发");

        assertTrue("子 agent 请求必须出现在切换后的客户端上（system + user(任务) 形态）",
                newLlm.requests.stream().anyMatch(r -> r.messages.size() == 2
                        && r.messages.get(0).role == Message.Role.SYSTEM
                        && r.messages.get(1).content.contains("派发子任务")));
        assertEquals("旧模型客户端不得再收到任何请求（子 agent 尤其不行），实际 "
                + oldLlm.requests.size() + " 次", 0, oldLlm.requests.size());
    }

    /** 子 agent 429 长重试：先 429 后成功，进入重试提示一条走子代理通道，成功后静默恢复，与主循环一致 */
    @Test
    public void subAgent_rateLimit_retryThenSuccess() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000); // 测试短退避
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        assertEquals(2, llm.requests.size()); // 原始请求 + 1 次重试
        // 进入重试只提示一条 notice（恢复静默；长重试不逐次刷屏）
        assertTrue("仅一条进入重试提示: " + ui.subNotices,
                ui.subNotices.size() == 1 && ui.subNotices.get(0).contains("自动重试"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty() && ui.toolCalls.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 429 持续失败：超总时长后总结停止，不无限重试 */
    @Test
    public void subAgent_rateLimit_exhausted_stopsWithSummary() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 20, 50); // 快速耗尽
        long start = System.currentTimeMillis();
        String result = sub.run();
        assertTrue("应在数百毫秒内停止", System.currentTimeMillis() - start < 5000);
        assertTrue(result.contains("失败"));
        // 第 0 条=进入重试提示，第 1 条=耗尽提示
        assertEquals(2, ui.subNotices.size());
        assertTrue(ui.subNotices.get(1).contains("重试了"));
        assertTrue(ui.subNotices.get(1).contains("仍失败"));
        assertTrue(llm.requests.size() >= 2 && llm.requests.size() <= 5);
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 429 长重试中遇永久性网络错误（retryable=false，DNS 解析失败）：退出重试并终止 */
    @Test
    public void subAgent_rateLimit_thenPermanentNetwork_resetsRetryProgress() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));
        llm.addTurnThrow(new LlmException(LlmException.Type.NETWORK,
                "网络错误: no-host.invalid（域名无法解析，请检查设置中的 API 地址）", false));

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 20, 60000);
        String result = sub.run();
        // 永久性网络错误：不继续退避，立即失败返回，错误文案准确（非"429 重试超时"）
        assertTrue(result.contains("失败"));
        assertTrue(result.contains("域名无法解析"));
        assertEquals(2, llm.requests.size()); // 原始请求 + 1 次重试
        // 进入重试 1 条 + 永久失败 1 条；不进主通道、不残留重试态
        assertEquals(2, ui.subNotices.size());
        assertTrue(ui.subNotices.get(1).contains("域名无法解析"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 网络超时：进入长重试，成功后静默恢复（与主循环一致） */
    @Test
    public void subAgent_networkTimeout_retryThenSuccess() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(new LlmException(LlmException.Type.TIMEOUT, "请求超时：60 秒内未收到模型输出", true));
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000);
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        assertEquals(2, llm.requests.size());
        assertTrue(ui.subNotices.get(0).contains("网络超时"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 零增量闸门：已吐字后网络掉断 → 不重试 */
    @Test
    public void subAgent_partialOutputThenNetwork_noRetry() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnPartialThenThrow("半截正文",
                new LlmException(LlmException.Type.NETWORK, "网络错误: Connection reset", true));

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000);
        sub.run();
        assertEquals(1, llm.requests.size());
        assertTrue(ui.subNotices.stream().noneMatch(n -> n.contains("自动重试")));
        assertEquals(1, ui.subNotices.size());
        assertTrue("已吐出的半截正文须走子代理正文通道: " + ui.subDeltas,
                ui.subDeltas.contains("半截正文"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 网络类耗尽：总结文案用中文标签前缀 */
    @Test
    public void subAgent_networkTimeout_exhausted_usesLabel() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(new LlmException(LlmException.Type.TIMEOUT, "请求超时", true));

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 50);
        String result = sub.run();
        assertEquals(2, ui.subNotices.size());
        assertTrue(ui.subNotices.get(1).startsWith("网络超时 重试了"));
        assertTrue(result.contains("网络超时"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 429 重试成功但流中断（onError 回调）：错误已在回调提示（子代理通道末条即失败原因），主通道无重试进度 */
    @Test
    public void subAgent_rateLimit_thenStreamError_noFalseRecovery() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(new LlmException(LlmException.Type.RATE_LIMIT, "请求过于频繁(429)", true));
        llm.addTurnError("连接中断"); // 重试请求：streamChat 正常返回但 handler 走 onError 回调

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000); // 测试短退避
        String result = sub.run();
        assertEquals(2, llm.requests.size()); // 原始请求 + 1 次重试
        // 成功路径静默恢复（主通道无警告/错误/重试进度事件），onError 回调已把错误发到子代理通道
        assertTrue("末条含连接中断: " + ui.subNotices,
                !ui.subNotices.isEmpty() && ui.subNotices.get(ui.subNotices.size() - 1).contains("连接中断"));
        assertTrue("主通道零调用", ui.warnings.isEmpty() && ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 500 服务端报错：进长重试，成功后静默恢复（与主循环一致） */
    @Test
    public void subAgent_serverError500_retryThenSuccess() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(LlmException.of(500, "{\"error\":\"internal\"}"));
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000); // 测试短固定间隔
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        assertEquals(2, llm.requests.size());
        assertTrue(ui.subNotices.size() == 1);
        assertTrue(ui.subNotices.get(0).contains("500"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty() && ui.warnings.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 503 服务不可用：500 类纳入长重试（与主循环一致），成功后静默恢复 */
    @Test
    public void subAgent_serverError503_longRetried() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(LlmException.of(503, "unavailable"));
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000);
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        assertEquals(2, llm.requests.size()); // 原始请求 + 1 次长重试
        assertTrue(ui.subNotices.size() == 1);
        assertTrue(ui.subNotices.get(0).contains("503"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty() && ui.warnings.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    /** 子 agent 重试循环内错误码切换（429 → 502）：只发首条进入提示，重试最终成功 */
    @Test
    public void subAgent_retry_codeSwitches_suffixFollowsLatestError() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config,
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();

        llm.addTurnThrow(LlmException.of(429, null));
        llm.addTurnThrow(LlmException.of(429, null));
        llm.addTurnThrow(LlmException.of(502, "{\"message\":\"bad gateway\"}"));
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.retryPolicy = new RetryPolicy(10, 10, 60000);
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        // 请求序列：原始 429 → 重试1 429 → 重试2 502 → 重试3 成功（FakeLlmClient 每 streamChat 消耗一回合）
        assertEquals(4, llm.requests.size());
        // 只发首条进入提示；"最近错误标签"由耗尽用例覆盖
        assertEquals(1, ui.subNotices.size());
        assertTrue(ui.subNotices.get(0).contains("429"));
        assertTrue("主通道零调用", ui.errors.isEmpty() && ui.retryProgress.isEmpty()
                && ui.thinking.isEmpty() && ui.contentParts.isEmpty());
    }

    // ===== 报告落盘（设计 2026-09-13：先落盘再返回摘要+路径，主代理不再重复落盘）=====

    /** 报告落盘：返回文本 = 全文 + 落盘路径；文件名含编号；文件内容 = 报告全文 */
    @Test
    public void report_dumpedWithNumberedFile() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();
        llm.addTurn("调研结论：ok");

        java.nio.file.Path reportDir = tmp.newFolder("tmp-session").toPath();
        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下", tmp.getRoot().getPath(),
                llm, registry, confirm, ui, reportDir.toString(), 3);
        String result = sub.run();

        assertTrue("返回应含完整正文", result.startsWith("调研结论：ok"));
        assertTrue("返回应含落盘说明与绝对路径", result.contains("完整报告已落盘："));
        java.util.List<java.nio.file.Path> files = new java.util.ArrayList<java.nio.file.Path>();
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(reportDir)) {
            s.forEach(files::add);
        }
        assertEquals(1, files.size());
        assertTrue("文件名含编号: " + files.get(0),
                files.get(0).getFileName().toString().startsWith("subagent-report-3-"));
        String dumped = new String(java.nio.file.Files.readAllBytes(files.get(0)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("调研结论：ok", dumped);
        assertTrue("路径在返回文本中: " + result, result.contains(files.get(0).toAbsolutePath().toString()));
    }

    /** 超长报告：只返回前 8000 字符 + 「完整报告 N 字符已落盘」说明（主代理需要细节用 Read） */
    @Test
    public void report_longTruncatedTo8000WithPath() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 9000; i++) big.append('x');
        llm.addTurn(big.toString());

        java.nio.file.Path reportDir = tmp.newFolder("tmp-session").toPath();
        SubAgentLoop sub = new SubAgentLoop("主系统提示", "长报告", tmp.getRoot().getPath(),
                llm, registry, confirm, ui, reportDir.toString(), 1);
        String result = sub.run();

        assertTrue("头部 8000 字符保留", result.startsWith(big.substring(0, 8000)));
        assertFalse("第 8001 字符不应返回", result.startsWith(big.substring(0, 8001)));
        assertTrue("应说明完整长度: " + result.substring(7990, 8100),
                result.contains("完整报告 9000 字符已落盘："));
    }

    /** 落盘失败（报告目录指向一个已存在的普通文件 → createDirectories 必失败）：
     *  降级返回全文 + 失败说明（成果不丢，不截断） */
    @Test
    public void report_dumpFailure_returnsFullReport() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();
        llm.addTurn("短报告");

        java.nio.file.Path notADir = tmp.newFile("not-a-dir").toPath();
        SubAgentLoop sub = new SubAgentLoop("主系统提示", "任务", tmp.getRoot().getPath(),
                llm, registry, confirm, ui, notADir.toString(), 0);
        String result = sub.run();

        assertTrue(result.startsWith("短报告"));
        assertTrue("应说明落盘失败: " + result, result.contains("报告落盘失败"));
    }

    /** 未接线（reportDir=null，如旧构造器）：原样返回报告，不追加失败说明——旧构造器返回值语义不变 */
    @Test
    public void report_notWired_returnsPlainReport() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();
        llm.addTurn("短报告");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "任务", tmp.getRoot().getPath(),
                llm, registry, confirm, ui); // 旧构造器
        assertEquals("短报告", sub.run());
    }

    /** 空报告不落盘（不产生空文件） */
    @Test
    public void report_empty_notDumped() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();
        llm.addTurn("");

        java.nio.file.Path reportDir = tmp.newFolder("tmp-session").toPath();
        SubAgentLoop sub = new SubAgentLoop("主系统提示", "任务", tmp.getRoot().getPath(),
                llm, registry, confirm, ui, reportDir.toString(), 2);
        String result = sub.run();

        assertEquals("", result);
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(reportDir)) {
            assertFalse("空报告不得落盘", s.findAny().isPresent());
        }
    }

    /** 中断路径不落盘：run 前已中断 → 中断文案、目录内无文件 */
    @Test
    public void report_interrupted_notDumped() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        RecordingUi ui = new RecordingUi();
        java.nio.file.Path reportDir = tmp.newFolder("tmp-session").toPath();
        SubAgentLoop sub = new SubAgentLoop("主系统提示", "任务", tmp.getRoot().getPath(),
                llm, registry, confirm, ui, reportDir.toString(), 1);
        String result;
        Thread.currentThread().interrupt(); // 模拟主循环 interrupt() 已取消该子代理
        try {
            result = sub.run();
        } finally {
            Thread.interrupted(); // 清理中断标志，避免污染后续测试（surefire 同线程复用）
        }
        assertTrue("中断文案: " + result, result.contains("已中断"));
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(reportDir)) {
            assertFalse("中断不落盘", s.findAny().isPresent());
        }
    }
}
