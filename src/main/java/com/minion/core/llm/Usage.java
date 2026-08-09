package com.minion.core.llm;

import com.google.gson.JsonObject;

public class Usage {
    public int inputTokens;
    public int outputTokens;
    public int reasoningTokens;
    public int totalTokens;

    public static Usage fromJson(com.google.gson.JsonObject usage) {
        Usage u = new Usage();
        if (usage == null) return u;
        if (usage.has("prompt_tokens") && !usage.get("prompt_tokens").isJsonNull())
            u.inputTokens = usage.get("prompt_tokens").getAsInt();
        if (usage.has("completion_tokens") && !usage.get("completion_tokens").isJsonNull())
            u.outputTokens = usage.get("completion_tokens").getAsInt();
        if (usage.has("completion_tokens_details")
                && usage.get("completion_tokens_details").isJsonObject()) {
            JsonObject details = usage.getAsJsonObject("completion_tokens_details");
            // reasoning_tokens 可能为 null（部分模型/接口不返回）：getAsInt 会抛 UOE，需守卫
            if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isJsonNull()) {
                u.reasoningTokens = details.get("reasoning_tokens").getAsInt();
            }
        }
        u.totalTokens = u.inputTokens + u.outputTokens;
        return u;
    }

    /** 取不到 API usage 时的估算兜底 */
    public static Usage estimate(java.util.List<Message> messages, String output) {
        Usage u = new Usage();
        u.inputTokens = com.minion.core.context.TokenCounter.estimateMessages(messages);
        u.outputTokens = com.minion.core.context.TokenCounter.estimate(output);
        u.totalTokens = u.inputTokens + u.outputTokens;
        return u;
    }
}
