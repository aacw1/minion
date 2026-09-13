package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ContextManagerTest {

    private static Message assistantWithTools(String... names) {
        Message m = Message.assistant(null);
        List<ToolCall> tcs = new ArrayList<ToolCall>();
        for (String n : names) {
            ToolCall tc = new ToolCall();
            tc.id = "c_" + n;
            tc.name = n;
            tc.arguments = "{}";
            tcs.add(tc);
        }
        m.toolCalls = tcs;
        return m;
    }

    /** 每链 2 条（10 中文字 = 7 token/条、+4 开销 → 22 token/链），供 take 计算断言 */
    private static List<Message> chains(int chainCount, String prefix) {
        List<Message> msgs = new ArrayList<Message>();
        String base = "一二三四五六七八九十";
        for (int i = 0; i < chainCount; i++) {
            msgs.add(Message.user(prefix + i));
            msgs.add(Message.assistant(base));
        }
        return msgs;
    }

    private static List<Message> sampleHistory() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(assistantWithTools("Read"));
        msgs.add(Message.toolResult("c_Read", "Read", "内容1"));
        msgs.add(Message.assistant("任务1完成"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("直接完成"));
        return msgs;
    }

    @Test
    public void chunkChains_keepsToolPairingIntact() {
        List<List<Message>> chains = ContextManager.chunkChains(sampleHistory());
        assertEquals(2, chains.size());
        assertEquals(4, chains.get(0).size());
        assertEquals(2, chains.get(1).size());
    }

    @Test
    public void chunkChains_skipsSummaryPinnedAndSystem() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("sys"));
        Message summary = Message.user("【摘要】旧");
        summary.summary = true;
        msgs.add(summary);
        msgs.add(Message.skill("<skill name=\"r\">正文</skill>"));
        msgs.add(Message.user("问题"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        assertEquals(1, chains.size());
        assertEquals(2, chains.get(0).size());
    }

    @Test
    public void hardcoded_metrics() {
        ContextManager cm = new ContextManager(100, new FakeLlmClient(), 0);
        assertEquals(0.65, cm.threshold(), 1e-9);
        assertEquals(5000, ContextManager.SUMMARY_MAX_CHARS);
        assertTrue(cm.shouldCompress(chains(5, "问题")));   // 5 链 > 100×0.65
        assertFalse(cm.shouldCompress(chains(2, "问题")));  // 2 链 < 65
    }

    /** systemTokens 计入 estimate（差值断言，不依赖具体 token 精度） */
    @Test
    public void estimate_includesSystemTokens() {
        ContextManager cm0 = new ContextManager(100, new FakeLlmClient(), 0);
        ContextManager cm50 = new ContextManager(100, new FakeLlmClient(), 50);
        assertEquals(50, cm50.estimate(chains(2, "问题")) - cm0.estimate(chains(2, "问题")));
    }

    /** take 累加：预算 = 100×0.65×0.8 = 52；从最早链累加首次 ≥ 预算即停（压缩量 ≥ 预算），
     *  后续链整链保留（不切断配对） */
    @Test
    public void compress_takesEarliestChainsUntilCompressBudget() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> input = chains(5, "问题");
        long total = TokenCounter.estimateMessages(input);
        assertTrue("场景前提：总 token 超过压缩预算", total > 52);
        List<Message> result = cm.compress(input);
        assertTrue(result.get(0).summary);
        assertTrue("应保留部分链", result.size() > 1 && result.size() < input.size());
        assertEquals("保留区为完整链（user+assistant 成对）", 0, (result.size() - 1) % 2);
        long kept = TokenCounter.estimateMessages(result.subList(1, result.size()));
        assertTrue("被压部分 token ≥ 预算（52）", total - kept >= 52);
        String batch = llm.completeChatRequests.get(0);
        assertTrue("最早链已压缩", batch.contains("[USER] 问题0"));
        assertFalse("最后一条链保留（不在压缩批次）", batch.contains("[USER] 问题4"));
    }

    /** 全部链合计仍不足压缩预算 → 全压（保留区为空，仅摘要） */
    @Test
    public void compress_allChainsWhenBudgetNotReached() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(10000, llm, 0); // 预算 5200 >> 全部链
        List<Message> result = cm.compress(chains(4, "问题"));
        assertEquals(1, result.size());
        assertTrue(result.get(0).summary);
    }

    /** system/pinned 原样保留在摘要先后，且不进压缩批次 */
    @Test
    public void compress_keepsSystemAndPinned() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("你是 minion"));
        for (int i = 0; i < 5; i++) {
            msgs.add(Message.user("问题" + i));
            if (i == 2) msgs.add(Message.skill("<skill>正文</skill>"));
            msgs.add(Message.assistant("一二三四五六七八九十"));
        }
        List<Message> result = cm.compress(msgs);
        assertEquals(Message.Role.SYSTEM, result.get(0).role);
        assertTrue(result.get(1).summary);
        assertTrue(result.get(2).pinned);
        assertFalse(llm.completeChatRequests.get(0).contains("<skill>正文</skill>"));
    }

    /** 二次压缩：旧摘要并入输入置前，新摘要取代旧摘要（不丢信息） */
    @Test
    public void compress_secondRun_feedsOldSummaryBack() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】第一轮";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> first = cm.compress(chains(5, "问题"));
        assertTrue(first.get(0).summary);
        // 再追加消息后二次压缩
        List<Message> second = new ArrayList<Message>(first);
        for (int i = 0; i < 4; i++) {
            second.add(Message.user("新" + i));
            second.add(Message.assistant("一二三四五六七八九十"));
        }
        cm.compress(second);
        assertTrue(llm.completeChatRequests.get(1).contains("【摘要】第一轮"));
    }

    /** 无可压缩（无链）→ 返回同一引用，不抛错 */
    @Test
    public void compress_noChains_returnsSameInstance() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("sys"));
        msgs.add(Message.skill("<skill>正文</skill>"));
        assertSame(msgs, cm.compress(msgs));
    }

    /** 压缩请求抛异常 → 原样上抛（不再降级/静默返回） */
    @Test
    public void compress_llmThrows_propagates() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.throwOnCompleteChat = true;
        ContextManager cm = new ContextManager(100, llm, 0);
        try {
            cm.compress(chains(5, "问题"));
            fail("应抛 LlmException");
        } catch (LlmException e) {
            assertEquals(LlmException.Type.OTHER, e.type);
        }
    }

    /** 空摘要 → EMPTY_RESPONSE（可重试，按 500 类） */
    @Test
    public void compress_emptySummary_throwsEmptyResponse() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "  ";
        ContextManager cm = new ContextManager(100, llm, 0);
        try {
            cm.compress(chains(5, "问题"));
            fail("应抛 EMPTY_RESPONSE");
        } catch (LlmException e) {
            assertEquals(LlmException.Type.EMPTY_RESPONSE, e.type);
            assertTrue(e.retryable);
        }
    }

    /** 保留区首条不为孤立 TOOL：链内配对（assistant(tools)+tool）不被切开 */
    @Test
    public void compress_keepRegionHeadKeepsToolPairing() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(200, llm, 0); // 触发 130、预算 104（8 链 39 token/链 → 压 3 链、保留 5 链）
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 8; i++) { // 每链 4 条 = 39 token（末条无工具 assistant 收链）
            msgs.add(Message.user("一二三四五六七八九十"));
            msgs.add(assistantWithTools("Read"));
            msgs.add(Message.toolResult("c_Read", "Read", "一二三四五六七八九十"));
            msgs.add(Message.assistant("一二三四五六七八九十"));
        }
        List<Message> result = cm.compress(msgs);
        assertTrue(result.get(0).summary);
        assertTrue("应保留部分链", result.size() > 1);
        assertNotEquals(Message.Role.TOOL, result.get(1).role); // 保留区首条为链首（user）
        assertEquals(Message.Role.TOOL, result.get(3).role);    // 配对完整：assistant(tools) 后紧跟 tool
    }

    @Test
    public void update_changesMaxTokens() {
        ContextManager cm = new ContextManager(1000, new FakeLlmClient(), 10);
        cm.update(100);
        assertEquals(100, cm.maxTokens());
    }
}
