package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/** 流式合并刷新时间窗（纯逻辑）：首次/超窗立即刷新，窗内合并到定时器（leading + trailing）。 */
public class ChatViewFlushPolicyTest {

    private static final long MS = 1000000L;

    @Test
    public void neverFlushed_flushesImmediately() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        assertTrue("首次增量必须立即渲染（不能等 80ms 才出现第一段）", p.shouldFlushNow(1000 * MS));
        assertEquals(0, p.remainingMillis(1000 * MS));
    }

    @Test
    public void withinWindow_mergesIntoPendingTimer() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS);
        assertFalse("窗口内增量只累积，不立即重排", p.shouldFlushNow(1050 * MS));
        assertEquals("距窗口结束还有 30ms", 30, p.remainingMillis(1050 * MS));
    }

    @Test
    public void afterWindow_flushesImmediately() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS);
        assertTrue(p.shouldFlushNow(1080 * MS));
        assertEquals(0, p.remainingMillis(1080 * MS));
        assertTrue("超过窗口同样立即刷新", p.shouldFlushNow(1200 * MS));
    }

    @Test
    public void remaining_neverNegative() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS);
        assertEquals(0, p.remainingMillis(2000 * MS));
    }

    @Test
    public void markFlushed_restartsWindow() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS);
        p.markFlushed(1100 * MS);
        assertFalse(p.shouldFlushNow(1150 * MS));
        assertEquals(30, p.remainingMillis(1150 * MS));
        assertTrue(p.shouldFlushNow(1180 * MS));
    }

    // ---- P1-4：窗口随累计字符数自适应放大 ----

    @Test
    public void windowFor_scalesWithTextLength() {
        assertEquals(80, ChatView.StreamFlushPolicy.windowFor(0));
        assertEquals(80, ChatView.StreamFlushPolicy.windowFor(19_999));
        assertEquals(150, ChatView.StreamFlushPolicy.windowFor(20_000));
        assertEquals(150, ChatView.StreamFlushPolicy.windowFor(49_999));
        assertEquals(250, ChatView.StreamFlushPolicy.windowFor(50_000));
        assertEquals(250, ChatView.StreamFlushPolicy.windowFor(500_000));
    }

    @Test
    public void markFlushed_withChars_usesAdaptiveWindow() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS, 60_000);
        assertEquals(250, p.windowMillis());
        assertFalse("窗口内不刷新", p.shouldFlushNow(1200 * MS));
        assertEquals(50, p.remainingMillis(1200 * MS));
        assertTrue(p.shouldFlushNow(1250 * MS));
    }

    @Test
    public void adaptiveWindow_shrinksBackForShortText() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS, 60_000);
        assertEquals(250, p.windowMillis());
        p.markFlushed(2000 * MS, 100); // 新回合短文本：回到默认窗口
        assertEquals(80, p.windowMillis());
        assertEquals(30, p.remainingMillis(2050 * MS));
    }

    @Test
    public void reset_restoresDefaultWindow() {
        ChatView.StreamFlushPolicy p = new ChatView.StreamFlushPolicy();
        p.markFlushed(1000 * MS, 60_000);
        p.reset();
        assertEquals(80, p.windowMillis());
        assertTrue("重置后首段立即渲染", p.shouldFlushNow(1 * MS));
    }
}
