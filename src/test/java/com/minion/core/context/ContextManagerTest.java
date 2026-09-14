package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
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

    /** 每链 2 个原子组（user 组 6 token + assistant 组 12 token，共 18 token），供 take 计算断言 */
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

    /** 输入末尾 n 条消息（用于"最近 6 组原样保留"断言） */
    private static List<Message> lastN(List<Message> msgs, int n) {
        return new ArrayList<Message>(msgs.subList(msgs.size() - n, msgs.size()));
    }

    /** 逐条引用相等断言（Message 未重写 equals，按引用比较即可） */
    private static void assertSameSequence(List<Message> expected, List<Message> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame("第 " + i + " 条不一致", expected.get(i), actual.get(i));
    }

    @Test
    public void chunkGroups_keepsToolPairingAsOneGroup() {
        List<List<Message>> groups = ContextManager.chunkGroups(sampleHistory());
        assertEquals("user / 工具组 / assistant / user / assistant = 5 组", 5, groups.size());
        assertEquals(1, groups.get(0).size());
        assertEquals("assistant(tool_calls)+tool 结果同组", 2, groups.get(1).size());
        assertSame(Message.Role.TOOL, groups.get(1).get(1).role);
        assertEquals(1, groups.get(2).size());
        assertEquals(1, groups.get(3).size());
        assertEquals(1, groups.get(4).size());
    }

    @Test
    public void chunkGroups_skipsSummaryPinnedAndSystem() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("sys"));
        Message summary = Message.user("【摘要】旧");
        summary.summary = true;
        msgs.add(summary);
        msgs.add(Message.skill("<skill name=\"r\">正文</skill>"));
        msgs.add(Message.user("问题"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> groups = ContextManager.chunkGroups(msgs);
        assertEquals("summary/pinned/system 不入组：user、assistant 各一组", 2, groups.size());
        assertEquals(Message.Role.USER, groups.get(0).get(0).role);
        assertEquals(Message.Role.ASSISTANT, groups.get(1).get(0).role);
    }

    @Test
    public void hardcoded_metrics() {
        ContextManager cm = new ContextManager(100, new FakeLlmClient(), 0);
        assertEquals(0.65, cm.threshold(), 1e-9);
        assertEquals(5000, ContextManager.SUMMARY_MAX_CHARS);
        assertEquals("至少保留最近 6 组（写死）", 6, ContextManager.KEEP_RECENT_GROUPS);
        assertTrue(cm.shouldCompress(chains(5, "问题")));   // 5 链（90 token）> 100×0.65
        assertFalse(cm.shouldCompress(chains(2, "问题")));  // 2 链（36 token）< 65
    }

    /** systemTokens 计入 estimate（差值断言，不依赖具体 token 精度） */
    @Test
    public void estimate_includesSystemTokens() {
        ContextManager cm0 = new ContextManager(100, new FakeLlmClient(), 0);
        ContextManager cm50 = new ContextManager(100, new FakeLlmClient(), 50);
        assertEquals(50, cm50.estimate(chains(2, "问题")) - cm0.estimate(chains(2, "问题")));
    }

    /** take 累加：预算 = 100×0.65×0.8 = 52；从最早组累加首次 ≥ 52 即停（12 链 24 组，组 token 6/12 交替，
     *  组 0..5 累计 54 → 压缩前 6 组），且至少保留最近 6 组（组 18..23 为硬下限） */
    @Test
    public void compress_takesEarliestGroupsUntilCompressBudget() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> input = chains(12, "问题"); // 24 组
        List<List<Message>> groups = ContextManager.chunkGroups(input);
        assertEquals(24, groups.size());
        long total = TokenCounter.estimateMessages(input);
        List<Message> result = cm.compress(input);
        assertTrue(result.get(0).summary);
        long kept = TokenCounter.estimateMessages(result.subList(1, result.size()));
        assertTrue("被压部分 token ≥ 预算 52", total - kept >= 52);
        String batch = llm.completeChatRequests.get(0);
        assertTrue("最早组已压缩", batch.contains("[USER] 问题0"));
        assertTrue("预算内最后一组已压缩", batch.contains("[USER] 问题2"));
        assertFalse("预算外的下一组保留", batch.contains("[USER] 问题3"));
        assertSameSequence(lastN(input, 6), lastN(result, 6)); // 最近 6 条（=组 18..23）原样保留
    }

    /** 全部组合计仍不足预算 → 最多压到"组数−6"，不得再压（保留最近 6 组是硬下限） */
    @Test
    public void compress_keepsRecent6GroupsWhenBudgetNotReached() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(10000, llm, 0); // 预算 5200 >> 全部组 216 token
        List<Message> input = chains(12, "问题");
        List<Message> result = cm.compress(input);
        assertEquals("摘要 + 最近 6 组（6 条）", 7, result.size());
        assertTrue(result.get(0).summary);
        String batch = llm.completeChatRequests.get(0);
        assertTrue("早期组已压缩", batch.contains("[USER] 问题8"));
        assertFalse("保留区首组（组 18）未参与压缩", batch.contains("[USER] 问题9"));
        assertSameSequence(lastN(input, 6), lastN(result, 6));
    }

    /** 原子组数 ≤ 6：无从压缩（不能再压就会破坏"至少保留 6 组"）→ 返回同一引用且不调 LLM */
    @Test
    public void compress_tooFewGroups_returnsSameInstanceWithoutLlmCall() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(60, llm, 0); // 阈值 39：6 组 51 token 已超触发
        List<Message> input = chains(3, "问题"); // 6 组
        assertTrue("场景前提：已超触发阈值", cm.shouldCompress(input));
        assertSame(input, cm.compress(input));
        assertTrue("不应发起压缩请求", llm.completeChatRequests.isEmpty());
    }

    /** system/pinned 原样保留在摘要后，且不进压缩批次 */
    @Test
    public void compress_keepsSystemAndPinned() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("你是 minion"));
        for (int i = 0; i < 12; i++) {
            msgs.add(Message.user("问题" + i));
            if (i == 2) msgs.add(Message.skill("<skill>正文</skill>"));
            msgs.add(Message.assistant("一二三四五六七八九十"));
        }
        List<Message> result = cm.compress(msgs);
        assertEquals(Message.Role.SYSTEM, result.get(0).role);
        assertTrue(result.get(1).summary);
        assertTrue("pinned 技能正文常驻于摘要后", result.get(2).pinned);
        assertFalse(llm.completeChatRequests.get(0).contains("<skill>正文</skill>"));
    }

    /** 二次压缩：旧摘要并入输入置前，新摘要取代旧摘要（不丢信息） */
    @Test
    public void compress_secondRun_feedsOldSummaryBack() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】第一轮";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> first = cm.compress(chains(12, "问题"));
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

    /** 历史遗留多条摘要（旧降级路径产物）：全部摘要文本都要并入压缩输入，不得只取首条 */
    @Test
    public void compress_joinsAllOldSummaries() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】新";
        ContextManager cm = new ContextManager(100, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        Message s1 = Message.user("【旧摘要甲】");
        s1.summary = true;
        msgs.add(s1);
        msgs.addAll(chains(12, "问题"));
        Message s2 = Message.user("【旧摘要乙】");
        s2.summary = true;
        msgs.add(s2);
        cm.compress(msgs);
        String sent = llm.completeChatRequests.get(0);
        assertTrue("第一条旧摘要并入输入", sent.contains("【旧摘要甲】"));
        assertTrue("第二条旧摘要并入输入（不丢信息）", sent.contains("【旧摘要乙】"));
    }

    /** 切割边界只落在原子组边界：保留区为原消息的连续后缀，且 assistant(tool_calls) 的 tool 结果紧随其后。
     *  8 链（每链 3 组：user 12 token / 工具组[assistant 6 + tool 12] 18 token / 终答 12 token），
     *  预算 104：组 0..7 累计 114 ≥ 104 → 压 8 组、保留组 8..23。 */
    @Test
    public void compress_boundaryFallsOnGroupBoundary_keepsToolPairing() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(200, llm, 0); // 触发 130、预算 104
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 8; i++) {
            msgs.add(Message.user("一二三四五六七八九十"));                            // 11 token
            msgs.add(assistantWithTools("Read"));                                    // 6 token
            msgs.add(Message.toolResult("c_Read", "Read", "一二三四五六七八九十"));     // 11 token
            msgs.add(Message.assistant("一二三四五六七八九十"));                       // 11 token
        }
        List<List<Message>> groups = ContextManager.chunkGroups(msgs);
        assertEquals("每链 3 组（user / 工具组 / 终答），共 24 组", 24, groups.size());
        List<Message> result = cm.compress(msgs);
        assertTrue(result.get(0).summary);
        List<Message> kept = result.subList(1, result.size());
        assertNotEquals("保留区首条必须是组首，不能是孤立 TOOL", Message.Role.TOOL, kept.get(0).role);
        List<Message> expected = new ArrayList<Message>();
        for (int i = 8; i < groups.size(); i++) expected.addAll(groups.get(i)); // 组 0..7 被压
        assertSameSequence(expected, kept);
        for (int i = 0; i < kept.size(); i++) {
            Message m = kept.get(i);
            if (m.role == Message.Role.ASSISTANT && m.toolCalls != null && !m.toolCalls.isEmpty()) {
                for (int j = 0; j < m.toolCalls.size(); j++) {
                    assertEquals("工具组的 tool 结果必须紧随 assistant", Message.Role.TOOL,
                            kept.get(i + 1 + j).role);
                }
            }
        }
    }

    /** 无可压缩（无原子组）→ 返回同一引用，不抛错 */
    @Test
    public void compress_noGroups_returnsSameInstance() throws Exception {
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
            cm.compress(chains(12, "问题"));
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
            cm.compress(chains(12, "问题"));
            fail("应抛 EMPTY_RESPONSE");
        } catch (LlmException e) {
            assertEquals(LlmException.Type.EMPTY_RESPONSE, e.type);
            assertTrue(e.retryable);
        }
    }

    /** 压缩 system 提示词携带固定 5000 字上限 */
    @Test
    public void compress_prompt_carriesSummaryLimit() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(100, llm, 0);
        cm.compress(chains(12, "问题"));
        String systemPrompt = llm.lastRequestMessages.get(0).content;
        assertTrue("提示词应写明 5000 字以内", systemPrompt.contains("5000 字以内"));
    }

    @Test
    public void update_changesMaxTokens() {
        ContextManager cm = new ContextManager(1000, new FakeLlmClient(), 10);
        cm.update(100);
        assertEquals(100, cm.maxTokens());
    }

    /** 定制压缩指令：压缩请求的 system 使用构造时注入的指令（子代理版），默认构造仍用主代理版 */
    @Test
    public void compress_customSystemPrompt_usedInRequest() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(50, llm, 0, "【子代理压缩指令】保留任务目标与落盘路径");
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 4; i++) {
            msgs.add(Message.user("历史" + i));
            msgs.add(Message.assistant("回复" + i));
        }
        cm.compress(msgs);
        assertEquals("【子代理压缩指令】保留任务目标与落盘路径",
                llm.lastRequestMessages.get(0).content);
    }

    /** 默认构造：system 仍是主代理版指令（前缀校验，防止抽取时误改） */
    @Test
    public void compress_defaultSystemPrompt_isMainPrompt() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(50, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 4; i++) {
            msgs.add(Message.user("历史" + i));
            msgs.add(Message.assistant("回复" + i));
        }
        cm.compress(msgs);
        assertTrue(llm.lastRequestMessages.get(0).content.startsWith("你是 minion 的上下文压缩器"));
    }

    /** 子代理定制指令：访问器返回构造时注入的指令，且文案保留 5000 字上限（压缩输出不得失控） */
    @Test
    public void subAgentCompressSystem_carriesSummaryLimit() {
        ContextManager cm = new ContextManager(50, new FakeLlmClient(), 0,
                ContextManager.SUB_AGENT_COMPRESS_SYSTEM);
        assertEquals(ContextManager.SUB_AGENT_COMPRESS_SYSTEM, cm.compressSystem());
        assertTrue("指令应写明 5000 字以内",
                ContextManager.SUB_AGENT_COMPRESS_SYSTEM.contains("5000 字以内"));
        assertTrue("指令应强调已落盘路径（后续汇报要引用）",
                ContextManager.SUB_AGENT_COMPRESS_SYSTEM.contains("落盘"));
    }
}
