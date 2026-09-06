package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.DangerousCommands;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.plugin.ToolContext;
import com.minion.core.tools.ToolResult;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 远程执行命令：危险命令（远端名单）弹确认；输出头尾截断超限落盘；默认超时 120s（同 Bash） */
public class SshExecTool extends SshTool {

    private static final int DEFAULT_TIMEOUT = BashTool.DEFAULT_TIMEOUT;

    public SshExecTool(SshConfig config, ToolContext ctx, SshExecutor executor) {
        super(config, ctx, executor);
    }

    @Override public String name() { return "SshExec"; }

    @Override
    public String description() {
        SshConnection c = current();
        if (c == null) {
            return "在 ssh 当前连接上执行命令（当前未选择连接，调用会返回提示，请在 设置 → 工具 → ssh 中选择）。"
                    + "参数 command 为要执行的命令，如 ls -la /var/log；timeoutSeconds 可选（默认 120）。";
        }
        return "在 ssh 当前连接（" + c.label() + "）上执行远程命令并返回输出。"
                + "command 原样交给远端 shell（需要登录 shell 环境时用 bash -lc '...' 包裹）；"
                + "timeoutSeconds 可选，默认 120，超时断开连接（远端可能残留进程）。"
                + "危险命令（rm/dd/reboot/systemctl/apt 等）会弹确认窗。输出超长自动截断并落盘。";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("ssh 远程执行命令",
                new String[]{"command", "timeoutSeconds"}, new String[]{"command"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) {
        return args != null && args.has("command")
                && DangerousCommands.isDangerousRemote(args.get("command").getAsString());
    }

    @Override
    public ToolResult execute(JsonObject args) {
        SshConnection c = current();
        if (c == null) return noConnectionError();
        if (args == null || !args.has("command")) return ToolResult.error("缺少 command 参数");
        String command = args.get("command").getAsString().trim();
        if (command.isEmpty()) return ToolResult.error("command 不能为空");
        final int timeout;
        if (args.has("timeoutSeconds")) {
            try {
                timeout = args.get("timeoutSeconds").getAsInt();
            } catch (Exception e) {
                return ToolResult.error("参数 timeoutSeconds 格式错误: " + e.getMessage());
            }
            if (timeout < 1) return ToolResult.error("timeoutSeconds 非法: " + timeout);
        } else {
            timeout = DEFAULT_TIMEOUT;
        }
        Path tmp = tmpDir() == null ? null : Paths.get(tmpDir());
        try {
            SshExecutor.ExecResult r = executor.exec(c, command, timeout, tmp);
            if (r.exitCode != 0) {
                return ToolResult.error("exit code " + r.exitCode + "（命令失败，输出如下）\n" + r.text);
            }
            return ToolResult.success(r.text);
        } catch (SshOpException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
