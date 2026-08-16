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

    /**
     * 增量重放 + 注册直通（原子）：锁内快照 [from, size) 并设 active/listener。
     * 拆两步有竞态——先取尾部快照后注册，间隙 add 的事件会丢（进缓冲但 listener 未注册）；
     * 先注册后取快照，间隙 add 的事件重复（既入缓冲又直通）。锁内一步完成两者，add 同锁互斥。
     * 返回 [from, size) 的副本，调用方锁外渲染（新增直通事件经 runLater 入队，顺序由 FX 队列 FIFO 保证）。
     */
    public synchronized List<Ev> rebind(Listener listener, int from) {
        this.active = true;
        this.listener = listener;
        int n = events.size();
        if (from >= n) return new ArrayList<Ev>();
        return new ArrayList<Ev>(events.subList(Math.max(0, from), n));
    }

    public synchronized void add(Ev e) {
        events.add(e);
        if (active && listener != null) listener.onEvent(e);
    }

    public synchronized List<Ev> snapshot() { return new ArrayList<Ev>(events); }

    public synchronized void clear() { events.clear(); }

    public synchronized int size() { return events.size(); }
}
