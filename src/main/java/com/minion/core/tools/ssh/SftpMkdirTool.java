package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

/** SFTP 递归建目录（mkdir -p 语义，已存在静默） */
public class SftpMkdirTool extends SshTool {

    public SftpMkdirTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SftpMkdir"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "在 ssh 当前连接远端递归创建目录（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "参数 path 为远端目录路径，如 /opt/app/conf。";
        }
        return "在 ssh 当前连接（" + c.label() + "）远端递归创建目录（mkdir -p 语义：中间目录自动创建，"
                + "已存在则静默跳过）。path 为远端目录路径，如 /opt/app/conf。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("SFTP 递归创建远端目录",
                new String[]{"path"}, new String[]{"path"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return false; }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        if (args == null || !args.has("path")) return ToolResult.error("缺少 path 参数");
        String path = args.get("path").getAsString().trim();
        if (path.isEmpty()) return ToolResult.error("path 不能为空");
        try {
            return ToolResult.success(executor.mkdirs(c, path));
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
