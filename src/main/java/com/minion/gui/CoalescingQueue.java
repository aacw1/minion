package com.minion.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 合并投递队列（纯逻辑、无 JavaFX 依赖，可单测）。
 *
 * 背景（P1-5）：SSE 流式回复的每个 chunk 都是一个 UI 事件，旧实现逐个 Platform.runLater，
 * 一次 2000 chunk 的回复产生 2000 个 FX 任务，每个都要在 FX 线程上做 map 查表与脏标记；
 * SSE 快于 FX 消费时队列堆积，滚动/切换延迟。
 *
 * 用法：后台线程 {@link #offer} 只入队，返回 true 时调度**一次**排空任务；FX 线程
 * {@link #drain} 一次取出整批按序处理。事件顺序由 ConcurrentLinkedQueue 的 FIFO 与
 * 「同一时刻至多一个排空者」共同保证。
 */
public class CoalescingQueue<T> {

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<T>();
    /** 是否已有排空任务在途：CAS 保证高频 offer 只调度一次 */
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    /** 入队；返回 true 表示调用方应投递一次排空任务（同一时刻至多一个） */
    public boolean offer(T item) {
        if (item == null) return false;
        queue.add(item);
        return scheduled.compareAndSet(false, true);
    }

    /**
     * 取出当前全部条目（按入队顺序）并复位调度标志；只在 FX 线程调用。
     * 复位放在取出之前：排空过程中新到的条目会重新调度一次排空（最多多一个空任务），
     * 不会出现「条目入队但无人排空」的丢事件状态。
     */
    public List<T> drain() {
        scheduled.set(false);
        List<T> out = new ArrayList<T>();
        T item;
        while ((item = queue.poll()) != null) out.add(item);
        return out;
    }

    /** 丢弃未排空条目（视图清空：事件仍保存在 EventList，切回时按游标重放） */
    public void clear() {
        queue.clear();
    }

    /** 当前积压条数（诊断/测试用） */
    public int pending() {
        return queue.size();
    }
}
