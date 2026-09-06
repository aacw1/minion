package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

import java.nio.file.Path;

/** SFTP 下载：远端 → 本地（本地路径先过守卫，越界拒绝；同名本地文件将被覆盖；下载后可 Read 查看内容） */
public class SftpGetTool extends SshTool {

    public SftpGetTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SftpGet"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "从 ssh 当前连接的远端下载文件到本地（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "remotePath 远端文件路径；localPath 本地保存路径（工作区内，同名文件将被覆盖）。";
        }
        return "从 ssh 当前连接（" + c.label() + "）下载远端文件到本地工作区"
                + "（localPath 相对会话目录解析；越界路径会被拒绝）。"
                + "remotePath 为远端文件路径，localPath 为本地保存路径"
                + "（本地同名文件将被覆盖，无确认，请确认目标路径）。"
                + "下载完成后可用 Read 工具查看文件内容。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("SFTP 下载远端文件到本地",
                new String[]{"remotePath", "localPath"}, new String[]{"remotePath", "localPath"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return false; }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        if (args == null || !args.has("remotePath")) return ToolResult.error("缺少 remotePath 参数");
        String remote = args.get("remotePath").getAsString().trim();
        if (remote.isEmpty()) return ToolResult.error("remotePath 不能为空");
        if (!args.has("localPath")) return ToolResult.error("缺少 localPath 参数");
        String lp = args.get("localPath").getAsString().trim();
        if (lp.isEmpty()) return ToolResult.error("localPath 不能为空");
        ToolResult g = guardLocal(lp);
        if (g != null) return g;
        Path local = resolveLocal(lp);
        try {
            return ToolResult.success(executor.get(c, remote, local));
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
