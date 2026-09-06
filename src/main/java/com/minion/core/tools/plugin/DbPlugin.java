package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.db.DataSourceConfig;
import com.minion.core.tools.db.DbExecutor;
import com.minion.core.tools.db.DbTool;
import com.minion.core.tools.db.DbType;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据库插件（mysql / postgresql / oracle 各一个实例）：
 * 只读工具的产出 + 数据源 CRUD + 当前数据源切换 + 测试连接。
 * 配置对象与 ToolStore 里的是同一实例，改动后立即落盘 → 全局会话下一轮生效。
 */
public class DbPlugin implements ToolPlugin {

    /** 测试连接结果：ok=false 时 message 是可直接展示的失败原因 */
    public static class TestResult {
        public final boolean ok;
        public final String message;
        public final long elapsedMs;

        TestResult(boolean ok, String message, long elapsedMs) {
            this.ok = ok;
            this.message = message;
            this.elapsedMs = elapsedMs;
        }
    }

    private final DbType type;
    private final DbConfig config;
    private final Runnable saver;

    public DbPlugin(DbType type, DbConfig config, Runnable saver) {
        this.type = type;
        this.config = config == null ? new DbConfig() : config;
        this.saver = saver;
    }

    @Override public String id() { return type.id(); }

    @Override public String displayName() { return type.displayName(); }

    @Override
    public String statusText() {
        List<String> names = config.names();
        if (names.isEmpty()) return "未配置数据源";
        DataSourceConfig ds = config.currentDataSource();
        if (ds == null) return "未选择数据源";
        return "当前数据源: " + ds.name;
    }

    @Override public boolean enabled() { return config.enabled; }

    @Override
    public void setEnabled(boolean on) {
        config.enabled = on;
        save();
    }

    @Override
    public List<Tool> createTools(ToolContext ctx) {
        String tmp = ctx == null ? null : ctx.tmpDir;
        List<Tool> list = new ArrayList<Tool>();
        list.add(new DbTool(type, config, tmp == null ? null : Paths.get(tmp)));
        return Collections.unmodifiableList(list);
    }

    public DbType type() { return type; }

    public DbConfig config() { return config; }

    /** 数据源标识名列表（GUI 下拉框） */
    public List<String> dataSourceNames() { return config.names(); }

    public DataSourceConfig find(String name) { return config.find(name); }

    public boolean addDataSource(DataSourceConfig ds) {
        boolean ok = config.add(ds);
        if (ok) save();
        return ok;
    }

    public boolean updateDataSource(String originalName, DataSourceConfig updated) {
        boolean ok = config.replace(originalName, updated);
        if (ok) save();
        return ok;
    }

    public boolean removeDataSource(String name) {
        boolean ok = config.remove(name);
        if (ok) save();
        return ok;
    }

    /** 切换当前数据源（下拉框）：改动即落盘 → 全局会话下一轮工具描述与执行都用新数据源 */
    public void setCurrent(String name) {
        String before = config.current;
        config.setCurrent(name);
        if (!config.current.equals(before)) save();
    }

    /**
     * 测试连接：新建连接即关（不缓存）。调用方应在后台线程执行，本方法同步阻塞至多 loginTimeout。
     * 失败原因取驱动异常首行，与工具错误文案同口径。
     */
    public TestResult testConnection(String name) {
        DataSourceConfig ds = config.find(name);
        if (ds == null) return new TestResult(false, "数据源不存在: " + name, 0);
        try {
            Class.forName(type.driverClass());
        } catch (Throwable e) {
            return new TestResult(false, type.displayName() + " 驱动加载失败（驱动应已内置，请检查 jar 完整性）: " + e, 0);
        }
        long t0 = System.currentTimeMillis();
        Connection c = null;
        try {
            DriverManager.setLoginTimeout(DbExecutor.LOGIN_TIMEOUT_SECONDS);
            c = DriverManager.getConnection(ds.url, ds.user, ds.password);
            long cost = System.currentTimeMillis() - t0;
            return new TestResult(true, "连接成功，耗时 " + cost + "ms", cost);
        } catch (Exception e) {
            return new TestResult(false, DbExecutor.firstLine(e.getMessage()), 0);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) { }
            }
        }
    }

    private void save() {
        if (saver != null) saver.run();
    }
}
