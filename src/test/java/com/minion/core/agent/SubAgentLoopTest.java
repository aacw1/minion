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
import java.util.Arrays;
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
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        // 子 agent 请求 = [system, user(任务描述)]
        assertEquals(Message.Role.SYSTEM, llm.lastRequestMessages.get(0).role);
        assertTrue(llm.lastRequestMessages.get(1).content.contains("调研一下"));
        // tool 结果已进入子 agent 自己的消息
        assertTrue(ui.toolCalls.contains("example"));
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
            @Override public synchronized void onToolResult(String name, com.minion.core.tools.ToolResult result) {
                super.onToolResult(name, result);
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
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)), ui);
        assertEquals("只做了自己的事", sub.run());
        // 防御拦截：task 调用作为错误 tool 结果回传
        assertTrue(ui.toolResults.contains("task"));
        com.minion.core.tools.ToolResult err = ui.results.get(0);
        assertFalse(err.ok);
        assertTrue(err.output.contains("task"));
        // 未派发嵌套子 agent：请求仅 2 次（工具轮 + 总结轮），无第三次派发请求
        assertEquals(2, llm.requests.size());
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
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.run();
        // schema 已剔除（模型不可见）
        for (com.google.gson.JsonObject s : llm.requests.get(0).tools) {
            String name = s.getAsJsonObject("function").get("name").getAsString();
            assertFalse("子 agent 不得暴露 AskUserQuestion", "AskUserQuestion".equals(name));
        }
        // 防御：即使模型违规调用，也返回错误、不挂起
        assertTrue(ui.toolResults.contains("AskUserQuestion"));
        assertTrue(ui.asksStarted.isEmpty());
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
                tmp.getRoot().getPath(), llm, registry, confirm, ui);
        sub.run();
        // schema 已剔除（模型不可见）
        for (com.google.gson.JsonObject s : llm.requests.get(0).tools) {
            String name = s.getAsJsonObject("function").get("name").getAsString();
            assertFalse("子 agent 不得暴露 Skill", "Skill".equals(name));
        }
        // 防御：即使模型违规调用，也返回错误、不注入主会话
        assertTrue(ui.toolResults.contains("Skill"));
        for (com.minion.core.llm.Message m : loop.messages()) {
            assertFalse("技能正文不得注入主会话", m.pinned);
        }
    }

    /** 子 agent 429 长重试：先 429 后成功，进度经 onRetryProgress 进指示器，消息区仅"已恢复"，与主循环一致 */
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
        sub.retryPolicy429 = new RetryPolicy(10, 10, 100, 60000); // 测试短退避
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        assertEquals(2, llm.requests.size()); // 原始请求 + 1 次重试
        assertEquals(1, ui.warnings.size());
        assertTrue(ui.warnings.get(0).contains("已恢复"));
        assertEquals(Arrays.asList(1, 0), ui.retryProgress);
        assertTrue(ui.errors.isEmpty());
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
        sub.retryPolicy429 = new RetryPolicy(10, 10, 20, 50); // 快速耗尽
        long start = System.currentTimeMillis();
        String result = sub.run();
        assertTrue("应在数百毫秒内停止", System.currentTimeMillis() - start < 5000);
        assertTrue(result.contains("失败"));
        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("重试了"));
        assertTrue(ui.errors.get(0).contains("仍失败"));
        assertTrue(llm.requests.size() >= 2 && llm.requests.size() <= 5);
        assertTrue(!ui.retryProgress.isEmpty());
        assertEquals(Integer.valueOf(0), ui.retryProgress.get(ui.retryProgress.size() - 1));
    }
}
