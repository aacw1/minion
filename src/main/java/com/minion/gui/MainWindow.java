package com.minion.gui;

import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.chat.ChatView;
import com.minion.gui.dialog.SettingsDialog;
import com.minion.gui.input.InputView;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.sidebar.SessionListView;
import com.minion.gui.sidebar.WorkspaceListView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Optional;

/** 主窗口：顶部栏 / 左侧 1/5（上会话下工作空间）/ 右侧 4/5（消息区 + 输入区） */
public class MainWindow {

    private final Stage stage;
    private final SessionManager manager;
    private final TabPane tabs = new TabPane();
    private SessionListView sessionList;
    private ChatView chatView;
    private ScrollPane chatScroll;
    private InputView inputView;

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
        gear.setOnAction(e -> {
            SettingsDialog.show(stage, manager.models(), manager, MinionApp.config());
            // 顶部模型名刷新（切换模型后显示新标识）
            modelLabel.setText("模型: " + manager.models().currentName());
        });
        topbar.getChildren().addAll(title, modelLabel, tabs, gear);

        // 左侧 1/5：上会话管理 / 下工作空间管理（Task 12 填充工作空间列表）
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        sidebar.setPrefWidth(220);
        Label sessionTitle = new Label("会话管理");
        sessionTitle.getStyleClass().add("section-title");
        sessionList = new SessionListView(manager,
                h -> {
                    removeTabById(h.id);
                    if (chatView != null && chatView.handle() == h) clearChatPane(); // 删除当前展示会话 → 右侧清空
                    sessionList.refresh();
                });
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
        WorkspaceListView wsList = new WorkspaceListView(manager);
        VBox.setVgrow(wsList, Priority.ALWAYS);
        Button newWs = new Button("＋ 新建工作空间");
        newWs.getStyleClass().add("btn-ghost");
        newWs.setMaxWidth(Double.MAX_VALUE);
        newWs.setOnAction(e -> {
            Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
            d.setTitle("新建工作空间");
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            GridPane g = new GridPane();
            g.setHgap(8); g.setVgap(8); g.setPadding(new Insets(10));
            TextField n = new TextField();
            n.setPromptText("名称");
            TextField wd = new TextField();
            wd.setPromptText("work.dir");
            TextField pm = new TextField();
            pm.setPromptText("project.md（可空）");
            g.addRow(0, new Label("名称:"), n);
            g.addRow(1, new Label("work.dir:"), wd);
            g.addRow(2, new Label("project.md:"), pm);
            d.getDialogPane().setContent(g);
            d.setResultConverter(bt -> {
                if (bt != ButtonType.OK) return null;
                WorkspaceConfig out = new WorkspaceConfig();
                out.workSpaceName = n.getText().trim();
                out.workDir = wd.getText().trim();
                out.projectMd = pm.getText().trim();
                return out;
            });
            Optional<WorkspaceConfig> r = d.showAndWait();
            if (r.isPresent()) {
                if (!manager.addWorkspace(r.get().workSpaceName, r.get().workDir, r.get().projectMd)) {
                    Alert a = new Alert(Alert.AlertType.ERROR, "名称非法或已存在", ButtonType.OK);
                    a.setTitle("新建失败");
                    a.showAndWait();
                }
                wsList.refresh();
            }
        });
        VBox wsBox = new VBox(6);
        wsBox.getChildren().addAll(newWs, wsList);
        VBox.setVgrow(wsBox, Priority.ALWAYS);
        sidebar.getChildren().setAll(sessionTitle, sessionBox, wsTitle, wsBox);

        // 右侧 4/5：消息区（ChatView）+ 输入区占位（Task 11 填充）
        VBox right = new VBox(8);
        right.getStyleClass().add("panel-dark");
        chatScroll = new ScrollPane();
        chatScroll.setFitToWidth(true);
        chatScroll.setContent(new Region()); // 激活会话后换 ChatView
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        setupAutoScroll();
        inputView = new InputView(manager);
        right.getChildren().setAll(chatScroll, inputView);

        // 注册 manager 监听（Tab 维护）
        manager.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) {
                Platform.runLater(() -> updateTab(h));
            }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                Platform.runLater(() -> updateTab(h));
                if (inputView != null) inputView.onRunningChanged(h, running);
            }
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> {
                    selectTab(h);
                    // 每会话一个 ChatView（绑定其 EventList）：重建 + bind(true) 清空后重放存量
                    chatView = ChatView.forSession(h);
                    chatView.bind(true);
                    chatScroll.setContent(chatView);
                    if (inputView != null) inputView.bindSession(h);
                });
            }
            @Override public void onWorkspaceChanged() {
                Platform.runLater(() -> {
                    clearChatPane(); // 需求 16：切换工作空间后右侧清空（先清再刷列表/页签）
                    wsList.refresh();
                    sessionList.refresh();
                    rebuildTabs();
                });
            }
            @Override public void onError(String message) {
                Platform.runLater(() -> {
                    if (chatView != null) chatView.appendSystemLine(message);
                    System.err.println("[minion] " + message);
                });
            }
        });

        root.setTop(topbar);
        root.setLeft(sidebar);
        root.setCenter(right);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                getClass().getResource("/theme/theme.css").toExternalForm());
        stage.setScene(scene);

        stage.setOnCloseRequest(e -> {
            if (!manager.hasRunning()) {
                manager.shutdown();
                return;
            }
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "仍有会话正在运行，确认退出？", ButtonType.OK, ButtonType.CANCEL);
            a.setTitle("退出确认");
            a.showAndWait();
            if (a.getResult() == ButtonType.OK) {
                manager.shutdown();
            } else {
                e.consume(); // 取消关闭
            }
        });

        stage.show();
    }

    /** 清空右侧面板：解绑事件流、回占位提示、解绑输入区（删除会话/切换工作空间后调用） */
    private void clearChatPane() {
        if (chatView != null) {
            chatView.bind(false);  // 分离监听器（EventList 缓冲保留，会话仍在时下次 bind(true) 重放）
            chatView.clear();      // 回「输入消息开始新的会话」占位
        }
        chatView = null;
        if (inputView != null) inputView.bindSession(null); // current=null → 下次发送自动建会话
    }

    /** 需求 10：消息区自动滚动——贴底时随新内容滚到底，离开底部即暂停，拖回底部恢复 */
    private void setupAutoScroll() {
        // pinned：是否贴底——贴底时新内容到达自动跟随滚到底，离开底部即暂停
        final boolean[] pinned = new boolean[] { true };
        chatScroll.vvalueProperty().addListener((obs, ov, nv) ->
                pinned[0] = nv.doubleValue() >= chatScroll.getVmax() - 1.0);
        chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
            if (pinned[0]) chatScroll.setVvalue(nv.doubleValue());
        });
    }

    private void onNewSession() {
        SessionHandle h = manager.createSession(null); // titlePending，无页签
        if (h == null) return; // 终审修复：当前空间删除中（后台 awaitTermination ≤5s）被点击，createSession 返回 null，忽略即可
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
                    if (chatView != null && chatView.handle() == h) clearChatPane();
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
        // 呼吸动画由 StatusDot Timeline 驱动（CSS keyframe JavaFX 8 不支持）
        return StatusDot.create(h.running);
    }

    private void rebuildTabs() {
        for (Tab t : tabs.getTabs()) StatusDot.stopPulseIn(t.getGraphic()); // 回收呼吸动画
        tabs.getTabs().clear();
        for (SessionHandle h : manager.sessions()) {
            if (h.title != null) addTab(h);
        }
    }
}
