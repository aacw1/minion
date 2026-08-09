package com.minion.core.context;

import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TokenCounterTest {

    @Test
    public void estimate_zhAndEn() {
        // 4 个中文字 ≈ 2.8 → ceil 3
        assertEquals(3, TokenCounter.estimate("你好世界"));
        // 8 个 ASCII ≈ 2
        assertEquals(2, TokenCounter.estimate("abcdefgh"));
        assertTrue(TokenCounter.estimate("中文 mixed 123") > 0);
    }

    @Test
    public void estimateMessages_countsAllFields() {
        Message u = Message.user("读取文件并分析");
        Message a = Message.assistant("好的");
        a.reasoningContent = "先看结构";
        ToolCall tc = new ToolCall();
        tc.name = "Read";
        tc.arguments = "{\"path\":\"src/Main.java\"}";
        a.toolCalls = Collections.singletonList(tc);
        Message t = Message.toolResult("c1", "Read", "public class Main {}");
        int n = TokenCounter.estimateMessages(Arrays.asList(u, a, t));
        // 每条消息至少 4 开销
        assertTrue(n >= 12);
    }
}
