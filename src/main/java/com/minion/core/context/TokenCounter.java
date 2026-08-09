package com.minion.core.context;

import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;

/** 启发式 token 估算：中文 1 字 ≈ 0.7 token，其他 1 字符 ≈ 0.25 token */
public class TokenCounter {

    private static final int MSG_OVERHEAD = 4;

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            tokens += isCjk(c) ? 0.7 : 0.25;
        }
        return (int) Math.ceil(tokens);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3000 && c <= 0x303F);
    }

    public static int estimateMessages(java.util.List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += MSG_OVERHEAD;
            total += estimate(m.content);
            if (m.reasoningContent != null) total += estimate(m.reasoningContent);
            if (m.toolCalls != null) {
                for (ToolCall tc : m.toolCalls) {
                    total += estimate(tc.name) + estimate(tc.arguments);
                }
            }
        }
        return total;
    }
}
