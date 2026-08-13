package com.minion.gui.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话 UI 事件流：会话激活时事件直通监听器（FX 线程包装由监听器负责），
 * 未激活（切到其他会话/工作空间）时只入缓冲；切换回来 setActive(true) 重放全部存量。
 * 纯逻辑、无 JavaFX 依赖，可单测。
 */
public class EventList {

    public enum Kind {
        USER_MESSAGE, USER_SUPPLEMENT, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT,
        SUB_AGENT_START, SUB_AGENT_DELTA, SUB_AGENT_DONE, STATS, SYSTEM, ERROR, WARNING
    }

    public static class Ev {
        public final Kind kind;
        public final String text;
        public final Object data;

        public Ev(Kind kind, String text, Object data) {
            this.kind = kind;
            this.text = text;
            this.data = data;
        }
    }

    public interface Listener {
        void onEvent(Ev e);
    }

    private final List<Ev> events = new ArrayList<Ev>();
    private volatile boolean active = false;
    private volatile Listener listener;

    /** 激活：重放存量后直通；去激活：listener 置空，事件只入缓冲 */
    public synchronized void setActive(boolean active, Listener listener) {
        this.active = active;
        this.listener = active ? listener : null;
        if (active && listener != null) {
            for (Ev e : events) listener.onEvent(e);
        }
    }

    public synchronized void add(Ev e) {
        events.add(e);
        if (active && listener != null) listener.onEvent(e);
    }

    public synchronized List<Ev> snapshot() { return new ArrayList<Ev>(events); }

    public synchronized void clear() { events.clear(); }

    public synchronized int size() { return events.size(); }
}
