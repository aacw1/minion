package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

/** SFTP 列目录 → Markdown 表（名称/类型/权限/大小/修改时间），服务传输路径发现 */
public class SftpLsTool extends SshTool {

    public SftpLsTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SftpLs"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "用 SFTP 列出 ssh 当前连接的远端目录（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "参数 path 为远端目录路径，如 /etc/nginx/conf.d。";
        }
        return "用 SFTP 列出 ssh 当前连接（" + c.label() + "）的远端目录，返回 Markdown 表"
                + "（名称/类型/权限/大小/修改时间），不经过远端 shell。"
                + "path 为远端目录路径（默认当前目录）；项数多会自动截断，需要精确列表请用 SshExec 执行 ls 过滤。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("SFTP 列出远端目录",
                new String[]{"path"}, new String[]{"path"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return false; }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        String path = ".";
        if (args != null && args.has("path")) {
            String v = args.get("path").getAsString().trim();
            if (v.isEmpty()) return ToolResult.error("path 不能为空");
            path = v;
        }
        try {
            return ToolResult.success(executor.list(c, path));
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
