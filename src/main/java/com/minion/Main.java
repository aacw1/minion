package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.CdpClient;
import com.minion.core.tools.browser.ChromeLauncher;
import com.minion.core.tools.confirm.ConfirmUi;
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
        ModelManager models = ModelManager.load(jarDir);

        // 全局技能目录（所有工作空间/模型共用）
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        SkillManager skillManager = new SkillManager(skillsDir);
        List<Skill> skills = skillManager.scan();

        // 浏览器工具（懒启动 Chrome；退出钩子关停自启进程）
        ChromeLauncher chrome = new ChromeLauncher(config.browserPath(), config.browserPort(),
                Paths.get(config.browserUserDataDir()), config.browserHeadless(),
                config.browserTimeoutMs());
        BrowserSession browserSession = new BrowserSession(chrome, new CdpClient(10000,
                config.browserTimeoutMs()));

        ConfirmUi confirmUi = new GuiConfirmUi();
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, skills, browserSession);

        // 退出钩子统一收口：先关会话（AgentLoop + LLM okhttp 资源 + 线程池），再停自启 Chrome
        // （manager.shutdown 幂等——关窗已 shutdown 时此处空转）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.shutdown();
            chrome.stop();
        }));

        MinionApp.start(config, workspaces, models, manager);
    }
}
