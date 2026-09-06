package com.minion.core.tools.plugin;

import com.minion.core.tools.db.DataSourceConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单个数据库类型的配置（tools.json 的 mysql/postgresql/oracle 段）：
 * 启用开关 + 当前数据源标识名 + 数据源列表。
 * current 的解析/回退规则集中在这里，GUI 与工具都只调这些方法，不直接改字段。
 */
public class DbConfig {
    public boolean enabled = false;
    /** 当前数据源标识名；空 = 未选择（工具调用时返回指引文案） */
    public String current = "";
    /** 数据源列表：设置页（FX 线程）与工具执行（会话线程）可能并发读写 → CopyOnWriteArrayList 防 CME/中间态 */
    public CopyOnWriteArrayList<DataSourceConfig> dataSources = new CopyOnWriteArrayList<DataSourceConfig>();

    /** 当前数据源；current 空或找不到对应项返回 null */
    public DataSourceConfig currentDataSource() {
        return find(current);
    }

    /** 按标识名查找（trim + 忽略大小写）；name 为 null 返回 null */
    public DataSourceConfig find(String name) {
        if (name == null) return null;
        String want = name.trim();
        if (want.isEmpty()) return null;
        if (dataSources == null) return null;
        for (DataSourceConfig d : dataSources) {
            if (d != null && d.name != null && want.equalsIgnoreCase(d.name.trim())) return d;
        }
        return null;
    }

    /** 数据源标识名列表（下拉框用，保持配置顺序） */
    public List<String> names() {
        List<String> out = new ArrayList<String>();
        if (dataSources != null) {
            for (DataSourceConfig d : dataSources) {
                if (d != null && d.name != null) out.add(d.name.trim());
            }
        }
        return out;
    }

    /** 新增；返回 false 表示标识名已存在（调用方应先用 DataSourceValidator 校验） */
    public boolean add(DataSourceConfig ds) {
        if (ds == null || find(ds.name) != null) return false;
        if (dataSources == null) dataSources = new CopyOnWriteArrayList<DataSourceConfig>();
        dataSources.add(ds);
        // 首个数据源自动成为当前项，省一次下拉选择
        if (current == null || current.trim().isEmpty()) current = ds.name.trim();
        return true;
    }

    /** 整项替换（可改标识名）；originalName 找不到返回 false；改名且它是当前项 → 同步 current */
    public boolean replace(String originalName, DataSourceConfig updated) {
        if (updated == null || dataSources == null) return false;
        for (int i = 0; i < dataSources.size(); i++) {
            DataSourceConfig d = dataSources.get(i);
            if (d == null || d.name == null) continue;
            if (!d.name.trim().equalsIgnoreCase(originalName == null ? "" : originalName.trim())) continue;
            dataSources.set(i, updated);
            if (current != null && current.trim().equalsIgnoreCase(d.name.trim())) {
                current = updated.name == null ? "" : updated.name.trim();
            }
            return true;
        }
        return false;
    }

    /** 删除；删的是当前项 → 回退到列表第一个，列表空则 current="" */
    public boolean remove(String name) {
        DataSourceConfig hit = find(name);
        if (hit == null || dataSources == null) return false;
        dataSources.remove(hit);
        boolean wasCurrent = current != null && current.trim().equalsIgnoreCase(hit.name.trim());
        if (wasCurrent) {
            current = dataSources.isEmpty() ? ""
                    : (dataSources.get(0).name == null ? "" : dataSources.get(0).name.trim());
        }
        return true;
    }

    /** 切换当前数据源；标识名不存在则忽略（防下拉框显示空值） */
    public void setCurrent(String name) {
        if (find(name) != null) current = name.trim();
    }
}
