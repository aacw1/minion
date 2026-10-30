package com.minion.gui.session;

import com.minion.gui.session.EventList.Ev;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class EventListTest {

    private EventList newList() { return new EventList(); }

    /** inactive 时事件只入缓冲，不直通 */
    @Test
    public void inactive_buffersEvents() {
        EventList l = newList();
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        assertEquals(2, l.size());
        assertTrue(seen.isEmpty());
    }

    /** 激活时重放全部存量，之后新事件直通 */
    @Test
    public void activate_replaysThenStreams() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.THINKING, "t", null));
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        assertEquals(2, seen.size());
        l.add(new Ev(EventList.Kind.ERROR, "e", null));
        assertEquals(3, seen.size());
        assertEquals("e", seen.get(2).text);
        assertEquals(3, l.size());
    }

    /** 事件顺序保持（流式 delta 顺序敏感） */
    @Test
    public void preservesOrder() {
        EventList l = newList();
        l.setActive(false, null);
        for (int i = 0; i < 5; i++) l.add(new Ev(EventList.Kind.CONTENT, "d" + i, null));
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        for (int i = 0; i < 5; i++) assertEquals("d" + i, seen.get(i).text);
    }

    /** 清空后重放为空 */
    @Test
    public void clear_emptiesBuffer() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.clear();
        assertEquals(0, l.size());
    }

    /** rebind 从 from 起增量快照，且注册直通（视图缓存增量重放路径） */
    @Test
    public void rebind_returnsTailFromAndStreamsNew() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.USER_MESSAGE, "u1", null));
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        final List<Ev> seen = new ArrayList<Ev>();
        // 游标=2：只重放最后 1 个存量
        List<Ev> tail = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        }, 2);
        assertEquals(1, tail.size());
        assertEquals("b", tail.get(0).text);
        assertTrue("存量重放走快照不直通", seen.isEmpty());
        // 注册后新事件直通
        l.add(new Ev(EventList.Kind.CONTENT, "c", null));
        assertEquals(1, seen.size());
        assertEquals("c", seen.get(0).text);
    }

    /** 二次 rebind 以新游标为起点，不重复渲染已直通事件 */
    @Test
    public void rebind_secondTimeDoesNotRepeat() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        List<Ev> t1 = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 0);
        assertEquals(1, t1.size());
        // 切走：解绑；期间事件只入缓冲
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        // 切回：游标=上次 size(1)，只重放 b
        List<Ev> t2 = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 1);
        assertEquals(1, t2.size());
        assertEquals("b", t2.get(0).text);
    }

    /** from 越界/负数 clamp：不越界、不丢 */
    @Test
    public void rebind_clampsFrom() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        assertEquals(0, l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 99).size()); // 越界：空快照
        assertEquals(1, l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, -1).size()); // 负数：从 0 全量
    }

    /** SYSTEM（斜杠命令结果）与非激活缓冲机制兼容：不激活入缓冲，激活重放 */
    @Test
    public void system_buffersAndReplays() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.SYSTEM, "已加载技能: x", null));
        assertEquals(1, l.size());
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        assertEquals(1, seen.size());
        assertEquals(EventList.Kind.SYSTEM, seen.get(0).kind);
        assertEquals("已加载技能: x", seen.get(0).text);
    }

    /** Ev 4 参构造带 subAgentId；3 参委托默认 0 */
    @Test
    public void ev_subAgentIdDefaultZero() {
        assertEquals(0, new Ev(EventList.Kind.CONTENT, "a", null).subAgentId);
        assertEquals(3, new Ev(EventList.Kind.CONTENT, "a", null, 3).subAgentId);
    }

    // ---------------- P1-6：容量上限 + 绝对游标 ----------------

    /** 超过上限丢弃最旧事件（内存有界），绝对序号仍连续 */
    @Test
    public void cap_dropsOldestAndKeepsAbsoluteCursor() {
        EventList l = newList();
        int n = EventList.MAX_EVENTS + 2;
        for (int i = 0; i < n; i++) l.add(new Ev(EventList.Kind.CONTENT, "d" + i, null));
        assertEquals("绝对事件数不变（含已丢弃）", n, l.size());
        assertEquals("丢弃最旧 2 条", 2, l.base());
        assertEquals("保留事件数封顶", EventList.MAX_EVENTS, l.snapshot().size());
        assertEquals("最早的保留事件是第 3 条", "d2", l.snapshot().get(0).text);
    }

    /** 丢弃后 rebind 按 base 钳制：from 落在已丢弃区间 → 从保留下限重放，不重复不丢 */
    @Test
    public void rebind_afterDrop_clampsToBase() {
        EventList l = newList();
        int n = EventList.MAX_EVENTS + 10;
        for (int i = 0; i < n; i++) l.add(new Ev(EventList.Kind.CONTENT, "d" + i, null));
        List<Ev> all = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 0);
        assertEquals("游标 0 被钳到 base：返回全部保留事件", EventList.MAX_EVENTS, all.size());
        assertEquals("d10", all.get(0).text);
        List<Ev> tail = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, n - 3);
        assertEquals("游标在保留下限之后：只重放尾部", 3, tail.size());
        assertEquals("d" + (n - 3), tail.get(0).text);
    }

    /** 丢弃后从「已渲染到的绝对位置」继续：不重复渲染已渲染部分 */
    @Test
    public void rebind_absoluteCursorDoesNotRepeat() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        List<Ev> first = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 0);
        assertEquals(1, first.size());
        l.setActive(false, null);
        // 期间积压超过上限：最旧（含已渲染的 a）被丢弃
        for (int i = 0; i < EventList.MAX_EVENTS + 5; i++) {
            l.add(new Ev(EventList.Kind.CONTENT, "x" + i, null));
        }
        int rendered = 1; // 视图游标是绝对序号
        List<Ev> second = l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, rendered);
        assertEquals("从游标 1 起重放（base=6 已越过游标）→ 全部保留事件", EventList.MAX_EVENTS, second.size());
        assertEquals("x5", second.get(0).text); // 绝对序号 6 = x5（a=0, x0=1 … x5=6）
    }

    /** clear 重置绝对序号基准 */
    @Test
    public void clear_resetsBase() {
        EventList l = newList();
        for (int i = 0; i < EventList.MAX_EVENTS + 1; i++) l.add(new Ev(EventList.Kind.CONTENT, "x", null));
        assertEquals(1, l.base());
        l.clear();
        assertEquals(0, l.base());
        assertEquals(0, l.size());
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        assertEquals(1, l.size());
        assertEquals(1, l.rebind(new EventList.Listener() {
            @Override public void onEvent(Ev e) { }
        }, 0).size());
    }
}
