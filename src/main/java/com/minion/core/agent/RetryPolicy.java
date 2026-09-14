package com.minion.core.agent;

import com.minion.core.llm.LlmException;

/** 瞬时错误长重试策略：按错误类别区分等待间隔 + 墙钟总时长上限（含分类判定）。
 *  默认：短等待 5s（429 限流 / 超时 / 可恢复网络错误）、长等待 30s（500 类：500/502/503/504
 *  及其他 5xx，与空响应）、墙钟总时长 12 分钟。等待时长由"最近一次失败的类别"决定。
 *  maxTotalMs 的计时口径由调用方决定，本类累计的是"进入重试态以来的真实时间"
 *  （含每次请求自身耗时），非纯等待时长；测试可构造小参数实例覆写。 */
public class RetryPolicy {

    /** 等待类别：SHORT=429/超时/可恢复网络错误；LONG=500 类/空响应 */
    public enum Kind { SHORT, LONG }

    /** 默认瞬时错误策略：SHORT 5s / LONG 30s，墙钟总时长 12 分钟 */
    public static RetryPolicy transientErrors() {
        return new RetryPolicy(5000, 30000, 12 * 60 * 1000L);
    }

    public final long shortDelayMs;
    public final long longDelayMs;
    public final long maxTotalMs;

    public RetryPolicy(long shortDelayMs, long longDelayMs, long maxTotalMs) {
        this.shortDelayMs = shortDelayMs;
        this.longDelayMs = longDelayMs;
        this.maxTotalMs = maxTotalMs;
    }

    /** 按最近一次失败的错误类别取等待时长 */
    public long delayMs(Kind kind) {
        return kind == Kind.LONG ? longDelayMs : shortDelayMs;
    }

    /** 距重试态起点已过去的真实时间是否达到总时长上限（入参由调用方以墙钟计算） */
    public boolean isExhausted(long elapsedMs) {
        return elapsedMs >= maxTotalMs;
    }

    /** 可进长重试的瞬时错误：429 / 超时 / 可恢复网络错误 / 500 类（含 503/504 等其他 5xx）/ 空响应。
     *  DNS 解析失败（NETWORK 且 retryable=false）与 400/401/403 等永久性错误不放行 */
    public static boolean isTransient(LlmException e) {
        if (e.type == LlmException.Type.EMPTY_RESPONSE) return true;
        if (e.type == LlmException.Type.RATE_LIMIT
                || e.type == LlmException.Type.TIMEOUT) return true;
        if (e.type == LlmException.Type.NETWORK) return e.retryable;
        return e.httpCode >= 500;
    }

    /** 错误类别（仅对 isTransient 为真的错误有意义）：500 类与空响应 LONG，其余瞬时错误 SHORT */
    public static Kind kindOf(LlmException e) {
        if (e.type == LlmException.Type.EMPTY_RESPONSE) return Kind.LONG;
        if (e.type == LlmException.Type.RATE_LIMIT
                || e.type == LlmException.Type.TIMEOUT
                || e.type == LlmException.Type.NETWORK) return Kind.SHORT;
        return Kind.LONG; // httpCode >= 500
    }
}
