package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.List;

public interface LlmClient {
    /** 流式对话；handler 回调在调用线程。tools 为空列表/null 表示不带工具。 */
    void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException;

    /** 非流式对话（压缩等内部请求），返回 content */
    String completeChat(List<Message> messages, String systemPrompt) throws LlmException;

    /** 中断进行中的请求（Ctrl+C / 用户打断） */
    default void cancel() { }
}
