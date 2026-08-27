package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpStore;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.CdpClient;
import com.minion.core.tools.browser.ChromeLauncher;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.OutputDump;
import com.minion.gui.MinionApp;
import com.minion.gui.confirm.GuiConfirmUi;
import com.minion.gui.session.SessionManager;

import java.nio.file.Paths;
import java.util.List;

/** 入口：装配配置/技能/浏览器/GUI，启动 JavaFX 主窗口（GUI 为唯一界面，CLI 已移除） */
public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        java.nio.file.Path jarDir = Config.jarDir();
        WorkspaceManager workspaces = WorkspaceManager.load(jarDir);

        // 工具输出落盘目录清理：仅删修改超 3 天的旧文件（最近的可供回溯 Read）
        OutputDump.cleanup(jarDir.resolve(".session").resolve("tmp"), OutputDump.RETENTION_MS);
        ModelManager models = ModelManager.load(jarDir);

        // 全局技能目录（所有工作空间/模型共用）
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        SkillManager skillManager = new SkillManager(skillsDir);
        List<Skill> skills = skillManager.scan();

        // MCP 服务器管理（mcp.json；惰性连接，退出钩子关停子进程）
        McpManager mcpManager = new McpManager(McpStore.load(jarDir));

        // 浏览器工具（懒启动 Chrome；未配置 browser.path 则不加载 CDP 工具）
        BrowserSession browserSession = null;
        ChromeLauncher chrome = null;
        String browserPath = config.browserPath();
        if (browserPath != null && !browserPath.trim().isEmpty()) {
            chrome = new ChromeLauncher(browserPath, config.browserPort(),
                    Paths.get(config.browserUserDataDir()), config.browserHeadless(),
                    config.browserTimeoutMs());
            browserSession = new BrowserSession(chrome, new CdpClient(10000,
                    config.browserTimeoutMs()));
        }
        final ChromeLauncher chromeToStop = chrome;

        ConfirmUi confirmUi = new GuiConfirmUi();
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, skills, browserSession, mcpManager);

        // 退出钩子统一收口：先关会话（AgentLoop + LLM okhttp 资源 + 线程池 + MCP 子进程），再停自启 Chrome
        // （manager.shutdown 幂等——关窗已 shutdown 时此处空转）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.shutdown();
            mcpManager.shutdown();
            if (chromeToStop != null) chromeToStop.stop();
        }));

        MinionApp.start(config, workspaces, models, manager);
    }
}
