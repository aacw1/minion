package com.minion.core.agent;

import com.minion.core.llm.LlmException;

/** 重试进度（agent 循环 → UI 指示器）：attempt=0 表示退出重试态（复位），
 *  httpCode/body 为最近一次失败的 HTTP 状态码与响应体（429/500/502 均完整展示）。
 *  网络超时/网络错误无 HTTP 码，改用 label 携带中文标签、body 携带异常消息。 */
public final class RetryProgress {

    public final int attempt;
    public final int httpCode;
    public final String body;
    /** 非 HTTP 类错误的中文标签（网络超时/网络错误）；null 表示 HTTP 错误，标签由 httpCode 推导 */
    public final String label;

    private RetryProgress(int attempt, int httpCode, String body, String label) {
        this.attempt = attempt;
        this.httpCode = httpCode;
        this.body = body;
        this.label = label;
    }

    public static RetryProgress of(int attempt, int httpCode, String body) {
        return new RetryProgress(attempt, httpCode, body, null);
    }

    /** 网络类错误进度（httpCode 恒 0，detail 为异常消息） */
    public static RetryProgress ofNetwork(int attempt, String label, String detail) {
        return new RetryProgress(attempt, 0, detail, label);
    }

    /** 异常 → 进度映射（只做归类，展示层的拼接/截断与此无关） */
    public static RetryProgress from(int attempt, LlmException e) {
        if (e.type == LlmException.Type.TIMEOUT) {
            return ofNetwork(attempt, "网络超时", e.getMessage());
        }
        if (e.type == LlmException.Type.NETWORK) {
            return ofNetwork(attempt, "网络错误", e.getMessage());
        }
        return of(attempt, e.httpCode, e.body);
    }

    /** 重试超时总结文案的错误前缀：429/500/502 用状态码，网络类用中文标签 */
    public static String tag(LlmException e) {
        if (e.type == LlmException.Type.TIMEOUT) return "网络超时";
        if (e.type == LlmException.Type.NETWORK) return "网络错误";
        if (e.type == LlmException.Type.RATE_LIMIT) return "429";
        return String.valueOf(e.httpCode);
    }

    /** 退出重试态（复位指示器） */
    public static RetryProgress none() {
        return new RetryProgress(0, 0, null, null);
    }
}
