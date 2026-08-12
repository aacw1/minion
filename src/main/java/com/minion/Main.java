package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.MinionApp;

public class Main {

    public static void main(String[] args) {
        Config config = Config.load();
        WorkspaceManager workspaces = WorkspaceManager.load(Config.jarDir());
        ModelManager models = ModelManager.load(Config.jarDir());
        MinionApp.start(config, workspaces, models);
    }
}
