package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
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
        // 链1 = user + assistant(tools) + tool + assistant(无工具)
        assertEquals(4, chains.get(0).size());
        assertEquals("任务1", chains.get(0).get(0).content);
        assertEquals("任务1完成", chains.get(0).get(3).content);
        // 链2 = user + assistant
        assertEquals(2, chains.get(1).size());
    }

    @Test
    public void chunkChains_skipsSummaryMessages() {
        List<Message> msgs = new ArrayList<Message>();
        Message summary = Message.user("【摘要】之前的内容");
        summary.summary = true;
        msgs.add(summary);
        msgs.add(Message.user("新问题"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        assertEquals(1, chains.size());
        assertFalse(chains.get(0).get(0).summary);
    }

    @Test
    public void chunkChains_skipsSystemMessages() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("你是 minion，一个编码助手"));
        msgs.addAll(sampleHistory());
        // 切块：system 不入链（链1 = 4 条，链2 = 2 条）
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        assertEquals(2, chains.size());
        assertEquals(4, chains.get(0).size());

        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】完成了任务1和任务2";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(msgs);
        // system 原样保留在结果最前，不随首链被压缩丢失
        assertEquals(4, result.size()); // system + summary + 链2(2条)
        assertEquals(Message.Role.SYSTEM, result.get(0).role);
        assertEquals("你是 minion，一个编码助手", result.get(0).content);
        assertEquals("任务2", result.get(2).content);
        // 压缩批次（[0]=压缩器 system，[1]=user 批次）不含系统提示词
        String batch = llm.lastRequestMessages.get(1).content;
        assertFalse(batch.contains("你是 minion，一个编码助手"));
    }

    @Test
    public void shouldCompress_overThreshold() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        // 100*0.8=80 token 触发；20 字符 ≈ 5 token
        List<Message> big = new ArrayList<Message>();
        for (int i = 0; i < 30; i++) {
            big.add(Message.user("一二三四五六七八九十"));
            big.add(Message.assistant("abcdefghij"));
        }
        assertTrue(cm.shouldCompress(big));
        List<Message> small = Collections.singletonList(Message.user("hi"));
        assertFalse(cm.shouldCompress(small));
    }

    @Test
    public void compress_replacesOldWithSummary() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】完成了任务1和任务2";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(sampleHistory());
        // summary 置前
        Message first = result.get(0);
        assertTrue(first.summary);
        assertTrue(first.content.contains("【摘要】"));
        // keepRecent=2 保留最后 2 条原文（链2）
        assertEquals(3, result.size());
        assertEquals("任务2", result.get(1).content);
        // 压缩请求带专用 system
        assertTrue(llm.lastRequestMessages.get(0).content.contains("压缩器"));
    }

    @Test
    public void compress_llmFailure_returnsOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        // FakeLlmClient.completeChat 不抛异常，这里通过设置 compressResult 为空串模拟
        llm.compressResult = "";
        List<Message> input = sampleHistory();
        List<Message> result = cm.compress(input);
        // 空摘要 → 视为失败，原样返回（失败路径返回同一实例，内容与顺序全等；
        // Message 无 equals 重写，引用相等是最强校验）
        assertSame(input, result);
    }

    @Test
    public void compress_llmThrows_returnsOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.throwOnCompleteChat = true;
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> input = sampleHistory();
        List<Message> result = cm.compress(input);
        // 压缩请求抛异常 → 打印告警并原样返回，下轮重试
        assertSame(input, result);
    }

    @Test
    public void estimate_includesSystemTokens() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm0 = new ContextManager(100, 0.8, 2, llm, 0);
        ContextManager cm50 = new ContextManager(100, 0.8, 2, llm, 50);
        // systemTokens 计入 estimate
        assertEquals(50, cm50.estimate(sampleHistory()) - cm0.estimate(sampleHistory()));
        // sampleHistory 6 条：内容 2+2+2+4+2+3=15 + 6×4 overhead=24 → 39；+50 → 89
        assertEquals(89, cm50.estimate(sampleHistory()));
    }

    @Test
    public void update_changesCompressionParams() {
        ContextManager cm = new ContextManager(1000, 0.8, 5, new FakeLlmClient(), 10);
        cm.update(100, 0.1, 8);
        assertEquals(100, cm.maxTokens());
        assertTrue(cm.shouldCompress(sampleHistory())); // 新阈值下必压缩
    }

    @Test
    public void setLlm_usedForCompressionRequests() {
        FakeLlmClient a = new FakeLlmClient();
        a.compressResult = "【摘要】A";
        FakeLlmClient b = new FakeLlmClient();
        b.compressResult = "【摘要】B";
        ContextManager cm = new ContextManager(100, 0.1, 0, a, 10); // 阈值极低：必触发压缩
        List<Message> out = cm.compress(sampleHistory());
        assertNotNull(a.lastRequestMessages); // 压缩走旧客户端
        cm.setLlm(b);
        out = cm.compress(sampleHistory());
        assertTrue(out.get(0).content.contains("【摘要】B")); // 换客户端后走新客户端
    }

    /** 压缩豁免：pinned 技能消息不入链（不参与切块/摘要），普通历史照常成链 */
    @Test
    public void chunkChains_skipsPinnedSkillMessages() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(Message.assistant("完成"));
        msgs.add(Message.skill("<skill name=\"review\">\n审查指令全文\n</skill>"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        // pinned 不入链：链1 = [任务1, 完成]，链2 = [任务2, 回答]
        assertEquals(2, chains.size());
        assertEquals(2, chains.get(0).size());
        assertEquals(2, chains.get(1).size());
    }

    /** 压缩后 pinned 技能消息原样保留（摘要后、保留链前），且不进入摘要批次 */
    @Test
    public void compress_keepsPinnedSkillMessages() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】历史要点";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(Message.assistant("完成"));
        msgs.add(Message.skill("<skill name=\"review\">\n审查指令全文\n</skill>"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("回答"));
        List<Message> result = cm.compress(msgs);
        // summary + pinned + 保留链（链2 = 2 条）
        assertEquals(4, result.size());
        assertTrue(result.get(0).summary);
        assertTrue(result.get(1).pinned);
        assertTrue(result.get(1).content.contains("审查指令全文"));
        assertEquals("任务2", result.get(2).content);
        assertEquals("回答", result.get(3).content);
        // 摘要批次不含技能正文
        String batch = llm.lastRequestMessages.get(1).content;
        assertFalse(batch.contains("审查指令全文"));
    }
}
