package com.minion.gui.session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 会话视图缓存 LRU（P1-6，纯逻辑）：上限淘汰、访问顺序、淘汰回调（解绑事件流） */
public class ViewCacheTest {

    private final List<String> evicted = new ArrayList<String>();

    private ViewCache<String> cache(int max) {
        return new ViewCache<String>(max, new java.util.function.Consumer<String>() {
            @Override public void accept(String v) { evicted.add(v); }
        });
    }

    @Test
    public void putBeyondMax_evictsOldest() {
        ViewCache<String> c = cache(3);
        c.put("a", "A");
        c.put("b", "B");
        c.put("c", "C");
        assertEquals(3, c.size());
        c.put("d", "D");
        assertEquals("上限 3", 3, c.size());
        assertFalse(c.contains("a"));
        assertTrue(c.contains("b") && c.contains("c") && c.contains("d"));
        assertEquals("淘汰回调收到最久未访问条目", 1, evicted.size());
        assertEquals("A", evicted.get(0));
    }

    /** get 刷新访问顺序：反复访问的会话不会被误淘汰 */
    @Test
    public void get_refreshesAccessOrder() {
        ViewCache<String> c = cache(2);
        c.put("a", "A");
        c.put("b", "B");
        assertEquals("A", c.get("a")); // a 变最近
        c.put("c", "C");               // 淘汰最久未访问的 b
        assertTrue(c.contains("a"));
        assertFalse(c.contains("b"));
        assertEquals("B", evicted.get(0));
    }

    /** 同 key 覆盖不触发淘汰（只更新值/访问顺序） */
    @Test
    public void put_sameKeyDoesNotEvict() {
        ViewCache<String> c = cache(2);
        c.put("a", "A");
        c.put("b", "B");
        c.put("a", "A2");
        assertEquals(2, c.size());
        assertTrue(evicted.isEmpty());
        assertEquals("A2", c.get("a"));
    }

    /** 删除/清空同样回调（删除会话、切换工作空间需解绑事件流） */
    @Test
    public void removeAndClear_callback() {
        ViewCache<String> c = cache(4);
        c.put("a", "A");
        c.put("b", "B");
        c.remove("a");
        assertEquals(1, c.size());
        assertEquals("A", evicted.get(0));
        c.clear();
        assertEquals(0, c.size());
        assertTrue(evicted.contains("B"));
        assertEquals(2, evicted.size());
        c.remove("missing"); // 幂等：不存在不回调
        assertEquals(2, evicted.size());
    }

    @Test
    public void defaultMax_isFour() {
        assertEquals(4, ViewCache.DEFAULT_MAX);
        assertEquals(4, new ViewCache<String>(null).max());
    }

    @Test(expected = IllegalArgumentException.class)
    public void maxBelowOne_rejected() {
        new ViewCache<String>(0, null);
    }
}
