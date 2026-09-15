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

    /** 每链 2 个原子组（user 组 6 token + assistant 组 12 token，共 18 token），供保留预算/可压量计算断言。
     *  实测口径：assistant 正文 10 个中文逐字符累加 0.7 得 7.000000000000001 → 向上取整 8（+4 开销 = 12），
     *  故按 7+4=11 的推演值一律不成立，本文件断言以实测为准。 */
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

    /** 输入末尾 n 条消息（用于"保留区 = 原消息连续后缀（组边界切割）"断言） */
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
        assertEquals("保留区预算比例（0.13×max）", 0.2, ContextManager.KEEP_BUDGET_RATIO, 1e-9);
        assertEquals("常态保底保留最近 4 组", 4, ContextManager.KEEP_MIN_GROUPS);
        assertEquals("危险区保底降为最新 1 组", 1, ContextManager.KEEP_CRITICAL_GROUPS);
        assertEquals("事前收益门槛", 0.05, ContextManager.MIN_COMPRESSIBLE_RATIO, 1e-9);
        assertEquals("危险区豁免", 0.85, ContextManager.FORCE_COMPRESS_RATIO, 1e-9);
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

    /** 常态保底最近 4 组（超预算仍保）：max=260（阈值 169、保留预算 33＝33.8 截断、危险区 221），
     *  chains(12) 24 组 216 token ∈ [169, 221) → 常态；从最新往前累加（12/6 交替）到 36 > 预算 33 仍继续，
     *  保底满 4 组才允许 break → keep=4（4 条消息，超预算也保留）、take=20。
     *  偏离说明（brief 原稿 max=100、8 条消息、超预算 13）：原稿下 204/216 token ≥ 危险区 85/221，
     *  「未进危险区」前提不成立（实测保底会降为 1 组）；且 chains 每组仅 1 条消息（4 组 = 4 条消息，非 8 条）——
     *  按实测口径（每链 18 token）校准窗口与数字，语义不变。 */
    @Test
    public void compress_keepsNewestFourGroupsEvenOverBudget() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(260, llm, 0);
        List<Message> input = chains(12, "问题"); // 24 组 216 token
        assertTrue("场景前提：超阈值且未进危险区（216 ∈ [169, 221)）",
                cm.shouldCompress(input) && cm.estimate(input) < 221);
        List<Message> result = cm.compress(input);
        assertTrue(result.get(0).summary);
        List<Message> kept = result.subList(1, result.size());
        assertEquals("常态保底：最近 4 组（4 条消息）", 4, kept.size());
        assertTrue("保底组自身超预算 33（截断前 33.8；36 token）也不压",
                TokenCounter.estimateMessages(kept) > 33);
        assertSameSequence(lastN(input, kept.size()), kept); // 原消息连续后缀（组边界切割）
        assertTrue("早期组已压缩", llm.completeChatRequests.get(0).contains("[USER] 问题0"));
    }

    /** 预算能装下最近多组时尽量多留（K>1）：max=1000（阈值 650、预算 130），chains(40) 80 组 720 token */
    @Test
    public void compress_keepsMultipleGroupsWithinBudget() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(1000, llm, 0);
        List<Message> input = chains(40, "问题");
        assertTrue(cm.shouldCompress(input));
        List<Message> result = cm.compress(input);
        List<Message> kept = result.subList(1, result.size());
        assertTrue("保留区不得超预算 130", TokenCounter.estimateMessages(kept) <= 130);
        assertTrue("应保留多组（不是保底 1 组）: " + kept.size(), kept.size() >= 2);
        assertSameSequence(lastN(input, kept.size()), kept);
    }

    /** 全部组都在保留预算内 → 无可压缩：返回同一引用、不调 LLM */
    @Test
    public void compress_allGroupsWithinBudget_returnsSameInstance() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(10000, llm, 0); // 预算 1300 >> 全部组 216 token
        List<Message> input = chains(12, "问题");
        assertSame(input, cm.compress(input));
        assertTrue(llm.completeChatRequests.isEmpty());
    }

    /** 常态（未进危险区）：最新组自身超预算也保底 4 组：max=70（阈值 45.5、保留预算 9＝9.1 截断、危险区 59.5），
     *  chains(3) = 6 组 54 token ∈ [45.5, 59.5) → keep=4（后 4 条消息），take=2、可压量 = 前 2 组 18 */
    @Test
    public void compress_keepsNewestFourGroupsWhenNewestOverBudget() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(70, llm, 0);
        List<Message> input = chains(3, "问题"); // 6 组 54 token（实测，brief 推演 51）
        assertTrue("场景前提：超阈值且未进危险区（54 < 59.5）",
                cm.shouldCompress(input) && cm.estimate(input) < 59.5);
        List<Message> result = cm.compress(input);
        assertEquals("摘要 + 最近 4 组（4 条消息）", 5, result.size());
        assertSameSequence(lastN(input, 4), result.subList(1, result.size()));
        assertEquals("可压量 = 6 组 − 4 组 = 前 2 组 18 token", 18L, cm.compressibleTokens(input));
    }

    /** 危险区（≥85%×max）保底降为最新 1 组：同一历史常态保 4 组、危险区只保 1 组 */
    @Test
    public void compress_dangerZone_downgradesKeepToOneGroup() throws Exception {
        List<Message> input = chains(4, "问题"); // 8 组 72 token（实测，brief 推演 68）
        FakeLlmClient normalLlm = new FakeLlmClient();
        normalLlm.compressResult = "【摘要】要点";
        ContextManager normal = new ContextManager(100, normalLlm, 0); // 阈值 65、危险区 85
        assertTrue(normal.shouldCompress(input));
        assertEquals("常态：72 < 危险区 85 → 保底 4 组", 4, normal.compress(input).size() - 1);
        FakeLlmClient dangerLlm = new FakeLlmClient();
        dangerLlm.compressResult = "【摘要】要点";
        ContextManager danger = new ContextManager(60, dangerLlm, 0); // 阈值 39、危险区 51：72 ≥ 51
        assertTrue("场景前提：危险区（72 ≥ 51）", danger.estimate(input) >= 51);
        List<Message> result = danger.compress(input);
        assertEquals("危险区：降为保底最新 1 组", 2, result.size());
        assertSame(input.get(input.size() - 1), result.get(1));
        assertEquals("危险区可压量 = 总 72 − 最新组 12", 60L, danger.compressibleTokens(input));
    }

    /** 可压量 = take 组合计 = 压缩前总量 − 保留区总量 */
    @Test
    public void compressibleTokens_matchesCompressedVolume() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(100, llm, 0); // 预算 13
        List<Message> input = chains(12, "问题");
        int total = TokenCounter.estimateMessages(input);
        long compressible = cm.compressibleTokens(input);
        assertTrue("可压量 > 0", compressible > 0);
        List<Message> result = cm.compress(input);
        int kept = TokenCounter.estimateMessages(result.subList(1, result.size()));
        assertEquals("可压量 = 压缩前总量 − 保留区总量", total - kept, compressible);
    }

    /** 组数 ≤ 保底 4 组：即使超阈值也无可压缩量 → 同一引用返回、不调 LLM */
    @Test
    public void compress_groupCountAtOrBelowMinKeep_returnsSameInstance() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(50, llm, 0); // 阈值 32.5、危险区 42.5
        List<Message> input = chains(2, "问题"); // 4 组 36 token ∈ [32.5, 42.5)（实测，brief 推演 34）
        assertTrue("场景前提：超阈值且未进危险区",
                cm.shouldCompress(input) && cm.estimate(input) < 42.5);
        assertEquals("组数 ≤ 4：可压量 0", 0L, cm.compressibleTokens(input));
        assertSame("无可压缩：同一引用返回", input, cm.compress(input));
        assertTrue("不调 LLM", llm.completeChatRequests.isEmpty());
    }

    /** 全部组在保留预算内 → 可压量 0 */
    @Test
    public void compressibleTokens_zeroWhenAllGroupsWithinBudget() {
        ContextManager cm = new ContextManager(10000, new FakeLlmClient(), 0);
        assertEquals(0L, cm.compressibleTokens(chains(12, "问题")));
    }

    /** worthCompressing：可压量达门槛（5%×max）→ true */
    @Test
    public void worthCompressing_trueWhenYieldReachesThreshold() {
        ContextManager cm = new ContextManager(100, new FakeLlmClient(), 0); // 门槛 5
        long yield = cm.compressibleTokens(chains(12, "问题"));
        assertTrue("可压量应远超门槛 5: " + yield, yield > 5);
        assertTrue(cm.worthCompressing(chains(12, "问题")));
    }

    /** worthCompressing：可压量不足门槛且未进危险区 → false；进入危险区（≥85%×max）→ true */
    @Test
    public void worthCompressing_dangerZoneForcesEvenIfYieldIsLow() {
        // pinned 3200 字符 = 800 token（+4 overhead = 804）；30 组 = 9×5 + 21×6 = 171 token → 总量 975；
        // max=1200：阈值 780、门槛 60、危险区 1020、保留预算 156 → 可压量仅 15（未进危险区）
        List<Message> input = new ArrayList<Message>();
        input.add(pinned(3200));
        for (int i = 0; i < 30; i++) input.add(Message.user(i < 9 ? "x" : "问题" + i));
        ContextManager low = new ContextManager(1200, new FakeLlmClient(), 0);
        assertTrue("场景前提：超阈值", low.shouldCompress(input));
        assertEquals("场景前提：可压量仅 15", 15L, low.compressibleTokens(input));
        assertFalse("危险区外不压", low.worthCompressing(input));
        ContextManager high = new ContextManager(1000, new FakeLlmClient(), 0); // 危险区 850
        assertTrue("危险区（≥85%）强制压", high.worthCompressing(input));
    }

    /** 测试辅助：pinned 大消息（ASCII 字符 n 个 ≈ n×0.25 token） */
    private static Message pinned(int chars) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars; i++) sb.append('x');
        Message m = Message.user(sb.toString());
        m.pinned = true;
        return m;
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

    /** 切割边界只落在原子组边界：保留区是原消息的连续后缀，且 assistant(tool_calls) 的工具结果紧随其后。
     *  8 链（每链 3 组：user 11 token / 工具组[assistant 6 + tool 11] 17 token / 终答 11 token）共 24 组 312 token，
     *  max=400（阈值 260、保留预算 52、门槛 20）：从最新往前累计 50 ≤ 52 → keep=4（组 20..23）、take=20 */
    @Test
    public void compress_boundaryFallsOnGroupBoundary_keepsToolPairing() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】要点";
        ContextManager cm = new ContextManager(400, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 8; i++) {
            msgs.add(Message.user("一二三四五六七八九十"));                        // 11 token
            msgs.add(assistantWithTools("Read"));                                // 6 token
            msgs.add(Message.toolResult("c_Read", "Read", "一二三四五六七八九十")); // 11 token
            msgs.add(Message.assistant("一二三四五六七八九十"));                   // 11 token
        }
        assertEquals("每链 3 组（user / 工具组 / 终答），共 24 组",
                24, ContextManager.chunkGroups(msgs).size());
        assertTrue(cm.shouldCompress(msgs));
        List<Message> result = cm.compress(msgs);
        assertTrue(result.get(0).summary);
        List<Message> kept = result.subList(1, result.size());
        assertNotEquals("保留区首条必须是组首，不能是孤立 TOOL", Message.Role.TOOL, kept.get(0).role);
        assertSameSequence(lastN(msgs, kept.size()), kept); // 保留区 = 原消息连续后缀（组边界切割）
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
