package com.minion.core.agent;

/** 重试进度（agent 循环 → UI 指示器）：attempt=0 表示退出重试态（复位），
 *  httpCode/body 为最近一次失败的 HTTP 状态码与响应体（429/500/502 均完整展示）。 */
public final class RetryProgress {

    public final int attempt;
    public final int httpCode;
    public final String body;

    private RetryProgress(int attempt, int httpCode, String body) {
        this.attempt = attempt;
        this.httpCode = httpCode;
        this.body = body;
    }

    public static RetryProgress of(int attempt, int httpCode, String body) {
        return new RetryProgress(attempt, httpCode, body);
    }

    /** 退出重试态（复位指示器） */
    public static RetryProgress none() {
        return new RetryProgress(0, 0, null);
    }
}
