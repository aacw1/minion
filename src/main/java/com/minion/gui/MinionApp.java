package com.minion.gui;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import javafx.application.Application;
import javafx.stage.Stage;

/** JavaFX 入口：静态配置注入 + 主窗口 */
public class MinionApp extends Application {

    private static Config config;
    private static WorkspaceManager workspaces;
    private static ModelManager models;

    /** Main 调用：装配配置后启动 GUI */
    public static void start(Config c, WorkspaceManager w, ModelManager m) {
        config = c;
        workspaces = w;
        models = m;
        launch();
    }

    @Override
    public void start(Stage stage) {
        new MainWindow(stage).show();
    }

    public static Config config() { return config; }
    public static WorkspaceManager workspaces() { return workspaces; }
    public static ModelManager models() { return models; }
}
