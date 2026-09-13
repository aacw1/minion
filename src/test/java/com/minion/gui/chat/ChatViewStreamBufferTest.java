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

    // ===== 子代理流式隔离（设计 2026-09-13：并发子代理的思考/正文必须按编号分道互不串台）=====

    /** 并发子代理流式隔离：不同 subAgentId 的正文/思考各自累积，互不串台 */
    @Test
    public void buffers_isolatedPerSubAgent() {
        ChatView.StreamBuffers buffers = new ChatView.StreamBuffers();
        buffers.of(1).onContent("子代理1正文");
        buffers.of(2).onContent("子代理2正文");
        buffers.of(0).onContent("主代理正文");
        assertEquals("子代理1正文", buffers.of(1).content());
        assertEquals("子代理2正文", buffers.of(2).content());
        assertEquals("主代理正文", buffers.of(0).content());
    }

    /** 轮次边界只清对应主人的缓冲（子代理工具调用不得清掉主代理正在累积的回复） */
    @Test
    public void buffers_roundBoundary_onlyClearsTarget() {
        ChatView.StreamBuffers buffers = new ChatView.StreamBuffers();
        buffers.of(1).onContent("子1");
        buffers.of(2).onContent("子2");
        buffers.onRoundBoundary(2);
        assertEquals("子1", buffers.of(1).content());
        assertEquals("", buffers.of(2).content());
    }

    /** 流式段身份判等：kind 相同 + 主人相同才就地更新；NONE 恒不参与 */
    @Test
    public void sameStream_requiresSameKindAndOwner() {
        assertTrue(ChatView.sameStream(ChatView.StreamKind.REPLY, 0, ChatView.StreamKind.REPLY, 0));
        assertFalse("不同主人不得合并同段", ChatView.sameStream(ChatView.StreamKind.REPLY, 1, ChatView.StreamKind.REPLY, 2));
        assertFalse(ChatView.sameStream(ChatView.StreamKind.THINK, 1, ChatView.StreamKind.REPLY, 1));
        assertFalse(ChatView.sameStream(ChatView.StreamKind.NONE, 1, ChatView.StreamKind.NONE, 1));
    }

    /** 标签与配色：子代理事件用【子代理N】+ log-subagent，主代理保持原标签 */
    @Test
    public void tagAndColor_subAgentVsMain() {
        assertEquals("【思考】", ChatView.tagOf(0, "【思考】"));
        assertEquals("【子代理2】", ChatView.tagOf(2, "【思考】"));
        assertEquals("【子代理12】", ChatView.tagOf(12, "【回复】"));
        assertEquals("log-reply", ChatView.colorOf(0, "log-reply"));
        assertEquals("log-subagent", ChatView.colorOf(3, "log-reply"));
    }

    /** 清空（删会话）后所有子代理缓冲一并释放 */
    @Test
    public void buffers_clearReleasesAll() {
        ChatView.StreamBuffers buffers = new ChatView.StreamBuffers();
        buffers.of(1).onContent("x");
        buffers.clear();
        assertEquals("", buffers.of(1).content());
    }
}
