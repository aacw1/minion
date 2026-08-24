package com.minion.core.agent;

/** 429 限流长重试策略：线性递增退避 + 总时长上限（纯算法，无依赖）。
 *  默认：2s 起步、每次 +2s、上限 10s、总时长 30 分钟——内网模型资源差场景硬编码常量，
 *  测试可构造小参数实例覆写。 */
public class RetryPolicy {

    /** 默认 429 策略：2s 起步 +2s/次，上限 10s，总时长 30 分钟 */
    public static RetryPolicy rateLimit() {
        return new RetryPolicy(2000, 2000, 10000, 30 * 60 * 1000L);
    }

    public final long initialDelayMs;
    public final long incrementMs;
    public final long maxDelayMs;
    public final long maxTotalMs;

    public RetryPolicy(long initialDelayMs, long incrementMs, long maxDelayMs, long maxTotalMs) {
        this.initialDelayMs = initialDelayMs;
        this.incrementMs = incrementMs;
        this.maxDelayMs = maxDelayMs;
        this.maxTotalMs = maxTotalMs;
    }

    /** 第 attempt 次重试（1 起）前的等待时长，封顶 maxDelayMs */
    public long delayMs(int attempt) {
        long d = initialDelayMs + incrementMs * (attempt - 1);
        return Math.min(d, maxDelayMs);
    }

    /** 累计等待是否已超总时长上限 */
    public boolean isExhausted(long elapsedMs) {
        return elapsedMs >= maxTotalMs;
    }
}
