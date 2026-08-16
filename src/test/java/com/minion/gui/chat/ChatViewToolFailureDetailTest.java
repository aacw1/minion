package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TOOL_RESULT 失败行原因展示纯逻辑测试。
 * 背景（线上实证）：AskUserQuestion 失败时消息区只打「❌ 名称 失败」，
 * SessionController 的 data 里带着具体原因（"error:" + output）却被丢弃，
 * 排查时看不到失败原因——失败行应追加原因首行（截断 120）。
 */
public class ChatViewToolFailureDetailTest {

    @Test
    public void errorDetail_returnsReasonWithColon() {
        assertEquals("：用户拒绝了该操作（AskUserQuestion）",
                ChatView.toolFailureDetail("error:用户拒绝了该操作（AskUserQuestion）"));
    }

    @Test
    public void errorDetail_emptyReason_returnsEmpty() {
        assertEquals("", ChatView.toolFailureDetail("error:"));
        assertEquals("", ChatView.toolFailureDetail("error:   "));
    }

    @Test
    public void okData_returnsEmpty() {
        assertEquals("", ChatView.toolFailureDetail("ok"));
        assertEquals("", ChatView.toolFailureDetail(null));
    }

    @Test
    public void errorDetail_multiline_keepsFirstNonEmptyLine() {
        assertEquals("：工具执行异常: InterruptedException",
                ChatView.toolFailureDetail("error:工具执行异常: InterruptedException\n\tat com.minion.core.tools.AskUserQuestionTool"));
    }

    @Test
    public void errorDetail_over120Chars_truncatedWithEllipsis() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("原因"); // 200 字，明确超过 120
        String out = ChatView.toolFailureDetail("error:" + sb);
        assertTrue(out.startsWith("："));
        assertTrue(out.length() == 1 + 120 + 1); // 冒号 + 120 字 + …
        assertTrue(out.endsWith("…"));
    }
}
