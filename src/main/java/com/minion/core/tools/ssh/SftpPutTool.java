package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;

/** SFTP 上传：本地（守卫）→ 远端；可能覆盖同名远端文件 → 一律高危确认 */
public class SftpPutTool extends SshTool {

    public SftpPutTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SftpPut"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "上传本地文件到 ssh 当前连接的远端（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "localPath 本地文件路径（工作区内）；remotePath 远端目标路径。会覆盖同名远端文件（弹确认）。";
        }
        return "上传本地工作区文件到 ssh 当前连接（" + c.label() + "）的远端。"
                + "localPath 相对会话目录解析（越界路径会被拒绝）；remotePath 为远端目标路径"
                + "（若远端目录不存在会失败，可先 SshExec mkdir -p）。"
                + "可能覆盖同名远端文件，执行前会弹确认窗。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("SFTP 上传本地文件到远端",
                new String[]{"localPath", "remotePath"}, new String[]{"localPath", "remotePath"});
    }

    /** 覆盖远端文件不可撤销 → 一律确认 */
    @Override
    public boolean isHighRisk(JsonObject args) { return true; }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        if (args == null || !args.has("localPath")) return ToolResult.error("缺少 localPath 参数");
        String lp = args.get("localPath").getAsString().trim();
        if (lp.isEmpty()) return ToolResult.error("localPath 不能为空");
        if (!args.has("remotePath")) return ToolResult.error("缺少 remotePath 参数");
        String remote = args.get("remotePath").getAsString().trim();
        if (remote.isEmpty()) return ToolResult.error("remotePath 不能为空");
        ToolResult g = guardLocal(lp);
        if (g != null) return g;
        Path local = resolveLocal(lp);
        if (!Files.isRegularFile(local)) {
            return ToolResult.error("本地文件不存在: " + local.toAbsolutePath());
        }
        try {
            return ToolResult.success(executor.put(c, local, remote));
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
