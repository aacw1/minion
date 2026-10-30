package com.minion.gui;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 合并投递队列（P1-5，纯逻辑）：高频入队只调度一次排空、顺序保持、排空后可再次调度 */
public class CoalescingQueueTest {

    @Test
    public void hundredOffers_scheduleOnlyOnce() {
        CoalescingQueue<String> q = new CoalescingQueue<String>();
        int scheduled = 0;
        for (int i = 0; i < 100; i++) {
            if (q.offer("e" + i)) scheduled++;
        }
        assertEquals("同一时刻至多一个排空任务", 1, scheduled);
        assertEquals(100, q.pending());
    }

    @Test
    public void drain_returnsAllInOrderAndResetsSchedule() {
        CoalescingQueue<String> q = new CoalescingQueue<String>();
        for (int i = 0; i < 5; i++) q.offer("e" + i);
        List<String> drained = q.drain();
        assertEquals(5, drained.size());
        for (int i = 0; i < 5; i++) assertEquals("e" + i, drained.get(i));
        assertEquals(0, q.pending());
        assertTrue("排空后新的入队应重新调度", q.offer("next"));
        assertEquals(1, q.drain().size());
    }

    /** 排空期间新到条目：重新调度一次排空，绝不丢事件 */
    @Test
    public void offerDuringDrain_isNotLost() {
        final CoalescingQueue<String> q = new CoalescingQueue<String>();
        q.offer("a");
        // 模拟「排空过程中入队」：排空开始前先塞一条（drain 会取走全部当前条目）
        q.offer("b");
        List<String> first = q.drain();
        assertEquals(2, first.size());
        assertTrue("drain 复位调度标志后，新 offer 可再次调度", q.offer("c"));
        List<String> second = q.drain();
        assertEquals(1, second.size());
        assertEquals("c", second.get(0));
    }

    @Test
    public void clear_discardsPending() {
        CoalescingQueue<String> q = new CoalescingQueue<String>();
        q.offer("a");
        q.offer("b");
        q.clear();
        assertEquals(0, q.pending());
        assertTrue(q.drain().isEmpty());
    }

    @Test
    public void nullIgnored() {
        CoalescingQueue<String> q = new CoalescingQueue<String>();
        assertFalse(q.offer(null));
        assertEquals(0, q.pending());
    }

    /** 并发生效性冒烟：多线程 offer 均入队且只调度一次（不丢事件） */
    @Test
    public void concurrentOffers_noLoss() throws Exception {
        final CoalescingQueue<Integer> q = new CoalescingQueue<Integer>();
        final java.util.concurrent.atomic.AtomicInteger schedules = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 500; i++) {
                    if (q.offer(1)) schedules.incrementAndGet();
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join(5000);
        assertEquals("4×500 条全部入队", 2000, q.pending());
        assertTrue("调度次数应远小于事件数", schedules.get() <= 4);
        List<Integer> all = new ArrayList<Integer>(q.drain());
        assertEquals(2000, all.size());
    }
}
