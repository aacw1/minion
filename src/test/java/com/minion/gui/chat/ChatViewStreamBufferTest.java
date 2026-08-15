package com.minion.gui.chat;

import com.minion.gui.session.EventList;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 流式缓冲纯逻辑测试：THINKING/CONTENT 增量累积；轮次边界（用户消息/补充/工具调用）重置。
 * 背景（线上实证）：AgentLoop 一轮 runUserTurn 内多轮 agent 回合（assistant→工具→assistant…）间
 * 没有 USER_MESSAGE，若缓冲不重置，多轮回复文本会跨轮累积——每轮【回复】段内容越滚越长，
 * 界面表现为"一直在回复同一段内容"，用户误判为上下文错乱/死循环。
 */
public class ChatViewStreamBufferTest {

    private ChatView.StreamBuffer buf() { return new ChatView.StreamBuffer(); }

    @Test
    public void content_accumulatesDeltas() {
        ChatView.StreamBuffer b = buf();
        b.onContent("继续修改 InputView.java。");
        b.onContent("先改 focus 监听：");
        assertEquals("继续修改 InputView.java。先改 focus 监听：", b.content());
    }

    @Test
    public void thinking_accumulatesDeltas() {
        ChatView.StreamBuffer b = buf();
        b.onThinking("先看设计文档格式。");
        b.onThinking("再查日期。");
        assertEquals("先看设计文档格式。再查日期。", b.thinking());
    }

    @Test
    public void roundBoundary_clearsContentAndThinking() {
        ChatView.StreamBuffer b = buf();
        b.onContent("轮1正文");
        b.onThinking("轮1思考");
        b.onRoundBoundary();
        assertEquals("", b.content());
        assertEquals("", b.thinking());
    }

    /** 线上 bug 场景：工具调用后的下一轮回复不得包含上一轮文本 */
    @Test
    public void multiRound_noAccumulationAcrossBoundary() {
        ChatView.StreamBuffer b = buf();
        b.onContent("轮1：先修改 InputView.java：");
        b.onRoundBoundary(); // TOOL_CALL 到达 = 轮 1 结束
        b.onContent("轮2：接着改按钮颜色映射：");
        assertEquals("轮2：接着改按钮颜色映射：", b.content());
    }

    @Test
    public void isRoundBoundary_userMessageAndSupplementAndToolCall() {
        assertTrue(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.USER_MESSAGE));
        assertTrue(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.USER_SUPPLEMENT));
        assertTrue(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.TOOL_CALL));
    }

    @Test
    public void isRoundBoundary_streamAndStaticKindsAreNotBoundary() {
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.THINKING));
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.CONTENT));
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.TOOL_RESULT));
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.STATS));
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.SYSTEM));
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.ERROR));
        assertFalse(ChatView.StreamBuffer.isRoundBoundary(EventList.Kind.WARNING));
    }
}
