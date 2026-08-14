package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.Path;

/** 页面截图保存到工作区(模型可随后用 Read 查看) */
public class BrowserScreenshotTool implements Tool {

    private final BrowserSession session;
    private final Workspace workspace;
    private final String skillsDir;
    private final ConfirmGate confirm;

    public BrowserScreenshotTool(BrowserSession session, Workspace workspace, String skillsDir) {
        this(session, workspace, skillsDir, null);
    }

    public BrowserScreenshotTool(BrowserSession session, Workspace workspace, String skillsDir,
                                 ConfirmGate confirm) {
        this.session = session;
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.confirm = confirm;
    }

    @Override
    public String name() { return "BrowserScreenshot"; }

    @Override
    public String description() { return "对浏览器当前页面截图保存到工作区(相对路径以当前目录为基准)"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("页面截图",
                new String[]{"path", "fullPage"},
                new String[]{"path"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("path")) return ToolResult.error("缺少 path 参数");
        boolean fullPage = !args.has("fullPage") || args.get("fullPage").getAsBoolean();
        Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, p);
        if (guard != null) {
            // 同 Read 工具：越界不静默拒绝，弹确认框让用户选（Y 放行本次 / N 拒绝 / W 会话放行）
            if (confirm == null || !confirm.checkWriteOutside(this, args, p.toString())) return guard;
        }
        try {
            return ToolResult.success(session.screenshot(p.toString(), fullPage));
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
