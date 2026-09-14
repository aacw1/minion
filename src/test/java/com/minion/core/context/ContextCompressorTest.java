package com.minion.core.context;

import com.minion.core.agent.RetryPolicy;
import com.minion.core.agent.RetryProgress;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 压缩执行器：单次压缩 + 瞬时错误长重试（主代理/子代理共用，文案由调用方拼装） */
public class ContextCompressorTest {

    /** 记录重试回调 + 可控中断标志（供 Sink 断言与中断用例） */
    private static class RecordingSink implements ContextCompressor.Sink {
        final List<RetryProgress> progress = new ArrayList<RetryProgress>();
        boolean interrupted = false;
        @Override public void onRetryProgress(RetryProgress p) { progress.add(p); }
        @Override public boolean interrupted() { return interrupted; }
        /** 重试次数序列（0=复位） */
        List<Integer> attempts() {
            List<Integer> out = new ArrayList<Integer>();
            for (RetryProgress p : progress) out.add(p.attempt);
            return out;
        }
    }

    /** 8 组普通历史（实测 64 token = 8×ceil(5×0.7+4)，> 50×0.65 阈值；保留区预算 0.13×max = 6.5 token；本场景 64 ≥ 危险区
     *  85%×max = 42.5，危险区降级保底最新 1 组，
     *  故 8 组中 7 组可压——v2 语义，旧的「≥7 组才可能压缩」不再成立） */
    private static List<Message> history() {
        List<Message> msgs = new ArrayList<Message>();
        for (int i = 0; i < 4; i++) {
            msgs.add(Message.user("历史问题" + i));
            msgs.add(Message.assistant("历史回复" + i));
        }
        return msgs;
    }

    @Test
    public void run_ok_returnsCompressedMessages() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】压缩结果";
        ContextManager cm = new ContextManager(50, llm, 0);
        RecordingSink sink = new RecordingSink();

        ContextCompressor.Result r = new ContextCompressor(new RetryPolicy(10, 10, 60000))
                .run(cm, history(), sink);

        assertEquals(ContextCompressor.Outcome.OK, r.outcome);
        assertTrue(r.messages.get(0).summary);
        assertTrue(sink.progress.isEmpty()); // 一次成功：不进重试态
    }

    @Test
    public void run_nothing_whenNoGroupToCompress() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100000, llm, 0);
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("a"));
        RecordingSink sink = new RecordingSink();

        ContextCompressor.Result r = new ContextCompressor(new RetryPolicy(10, 10, 60000))
                .run(cm, msgs, sink);

        assertEquals(ContextCompressor.Outcome.NOTHING, r.outcome);
        assertSame("NOTHING 必须返回原列表实例（引用相等语义供 AgentLoop 判断）", msgs, r.messages);
        assertTrue(llm.completeChatRequests.isEmpty());
    }

    @Test
    public void run_permanentFailure_immediateFailed() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.throwOnCompleteChat = true; // OTHER/retryable=false
        ContextManager cm = new ContextManager(50, llm, 0);
        RecordingSink sink = new RecordingSink();

        ContextCompressor.Result r = new ContextCompressor(new RetryPolicy(10, 10, 60000))
                .run(cm, history(), sink);

        assertEquals(ContextCompressor.Outcome.FAILED, r.outcome);
        assertFalse(r.exhausted);
        assertNotNull(r.failReason);
        assertTrue(sink.progress.isEmpty()); // 不可重试：不进重试态
    }

    @Test
    public void run_transientThenSuccess_retriesOnce() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】重试成功";
        llm.failAtCompleteChat.add(1); // 第 1 次返回空串 → EMPTY_RESPONSE（可重试）
        ContextManager cm = new ContextManager(50, llm, 0);
        RecordingSink sink = new RecordingSink();

        ContextCompressor.Result r = new ContextCompressor(new RetryPolicy(10, 10, 60000))
                .run(cm, history(), sink);

        assertEquals(ContextCompressor.Outcome.OK, r.outcome);
        assertEquals(Integer.valueOf(0), sink.attempts().get(sink.attempts().size() - 1)); // 退出重试态复位
        assertEquals(Integer.valueOf(1), sink.attempts().get(0));
    }

    @Test
    public void run_exhausted_returnsExhaustedFailure() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = ""; // 恒空摘要
        ContextManager cm = new ContextManager(50, llm, 0);
        RecordingSink sink = new RecordingSink();

        ContextCompressor.Result r = new ContextCompressor(new RetryPolicy(10, 10, 50))
                .run(cm, history(), sink);

        assertEquals(ContextCompressor.Outcome.FAILED, r.outcome);
        assertTrue(r.exhausted);
        assertTrue("耗尽文案含重试次数: " + r.failReason, r.failReason.contains("重试了"));
        assertEquals(Integer.valueOf(0), sink.attempts().get(sink.attempts().size() - 1));
    }

    @Test
    public void run_interrupted_returnsInterruptedWithoutRequest() throws Exception {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(50, llm, 0);
        RecordingSink sink = new RecordingSink();
        sink.interrupted = true;

        ContextCompressor.Result r = new ContextCompressor(new RetryPolicy(10, 10, 60000))
                .run(cm, history(), sink);

        assertEquals(ContextCompressor.Outcome.INTERRUPTED, r.outcome);
        assertTrue(llm.completeChatRequests.isEmpty()); // 中断即不发请求
        assertTrue(sink.progress.isEmpty());
    }
}
