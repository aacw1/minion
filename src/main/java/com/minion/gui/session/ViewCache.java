package com.minion.gui.session;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 会话视图缓存（LRU，纯逻辑、无 JavaFX 依赖，可单测）。
 *
 * 背景（P1-6）：MainWindow 的 viewCache 只增不淘汰，每个打开的会话永久驻留一个 ChatView
 * （最多 MAX_SEGS=200 段 MessageTextArea）——打开 20 个会话即最多 4000 个 TextArea 常驻。
 * 本类把缓存收敛到固定上限（默认 4），超过淘汰**最久未访问**的条目，并通过 onEvict
 * 回调让调用方解绑事件流（ChatView.bind(false)），否则被淘汰视图仍持有 EventList 监听器。
 *
 * accessOrder=true：get/put 都算一次访问——反复切换的会话不会被误淘汰，真正冷的才淘汰。
 * 淘汰回调在 put/remove/clear 内同步执行；回调不得再操作本缓存（调用方只做 bind(false)）。
 */
public class ViewCache<V> {

    /** 默认上限：屏幕上同时看不到更多消息区，4 个已足够覆盖来回切换 */
    public static final int DEFAULT_MAX = 4;

    private final int max;
    private final Consumer<V> onEvict;
    private final LinkedHashMap<String, V> map;

    public ViewCache(Consumer<V> onEvict) {
        this(DEFAULT_MAX, onEvict);
    }

    public ViewCache(int max, Consumer<V> onEvict) {
        if (max < 1) throw new IllegalArgumentException("max 必须 ≥ 1");
        this.max = max;
        this.onEvict = onEvict;
        this.map = new LinkedHashMap<String, V>(16, 0.75f, true);
    }

    public V get(String id) {
        return id == null ? null : map.get(id);
    }

    public boolean contains(String id) {
        return id != null && map.containsKey(id);
    }

    /** 放入并触发超限淘汰（幂等：同 key 覆盖只更新访问顺序） */
    public void put(String id, V view) {
        if (id == null || view == null) return;
        map.put(id, view);
        evictOverflow();
    }

    /** 移除并回调（删除会话/关页签：解绑事件流） */
    public void remove(String id) {
        if (id == null) return;
        V v = map.remove(id);
        if (v != null && onEvict != null) onEvict.accept(v);
    }

    /** 清空并逐个回调（切换工作空间：全部解绑） */
    public void clear() {
        List<V> all = new ArrayList<V>(map.values());
        map.clear();
        if (onEvict != null) {
            for (V v : all) onEvict.accept(v);
        }
    }

    public int size() { return map.size(); }

    /** 当前缓存上限（测试断言用） */
    public int max() { return max; }

    private void evictOverflow() {
        if (map.size() <= max) return;
        Iterator<Map.Entry<String, V>> it = map.entrySet().iterator();
        while (map.size() > max && it.hasNext()) {
            Map.Entry<String, V> eldest = it.next();
            it.remove();
            if (onEvict != null) onEvict.accept(eldest.getValue());
        }
    }
}
