package com.minion.core.llm;

import java.util.List;

public interface StreamHandler {
    default void onThinking(String delta) { }
    default void onContent(String delta) { }
    default void onUsage(Usage usage) { }
    void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls);
    default void onError(LlmException e) { }
}
