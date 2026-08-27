package com.minion.core.agent;

/** 瞬时错误长重试策略：固定间隔重试 + 总时长上限（纯算法，无依赖）。
 *  默认：固定 5s/次、总时长 20 分钟——内网模型资源差场景硬编码常量（429 限流/500 服务端报错/502 网关报错），
 *  测试可构造小参数实例覆写。 */
public class RetryPolicy {

    /** 默认瞬时错误策略（429/500/502）：固定 5s/次，总时长 20 分钟 */
    public static RetryPolicy transientErrors() {
        return new RetryPolicy(5000, 0, 5000, 20 * 60 * 1000L);
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
