package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class AgentLoopCompactTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 预置 4 轮普通历史 = 8 个原子组（56 token）：max=50 时 56+本轮 user 7 = 63 ≥ 阈值 32.5，
     *  且已进危险区（≥50×0.85=42.5）→ 强制压缩；max=100 时 56+6 = 62 < 阈值 65 → 不自动压缩，
     *  仅手动 /compact 时按保留区预算（13 token）保留最近 2 组、可压 7 组 */
    private static void seedHistory(AgentLoop loop) {
        for (int i = 0; i < 4; i++) {
            loop.messages().add(Message.user("历史" + i));
            loop.messages().add(Message.assistant("回复" + i));
        }
    }

    @Test
    public void autoCompress_triggersOverThreshold() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        // 小上下文上限，快速触发压缩（50×0.65=32.5；seed 8 组 56 + 本轮 user 7 = 63 ≥ 危险区 42.5 → 强制压缩）
        ContextManager cm = new ContextManager(50, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        loop.roundLimit = 10;
        seedHistory(loop);
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩"); // 本轮 user 入历史后触发压缩
        boolean compressed = ui.warnings.stream().anyMatch(w -> w.contains("自动压缩"));
        assertTrue("应触发自动压缩", compressed);
        assertTrue(loop.messages().get(0).summary);
    }

    /** v2：可压量不足 → 事前跳过（不调压缩 LLM）+ 提示只出现一次（每回合一次，不阻塞后续压缩） */
    @Test
    public void autoCompress_lowYield_skipsWithoutLlmAndWarnsOnce() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(200000, llm, 0); // 阈值 130k、门槛 10k、预算 26k、危险区 170k
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000);
        loop.roundLimit = 20;
        // pinned 120k token（480000 字符）+ 4 组各 3k（12000 字符）：可压量 ≈ 3k < 门槛 10k，estimate ≈ 132k ∈ [130k,170k)
        // 说明：brief 原给的 pin 420000 字符实测仅 105004 token，pin+4 组 = 117020 < 阈值 130000，
        // 场景前提不成立（大窗口 + 小历史本就无任何可压量），故按实际 token 口径改为 480000 字符。
        Message pin = Message.user(ascii(480000));
        pin.pinned = true;
        loop.messages().add(pin);
        for (int i = 0; i < 4; i++) loop.messages().add(Message.user(ascii(12000)));
        assertTrue("场景前提：超阈值", cm.shouldCompress(loop.messages()));
        assertFalse("场景前提：可压量不足门槛", cm.worthCompressing(loop.messages()));
        // 3 轮小工具（可压量增长但始终 < 门槛 10k）+ 1 轮收尾：每次请求前都走「暂缓」分支
        for (int r = 0; r < 3; r++) {
            ToolCall tc = new ToolCall();
            tc.id = "s" + r;
            tc.name = "example";
            tc.arguments = "{\"text\":\"hi\"}";
            llm.addTurnWithTools(Collections.singletonList(tc), null);
        }
        llm.addTurn("完成"); // 暂缓不阻塞本轮请求
        loop.runUserTurn(ascii(60000)); // 本轮 user 15k token
        assertTrue("可压内容不足 → 不调压缩 LLM: " + llm.completeChatRequests,
                llm.completeChatRequests.isEmpty());
        assertEquals("「暂缓」提示每回合只一次: " + ui.warnings,
                1, ui.warnings.stream().filter(w -> w.contains("自动压缩暂缓")).count());
    }

    /** 长任务回合：任务推进使可压量增长 → 压缩可发生多次（回归"回合内只能用一次"） */
    @Test
    public void autoCompress_longTurn_compressesMultipleTimes() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(60000, llm, 0); // 阈值 39k、门槛 3k、预算 7.8k、危险区 51k
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000);
        loop.roundLimit = 20;
        // 每轮 1 个 example 工具，text 30000 字符 → 组 ≈ 15k token（arguments 7.5k + tool 结果 7.5k）
        for (int r = 0; r < 5; r++) {
            ToolCall tc = new ToolCall();
            tc.id = "m" + r;
            tc.name = "example";
            tc.arguments = "{\"text\":\"" + ascii(30000) + "\"}";
            llm.addTurnWithTools(Collections.singletonList(tc), null);
        }
        llm.addTurn("完成");
        loop.runUserTurn("跑长任务");
        int n = llm.completeChatRequests.size();
        assertTrue("长任务回合内应能多次压缩（至少 2 次），实际 " + n, n >= 2);
        assertTrue("压缩有效提示出现: " + ui.warnings,
                ui.warnings.stream().anyMatch(w -> w.contains("降低至") || w.contains("仍占")));
        assertEquals("完成", loop.messages().get(loop.messages().size() - 1).content);
    }

    /** 危险区（≥85%×max）：可压量不足门槛也强制压缩一次（保命） */
    @Test
    public void autoCompress_dangerZone_compressesDespiteLowYield() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(1200, llm, 0); // 阈值 780、门槛 60、预算 156、危险区 1020
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000);
        loop.roundLimit = 20;
        Message pin = Message.user(ascii(4000)); // 1000 token → estimate ≥ 危险区
        pin.pinned = true;
        loop.messages().add(pin);
        for (int i = 0; i < 30; i++) loop.messages().add(Message.user(i < 9 ? "x" : "问" + (i % 10)));
        assertTrue("场景前提：超阈值", cm.shouldCompress(loop.messages()));
        assertTrue("场景前提：进危险区", cm.estimate(loop.messages()) >= 1020);
        assertTrue("场景前提：强制压", cm.worthCompressing(loop.messages()));
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
        assertEquals("危险区强制压缩一次", 1, llm.completeChatRequests.size());
    }

    /** ascii 辅助（Task 内新增，供多轮/跳过用例共用） */
    private static String ascii(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
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
        // max=100（阈值 65、保留预算 13）：seed 8 组 56 + 本轮 user 6 = 62 < 65 不自动压缩；
        // 新语义下大窗口 + 小历史无可压量（保留预算 13000 ≫ 54），手动压缩会返回「暂无可压缩」，故改小上限
        ContextManager cm = new ContextManager(100, llm, 0);
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000); // 压缩失败重试的小参数（防真等）
        seedHistory(loop);
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
        seedHistory(loop);
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
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
        seedHistory(loop);
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
        seedHistory(loop); // 8 组（48 token）+ 本轮 user = 9 组，超阈值且可压缩
        llm.addTurn("压缩后回复"); // 压缩成功后仍发送本轮请求
        loop.runUserTurn("触发压缩");
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
        seedHistory(loop);
        loop.runUserTurn("触发压缩"); // 压缩持续失败耗尽后中止，未发送请求
        assertTrue("压缩失败中止本轮：未发送请求", llm.requests.isEmpty());
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
        seedHistory(loop);
        loop.runUserTurn("触发压缩"); // 不可重试 → 立即失败中止
        assertTrue(ui.retryProgress.isEmpty());                 // 未进重试态
        assertTrue(ui.errors.get(0).contains("自动压缩失败"));
        assertTrue("中止本轮：未发送请求", llm.requests.isEmpty());
    }

    /** 手动 /compact 失败文案：中性「压缩失败」，不带「自动」字样（与自动压缩路径区分） */
    @Test
    public void compactNow_failure_wordingIsNeutral() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.throwOnCompleteChat = true; // 不可重试错误 → 立即失败
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        // max=100：同 compactNow_compressesImmediately——大窗口下小历史无任何可压量，
        // 手动压缩会直接返回「暂无可压缩」而不进失败路径，故用能压出内容的窗口上限
        ContextManager cm = new ContextManager(100, llm, 0); // 62 < 阈值 65：不自动压缩，只走手动路径
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cm,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.retryPolicy = new RetryPolicy(10, 10, 60000);
        seedHistory(loop);
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        loop.compactNow();
        assertEquals(1, ui.errors.size());
        assertTrue("手动压缩失败应报「压缩失败：…」", ui.errors.get(0).startsWith("压缩失败："));
        assertFalse("不应出现「自动」字样", ui.errors.get(0).contains("自动"));
    }
}
