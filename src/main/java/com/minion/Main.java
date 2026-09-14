package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpStore;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionTempCleaner;
import com.minion.core.tools.plugin.ToolPluginManager;
import com.minion.core.tools.plugin.ToolStore;
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

        // 会话临时目录孤儿清理：落盘文件生命周期 = 会话生命周期（删会话时 SessionManager 已递归删除），
        // 启动只兜底清理无对应会话文件的残留目录；1 小时宽限防竞态（正在落盘的活跃会话目录不误删）
        SessionTempCleaner.cleanOrphans(jarDir.resolve("session"),
                jarDir.resolve(".session").resolve("tmp"), 3600_000L);
        ModelManager models = ModelManager.load(jarDir);

        // 全局技能目录（所有工作空间/模型共用）
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        SkillManager skillManager = new SkillManager(skillsDir);
        List<Skill> skills = skillManager.scan();

        // MCP 服务器管理（mcp.json；惰性连接，退出钩子关停子进程）
        McpManager mcpManager = new McpManager(McpStore.load(jarDir));

        // 可插拔工具（tools.json）：浏览器懒启动 Chrome、数据库每次调用新建连接；
        // 四项默认不启用，启用状态由设置页「工具」页实时改，全局会话下一轮请求生效
        final ToolPluginManager plugins = new ToolPluginManager(ToolStore.load(jarDir));

        ConfirmUi confirmUi = new GuiConfirmUi();
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, skills, plugins, mcpManager);

        // 退出钩子统一收口：先关会话（AgentLoop + LLM okhttp 资源 + 线程池 + MCP 子进程），再停自启 Chrome
        // （manager.shutdown 幂等——关窗已 shutdown 时此处空转）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.shutdown();
            mcpManager.shutdown();
            plugins.shutdown();
        }));

        MinionApp.start(config, workspaces, models, manager);
    }
}
