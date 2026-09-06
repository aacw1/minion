package com.minion.core.tools.plugin;

import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.db.DbType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可插拔工具门面：装配 4 个插件、实现 ToolRegistry.PluginGate（拉模式过滤的判定源）、
 * 统一落盘 tools.json、向 GUI 广播变更（监听只用于刷面板，不参与生效链路）。
 * 全局单例，所有会话的 registry 共享同一个 gate 实例 → 改开关后下一轮请求即生效，无需遍历会话。
 */
public class ToolPluginManager implements ToolRegistry.PluginGate {

    private final ToolStore store;
    private final BrowserManager browserManager;
    private final BrowserPlugin browserPlugin;
    private final List<DbPlugin> dbPlugins = new ArrayList<DbPlugin>();
    private final List<ToolPlugin> all;
    private final List<Runnable> listeners = new ArrayList<Runnable>();
    /** 插件落盘回调：写文件后顺带通知 GUI 刷新 */
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            store.save();
            notifyListeners();
        }
    };

    public ToolPluginManager(ToolStore store) {
        this.store = store;
        this.browserManager = new BrowserManager(store.browserConfig());
        this.browserPlugin = new BrowserPlugin(store.browserConfig(), browserManager, saver);
        for (DbType t : DbType.values()) {
            DbConfig c = store.dbConfig(t.id());
            if (c != null) dbPlugins.add(new DbPlugin(t, c, saver));
        }
        List<ToolPlugin> list = new ArrayList<ToolPlugin>();
        list.add(browserPlugin);
        list.addAll(dbPlugins);
        this.all = Collections.unmodifiableList(list);
    }

    /** 设置页行顺序：浏览器操作 / mysql / postgreSQL / oracle */
    public List<ToolPlugin> plugins() { return all; }

    public ToolPlugin plugin(String id) {
        if (id == null) return null;
        for (ToolPlugin p : all) {
            if (id.equals(p.id())) return p;
        }
        return null;
    }

    /**
     * PluginGate 判定：null（内置工具）恒放行；未登记 id 保守放行
     * （避免 id 拼写错误导致工具集体消失且无从排查）。
     */
    @Override
    public boolean enabled(String pluginId) {
        if (pluginId == null) return true;
        ToolPlugin p = plugin(pluginId);
        return p == null || p.enabled();
    }

    /** 切换启用状态：插件内部已落盘并通知，这里只兜未知 id */
    public void setEnabled(String id, boolean on) {
        ToolPlugin p = plugin(id);
        if (p == null) return;
        p.setEnabled(on);
    }

    public BrowserManager browserManager() { return browserManager; }

    public BrowserPlugin browserPlugin() { return browserPlugin; }

    /** mysql / postgresql / oracle；非数据库插件 id 返回 null */
    public DbPlugin dbPlugin(String id) {
        for (DbPlugin p : dbPlugins) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    public ToolStore store() { return store; }

    /** GUI 面板保存配置后调用（插件内部的数据源变更已自动落盘） */
    public void save() { saver.run(); }

    /** 变更监听：仅用于 GUI 刷新状态文案/下拉框，不参与工具生效链路 */
    public void addListener(Runnable onChange) {
        if (onChange != null) listeners.add(onChange);
    }

    private void notifyListeners() {
        for (Runnable l : new ArrayList<Runnable>(listeners)) l.run();
    }

    /** 退出钩子：关掉 minion 自启的 Chrome */
    public void shutdown() { browserManager.shutdown(); }
}
