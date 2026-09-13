package com.minion.core.agent;

import com.minion.core.llm.LlmException;

/** 重试进度（agent 循环 → UI 指示器）：attempt=0 表示退出重试态（复位），
 *  httpCode/body 为最近一次失败的 HTTP 状态码与响应体（429/500/502 等完整展示）。
 *  网络超时/网络错误/空响应无 HTTP 码，改用 label 携带中文标签、body 携带异常消息。 */
public final class RetryProgress {

    public final int attempt;
    public final int httpCode;
    public final String body;
    /** 非 HTTP 类错误的中文标签（网络超时/网络错误/空响应）；null 表示 HTTP 错误，标签由 httpCode 推导 */
    public final String label;
    /** 距下次重试的等待毫秒（0=不展示等待提示） */
    public final long nextDelayMs;

    private RetryProgress(int attempt, int httpCode, String body, String label, long nextDelayMs) {
        this.attempt = attempt;
        this.httpCode = httpCode;
        this.body = body;
        this.label = label;
        this.nextDelayMs = nextDelayMs;
    }

    public static RetryProgress of(int attempt, int httpCode, String body) {
        return new RetryProgress(attempt, httpCode, body, null, 0);
    }

    /** 网络类错误进度（httpCode 恒 0，detail 为异常消息） */
    public static RetryProgress ofNetwork(int attempt, String label, String detail) {
        return new RetryProgress(attempt, 0, detail, label, 0);
    }

    /** 异常 → 进度映射（不带等待时长；展示层的拼接/截断与此无关） */
    public static RetryProgress from(int attempt, LlmException e) {
        return from(attempt, e, 0);
    }

    /** 异常 → 进度映射 + 下次等待时长（指示器展示「约 N 秒后重试」） */
    public static RetryProgress from(int attempt, LlmException e, long nextDelayMs) {
        RetryProgress base = classify(attempt, e);
        return new RetryProgress(base.attempt, base.httpCode, base.body, base.label, nextDelayMs);
    }

    /** 归类：超时/网络/空响应 → 中文标签；其余 → HTTP 状态码 */
    private static RetryProgress classify(int attempt, LlmException e) {
        if (e.type == LlmException.Type.TIMEOUT) {
            return ofNetwork(attempt, "网络超时", e.getMessage());
        }
        if (e.type == LlmException.Type.NETWORK) {
            return ofNetwork(attempt, "网络错误", e.getMessage());
        }
        if (e.type == LlmException.Type.EMPTY_RESPONSE) {
            return ofNetwork(attempt, "空响应", e.getMessage());
        }
        if (e.type == LlmException.Type.RATE_LIMIT && e.httpCode <= 0) {
            // 类型已表明 HTTP 429（与 tag() 的归一化一致；真实异常恒带 429，此处为防御）
            return of(attempt, 429, e.body);
        }
        return of(attempt, e.httpCode, e.body);
    }

    /** 重试超时总结文案的错误前缀：429/500 类用状态码，网络类/空响应用中文标签 */
    public static String tag(LlmException e) {
        if (e.type == LlmException.Type.TIMEOUT) return "网络超时";
        if (e.type == LlmException.Type.NETWORK) return "网络错误";
        if (e.type == LlmException.Type.EMPTY_RESPONSE) return "空响应";
        if (e.type == LlmException.Type.RATE_LIMIT) return "429";
        return String.valueOf(e.httpCode);
    }

    /** 退出重试态（复位指示器） */
    public static RetryProgress none() {
        return new RetryProgress(0, 0, null, null, 0);
    }
}
