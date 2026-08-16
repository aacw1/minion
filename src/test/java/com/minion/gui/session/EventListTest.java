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
}
