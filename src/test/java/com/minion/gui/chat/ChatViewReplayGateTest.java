package com.minion.gui.chat;

import com.minion.gui.session.EventList;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** 重放分批期间的直通事件闸门（纯逻辑）：重放中直通事件按序入队，重放结束统一处理；
 *  解绑/清理使旧代失效，旧批次回调不得继续向已切换的视图渲染。 */
public class ChatViewReplayGateTest {

    private static EventList.Ev ev(String text) {
        return new EventList.Ev(EventList.Kind.CONTENT, text, null);
    }

    @Test
    public void begin_generationIncreases() {
        ChatView.ReplayGate g = new ChatView.ReplayGate();
        int a = g.begin();
        int b = g.begin();
        assertTrue(b > a);
    }

    @Test
    public void duringReplay_directEventsQueuedInOrder() {
        ChatView.ReplayGate g = new ChatView.ReplayGate();
        int gen = g.begin();
        assertTrue(g.isCurrent(gen));
        assertTrue(g.enqueueIfReplaying(ev("a")));
        assertTrue(g.enqueueIfReplaying(ev("b")));
        List<EventList.Ev> pending = g.finish();
        assertEquals(2, pending.size());
        assertEquals("a", pending.get(0).text);
        assertEquals("b", pending.get(1).text);
        assertFalse("重放结束后不再入队", g.enqueueIfReplaying(ev("c")));
    }

    @Test
    public void abort_invalidatesOldGenerationAndDropsPending() {
        ChatView.ReplayGate g = new ChatView.ReplayGate();
        int gen = g.begin();
        g.enqueueIfReplaying(ev("lost"));
        g.abort();
        assertFalse("旧批次回调必须停止", g.isCurrent(gen));
        assertFalse(g.enqueueIfReplaying(ev("direct")));
        assertTrue("abort 后队列为空（事件仍在 EventList 中，切回时整体重放）", g.finish().isEmpty());
    }

    @Test
    public void finish_clearsPending() {
        ChatView.ReplayGate g = new ChatView.ReplayGate();
        g.begin();
        g.enqueueIfReplaying(ev("a"));
        assertEquals(1, g.finish().size());
        assertTrue(g.finish().isEmpty());
    }

    @Test
    public void notReplaying_directEventNotQueued() {
        ChatView.ReplayGate g = new ChatView.ReplayGate();
        assertFalse(g.enqueueIfReplaying(ev("x")));
    }
}
