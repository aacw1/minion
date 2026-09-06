package com.minion.core.tools.db;

import java.util.List;

/**
 * 数据源表单校验（纯函数，GUI 只负责弹提示，规则集中在这里便于单测）。
 * validate 返回 null 表示通过，否则返回可直接展示的中文原因。
 */
public final class DataSourceValidator {

    /** 标识名长度上限 */
    public static final int NAME_MAX = 40;

    private DataSourceValidator() { }

    /**
     * @param name         表单里的标识名（未 trim）
     * @param url          表单里的 URL（未 trim）
     * @param all          该数据库类型下已有数据源全集
     * @param originalName 修改场景传原标识名（改回自身原名不算重复）；新建传 null
     */
    public static String validate(String name, String url,
                                 List<DataSourceConfig> all, String originalName) {
        String n = name == null ? "" : name.trim();
        String u = url == null ? "" : url.trim();
        if (n.isEmpty()) return "标识名不能为空";
        if (n.length() > NAME_MAX) return "标识名过长（≤" + NAME_MAX + " 字符）";
        if (all != null) {
            for (DataSourceConfig d : all) {
                if (d == null || d.name == null) continue;
                String existing = d.name.trim();
                if (existing.equalsIgnoreCase(n)
                        && (originalName == null || !existing.equalsIgnoreCase(originalName.trim()))) {
                    return "标识名已存在：" + n;
                }
            }
        }
        if (u.isEmpty()) return "URL 不能为空";
        if (!u.toLowerCase().startsWith("jdbc:")) return "URL 必须以 jdbc: 开头";
        return null;
    }
}
