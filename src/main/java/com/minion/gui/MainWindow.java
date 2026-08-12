package com.minion.gui;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.sidebar.SessionListView;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/** 主窗口：顶部栏 / 左侧 1/5（上会话下工作空间）/ 右侧 4/5（消息区 + 输入区） */
public class MainWindow {

    private final Stage stage;
    private final SessionManager manager;
    private final TabPane tabs = new TabPane();
    private SessionListView sessionList;

    public MainWindow(Stage stage, SessionManager manager) {
        this.stage = stage;
        this.manager = manager;
    }

    public void show() {
        stage.setTitle("minion");
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");

        // 顶部栏：标识 | 当前模型 | 会话页签区 | ⚙
        HBox topbar = new HBox(10);
        topbar.getStyleClass().add("topbar");
        Label title = new Label("minion");
        title.getStyleClass().add("topbar-title");
        Label modelLabel = new Label("模型: " + manager.models().currentName());
        modelLabel.getStyleClass().add("topbar-model");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        HBox.setHgrow(tabs, Priority.ALWAYS);
        Button gear = new Button("⚙");
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> { }); // Task 13 模型弹窗
        topbar.getChildren().addAll(title, modelLabel, tabs, gear);

        // 左侧 1/5：上会话管理 / 下工作空间管理（Task 12 填充工作空间列表）
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        sidebar.setPrefWidth(220);
        Label sessionTitle = new Label("会话管理");
        sessionTitle.getStyleClass().add("section-title");
        sessionList = new SessionListView(manager,
                h -> { removeTabById(h.id); sessionList.refresh(); });
        VBox.setVgrow(sessionList, Priority.ALWAYS);
        Button newSession = new Button("＋ 新建会话");
        newSession.getStyleClass().add("btn-ghost");
        newSession.setMaxWidth(Double.MAX_VALUE);
        newSession.setOnAction(e -> onNewSession());
        VBox sessionBox = new VBox(6);
        sessionBox.getChildren().addAll(newSession, sessionList);
        VBox.setVgrow(sessionBox, Priority.ALWAYS);
        Label wsTitle = new Label("工作空间");
        wsTitle.getStyleClass().add("section-title");
        Region wsListPlaceholder = new Region();      // Task 12
        sidebar.getChildren().addAll(sessionTitle, sessionBox, wsTitle, wsListPlaceholder);

        // 右侧 4/5：消息区 + 输入区占位（Task 10/11 填充）
        VBox right = new VBox(8);
        right.getStyleClass().add("panel-dark");
        Region chatPlaceholder = new Region();        // Task 10
        VBox.setVgrow(chatPlaceholder, Priority.ALWAYS);
        Region inputPlaceholder = new Region();       // Task 11
        right.getChildren().addAll(chatPlaceholder, inputPlaceholder);

        // 注册 manager 监听（Tab 维护）
        manager.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) {
                Platform.runLater(() -> updateTab(h));
            }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                Platform.runLater(() -> updateTab(h));
                // inputView.onRunningChanged 接线在 Task 11
            }
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> selectTab(h));
                // chatView 重建绑定在 Task 10 Step 8 追加
            }
            @Override public void onWorkspaceChanged() {
                Platform.runLater(() -> { sessionList.refresh(); rebuildTabs(); });
            }
            @Override public void onError(String message) {
                // 消息区未建（Task 10 前）→ 先落控制台；Task 10 改为 chatView 横幅
                System.err.println("[minion] " + message);
            }
        });

        root.setTop(topbar);
        root.setLeft(sidebar);
        root.setCenter(right);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                getClass().getResource("/theme/theme.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    private void onNewSession() {
        SessionHandle h = manager.createSession(null); // titlePending，无页签
        sessionList.refresh(); // createSession 无 Listener 通知，UI 层自行刷新
        manager.activateSession(h);
        // 消息区/输入区绑定由 Task 10/11 在 onSessionActivated 中接线
    }

    private void updateTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h.id.equals(t.getUserData())) {
                t.setText(h.title == null ? "(新会话)" : h.title);
                t.setGraphic(runningIndicator(h));
                return;
            }
        }
        if (h.title != null) addTab(h); // 标题生成后才建页签
    }

    private void addTab(SessionHandle h) {
        if (h.title == null) return;
        Tab t = new Tab(h.title);
        t.setUserData(h.id);
        t.setGraphic(runningIndicator(h));
        t.setClosable(true);
        t.setOnCloseRequest(e -> {
            e.consume();
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除会话「" + h.title + "」？", ButtonType.OK, ButtonType.CANCEL);
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    manager.deleteSession(h);
                    tabs.getTabs().remove(t);
                    sessionList.refresh(); // 页签关闭路径同样联动刷新列表
                }
            });
        });
        tabs.getTabs().add(t);
        tabs.getSelectionModel().select(t);
    }

    /** 按会话 id 移除页签（删除联动；侧栏右键删除回调与页签关闭共用） */
    private void removeTabById(String id) {
        for (Tab t : tabs.getTabs()) {
            if (id.equals(t.getUserData())) {
                tabs.getTabs().remove(t);
                return;
            }
        }
    }

    private void selectTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h.id.equals(t.getUserData())) {
                tabs.getSelectionModel().select(t);
                return;
            }
        }
    }

    private Node runningIndicator(SessionHandle h) {
        Circle dot = new Circle(4);
        dot.getStyleClass().add("status-dot");
        if (h.running) dot.getStyleClass().add("status-dot-running");
        return dot;
    }

    private void rebuildTabs() {
        tabs.getTabs().clear();
        for (SessionHandle h : manager.sessions()) {
            if (h.title != null) addTab(h);
        }
    }
}
