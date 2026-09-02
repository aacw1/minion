package com.minion.core.agent;

/** 瞬时错误长重试策略：固定间隔重试 + 墙钟总时长上限（纯算法，无依赖）。
 *  默认：固定 5s/次、墙钟 20 分钟——内网模型资源差场景硬编码常量
 *  （429 限流 / 500 服务端报错 / 502 网关报错 / 网络超时 / 可恢复网络错误），
 *  测试可构造小参数实例覆写。maxTotalMs 的计时口径由调用方决定，本类累计的是
 *  "进入重试态以来的真实时间"（含每次请求自身耗时），非纯等待时长。 */
public class RetryPolicy {

    /** 默认瞬时错误策略：固定 5s/次，墙钟总时长 20 分钟 */
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

    /** 距重试态起点已过去的真实时间是否达到总时长上限（入参由调用方以墙钟计算） */
    public boolean isExhausted(long elapsedMs) {
        return elapsedMs >= maxTotalMs;
    }
}
