# minion UI 修复（第二轮）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 5 组 UI 问题：侧栏字体看不清、工作空间拖拽失效与按钮合并、会话时间不显示、自动滚动竞态、设置窗左列导航与基础设置选项不显示。

**Architecture:** 全部为 GUI 层改动（theme.css + gui/sidebar + gui/dialog + MainWindow），纯逻辑类（AutoScrollPolicy/TimeFormatter/WorkspaceManager.move）不动，现有单测继续通过；无新增单测（CSS/接线类修复以编译 + 手工清单验证）。

**Tech Stack:** Java 8 + JavaFX 8（JDK 自带 jfxrt）+ Maven 单模块；构建 `JAVA_HOME="E:/javame/jdk8" mvn clean package`。

## Global Constraints

- JDK 8 兼容；资源目录是 `src/resource`（非 src/main/resources），CSS 位于 `src/resource/theme/theme.css`。
- 新代码落位：界面 → gui 包（sidebar/dialog）；跨线程回调一律 Platform.runLater 包装。
- 文档、注释、commit 均用中文；commit 用 conventional 格式（fix:/feat:/docs:）。
- JavaFX 8 无 CSS keyframe（动画用 Timeline）；TabPane `Side.LEFT` 文字旋转 90° 不可用；DragEvent 用 `setDropCompleted`（无 setDropHandled）。
- 完成前自查：`JAVA_HOME="E:/javame/jdk8" mvn compile` + `mvn test` 通过；改动同步 README 与 ARCHITECTURE。

---

### Task 1: 侧栏字体颜色（.cell-text 样式类）

**Files:**
- Modify: `src/resource/theme/theme.css`（`.cell-time` 规则后新增）
- Modify: `src/main/java/com/minion/gui/sidebar/SessionListView.java`（约 74 行）
- Modify: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java`（约 73 行）

**Interfaces:**
- Consumes: 无
- Produces: CSS 样式类 `.cell-text`（供 Task 1 之后的视觉验收使用）

- [ ] **Step 1: theme.css 新增 `.cell-text` 规则**

在 `.cell-time` 规则（现为 `-fx-text-fill: #7a828e`）之后插入：

```css
/* 侧栏列表项名称文字：graphic 内 Label 不响应 .list-cell 的 -fx-text-fill（继承默认黑色），须显式上色 */
.cell-text { -fx-text-fill: #d3d7de; }
.list-view .list-cell:selected .cell-text { -fx-text-fill: #f0f2f6; }
```

注意：**不要**用 `.list-view .list-cell .label` 后代选择器——其 CSS 特异性 (0,0,2,1) 高于 `.section-title` (0,0,1,0)，会把会话摘要行颜色一并覆盖。

- [ ] **Step 2: SessionListView 名称 Label 加样式类**

`SessionCell.updateItem` 中：

```java
Label name = new Label(label);
```

改为：

```java
Label name = new Label(label);
name.getStyleClass().add("cell-text"); // 显式上色：graphic 内 Label 不响应 .list-cell 的 -fx-text-fill
```

- [ ] **Step 3: WorkspaceListView 名称 Label 加样式类**

`WsCell.updateItem` 中：

```java
Label nameLabel = new Label(name + (name.equals(workspaces.currentName()) ? "  ●" : ""));
```

改为：

```java
Label nameLabel = new Label(name + (name.equals(workspaces.currentName()) ? "  ●" : ""));
nameLabel.getStyleClass().add("cell-text");
```

- [ ] **Step 4: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS，现有测试全过。

- [ ] **Step 5: 提交**

```bash
git add src/resource/theme/theme.css src/main/java/com/minion/gui/sidebar/SessionListView.java src/main/java/com/minion/gui/sidebar/WorkspaceListView.java
git commit -m "fix: 侧栏会话/工作空间名称显式上色（cell-text 样式类），修复黑底黑字看不清"
```

### Task 2: 工作空间拖拽修复（onDragOver 条件反转）

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java:110-114`

**Interfaces:**
- Consumes: 无（依赖已有 `manager.moveWorkspace(name, newIndex)`，签名 `boolean moveWorkspace(String name, int newIndex)`）
- Produces: 可用的工作空间拖拽排序（Task 3 不改此链路）

- [ ] **Step 1: 反转 onDragOver 的跳过条件**

`WsCell.updateItem` 中现有代码（条件写反：拖拽源永远不是目标 cell，导致所有目标一律 return 拒绝 drop，拖拽从未生效）：

```java
setOnDragOver(e -> {
    if (e.getGestureSource() != this) return;
    e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
    e.consume();
});
```

改为：

```java
setOnDragOver(e -> {
    if (e.getGestureSource() == this) return; // 仅跳过拖起源自身（原条件写反导致所有目标拒绝 drop）
    e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
    e.consume();
});
```

其余拖拽链路（dragDetected 携带名称 / drop 调 moveWorkspace + refresh / workspace.json 顺序持久化）已正确，不动。

- [ ] **Step 2: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/minion/gui/sidebar/WorkspaceListView.java
git commit -m "fix: 工作空间拖拽排序 onDragOver 条件反转，修复拖拽从未生效"
```

### Task 3: 工作空间按钮合并（✎ 重命名并入 ⚙ 修改弹窗）

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java`

**Interfaces:**
- Consumes: `manager.renameWorkspace(String oldName, String newName)`（false=新名非法/重名，内部含目录迁移 + notifyWorkspaceChanged 刷新列表）；`manager.updateWorkspace(String name, String workDir, String projectMd)`
- Produces: 工作空间悬停仅 ⚙/✕ 两按钮；修改弹窗含名称字段（改名+查重）

- [ ] **Step 1: 删除悬停 renameBtn**

`WsCell.updateItem` 中删除以下三行（✎ 按钮创建）：

```java
Button renameBtn = new Button("✎");
renameBtn.getStyleClass().add("btn-cell");
renameBtn.setTooltip(new Tooltip("重命名"));
renameBtn.setOnAction(e -> doRename(name));
```

按钮组改为：

```java
btns.getChildren().addAll(editBtn, delBtn);
```

- [ ] **Step 2: 删除 doRename 方法**

删除整个 `doRename(String oldName)` 方法（原 127-138 行，逻辑并入 doEdit）。

- [ ] **Step 3: 删除 TextInputDialog 导入**

删除 `import javafx.scene.control.TextInputDialog;`（本文件已无引用）。

- [ ] **Step 4: doEdit 弹窗加名称字段与重命名逻辑**

`doEdit` 整体替换为：

```java
/** 修改：名称（可重命名，重复名被拒）/ workDir / projectMd 可改（重命名并入本弹窗，取消独立 ✎ 按钮） */
private void doEdit(String name) {
    WorkspaceConfig w = workspaces.get(name);
    Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
    d.setTitle("修改工作空间");
    d.setHeaderText("工作空间「" + name + "」（重命名会同步迁移会话目录；work.dir/project.md 修改对新会话生效）");
    Theme.style(d); // 弹窗深色
    d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(8);
    grid.setPadding(new Insets(10));
    TextField nameField = new TextField(name);
    HBox.setHgrow(nameField, Priority.ALWAYS);
    HBox workDirBox = new HBox(6);
    TextField workDir = new TextField(w.workDir);
    HBox.setHgrow(workDir, Priority.ALWAYS);
    Button browse = new Button("浏览…");
    browse.getStyleClass().add("btn-ghost");
    browse.setOnAction(e -> {
        DirectoryChooser dc = new DirectoryChooser();
        String cur = workDir.getText().trim();
        if (!cur.isEmpty()) {
            java.io.File f = new java.io.File(cur);
            if (f.isDirectory()) dc.setInitialDirectory(f);
        }
        java.io.File dir = dc.showDialog(d.getOwner());
        if (dir != null) workDir.setText(dir.getAbsolutePath());
    });
    workDirBox.getChildren().addAll(workDir, browse);
    HBox pmBox = new HBox(6);
    TextField projectMd = new TextField(w.projectMd == null ? "" : w.projectMd);
    HBox.setHgrow(projectMd, Priority.ALWAYS);
    Button pmBrowse = new Button("浏览…");
    pmBrowse.getStyleClass().add("btn-ghost");
    pmBrowse.setOnAction(e -> {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("选择 project.md");
        fc.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"));
        String cur = projectMd.getText().trim();
        if (!cur.isEmpty()) {
            java.io.File f = new java.io.File(cur);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                fc.setInitialDirectory(f.getParentFile());
            }
        }
        java.io.File file = fc.showOpenDialog(d.getOwner());
        if (file != null) projectMd.setText(file.getAbsolutePath());
    });
    pmBox.getChildren().addAll(projectMd, pmBrowse);
    grid.addRow(0, new Label("名称:"), nameField);
    grid.addRow(1, new Label("work.dir:"), workDirBox);
    grid.addRow(2, new Label("project.md:"), pmBox);
    d.getDialogPane().setContent(grid);

    d.setResultConverter(bt -> {
        if (bt != ButtonType.OK) return null;
        WorkspaceConfig out = new WorkspaceConfig();
        out.workSpaceName = nameField.getText().trim();
        out.workDir = workDir.getText().trim();
        out.projectMd = projectMd.getText().trim();
        return out;
    });
    Optional<WorkspaceConfig> result = d.showAndWait();
    if (!result.isPresent()) return;
    String newName = result.get().workSpaceName;
    if (!newName.equals(name)) {
        // 重命名：renameWorkspace 校验非法/重名，false 中止（目录迁移与列表刷新由其通知完成）
        if (!manager.renameWorkspace(name, newName)) {
            error("重命名失败", "名称非法或已存在");
            return;
        }
    }
    manager.updateWorkspace(newName, result.get().workDir, result.get().projectMd);
}
```

- [ ] **Step 5: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS（无 TextInputDialog 未使用告警报错即可，编译通过为硬指标）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/minion/gui/sidebar/WorkspaceListView.java
git commit -m "feat: 工作空间重命名并入修改弹窗（悬停按钮减为 ⚙/✕，改名查重失败弹错）"
```

### Task 4: 会话时间显示（提亮 + 刷新时机）

**Files:**
- Modify: `src/resource/theme/theme.css`（`.cell-time` 颜色）
- Modify: `src/main/java/com/minion/gui/sidebar/SessionListView.java`（构造器加 60 秒 Timeline）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（onSessionActivated 加 refresh）

**Interfaces:**
- Consumes: 无（时间不进 LLM 上下文已验证：Message.toApiJson 不输出 ts）
- Produces: 会话相对时间可见且周期刷新

- [ ] **Step 1: `.cell-time` 提亮**

theme.css 中：

```css
.cell-time { -fx-text-fill: #7a828e; -fx-font-size: 11px; }
```

改为：

```css
.cell-time { -fx-text-fill: #98a0ab; -fx-font-size: 11px; } /* 原 #7a828e 过暗看不清 */
```

- [ ] **Step 2: SessionListView 加 60 秒周期刷新**

构造器中 `setCellFactory(v -> new SessionCell());` 之后插入：

```java
// 相对时间周期刷新（60 秒）：5m/3h/2d 不停留初始值；Timeline 运行于 FX 线程，随应用退出自然停止
javafx.animation.Timeline clock = new javafx.animation.Timeline(
        new javafx.animation.KeyFrame(javafx.util.Duration.minutes(1), e -> refresh()));
clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
clock.play();
```

- [ ] **Step 3: MainWindow 激活会话时刷新列表**

`onSessionActivated` 的 `Platform.runLater` 内，`inputView.bindSession(h)` 之后加一行：

```java
sessionList.refresh(); // 激活即刷新该会话相对时间（不停留切换前旧值）
```

- [ ] **Step 4: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add src/resource/theme/theme.css src/main/java/com/minion/gui/sidebar/SessionListView.java src/main/java/com/minion/gui/MainWindow.java
git commit -m "fix: 会话时间提亮并增强刷新（激活即刷 + 60 秒周期），时间不参与 LLM 上下文"
```

### Task 5: 自动滚动竞态修复

**Files:**
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（setupAutoScroll 的 vmax 监听）

**Interfaces:**
- Consumes: `AutoScrollPolicy`（不动：`void onScroll(double vvalue, double vmax)`、`boolean shouldFollow()`）
- Produces: 贴底跟随/离开暂停/拖回恢复的正常自动滚动

- [ ] **Step 1: 重写 vmax 监听（执行时重读 vmax，不捕获旧值）**

`setupAutoScroll` 中现有代码：

```java
chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
    if (policy.shouldFollow()) {
        final double target = nv.doubleValue();
        Platform.runLater(() -> chatScroll.setVvalue(target));
    }
});
```

改为：

```java
chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
    if (policy.shouldFollow()) {
        // 执行时重读当前 vmax 并二次确认贴底：捕获监听时旧值会在内容继续增长时
        // 把 vvalue 卡在旧底部 < 新 vmax，被误判"离开底部"→ pinned 永不复原（失效根因）
        Platform.runLater(() -> {
            if (policy.shouldFollow()) chatScroll.setVvalue(chatScroll.getVmax());
        });
    }
});
```

vvalue 监听与 AutoScrollPolicy 本身不动（贴底判定 `vvalue >= vmax - 0.001` 正确）。

- [ ] **Step 2: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS（AutoScrollPolicyTest 继续通过）。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/minion/gui/MainWindow.java
git commit -m "fix: 自动滚动跟随竞态——runLater 内重读 vmax 并二次确认贴底，不再捕获监听时旧值"
```

### Task 6: 设置窗左列导航（TabPane → ListView 导航 + StackPane 内容切换）

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`

**Interfaces:**
- Consumes: 现有 `form(ModelConfig)`、`refresh(ListView, ModelManager)`、`setInt`、`parseInt/parseDouble`、`error` 不动
- Produces: `basicPane(config)` / `modelPane(models, manager)` / `aboutPane()` 三个 pane 构建器（Task 7 重写 basicPane 内部布局，签名不变）

- [ ] **Step 1: 更新导入**

删除：`import javafx.scene.control.Tab;`、`import javafx.scene.control.TabPane;`

新增：

```java
import javafx.scene.Node;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
```

- [ ] **Step 2: 重写 show()（左列 ListView 导航 + StackPane）**

`show` 整体替换为：

```java
public static void show(Window owner, final ModelManager models,
                        final SessionManager manager, final Config config) {
    Dialog<Void> d = new Dialog<Void>();
    d.initOwner(owner);
    d.setTitle("设置");
    d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    Theme.style(d);

    // 左列导航：TabPane 侧放文字旋转 90°（历史"字倒了"根因）不可用；ListView 复用现有深色样式
    final ListView<String> nav = new ListView<String>();
    nav.getItems().addAll("基础设置", "模型", "关于");
    nav.setPrefWidth(120);
    final Node basic = basicPane(config);
    final Node model = modelPane(models, manager);
    final Node about = aboutPane();
    final StackPane content = new StackPane();
    nav.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
        if (item == null) return;
        content.getChildren().setAll("基础设置".equals(item) ? basic
                : "模型".equals(item) ? model : about);
    });
    nav.getSelectionModel().select(0); // 默认选中基础设置（选中监听触发内容显示）

    HBox box = new HBox(0);
    box.getChildren().addAll(nav, content);
    HBox.setHgrow(content, Priority.ALWAYS);
    box.setPrefSize(620, 500);
    d.getDialogPane().setContent(box);
    d.showAndWait();
}
```

- [ ] **Step 3: modelTab → modelPane（内容不变，去掉 Tab 壳）**

方法签名与末尾改为（`ListView`/`actions` 构建代码原样保留）：

```java
private static VBox modelPane(final ModelManager models, final SessionManager manager) {
```

末尾：

```java
    VBox box = new VBox(10);
    box.setPadding(new Insets(10));
    box.getChildren().addAll(list, actions);
    return box;
}
```

（删除原 `Tab tab = new Tab("模型", box); tab.setClosable(false); return tab;` 三行。）

- [ ] **Step 4: aboutTab → aboutPane（内容不变，去掉 Tab 壳）**

整体替换为：

```java
private static VBox aboutPane() {
    VBox box = new VBox(10);
    box.setPadding(new Insets(16));
    box.getChildren().addAll(
            new Label("minion——类 Claude Code 的代码开发助手"),
            new Separator(),
            new Label("作者：尹承"),
            new Label("联系方式：258915527@qq.com"),
            new Label("开发语言：Java 8 + JavaFX"));
    return box;
}
```

- [ ] **Step 5: basicTab → basicPane（本轮只换壳，GridPane 内容保留，Task 7 重写布局）**

方法签名改为 `private static VBox basicPane(final Config config)`，末尾的 Tab 创建三行：

```java
        Tab tab = new Tab("基础设置", box);
        tab.setClosable(false);
        return tab;
```

改为：

```java
        return box;
```

- [ ] **Step 6: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "feat: 设置窗改左列 ListView 导航（基础设置/模型/关于）+ StackPane 内容切换，弃用 TabPane"
```

### Task 7: 基础设置行布局（GridPane 截断 → HBox 行 + ScrollPane）

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`（basicPane 重写 + row 辅助方法）

**Interfaces:**
- Consumes: Task 6 的 `basicPane(config)` 签名（返回类型 Node/VBox 兼容，本任务改为返回 ScrollPane 包装）
- Produces: 基础设置所有选项完整可见，窗口小时可滚动

- [ ] **Step 1: 新增导入**

```java
import javafx.scene.control.ScrollPane;
```

- [ ] **Step 2: basicPane 整体重写（GridPane → VBox 行 + ScrollPane）**

`basicPane` 整体替换为（保存逻辑与校验逐字保留）：

```java
private static Node basicPane(final Config config) {
    TextField skillsDir = new TextField(config.skillsDir());
    TextArea toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
    toolWhitelist.setPrefRowCount(2);
    TextArea cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
    cmdWhitelist.setPrefRowCount(2);
    CheckBox allowOutside = new CheckBox("允许读取工作区外文件（Read/Grep/Glob）");
    allowOutside.setSelected(config.readAllowOutside());
    CheckBox skipConfirm = new CheckBox("跳过高危操作确认");
    skipConfirm.setSelected(config.confirmSkip());
    Label browserNote = new Label("浏览器配置（以下项需重启后生效）");
    browserNote.getStyleClass().add("msg-thinking");
    TextField browserPath = new TextField(config.browserPath());
    TextField browserPort = new TextField(String.valueOf(config.browserPort()));
    TextField browserUserData = new TextField(config.browserUserDataDir());
    CheckBox browserHeadless = new CheckBox("无头模式");
    browserHeadless.setSelected(config.browserHeadless());
    TextField browserTimeout = new TextField(String.valueOf(config.browserTimeoutMs()));

    VBox rows = new VBox(10);
    rows.getChildren().addAll(
            row("技能目录 skills.dir:", skillsDir),
            row("确认白名单\n(工具, 逗号分隔):", toolWhitelist),
            row("确认白名单\n(命令, 逗号分隔):", cmdWhitelist),
            row("读逃逸:", allowOutside),
            row("确认开关:", skipConfirm),
            browserNote,
            row("browser.path:", browserPath),
            row("browser.port:", browserPort),
            row("browser.userDataDir:", browserUserData),
            row("browser.headless:", browserHeadless),
            row("browser.timeoutMs:", browserTimeout));

    Button save = new Button("保存");
    save.getStyleClass().add("btn-primary");
    save.setOnAction(e -> {
        config.set("skills.dir", skillsDir.getText().trim());
        // 白名单是逗号分隔的单行配置：多行粘贴的换行替换为空格，否则落盘后重载会静默丢内容
        config.set("confirm.whitelist.tools",
                toolWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
        config.set("confirm.whitelist.commands",
                cmdWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
        config.set("paths.read.allowOutside", String.valueOf(allowOutside.isSelected()));
        config.set("confirm.skip", String.valueOf(skipConfirm.isSelected()));
        config.set("browser.path", browserPath.getText().trim());
        if (!setInt("browser.port", browserPort.getText(), config)) {
            error("保存失败", "browser.port 必须是整数，未保存");
        }
        config.set("browser.userDataDir", browserUserData.getText().trim());
        config.set("browser.headless", String.valueOf(browserHeadless.isSelected()));
        if (!setInt("browser.timeoutMs", browserTimeout.getText(), config)) {
            error("保存失败", "browser.timeoutMs 必须是整数，未保存");
        }
    });

    VBox contentBox = new VBox(10);
    contentBox.getChildren().addAll(rows, save);
    contentBox.setPadding(new Insets(12));
    ScrollPane sp = new ScrollPane(contentBox); // 窗口小时可滚动，选项不再被裁剪
    sp.setFitToWidth(true);
    return sp;
}
```

- [ ] **Step 3: 新增 row 辅助方法（basicPane 之后插入）**

```java
/** 表单行：标签固定宽 160 不收缩（GridPane+ColumnConstraints 在 JavaFX 8 下仍挤压截断，弃用），输入控件铺满剩余宽度 */
private static HBox row(String labelText, javafx.scene.control.Control control) {
    Label l = new Label(labelText);
    l.setMinWidth(160);
    l.setPrefWidth(160);
    l.setWrapText(true);
    control.setMaxWidth(Double.MAX_VALUE);
    HBox box = new HBox(8);
    HBox.setHgrow(control, Priority.ALWAYS);
    box.getChildren().addAll(l, control);
    return box;
}
```

- [ ] **Step 4: 编译 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS（GridPane 仍被 model 表单 form() 使用，GridPane 导入保留）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "fix: 基础设置改固定宽标签行布局 + ScrollPane，修复选项截断/不显示"
```

### Task 8: 文档同步与最终验收

**Files:**
- Modify: `README.md`（25、28 行附近）
- Modify: `docs/ARCHITECTURE.md`（36、40、47 行附近）

**Interfaces:**
- Consumes: Task 1-7 全部产物
- Produces: 文档与实现一致 + 全量构建/手工清单通过

- [ ] **Step 1: README 同步**

第 25 行 `- ⚙ 设置（右上角）：模型 / 基础设置 / 关于；切换模型、修改参数即时生效（运行中会话下一轮生效）` 改为：

```
- ⚙ 设置（右上角）：左列导航（基础设置 / 模型 / 关于）；切换模型、修改参数即时生效（运行中会话下一轮生效）
```

第 28 行 `- 侧栏悬停会话/工作空间项显示操作按钮（✎ 重命名 / ⚙ 修改 / ✕ 删除），移开隐藏；会话项非悬停显示最近消息时间（如 1m/5m/3h/2d）` 改为：

```
- 侧栏悬停会话项显示操作按钮（✎ 重命名 / ✕ 删除）、工作空间项（⚙ 修改 / ✕ 删除，重命名并入修改弹窗），移开隐藏；会话项非悬停显示最近消息时间（如 1m/5m/3h/2d，60 秒周期刷新）
```

- [ ] **Step 2: ARCHITECTURE 同步**

第 36 行 sidebar 描述中「悬停显示 ✎/⚙/✕ 操作按钮替代右键菜单」改为「会话项悬停 ✎/✕、工作空间项悬停 ⚙/✕（重命名并入修改弹窗）；名称用 cell-text 样式类显式上色；会话时间 60 秒周期刷新」。

第 40 行 SettingsDialog 描述改为：「设置窗（左列 ListView 导航：基础设置/模型/关于 + StackPane 内容切换；基础设置 HBox 行布局标签固定 160 宽 + ScrollPane 防裁剪）」。

第 47 行 AutoScrollPolicy 描述中「vmax 变化后 Platform.runLater 设 setVvalue(vmax) 防 clamp 吞掉」改为「vmax 变化后 runLater 内重读 getVmax() 并二次确认贴底（捕获监听时旧值会卡在旧底部导致误判离开底部）」。

- [ ] **Step 3: 全量构建 + 测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn clean package && JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS，产物 `target/minion-0.1.0.jar`。

- [ ] **Step 4: 手工验证清单（minion.bat 启动）**

1. 侧栏会话/工作空间列表文字浅色可读，选中态更亮，摘要行颜色不变。
2. 工作空间拖拽排序成功，重启后顺序保持。
3. 工作空间悬停只有 ⚙/✕ 两按钮；修改弹窗改名成功、重复名报「名称非法或已存在」、改名后会话目录随迁。
4. 会话列表非悬停显示 5m/3h/2d 且颜色可读；等待 1 分钟后数值刷新。
5. 流式输出：贴底自动跟随、上翻暂停、拖回底部恢复跟随。
6. 设置窗左列三项导航（默认基础设置），点击切换内容；模型页切换模型仍实时生效。
7. 基础设置所有选项完整可见、可编辑、保存生效；窗口缩小时可滚动。

- [ ] **Step 5: 提交**

```bash
git add README.md docs/ARCHITECTURE.md
git commit -m "docs: 同步 UI 修复第二轮说明（左列导航/按钮合并/时间周期刷新/滚动修复）"
```
