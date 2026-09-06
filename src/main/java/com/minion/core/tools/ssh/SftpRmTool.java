package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

/** SFTP 删除：文件 / 空目录（不递归，一律确认；递归删除交给 SshExec rm -rf 弹确认） */
public class SftpRmTool extends SshTool {

    public SftpRmTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SftpRm"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "删除 ssh 当前连接远端的文件或空目录（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "参数 path 为远端路径。只删文件/空目录，不递归；递归删除用 SshExec 执行 rm -rf（会弹确认）。";
        }
        return "删除 ssh 当前连接（" + c.label() + "）远端的文件或空目录（删除不可恢复，执行前会弹确认窗）。"
                + "path 为远端路径；不递归——目录非空会失败并提示，请改用 SshExec 执行 rm -rf 递归删除（同样弹确认）。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("SFTP 删除远端文件或空目录",
                new String[]{"path"}, new String[]{"path"});
    }

    /** 删除不可恢复 → 一律确认 */
    @Override
    public boolean isHighRisk(JsonObject args) { return true; }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        if (args == null || !args.has("path")) return ToolResult.error("缺少 path 参数");
        String path = args.get("path").getAsString().trim();
        if (path.isEmpty()) return ToolResult.error("path 不能为空");
        try {
            return ToolResult.success(executor.rm(c, path));
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
