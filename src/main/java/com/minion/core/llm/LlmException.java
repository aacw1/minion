package com.minion.core.llm;

public class LlmException extends Exception {

    public enum Type { AUTH, RATE_LIMIT, BAD_REQUEST, NETWORK, TIMEOUT, OTHER }

    public final Type type;
    public final boolean retryable;

    public LlmException(Type type, String message, boolean retryable) {
        super(message);
        this.type = type;
        this.retryable = retryable;
    }

    public static LlmException of(int httpCode, String body) {
        if (httpCode == 401 || httpCode == 403) {
            return new LlmException(Type.AUTH, "认证失败(" + httpCode + ")，请检查 config.properties 的 model.key", false);
        }
        if (httpCode == 429) {
            return new LlmException(Type.RATE_LIMIT, "请求过于频繁(" + httpCode + ")，请稍后重试或检查余额", true);
        }
        if (httpCode == 400) {
            // 带 body：400 根因（如上下文超限、tool_call 配对）只在响应体里，
            // 曾只给通用文案导致无法诊断
            String detail = truncate(body);
            String msg = "请求被拒绝(400)";
            if (!detail.isEmpty()) msg += "：\n" + detail;
            return new LlmException(Type.BAD_REQUEST, msg, false);
        }
        return new LlmException(Type.OTHER, "API 错误(" + httpCode + "): " + truncate(body), httpCode >= 500);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
