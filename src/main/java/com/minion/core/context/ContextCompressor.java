package com.minion.core.context;

import com.minion.core.agent.RetryPolicy;
import com.minion.core.agent.RetryProgress;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;

import java.util.List;

/** 上下文压缩执行器：单次 compress + 瞬时错误长重试（与请求重试同一策略：分类间隔/墙钟上限），
 *  可被"停止"中断；耗尽或不可重试错误 → FAILED（调用方按场景拼文案），中断 → INTERRUPTED（静默）。
 *  主代理（AgentLoop.compressWithRetry）与子代理（SubAgentLoop）共用本类，避免两套重试策略漂移；
 *  文案（前缀/尾部）与提示通道由调用方决定：主代理走 onError/主重试指示器，子代理走 onSubAgentNotice。 */
public class ContextCompressor {

    /** OK=已压缩；NOTHING=暂无可压缩；FAILED=失败（failReason 有效）；INTERRUPTED=被停止中断（静默） */
    public enum Outcome { OK, NOTHING, FAILED, INTERRUPTED }

    /** 压缩执行结果：messages 在 OK 时为压缩后新列表，其余为原列表 */
    public static class Result {
        public final Outcome outcome;
        public final List<Message> messages;
        /** FAILED 时的原始失败原因（不含调用方前缀/尾部）；其余为 null */
        public final String failReason;
        /** FAILED 的两种来源：true=重试耗尽；false=不可重试错误。文案尾部不同（"仍失败" vs 原始原因） */
        public final boolean exhausted;

        private Result(Outcome outcome, List<Message> messages, String failReason, boolean exhausted) {
            this.outcome = outcome;
            this.messages = messages;
            this.failReason = failReason;
            this.exhausted = exhausted;
        }

        static Result ok(List<Message> messages) { return new Result(Outcome.OK, messages, null, false); }
        static Result nothing(List<Message> messages) { return new Result(Outcome.NOTHING, messages, null, false); }
        static Result failed(List<Message> messages, String reason, boolean exhausted) {
            return new Result(Outcome.FAILED, messages, reason, exhausted);
        }
        static Result interrupted(List<Message> messages) {
            return new Result(Outcome.INTERRUPTED, messages, null, false);
        }
    }

    /** 调用方回调：重试态进度（进入/更新/复位，供主代理指示器或子代理附注）；中断检查（停止按钮/线程中断） */
    public interface Sink {
        void onRetryProgress(RetryProgress p);
        boolean interrupted();
    }

    private final RetryPolicy policy;

    public ContextCompressor(RetryPolicy policy) {
        this.policy = policy;
    }

    public Result run(ContextManager cm, List<Message> messages, Sink sink) {
        if (cm == null) return Result.nothing(messages);
        int attempts = 0;
        long retryStart = System.currentTimeMillis(); // 墙钟基准：含每次压缩请求自身耗时
        boolean inRetry = false;
        try {
            while (true) {
                if (sink.interrupted()) return Result.interrupted(messages);
                try {
                    List<Message> after = cm.compress(messages);
                    return after == messages ? Result.nothing(messages) : Result.ok(after);
                } catch (LlmException e) {
                    if (!RetryPolicy.isTransient(e)) {
                        return Result.failed(messages, e.getMessage(), false);
                    }
                    attempts++;
                    long delay = policy.delayMs(RetryPolicy.kindOf(e));
                    inRetry = true;
                    sink.onRetryProgress(RetryProgress.from(attempts, e, delay)); // 尝试前更新（含等待时长）
                    if (!sleepWithInterruptCheck(delay, sink)) return Result.interrupted(messages);
                    long elapsed = System.currentTimeMillis() - retryStart;
                    if (policy.isExhausted(elapsed)) {
                        return Result.failed(messages, RetryProgress.tag(e) + " 重试了 " + attempts
                                + " 次，持续 " + (elapsed / 60000) + " 分钟仍失败", true);
                    }
                }
            }
        } finally {
            if (inRetry) sink.onRetryProgress(RetryProgress.none()); // 退出重试态复位（幂等）
        }
    }

    /** 可中断等待：100ms 小片轮询中断检查（与 AgentLoop/SubAgentLoop 的 sleepWithInterruptCheck 同语义） */
    private boolean sleepWithInterruptCheck(long ms, Sink sink) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (sink.interrupted()) return false;
            try {
                // Math.max(0, …)：跨毫秒边界/GC 停顿时差值可能为负，Thread.sleep 负值会抛 IllegalArgumentException
                Thread.sleep(Math.max(0, Math.min(100, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断标志，不吞掉中断
                return false;
            }
        }
        return !sink.interrupted();
    }
}
