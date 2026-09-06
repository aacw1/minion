package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

/** SFTP 改名/移动（同一服务器内；目标已存在多数服务器会失败） */
public class SftpRenameTool extends SshTool {

    public SftpRenameTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SftpRename"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "在 ssh 当前连接远端改名或移动文件/目录（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "srcPath 原路径；dstPath 目标路径。";
        }
        return "在 ssh 当前连接（" + c.label() + "）远端改名/移动（同一服务器内）。"
                + "srcPath 为原路径，dstPath 为目标路径；目标已存在时多数服务器会拒绝（返回错误文案）。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("SFTP 远端改名或移动",
                new String[]{"srcPath", "dstPath"}, new String[]{"srcPath", "dstPath"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return false; }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        if (args == null || !args.has("srcPath")) return ToolResult.error("缺少 srcPath 参数");
        String src = args.get("srcPath").getAsString().trim();
        if (src.isEmpty()) return ToolResult.error("srcPath 不能为空");
        if (!args.has("dstPath")) return ToolResult.error("缺少 dstPath 参数");
        String dst = args.get("dstPath").getAsString().trim();
        if (dst.isEmpty()) return ToolResult.error("dstPath 不能为空");
        try {
            return ToolResult.success(executor.rename(c, src, dst));
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
