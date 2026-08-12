package com.minion.core.agent;

/** 新会话标题生成：LLM 摘要（completeChat 非流式）+ 本地兜底 */
public class TitleGenerator {

    public static final int MAX_TITLE_LEN = 20;

    /** 摘要请求 prompt：指令 + 用户首条消息（completeChat 的 system 侧） */
    public static String buildPrompt(String firstUserMessage) {
        return "为以下用户消息生成一个不超过 " + MAX_TITLE_LEN
                + " 字的会话标题。直接输出标题本身，不要引号、不要前缀、不要解释。\n\n"
                + firstUserMessage;
    }

    /** 摘要文本清洗：去引号/首尾空白/换行、超长截断；空则回退兜底标题 */
    public static String clean(String raw) {
        if (raw == null) return fallbackTitle("");
        String t = raw.trim().replace('\n', ' ').replace('\r', ' ');
        t = t.replaceAll("^[\"「『]+", "").replaceAll("[\"」』]+$", "");
        if (t.length() > MAX_TITLE_LEN) t = t.substring(0, MAX_TITLE_LEN);
        return t.isEmpty() ? fallbackTitle("") : t;
    }

    /** 兜底标题：用户消息前 30 字；空消息给「新会话」 */
    public static String fallbackTitle(String firstUserMessage) {
        String t = firstUserMessage == null ? "" : firstUserMessage.trim().replace('\n', ' ').replace('\r', ' ');
        if (t.length() > 30) t = t.substring(0, 30);
        return t.isEmpty() ? "新会话" : t;
    }
}
