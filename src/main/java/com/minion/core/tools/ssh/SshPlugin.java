package com.minion.core.tools.ssh;

import com.minion.core.tools.Tool;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.plugin.ToolPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ssh 插件：远程命令 + SFTP 文件操作。连接 CRUD/当前切换/测试连接，改动即落盘
 *  （与 DbPlugin 同一 saver → ToolPluginManager 通知 GUI）。无连接时不允许启用。 */
public class SshPlugin implements ToolPlugin {

    private final SshConfig config;
    private final Runnable saver;

    public SshPlugin(SshConfig config, Runnable saver) {
        this.config = config == null ? new SshConfig() : config;
        this.saver = saver;
    }

    @Override public String id() { return "ssh"; }

    @Override public String displayName() { return "ssh"; }

    /** 状态列对 ssh 行恒空（同 db 口径）：当前选中名/（无当前连接）均由行内下拉框表达，不重复占列 */
    @Override
    public String statusText() { return ""; }

    @Override public boolean enabled() { return config.enabled; }

    /** 无连接时不允许启用（同 db：避免模型拿到工具却只能报「未选择连接」） */
    @Override public boolean canEnable() { return !config.names().isEmpty(); }

    @Override
    public void setEnabled(boolean on) {
        config.enabled = on;
        save();
    }

    @Override
    public List<Tool> createTools(ToolContext ctx) {
        SshExecutor executor = new SshExecutor();
        List<Tool> list = new ArrayList<Tool>();
        list.add(new SshExecTool(config, ctx, executor));
        list.add(new SftpLsTool(config, ctx, executor));
        list.add(new SftpGetTool(config, ctx, executor));
        list.add(new SftpPutTool(config, ctx, executor));
        list.add(new SftpRmTool(config, ctx, executor));
        list.add(new SftpMkdirTool(config, ctx, executor));
        list.add(new SftpRenameTool(config, ctx, executor));
        return Collections.unmodifiableList(list);
    }

    public SshConfig config() { return config; }

    /** 连接标识名列表（GUI 下拉框） */
    public List<String> connectionNames() { return config.names(); }

    public SshConnection find(String name) { return config.find(name); }

    public boolean addConnection(SshConnection c) {
        boolean ok = config.add(c);
        if (ok) save();
        return ok;
    }

    public boolean updateConnection(String originalName, SshConnection updated) {
        boolean ok = config.replace(originalName, updated);
        if (ok) save();
        return ok;
    }

    public boolean removeConnection(String name) {
        boolean ok = config.remove(name);
        if (ok) save();
        return ok;
    }

    /** 切换当前连接（下拉框）：改动即落盘 → 全局会话下一轮生效 */
    public void setCurrent(String name) {
        String before = config.current;
        config.setCurrent(name);
        if (!config.current.equals(before)) save();
    }

    /** 测试连接：连接+认证即断。调用方应在后台线程执行，至多阻塞 10s */
    public SshExecutor.TestResult testConnection(String name) {
        SshConnection c = config.find(name);
        if (c == null) return new SshExecutor.TestResult(false, "连接不存在: " + name, 0);
        return new SshExecutor().test(c);
    }

    private void save() {
        if (saver != null) saver.run();
    }
}
