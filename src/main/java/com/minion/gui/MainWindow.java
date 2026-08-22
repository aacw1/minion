package com.minion.gui;

import com.minion.core.config.ModelConfig;
import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.chat.ChatView;
import com.minion.gui.dialog.ConfirmSheet;
import com.minion.gui.dialog.SettingsDialog;
import com.minion.gui.input.InputView;
import com.minion.gui.session.AutoScrollPolicy;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.sidebar.SessionListView;
import com.minion.gui.sidebar.WorkspaceListView;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 主窗口：自绘标题栏（无边框）/ 左侧 1/4 侧栏（上会话下工作空间）/ 右侧 3/4（页签栏 + 消息区 + 输入区），GridPane 固定 25%/75% 不可拖拽 */
public class MainWindow {

    private final Stage stage;
    private final SessionManager manager;
    private final TabPane tabs = new TabPane();
    private SessionListView sessionList;
    private ChatView chatView;
    private ScrollPane chatScroll;
    private final AutoScrollPolicy policy = new AutoScrollPolicy();
    /** 会话视图缓存（key = 会话 id）：切页签不重建消息区，只增量重放新事件；删除会话/切工作空间清理 */
    private final Map<String, ChatView> viewCache = new HashMap<String, ChatView>();
    /** 无活动会话时的空白占位（clearChatPane 后 setContent 用；视图内容保留在缓存中不销毁） */
    private final Region placeholder = new Region();
    private InputView inputView;
    /** 运行状态指示器（正文区左下角悬浮齿轮+动态文案；仅当前激活会话运行时显示） */
    private RunningIndicator runningIndicator;
    private TitleBar titleBar; // 自绘标题栏（openSettings 刷新顶部模型名用）
    private HBox tabsBar; // 右侧顶部页签栏（无会话时整行隐藏）
    /** 已打开会话 id 集合：页签存在性的唯一权威（页签 ⇔ openedIds 含 id）；切工作空间保留 */
    private final Set<String> openedIds = new HashSet<String>();

    public MainWindow(Stage stage, SessionManager manager) {
        this.stage = stage;
        this.manager = manager;
    }

    public void show() {
        stage.setTitle("minion");
        stage.initStyle(StageStyle.UNDECORATED); // 需求 1：隐藏系统标题栏
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        // 初始尺寸钳制到屏幕可视范围：无边框窗口没有系统窗口管理器的「缩到屏内」钳制，
        // 而窗口按场景首选尺寸打开（侧栏两个 ListView 各 ~400px，总高常超 900px），
        // 小屏上会把顶部标题栏和底部输入框顶出屏幕；故首次显示前显式设尺寸
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1280, vb.getWidth() - 40));
        stage.setHeight(Math.min(760, vb.getHeight() - 40));
        stage.setMinWidth(Math.min(960, vb.getWidth() - 40));
        stage.setMinHeight(Math.min(640, vb.getHeight() - 40));

        // 根容器：AnchorPane 承载内容 + 8 个缩放区域（ResizeHelper 覆盖在边缘）
        AnchorPane frame = new AnchorPane();
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        AnchorPane.setTopAnchor(root, 0.0);
        AnchorPane.setBottomAnchor(root, 0.0);
        AnchorPane.setLeftAnchor(root, 0.0);
        AnchorPane.setRightAnchor(root, 0.0);
        frame.getChildren().add(root);

        // 自绘标题栏：应用名 | 模型标签 | 留白 | ⚙ | 窗口按钮
        Label modelLabel = new Label(modelLabelText());
        modelLabel.getStyleClass().add("topbar-model");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        // 需求：标题栏页签点击激活对应会话（userData 存会话 id）；跨工作空间的页签点击先自动切回该空间
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv == null) return;
            Object id = nv.getUserData();
            if (id == null) return;
            // JavaFX 8 幽灵选中防御：TabPaneSkin 移除页签时执行 clearSelection()+select(旧选中项)，
            // 旧选中项已不在 tabs 列表 → SingleSelectionModel 走 setSelectedItem 分支产生幽灵选中，
            // 若不拦截会 activateSession 已关闭会话 → onSessionActivated 复活页签（点 X 关不掉）
            if (!tabs.getTabs().contains(nv)) return;
            SessionHandle h = manager.findSession((String) id); // 跨所有工作空间查找
            if (h == null) return; // 已删会话的残留页签（理论上 onSessionDeleted 已清理）：忽略
            if (!manager.workspaces().currentName().equals(h.workspaceName)) {
                manager.switchWorkspace(h.workspaceName); // 页签与工作空间无关：自动切回所属空间
            }
            manager.activateSession(h); // deleted/空间守卫由 activateSession 内部处理
        });
        titleBar = new TitleBar(stage, modelLabel, this::openSettings, this::confirmClose);
        root.setTop(titleBar);

        // 左侧 1/4 侧栏（会话/工作空间）+ 右侧 3/4（消息区 + 输入区）→ GridPane，百分比列固定 25%/75%
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        sessionList = new SessionListView(manager,
                h -> {
                    removeTabById(h.id);
                    openedIds.remove(h.id); // 删除会话：打开标记一并清除
                    viewCache.remove(h.id); // 删除会话：缓存视图一并释放
                    if (chatView != null && chatView.handle() == h) clearChatPane();
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
        final WorkspaceListView wsList = new WorkspaceListView(manager);
        VBox.setVgrow(wsList, Priority.ALWAYS);
        Button newWs = new Button("＋ 新建工作空间");
        newWs.getStyleClass().add("btn-ghost");
        newWs.setMaxWidth(Double.MAX_VALUE);
        newWs.setOnAction(e -> onNewWorkspace(wsList));
        VBox wsBox = new VBox(6);
        wsBox.getChildren().addAll(newWs, wsList);
        VBox.setVgrow(wsBox, Priority.ALWAYS);
        // VBox(8) 间距自然分隔上下两区，不加分隔线
        sidebar.getChildren().setAll(sessionBox, wsBox);
        // 需求：会话/工作空间高度固定黄金比例 0.618:0.382（VBox 无权重 API，监听高度动态设 prefHeight 实现）
        sidebar.heightProperty().addListener((obs, ov, nv) -> {
            double h = Math.max(0, nv.doubleValue() - sidebar.getSpacing()); // 扣除两区间距
            double sessionH = h * 0.618;
            sessionBox.setPrefHeight(sessionH);
            wsBox.setPrefHeight(h - sessionH);
        });

        // 右侧：页签栏（会话 Tab）+ 消息区（ChatView）+ 输入区
        VBox right = new VBox(8);
        right.getStyleClass().add("panel-dark");
        chatScroll = new ScrollPane();
        chatScroll.setFitToWidth(true);
        chatScroll.setFitToHeight(true); // 消息区铺满正文窗口：内容少时 ChatView 拉伸到视口高（背景 #121314 铺满）
        chatScroll.setPrefHeight(200); // 固定 pref：否则 prefHeight 随消息内容增长，挤压右侧 VBox 把页签行压扁（探针验证）
        chatScroll.setContent(placeholder); // 激活会话后换 ChatView
        setupAutoScroll();
        WheelScrollAccelerator.attach(chatScroll); // 滚轮加速：每格固定像素滚动（替换 JavaFX 8 默认慢速比例滚动）
        inputView = new InputView(manager, MinionApp.config());
        // 运行状态指示器：正文区左下角悬浮（齿轮+动态文案），StackPane 叠加于消息区之上，不占布局
        runningIndicator = new RunningIndicator();
        StackPane chatHost = new StackPane(chatScroll, runningIndicator);
        StackPane.setAlignment(runningIndicator, Pos.BOTTOM_LEFT);
        StackPane.setMargin(runningIndicator, new Insets(0, 0, 10, 14));
        // JavaFX 8 无 StackPane.setFillWidth/Height（12+ 才有）：用 max 钳制防默认 fill 拉伸铺满
        runningIndicator.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        VBox.setVgrow(chatHost, Priority.ALWAYS); // 原 chatScroll 的 vgrow 约束移交 chatHost
        // 页签栏（右侧顶部，下带 1px 分隔线；页签为空时整行隐藏）
        tabsBar = new HBox(tabs);
        tabsBar.getStyleClass().add("tabs-bar");
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> {
            boolean empty = tabs.getTabs().isEmpty();
            tabsBar.setVisible(!empty);
            tabsBar.setManaged(!empty);
        });
        // 启动时无页签（懒加载）；注册监听后立即同步一次初始可见性
        tabsBar.setVisible(!tabs.getTabs().isEmpty());
        tabsBar.setManaged(!tabs.getTabs().isEmpty());
        right.getChildren().setAll(tabsBar, chatHost, inputView);

        // 右侧面板外包 StackPane：ConfirmSheet 遮罩与卡片挂其顶层（遮罩范围即右侧，不越分隔线）
        StackPane rightStack = new StackPane(right);
        ConfirmSheet.setHost(rightStack);
        // 工具提问弹窗显示期间隐藏运行状态指示器（防"等待用户操作"被误判为卡死），关闭后恢复
        ConfirmSheet.setVisibilityListener(showing -> Platform.runLater(() -> {
            if (showing) runningIndicator.suspend(); else runningIndicator.resume();
        }));

        // 需求：左右无分隔线、不可拖拽，侧栏严格占整体 1/4（GridPane 百分比列随窗口缩放）
        GridPane center = new GridPane();
        ColumnConstraints leftCol = new ColumnConstraints();
        leftCol.setPercentWidth(25);
        ColumnConstraints rightCol = new ColumnConstraints();
        rightCol.setPercentWidth(75);
        center.getColumnConstraints().addAll(leftCol, rightCol);
        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS); // 行撑满可用空间：chatScroll pref 固定后防底部留白
        center.getRowConstraints().add(row);
        center.add(sidebar, 0, 0);
        center.add(rightStack, 1, 0); // 右侧为 StackPane 宿主（ConfirmSheet 遮罩挂顶层，不越分隔线）
        root.setCenter(center);

        // 注册 manager 监听（Tab 维护；内容与 Task 5 一致，含 clearChatPane）
        manager.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) {
                Platform.runLater(() -> {
                    updateTab(h);
                    sessionList.refresh(); // 重命名后侧栏列表同步新标题（refresh 仅重建 cell，不触发回调，无通知循环）
                });
            }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                Platform.runLater(() -> updateTab(h));
                if (inputView != null) inputView.onRunningChanged(h, running);
                // 仅当前激活会话反映到指示器（chatView 字段 FX 线程读写，统一 runLater 访问）
                Platform.runLater(() -> {
                    if (chatView != null && chatView.handle() == h) {
                        runningIndicator.setRunning(running);
                        runningIndicator.setCompressing(false); // 运行结束复位压缩态
                    }
                });
            }
            @Override public void onCompressingChanged(SessionHandle h, boolean compressing) {
                Platform.runLater(() -> {
                    if (chatView != null && chatView.handle() == h) { // 仅当前激活会话
                        runningIndicator.setCompressing(compressing);
                    }
                });
            }
            @Override public void onSessionAskChanged(SessionHandle h, boolean asking, String question) {
                if (inputView != null) inputView.onAskChanged(h, asking, question);
            }
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> {
                    if (!openedIds.contains(h.id)) {
                        openedIds.add(h.id); // 打开过的会话才有页签（懒加载）；titlePending 等标题生成后由 updateTab 补建
                        addTab(h); // addTab 内部有 title 非空守卫
                    }
                    selectTab(h);
                    if (chatView != null) chatView.rememberVvalue(chatScroll.getVvalue()); // 切走前记滚动位置
                    ChatView cached = viewCache.get(h.id);
                    if (cached != null) {
                        // 缓存命中：不重建消息区，只增量重放上次绑定后的新事件（毫秒级），恢复滚动位置
                        chatView = cached;
                        chatView.bind(true);
                        chatScroll.setContent(chatView);
                        chatScroll.setVvalue(chatView.savedVvalue());
                        if (inputView != null) inputView.bindSession(h);
                        sessionList.refresh(); // 激活即刷新该会话相对时间（不停留切换前旧值）
                        runningIndicator.setRunning(h.running); // 切会话：按新会话运行态刷新指示器
                        runningIndicator.setCompressing(false); // 切会话复位压缩态（压缩事件按会话隔离）
                        return;
                    }
                    chatView = ChatView.forSession(h);
                    viewCache.put(h.id, chatView);
                    chatView.setScrollBottomRequest(() -> {
                        policy.forceFollow();
                        Platform.runLater(() -> chatScroll.setVvalue(1.0)); // 布局完成后置底
                    });
                    // 截断保活补偿：头部段被删 → 内容变矮，vvalue（归一化）不变时视口绝对位置下移。
                    // 双 runLater 等布局跑完再读高度（layoutBounds 是布局缓存，删除后未布局仍返回旧值）；
                    // 贴底（v=1）时公式自然收敛 1.0，无需分支。
                    chatView.setTrimListener(removedH -> Platform.runLater(() -> Platform.runLater(() -> {
                        Node content = chatScroll.getContent();
                        double viewport = chatScroll.getViewportBounds().getHeight();
                        double hNow = content != null ? content.getLayoutBounds().getHeight() : 0;
                        double hOld = hNow + removedH;              // 截断前内容高度
                        double scrollableOld = hOld - viewport;     // 截断前可滚动行程
                        double scrollableNew = hNow - viewport;     // 截断后可滚动行程
                        if (scrollableOld <= 0 || scrollableNew <= 0) return;
                        double v = chatScroll.getVvalue();
                        double v2 = v * scrollableOld / scrollableNew; // 视口顶部绝对位置守恒
                        chatScroll.setVvalue(Math.max(0.0, Math.min(1.0, v2)));
                    })));
                    chatView.bind(true);
                    chatScroll.setContent(chatView);
                    if (inputView != null) inputView.bindSession(h);
                    sessionList.refresh(); // 激活即刷新该会话相对时间（不停留切换前旧值）
                    runningIndicator.setRunning(h.running); // 切会话：按新会话运行态刷新指示器
                    runningIndicator.setCompressing(false); // 切会话复位压缩态（压缩事件按会话隔离）
                });
            }
            @Override public void onWorkspaceChanged() {
                Platform.runLater(() -> {
                    clearChatPane();
                    viewCache.clear(); // 工作空间切换：不跨空间残留缓存视图；openedIds 保留（页签与空间无关）
                    wsList.refresh();
                    sessionList.refresh();
                });
            }
            @Override public void onSessionDeleted(SessionHandle h) {
                Platform.runLater(() -> {
                    removeTabById(h.id);
                    openedIds.remove(h.id);
                    viewCache.remove(h.id); // 死页签清理（幂等：与 SessionListView.onDeleted 回调共存）
                    if (chatView != null && chatView.handle() == h) clearChatPane();
                });
            }
            @Override public void onError(String message) {
                Platform.runLater(() -> {
                    if (chatView != null) chatView.appendSystemLine(message);
                    System.err.println("[minion] " + message);
                });
            }
        });

        Scene scene = new Scene(frame);
        scene.getStylesheets().add(
                getClass().getResource("/theme/theme.css").toExternalForm());
        stage.setScene(scene);

        // 系统关闭事件（Alt+F4/任务栏关闭）与自绘 ✕ 共用 confirmClose
        stage.setOnCloseRequest(e -> {
            e.consume(); // 统一走 confirmClose（stage.close() 不触发 onCloseRequest，须自行 close）
            confirmClose();
        });

        ResizeHelper.attach(stage, frame); // 无边框窗口边缘/四角缩放

        stage.show();
    }

    /** 顶部模型标签文本：显示当前模型的模型名（modelName），缺失时回退标识名（displayName） */
    private String modelLabelText() {
        ModelConfig c = manager.models().current();
        String name = (c != null && c.modelName != null && !c.modelName.trim().isEmpty())
                ? c.modelName.trim() : manager.models().currentName();
        return "模型: " + name;
    }

    /** 右上角 ⚙：打开设置窗，关闭后刷新顶部模型名（TitleBar.modelLabel() 持有引用） */
    private void openSettings() {
        SettingsDialog.show(stage, manager.models(), manager, MinionApp.config(),
                manager.mcpManager());
        if (titleBar != null) {
            titleBar.modelLabel().setText(modelLabelText());
        }
    }

    /**
     * 关闭确认（自绘 ✕ 按钮与 stage.setOnCloseRequest 共用）：
     * 无运行中会话直接退出；有则弹确认再退。stage.close() 不触发 onCloseRequest，须自行调用。
     */
    private void confirmClose() {
        if (!manager.hasRunning()) {
            manager.shutdown();
            stage.close();
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "仍有会话正在运行，确认退出？", ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);                 // 去掉左边的"确认"文字（header 行移除 → 弹窗高度减一行）
        a.getDialogPane().setGraphic(null);    // 去掉叹号圆圈图标
        a.getDialogPane().getStyleClass().add("dialog-exit"); // 正文居中
        Theme.style(a);
        a.setTitle("退出确认");
        a.showAndWait();
        if (a.getResult() == ButtonType.OK) {
            manager.shutdown();
            stage.close();
        }
    }

    /** 清空右侧面板：解绑事件流、回空白占位、解绑输入区（删除会话/切换工作空间后调用）。
     *  视图内容保留在缓存中（下次激活增量重放），不 clear 销毁——销毁由 viewCache 清理（删除/切空间）连带 GC。 */
    private void clearChatPane() {
        if (chatView != null) chatView.bind(false); // 分离监听器（EventList 缓冲保留，视图缓存保留）
        chatView = null;
        chatScroll.setContent(placeholder); // 无活动会话：空白占位
        if (inputView != null) inputView.bindSession(null); // current=null → 下次发送自动建会话
        runningIndicator.setRunning(false); // 无活动会话：指示器隐藏并停动画
        runningIndicator.setCompressing(false);
    }

    /** 需求：消息区自动滚动——贴底时随新内容滚到底，离开底部即暂停，拖回底部恢复。
     *  vmax 恒 1.0 不可用（vvalue 为归一化比例），内容增长改监听内容节点高度变化；
     *  会话切换时内容节点被替换，须随 contentProperty 重挂监听 */
    private void setupAutoScroll() {
        javafx.beans.value.ChangeListener<javafx.geometry.Bounds> contentGrew = (obs, o, n) -> {
            if (policy.shouldFollow()) {
                Platform.runLater(() -> { // 布局完成后置底；二次确认防监听时旧状态
                    if (policy.shouldFollow()) chatScroll.setVvalue(1.0);
                });
            }
        };
        chatScroll.vvalueProperty().addListener((obs, ov, nv) ->
                policy.sync(nv.doubleValue(), eps()));
        chatScroll.contentProperty().addListener((obs, ov, nv) -> {
            if (ov != null) ov.layoutBoundsProperty().removeListener(contentGrew);
            if (nv != null) nv.layoutBoundsProperty().addListener(contentGrew);
        });
    }

    /** 动态半屏容差（归一化）：0.5×视口高/可滚动行程；未超一屏返回 1.0（恒贴底） */
    private double eps() {
        double viewport = chatScroll.getViewportBounds().getHeight();
        double content = chatScroll.getContent() != null
                ? chatScroll.getContent().getLayoutBounds().getHeight() : 0;
        double scrollable = content - viewport;
        return scrollable <= 0 ? 1.0 : 0.5 * viewport / scrollable;
    }

    private void onNewSession() {
        SessionHandle h = manager.createSession(null); // titlePending，无页签
        if (h == null) return; // 终审修复：当前空间删除中（后台 awaitTermination ≤5s）被点击，createSession 返回 null，忽略即可
        sessionList.refresh(); // createSession 无 Listener 通知，UI 层自行刷新
        manager.activateSession(h);
        // 会话激活后，onSessionActivated 回调在 show() 注册的监听器中绑定消息区与输入区
    }

    /** 新建工作空间弹窗（Task 9 从 show() 抽取）：work.dir 支持系统文件夹选择框 */
    private void onNewWorkspace(WorkspaceListView wsList) {
        Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
        d.setTitle("新建工作空间");
        Theme.style(d);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(8); g.setPadding(new Insets(10));
        TextField n = new TextField();
        n.setPromptText("名称");
        HBox wdBox = new HBox(6);
        TextField wd = new TextField();
        wd.setPromptText("work.dir");
        HBox.setHgrow(wd, Priority.ALWAYS);
        Button browse = new Button("浏览…");
        browse.getStyleClass().add("btn-ghost");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            java.io.File dir = dc.showDialog(d.getOwner());
            if (dir != null) wd.setText(dir.getAbsolutePath());
        });
        wdBox.getChildren().addAll(wd, browse);
        HBox pmBox = new HBox(6);
        TextField pm = new TextField();
        pm.setPromptText("project.md（可空）");
        HBox.setHgrow(pm, Priority.ALWAYS);
        Button pmBrowse = new Button("浏览…");
        pmBrowse.getStyleClass().add("btn-ghost");
        pmBrowse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("选择 project.md");
            fc.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"));
            java.io.File file = fc.showOpenDialog(d.getOwner());
            if (file != null) pm.setText(file.getAbsolutePath());
        });
        pmBox.getChildren().addAll(pm, pmBrowse);
        g.addRow(0, new Label("名称:"), n);
        g.addRow(1, new Label("work.dir:"), wdBox);
        g.addRow(2, new Label("project.md:"), pmBox);
        // 名称预校验（同 WorkspaceManager.isValidName：非空/无非法字符/不重名）：非法时 OK 禁用 + 行内红字提示，
        // 不再"先提交后弹错"。existing 为打开弹窗时的名称快照（模态弹窗期间 UI 无法改列表，快照足够）
        final java.util.List<String> existing = new java.util.ArrayList<String>();
        for (WorkspaceConfig w : manager.workspaces().list()) existing.add(w.workSpaceName);
        Label nameErr = new Label();
        nameErr.getStyleClass().add("log-error"); // 红字提示（theme.css 已挂载）
        nameErr.setVisible(false);
        g.add(nameErr, 1, 3);
        d.getDialogPane().setContent(g);
        Button okBtn = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !com.minion.core.config.WorkspaceManager.isValidName(n.getText().trim(), existing),
                n.textProperty()));
        n.textProperty().addListener((obs, ov, nv) -> nameErr.setVisible(false)); // 继续编辑即清旧提示
        // 兜底：校验不过不关弹窗（正常路径 OK 已禁用，此处防绕过与快照过期竞态）
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!com.minion.core.config.WorkspaceManager.isValidName(n.getText().trim(), existing)) {
                e.consume();
                nameErr.setText("名称非法或已存在");
                nameErr.setVisible(true);
            }
        });
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
                Theme.style(a);
                a.setTitle("新建失败");
                a.showAndWait();
            }
            wsList.refresh();
        }
    }

    private static final int TAB_TITLE_MAX = 16; // 页签标题截取长度（过长撑宽页签栏致标题栏错乱）

    /** 页签标题：超 16 字符截断加省略号（完整标题挂 Tooltip，信息不丢失） */
    static String tabTitle(String title) {
        if (title != null && title.length() > TAB_TITLE_MAX) {
            return title.substring(0, TAB_TITLE_MAX) + "…";
        }
        return title;
    }

    private void updateTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h.id.equals(t.getUserData())) {
                t.setText(tabTitle(h.title == null ? "(新会话)" : h.title));
                t.setTooltip(new Tooltip(h.title == null ? "" : h.title)); // 完整标题提示
                t.setGraphic(runningIndicator(h));
                return;
            }
        }
        if (h.title != null && openedIds.contains(h.id)) addTab(h); // 标题生成后才建页签；已关闭页签的不复活
    }

    private void addTab(SessionHandle h) {
        if (h.title == null) return;
        Tab t = new Tab(tabTitle(h.title));
        t.setUserData(h.id);
        t.setTooltip(new Tooltip(h.title)); // 完整标题提示
        t.setGraphic(runningIndicator(h));
        t.setClosable(true);
        t.setOnCloseRequest(e -> {
            e.consume(); // 不触发 TabPane 默认移除，由本流程显式移除
            if (h.running) {
                // 运行中：确认才关（关闭 = 中断运行，会话不删除）
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "会话「" + h.title + "」正在运行，关闭将中断运行，确认关闭？",
                        ButtonType.OK, ButtonType.CANCEL);
                a.setHeaderText(null);                 // 去掉左边的"确认"文字
                a.getDialogPane().setGraphic(null);    // 去掉叹号圆圈图标
                a.getDialogPane().getStyleClass().add("dialog-exit"); // 正文居中
                Theme.style(a);
                a.setTitle("关闭会话");
                a.showAndWait();
                if (a.getResult() != ButtonType.OK) return; // 取消：页签保留、运行继续
                manager.stop(h); // 中断运行（会话不删除，保留在列表）
            }
            // 关闭流程：卸载视图缓存（再次从左侧打开时重新加载）+ 移除页签
            openedIds.remove(h.id);
            viewCache.remove(h.id);
            tabs.getTabs().remove(t);
            if (chatView != null && chatView.handle() == h) {
                manager.deactivateSession(h); // 幂等守卫不再拦截再次激活
                clearChatPane();
            }
        });
        tabs.getTabs().add(t);
        tabs.getSelectionModel().select(t);
    }

    /** 按会话 id 移除页签（删除联动；侧栏删除按钮回调与页签关闭共用） */
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
}
