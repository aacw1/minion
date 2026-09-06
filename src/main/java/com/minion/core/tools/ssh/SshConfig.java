package com.minion.core.tools.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ssh 插件配置（tools.json 的 ssh 段）：启用开关 + 当前连接标识名 + 连接列表。
 * current 的解析/回退规则集中在这里，GUI 与工具都只调这些方法，不直接改字段。
 * 刻意不把 DbConfig 泛型化：改动既有 db 链路得不偿失（规格 §4.3 决策）。
 */
public class SshConfig {
    public boolean enabled = false;
    /** 当前连接标识名；空 = 未选择（工具调用时返回指引文案） */
    public String current = "";
    /** 连接列表：设置页（FX 线程）与工具执行（会话线程）可能并发读写 → CopyOnWriteArrayList 防 CME/中间态 */
    public CopyOnWriteArrayList<SshConnection> connections = new CopyOnWriteArrayList<SshConnection>();

    /** 当前连接；current 空或找不到对应项返回 null */
    public SshConnection currentConnection() {
        return find(current);
    }

    /** 按标识名查找（trim + 忽略大小写）；name 为 null 返回 null */
    public SshConnection find(String name) {
        if (name == null) return null;
        String want = name.trim();
        if (want.isEmpty()) return null;
        if (connections == null) return null;
        for (SshConnection c : connections) {
            if (c != null && c.name != null && want.equalsIgnoreCase(c.name.trim())) return c;
        }
        return null;
    }

    /** 连接标识名列表（下拉框用，保持配置顺序） */
    public List<String> names() {
        List<String> out = new ArrayList<String>();
        if (connections != null) {
            for (SshConnection c : connections) {
                if (c != null && c.name != null) out.add(c.name.trim());
            }
        }
        return out;
    }

    /** 新增；返回 false 表示标识名已存在（调用方应先用 SshValidator 校验） */
    public boolean add(SshConnection c) {
        if (c == null || find(c.name) != null) return false;
        if (connections == null) connections = new CopyOnWriteArrayList<SshConnection>();
        connections.add(c);
        // 首个连接自动成为当前项，省一次下拉选择
        if (current == null || current.trim().isEmpty()) current = c.name.trim();
        return true;
    }

    /** 整项替换（可改标识名）；originalName 找不到返回 false；改名且它是当前项 → 同步 current */
    public boolean replace(String originalName, SshConnection updated) {
        if (updated == null || connections == null) return false;
        for (int i = 0; i < connections.size(); i++) {
            SshConnection c = connections.get(i);
            if (c == null || c.name == null) continue;
            if (!c.name.trim().equalsIgnoreCase(originalName == null ? "" : originalName.trim())) continue;
            connections.set(i, updated);
            if (current != null && current.trim().equalsIgnoreCase(c.name.trim())) {
                current = updated.name == null ? "" : updated.name.trim();
            }
            return true;
        }
        return false;
    }

    /** 删除；删的是当前项 → 回退到列表第一个，列表空则 current="" */
    public boolean remove(String name) {
        SshConnection hit = find(name);
        if (hit == null || connections == null) return false;
        connections.remove(hit);
        boolean wasCurrent = current != null && current.trim().equalsIgnoreCase(hit.name.trim());
        if (wasCurrent) {
            current = connections.isEmpty() ? ""
                    : (connections.get(0).name == null ? "" : connections.get(0).name.trim());
        }
        return true;
    }

    /** 切换当前连接；标识名不存在则忽略（防下拉框显示空值） */
    public void setCurrent(String name) {
        if (find(name) != null) current = name.trim();
    }
}
