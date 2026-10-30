package com.minion.gui.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话 UI 事件流：会话激活时事件直通监听器（FX 线程包装由监听器负责），
 * 未激活（切到其他会话/工作空间）时只入缓冲；切换回来 setActive(true) 重放全部存量。
 * 纯逻辑、无 JavaFX 依赖，可单测。
 *
 * 容量上限（P1-6）：事件只增不删会让常驻内存随时长无界增长（每条 chunk 一个 Ev + 文本）。
 * 超过 {@link #MAX_EVENTS} 即从头部丢弃最旧事件，丢弃数记入 {@link #base}：
 *  - 视图游标（ChatView.replayed）是**绝对序号**（含已丢弃事件），rebind 时按 base 钳制，
 *    因此丢弃后「已渲染到哪」仍成立：不会重放已丢弃历史，也不会丢新事件；
 *  - 不合并增量事件：合并会让绝对序号与已渲染游标错位（切回视图可能重复或丢内容）；
 *    视图侧本就有 MAX_SEGS=200 的截断保活，丢最旧事件的语义与之一致；
 *  - 锁内快照因此封顶 5000 个引用（毫秒级），不再随会话时长增长。
 */
public class EventList {

    /** 事件条数上限：超过即丢弃最旧事件（视图侧 MAX_SEGS=200 段截断保活的上一层兜底） */
    public static final int MAX_EVENTS = 5000;

    public enum Kind {
        USER_MESSAGE, USER_SUPPLEMENT, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT,
        SUB_AGENT_START, SUB_AGENT_DONE, STATS, SYSTEM, ERROR, WARNING
    }   // SUB_AGENT_DELTA 删除：正文改走 CONTENT + subAgentId（渲染层按 id 区分）

    public static class Ev {
        public final Kind kind;
        public final String text;
        public final Object data;
        /** 事件主人：0=主代理，>0=子代理编号（渲染层据此加【子代理N】前缀与独立流式缓冲） */
        public final int subAgentId;

        public Ev(Kind kind, String text, Object data) {
            this(kind, text, data, 0);
        }

        public Ev(Kind kind, String text, Object data, int subAgentId) {
            this.kind = kind;
            this.text = text;
            this.data = data;
            this.subAgentId = subAgentId;
        }
    }

    public interface Listener {
        void onEvent(Ev e);
    }

    private final List<Ev> events = new ArrayList<Ev>();
    /** 已被丢弃的旧事件数（绝对序号基准：events.get(i) 的绝对序号 = base + i） */
    private int base = 0;
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
     *
     * @param from 绝对游标（调用方已渲染到的事件数，含已被丢弃的历史）；小于 base 时钳制到 base
     */
    public synchronized List<Ev> rebind(Listener listener, int from) {
        this.active = true;
        this.listener = listener;
        int n = events.size();
        int absFrom = Math.max(from, base); // 已丢弃的历史无法重放：游标前移到保留下限
        int start = Math.max(0, absFrom - base);
        if (start >= n) return new ArrayList<Ev>();
        return new ArrayList<Ev>(events.subList(start, n));
    }

    public synchronized void add(Ev e) {
        events.add(e);
        if (events.size() > MAX_EVENTS) trimHeadLocked(events.size() - MAX_EVENTS);
        if (active && listener != null) listener.onEvent(e);
    }

    /** 丢弃头部最旧事件并把数量记入绝对序号基准（锁内，与 add/rebind 互斥） */
    private void trimHeadLocked(int drop) {
        if (drop <= 0) return;
        events.subList(0, drop).clear();
        base += drop;
    }

    /** 绝对事件总数（含已被丢弃的历史；调用方游标以此计数） */
    public synchronized int size() { return base + events.size(); }

    /** 当前保留事件的绝对区间起点（诊断/测试断言丢弃行为用） */
    public synchronized int base() { return base; }

    public synchronized List<Ev> snapshot() { return new ArrayList<Ev>(events); }

    public synchronized void clear() {
        events.clear();
        base = 0;
    }
}
