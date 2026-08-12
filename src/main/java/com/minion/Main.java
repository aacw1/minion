package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.MinionApp;
import com.minion.gui.session.SessionManager;

import java.util.ArrayList;

public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        java.nio.file.Path jarDir = Config.jarDir();
        WorkspaceManager workspaces = WorkspaceManager.load(jarDir);
        ModelManager models = ModelManager.load(jarDir);
        // 临时装配（Task 15 替换为最终版：GuiConfirmUi + 技能扫描 + 浏览器）
        ConfirmUi confirmUi = new ConfirmUi() {
            @Override public ConfirmUi.Decision ask(String message) { return ConfirmUi.Decision.APPROVE; }
        };
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, new ArrayList<Skill>(), null);
        MinionApp.start(config, workspaces, models, manager);
    }
}
