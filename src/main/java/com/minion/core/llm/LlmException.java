package com.minion.core.llm;

public class LlmException extends Exception {

    public enum Type { AUTH, RATE_LIMIT, BAD_REQUEST, NETWORK, TIMEOUT, OTHER }

    public final Type type;
    public final boolean retryable;
    /** HTTP 状态码（非 HTTP 错误为 0；瞬时错误长重试判断依据） */
    public final int httpCode;
    /** 原始响应体（非 HTTP 错误或未捕获为 null；429/500/502 指示器展示用） */
    public final String body;

    public LlmException(Type type, String message, boolean retryable) {
        this(type, message, retryable, 0, null);
    }

    public LlmException(Type type, String message, boolean retryable, int httpCode, String body) {
        super(message);
        this.type = type;
        this.retryable = retryable;
        this.httpCode = httpCode;
        this.body = body;
    }

    public static LlmException of(int httpCode, String body) {
        if (httpCode == 401 || httpCode == 403) {
            return new LlmException(Type.AUTH, "认证失败(" + httpCode + ")，请检查 config.properties 的 model.key", false, httpCode, body);
        }
        if (httpCode == 429) {
            return new LlmException(Type.RATE_LIMIT, "请求过于频繁(" + httpCode + ")，限流中，请稍后重试", true, httpCode, body);
        }
        if (httpCode == 400) {
            // 带 body：400 根因（如上下文超限、tool_call 配对）只在响应体里，
            // 曾只给通用文案导致无法诊断
            String detail = truncate(body);
            String msg = "请求被拒绝(400)";
            if (!detail.isEmpty()) msg += "：\n" + detail;
            return new LlmException(Type.BAD_REQUEST, msg, false, httpCode, body);
        }
        return new LlmException(Type.OTHER, "API 错误(" + httpCode + "): " + truncate(body), httpCode >= 500, httpCode, body);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
