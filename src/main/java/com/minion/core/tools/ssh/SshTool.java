package com.minion.core.tools.ssh;

import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.plugin.ToolContext;

import java.nio.file.Path;

/** 工具公共基类：当前连接解析 + 本地路径守卫 + 文案；子类实现 name/description/schema/execute。
 *  description 每轮由 AgentLoop 重新取，故动态拼接「当前连接」——设置页切下拉框后无需重建工具即生效。 */
public abstract class SshTool implements Tool {

    protected final SshConfig config;
    protected final ToolContext ctx;
    protected final SshExecutor executor;

    protected SshTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        this.config = config;
        this.ctx = ctx;
        this.executor = executor == null ? new SshExecutor() : executor;
    }

    /** 当前连接；null = 未选择 */
    protected SshConnection current() {
        return config == null ? null : config.currentConnection();
    }

    /** 未选连接的指引文案（同 db 口径） */
    protected ToolResult noConnectionError() {
        return ToolResult.error("ssh 插件未选择连接，请在 设置 → 工具 → ssh 中选择");
    }

    /** 本地路径守卫：越界返回错误（内部覆盖工作路径/额外放行/技能/tmp 四类命中判定）；
     *  相对路径按会话 cwd 解析（同 Read/Write 的 PathsGuard.errorIfOutside 口径）。null = 通过 */
    protected ToolResult guardLocal(String path) {
        if (path == null || path.trim().isEmpty()) return null;   // 必填校验由子类先做
        if (ctx == null || ctx.workspace == null) return ToolResult.error("会话上下文缺失，无法校验本地路径");
        Path p = resolveLocal(path);
        return PathsGuard.errorIfOutside(ctx.workspace, ctx.skillsDir, ctx.tmpDir, p);
    }

    /** 相对路径按会话 cwd 解析为绝对路径（调用方必须先过 guardLocal） */
    protected Path resolveLocal(String path) {
        return PathsGuard.resolve(ctx.workspace.cwd().toString(), path).toAbsolutePath();
    }

    protected Workspace workspace() { return ctx == null ? null : ctx.workspace; }
    protected String skillsDir() { return ctx == null ? null : ctx.skillsDir; }
    protected String tmpDir() { return ctx == null ? null : ctx.tmpDir; }
}
