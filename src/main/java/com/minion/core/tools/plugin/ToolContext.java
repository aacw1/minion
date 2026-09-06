package com.minion.core.tools.plugin;

import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;

/**
 * 插件创建工具时需要的会话级依赖（由 SessionManager.newRegistry 每会话构造一次）。
 * 不含 ToolPluginManager 引用：现有插件都不需要它（DbTool 读 DbConfig、浏览器工具读 BrowserManager），
 * 少一个循环引用点。
 */
public class ToolContext {

    public final Workspace workspace;
    public final String skillsDir;
    /** 会话临时目录 jarDir/.session/tmp/&lt;sessionId&gt;，可为 null（测试） */
    public final String tmpDir;
    public final ConfirmGate confirmGate;

    public ToolContext(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirmGate) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
        this.confirmGate = confirmGate;
    }
}
