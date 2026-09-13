package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;

import static org.junit.Assert.*;

public class AgentLoopCompactTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void autoCompress_triggersOverThreshold() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        // 小上下文上限，快速触发压缩（50×0.65=32.5 在第 4 轮触发）
        ContextManager cm = new ContextManager(50, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        loop.roundLimit = 10;
        // 塞满历史：3 轮 user+assistant ≈ 每轮 12 token
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        // 第 4 轮触发压缩
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
        boolean compressed = ui.warnings.stream().anyMatch(w -> w.contains("自动压缩"));
        assertTrue("应触发自动压缩", compressed);
        assertTrue(loop.messages().get(0).summary);
    }

    @Test
    public void compactNow_compressesImmediately() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        assertFalse(loop.messages().get(0).summary); // 未触发
        loop.compactNow();
        assertTrue(loop.messages().get(0).summary);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("已压缩")));
    }

    /** 自动压缩：压缩前后发 onCompressingChanged，首事件 true、末事件 false */
    @Test
    public void autoCompress_firesCompressingEvents() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        loop.roundLimit = 10;
        // 每轮 ≈12 token（4+2 开销+文本），50×0.65=32.5 阈值：第 4 轮 user 入历史后触发
        for (int i = 0; i < 4; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        assertFalse("应触发自动压缩", ui.compressing.isEmpty());
        assertTrue("首事件=压缩开始", ui.compressing.get(0));
        assertFalse("末事件=压缩结束", ui.compressing.get(ui.compressing.size() - 1));
    }

    /** 手动 /compress：严格 true→false 成对 */
    @Test
    public void compactNow_firesCompressingEvents() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, llm, 0); // 大阈值不自动压缩
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        ui.compressing.clear();
        loop.compactNow();
        assertEquals(Arrays.asList(true, false), ui.compressing);
    }

    /** 手动压缩：压缩完成后推送上下文统计（环形进度圈刷新依据） */
    @Test
    public void compactNow_pushesContextStats() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        assertFalse("用户消息入历史即应推送一次", ui.ctxStats.isEmpty());
        int before = ui.ctxStats.size();
        loop.compactNow();
        assertTrue("压缩完成后应再推送", ui.ctxStats.size() > before);
        int[] last = ui.ctxStats.get(ui.ctxStats.size() - 1);
        assertTrue(last[0] >= 0);
        assertEquals(100000, last[1]);
    }

    /** 自动压缩：整个流程应有上下文统计推送 */
    @Test
    public void autoCompress_pushesContextStats() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        loop.roundLimit = 10;
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
        assertFalse("整个流程应有上下文统计推送", ui.ctxStats.isEmpty());
    }

    /** 压缩瞬时失败（空摘要 → EMPTY_RESPONSE）→ 同策略小参数重试后成功 */
    @Test
    public void autoCompress_transientFailureThenSuccess() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】重试成功";
        llm.failAtCompleteChat.add(1); // 第 1 次压缩返回空串 → EMPTY_RESPONSE（可重试）
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, llm, 0); // 50×0.65=32.5 触发
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000);
        loop.roundLimit = 10;
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        llm.addTurn("压缩后回复"); // 压缩成功后仍发送本轮请求
        loop.runUserTurn("触发压缩"); // 第 4 轮 user 入历史后触发压缩（42 ≥ 32.5）
        assertEquals(2, llm.completeChatRequests.size()); // 失败 1 次 + 重试 1 次
        assertTrue(loop.messages().get(0).summary);
        assertFalse(ui.retryProgress.isEmpty());                     // 进过重试态
        assertEquals(Integer.valueOf(0), ui.retryAttempts().get(ui.retryAttempts().size() - 1));
        assertTrue(ui.errors.isEmpty());
    }

    /** 压缩持续失败至墙钟耗尽：报错并中止本轮（不发送请求），指示器复位 */
    @Test
    public void autoCompress_retryExhausted_stopsTurnWithoutRequest() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = ""; // 恒空摘要 → 恒 EMPTY_RESPONSE
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 50); // 快速耗尽
        loop.roundLimit = 10;
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        loop.runUserTurn("触发压缩"); // 第 4 轮触发压缩：持续失败耗尽后中止，未发送请求
        assertEquals(3, llm.requests.size()); // 前三轮各 1 次请求；第 4 轮压缩失败后中止，未发送
        assertEquals(1, ui.errors.size());
        assertTrue(ui.errors.get(0).contains("自动压缩失败"));
        assertTrue(ui.errors.get(0).contains("仍失败"));
        assertEquals(Integer.valueOf(0), ui.retryAttempts().get(ui.retryAttempts().size() - 1));
    }

    /** 压缩不可重试错误（OTHER/retryable=false）：立即失败中止本轮，不进重试态 */
    @Test
    public void autoCompress_nonRetryableError_stopsImmediately() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.throwOnCompleteChat = true; // LlmException(OTHER, retryable=false)
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(50, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000);
        loop.roundLimit = 10;
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        loop.runUserTurn("触发压缩"); // 第 4 轮触发压缩：不可重试 → 立即失败中止
        assertTrue(ui.retryProgress.isEmpty());                 // 未进重试态
        assertTrue(ui.errors.get(0).contains("自动压缩失败"));
        assertEquals(3, llm.requests.size());                   // 第 4 轮中止：未发送请求
    }
}
