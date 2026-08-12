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
        // 小上下文上限，快速触发压缩（60×0.8=48 需 5 轮才够，50×0.8=40 在第 4 轮触发）
        ContextManager cm = new ContextManager(50, 0.8, 2, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
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
        ContextManager cm = new ContextManager(100000, 0.8, 1, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        assertFalse(loop.messages().get(0).summary); // 未触发
        loop.compactNow();
        assertTrue(loop.messages().get(0).summary);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("已压缩")));
    }
}
