package com.minion.gui;

import com.minion.Boot;
import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** JavaFX 入口：静态配置注入 + 主窗口 */
public class MinionApp extends Application {

    private static Config config;
    private static WorkspaceManager workspaces;
    private static ModelManager models;
    private static SessionManager sessionManager;

    /** Main 调用：装配配置后启动 GUI */
    public static void start(Config c, WorkspaceManager w, ModelManager m, SessionManager s) {
        config = c;
        workspaces = w;
        models = m;
        sessionManager = s;
        launch();
    }

    @Override
    public void start(Stage stage) {
        new MainWindow(stage, MinionApp.sessionManager()).show();
        if ("1".equals(System.getProperty(Boot.WARN_PROPERTY))) showJdk8Warn();
    }

    /** 非 JDK8 且找不到 JDK8 的兜底：非模态提示（可关闭，不影响运行） */
    private void showJdk8Warn() {
        String version = System.getProperty("java.version", "?");
        Alert alert = new Alert(AlertType.WARNING,
                "检测到当前运行环境不是 JDK 8（当前版本：" + version
                        + "），且未找到可用的 JDK 8。建议安装并使用 JDK 8（Oracle JDK 8 或 Zulu 8 FX）运行，"
                        + "以获得最佳界面兼容性。此提示不影响使用，可关闭后继续。",
                ButtonType.OK);
        alert.initModality(Modality.NONE);   // 非模态：主界面可继续交互
        Theme.style(alert);                  // 深色样式表挂载（同 SettingsDialog/ConfirmSheet）
        alert.show();
    }

    public static Config config() { return config; }
    public static WorkspaceManager workspaces() { return workspaces; }
    public static ModelManager models() { return models; }
    public static SessionManager sessionManager() { return sessionManager; }
}
