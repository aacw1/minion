# UI 打磨（底部确认卡片/页签移位/文字模糊）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按设计文档（docs/superpowers/specs/2026-08-13-bottom-sheet-tabs-blur-design.md）完成 3 项 UI 打磨：危险操作确认改为右侧底部弹出卡片、会话页签从标题栏移到右侧正文上方（带分隔线）、修复 ScrollPane 内文字发虚（含 125% 缩放观感）。

**Architecture:** 确认路径重构先行（ConfirmSheet 新类 + GuiConfirmUi 线程语义改造，FX 线程不再阻塞），再接线主窗口（StackPane 宿主 + tabs-bar 移位），最后文字模糊分步修复（设置页去 ScrollPane → 聊天区显式 LCD → 条件任务 T2K 光栅器），收尾文档同步与全量验收。

**Tech Stack:** Java 8 + JavaFX 8 + Maven + junit4。GUI 改动无自动化测试（项目无 TestFX 依赖），验证 = 编译/既有测试全过 + 手工清单。

## Global Constraints

- JDK 8 兼容：可用 lambda/stream（JDK 8 特性），禁用 var 等 9+ 语法；不新增依赖。
- 测试命令：`JAVA_HOME="E:/javame/jdk8" mvn test`；构建：`JAVA_HOME="E:/javame/jdk8" mvn clean package`（产物 target/minion-0.1.0.jar）。
- 启动 GUI 手工验收：`minion.bat`（本机 PATH 的 java 须为 JDK 8）。
- 注释、文档、commit 用中文；commit 用 conventional 格式（feat:/fix:/docs:）。
- **git commit 必须用 `-F` 消息文件**：bash wrapper 对非 ASCII 命令行崩溃（报 exit 127）。每步 commit 先用 Write 工具写 `.git/COMMIT_MSG.txt`（内容为中文消息），再执行纯 ASCII 的 `git add … && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt`。**不要在 bash 命令行里直接写中文。**
- 本轮不改 API 契约（reasoning_content 原样回传、tool_call↔tool 配对），不新增依赖。
- 资源目录是 `src/resource`；设计文档在 `docs/superpowers/specs/`，实施计划在 `docs/superpowers/plans/`。
- 设计文档 §4.5 已知边界保留不修：排队中的确认若工具侧已超时，其卡片仍会随后弹出、点击无效果。

---

### Task 1: ConfirmSheet 底部确认卡片 + GuiConfirmUi 改造 + 删除 ConfirmDialog

**Files:**
- Create: `src/main/java/com/minion/gui/dialog/ConfirmSheet.java`
- Modify: `src/main/java/com/minion/gui/confirm/GuiConfirmUi.java`（整文件重写）
- Delete: `src/main/java/com/minion/gui/dialog/ConfirmDialog.java`
- Modify: `src/resource/theme/theme.css`（追加 `.sheet-*`/`.btn-approve` 段）

**Interfaces:**
- Consumes: `com.minion.core.tools.confirm.ConfirmUi`（Decision 枚举：APPROVE/REJECT/APPROVE_SESSION/APPROVE_WHITELIST）
- Produces: `ConfirmSheet.setHost(StackPane)`（Task 2 使用）、`ConfirmSheet.show(String, Consumer<ConfirmUi.Decision>)`（必须在 FX 线程调用）

本任务无新增自动化测试（纯 JavaFX UI，无 TestFX；项目惯例）。回归 = 既有 `GuiConfirmUiTest` 继续通过（无 FX 环境走 ISE→REJECT 路径，语义不变）+ 编译。

- [ ] **Step 1: 新建 ConfirmSheet.java**

`src/main/java/com/minion/gui/dialog/ConfirmSheet.java`：

```java
package com.minion.gui.dialog;

import com.minion.core.tools.confirm.ConfirmUi;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * 高危/越界操作确认底部卡片（替代旧 Alert 版 ConfirmDialog）：
 * 挂右侧面板 StackPane 顶层，遮罩仅压暗右侧、卡片从底部滑入；弹窗期间
 * 全窗点击拦截（Scene 事件过滤器）、焦点圈定卡片内；并发确认串行排队。
 * 必须在 FX 线程调用（GuiConfirmUi 经 Platform.runLater 保证）。
 */
public class ConfirmSheet {

    private static StackPane host;
    private static boolean showing;
    private static final Queue<Runnable> pending = new ArrayDeque<Runnable>();
    private static VBox currentCard;
    private static Region currentScrim;
    private static Consumer<ConfirmUi.Decision> currentCallback;

    private ConfirmSheet() { }

    /** MainWindow 注册右侧面板栈（遮罩与卡片的挂载点） */
    public static void setHost(StackPane h) { host = h; }

    public static void show(String message, Consumer<ConfirmUi.Decision> callback) {
        if (host == null) {
            System.err.println("[minion] ConfirmSheet host 未注册，确认请求自动拒绝");
            callback.accept(ConfirmUi.Decision.REJECT);
            return;
        }
        Runnable job = new Runnable() {
            @Override public void run() { display(message, callback); }
        };
        if (showing) { pending.add(job); return; }
        job.run();
    }

    /** 坐标落卡片外一律拦截：实现「点遮罩无反应 + 弹窗期间全窗点击拦截（含侧栏）」 */
    private static final EventHandler<MouseEvent> mouseFilter = new EventHandler<MouseEvent>() {
        @Override public void handle(MouseEvent e) {
            VBox card = currentCard;
            if (card == null) return;
            if (!card.contains(card.sceneToLocal(e.getSceneX(), e.getSceneY()))) e.consume();
        }
    };

    /** Esc=拒绝；Enter 放行触发焦点按钮（默认焦点=批准）；其余按键一律拦截（Tab 圈定焦点防逃逸到侧栏） */
    private static final EventHandler<KeyEvent> keyFilter = new EventHandler<KeyEvent>() {
        @Override public void handle(KeyEvent e) {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                finish(ConfirmUi.Decision.REJECT);
            } else if (e.getCode() != KeyCode.ENTER) {
                e.consume();
            }
        }
    };

    private static void display(String message, final Consumer<ConfirmUi.Decision> callback) {
        showing = true;

        final Region scrim = new Region();
        scrim.getStyleClass().add("sheet-scrim");
        FadeTransition scrimIn = new FadeTransition(Duration.millis(150), scrim);
        scrimIn.setFromValue(0);
        scrimIn.setToValue(1);
        scrimIn.play();

        final VBox card = buildCard(message);
        StackPane.setAlignment(card, Pos.BOTTOM_CENTER);
        StackPane.setMargin(card, new Insets(0, 16, 12, 16));
        // 宽度随右侧面板收缩（分栏拖窄时不越过分隔线），上限 640
        card.maxWidthProperty().bind(Bindings.min(640, host.widthProperty().subtract(32)));

        currentCard = card;
        currentScrim = scrim;
        currentCallback = callback;
        Scene scene = host.getScene();
        scene.addEventFilter(MouseEvent.ANY, mouseFilter);
        scene.addEventFilter(KeyEvent.ANY, keyFilter);
        host.getChildren().addAll(scrim, card);

        // 滑入动画需布局完成后的卡片高度，runLater 等一个布局周期
        Platform.runLater(new Runnable() {
            @Override public void run() {
                double h = card.getBoundsInParent().getHeight();
                TranslateTransition slide = new TranslateTransition(Duration.millis(180), card);
                slide.setFromY(h);
                slide.setToY(0);
                slide.setInterpolator(Interpolator.EASE_OUT);
                slide.play();
                card.requestFocus();
            }
        });
    }

    /** 卡片：顶部琥珀危险饰条 | ⚠ 标题 | 消息内嵌面板 | 按钮行 | 快捷键提示 */
    private static VBox buildCard(String message) {
        VBox card = new VBox();
        card.getStyleClass().add("sheet-card");
        card.setPrefWidth(560);

        Region accent = new Region();
        accent.getStyleClass().add("sheet-accent");

        Label warn = new Label("⚠");
        warn.getStyleClass().add("sheet-warn");
        Label title = new Label("高危操作确认");
        title.getStyleClass().add("sheet-title");
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(warn, title);

        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("sheet-message");

        Button reject = new Button("拒绝");
        reject.getStyleClass().add("btn-ghost");
        reject.setOnAction(e -> finish(ConfirmUi.Decision.REJECT));
        Button session = new Button("本次会话批准");
        session.getStyleClass().add("btn-ghost");
        session.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_SESSION));
        Button whitelist = new Button("批准并记住");
        whitelist.getStyleClass().add("btn-ghost");
        whitelist.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_WHITELIST));
        Button approve = new Button("批准");
        approve.getStyleClass().add("btn-approve");
        approve.setDefaultButton(true); // Enter 即批准（默认焦点落批准）
        approve.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE));
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(reject, session, whitelist, approve);

        Label hint = new Label("Enter 批准 · Esc 拒绝");
        hint.getStyleClass().add("sheet-hint");

        VBox content = new VBox(10);
        content.setPadding(new Insets(12, 14, 12, 14));
        content.getChildren().addAll(head, body, buttons, hint);
        card.getChildren().addAll(accent, content);
        return card;
    }

    /** 收尾：移除拦截 → 120ms 淡出 → 卸载节点 → 回调结果 → 出队展示下一个 */
    private static void finish(final ConfirmUi.Decision d) {
        final VBox card = currentCard;
        final Region scrim = currentScrim;
        final Consumer<ConfirmUi.Decision> callback = currentCallback;
        if (card == null) return; // 双击/重复触发防御
        Scene scene = host.getScene();
        scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
        scene.removeEventFilter(KeyEvent.ANY, keyFilter);
        currentCard = null;
        currentScrim = null;
        currentCallback = null;
        ParallelTransition out = new ParallelTransition(
                new FadeTransition(Duration.millis(120), scrim),
                new FadeTransition(Duration.millis(120), card));
        out.setOnFinished(new EventHandler<ActionEvent>() {
            @Override public void handle(ActionEvent e) {
                host.getChildren().removeAll(scrim, card);
                showing = false;
                callback.accept(d);
                Runnable next = pending.poll();
                if (next != null) next.run();
            }
        });
        out.play();
    }
}
```

- [ ] **Step 2: 重写 GuiConfirmUi.java**

`src/main/java/com/minion/gui/confirm/GuiConfirmUi.java`（整文件替换）：

```java
package com.minion.gui.confirm;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.dialog.ConfirmSheet;
import javafx.application.Platform;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * GUI 确认交互：工具线程 ask → Platform.runLater 投递 ConfirmSheet 底部卡片 →
 * 工具线程 poll 阻塞等待结果（不阻塞 FX 线程，消除旧版 showAndWait 嵌套事件循环）。
 * 无 GUI 环境（测试/FX toolkit 未启动）时 JDK8 的 Platform.runLater 直接抛
 * IllegalStateException，捕获后安全默认 REJECT 不挂死；3 秒未响应同样兜底
 * REJECT——拒绝比错误批准安全，用户重发请求即可（超时后卡片仍在展示，
 * 用户点击结果被丢弃，与旧 Alert 语义一致）。
 */
public class GuiConfirmUi implements ConfirmUi {

    @Override
    public Decision ask(String message) {
        final LinkedBlockingQueue<Decision> q = new LinkedBlockingQueue<Decision>(1);
        try {
            Platform.runLater(new Runnable() {
                @Override public void run() { ConfirmSheet.show(message, q::offer); }
            });
        } catch (IllegalStateException e) {
            return Decision.REJECT; // FX toolkit 未启动（无 GUI 环境），防御性拒绝
        }
        try {
            Decision d = q.poll(3, TimeUnit.SECONDS);
            return d != null ? d : Decision.REJECT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.REJECT;
        }
    }
}
```

- [ ] **Step 3: 删除 ConfirmDialog.java**

```bash
git rm src/main/java/com/minion/gui/dialog/ConfirmDialog.java
```

- [ ] **Step 4: theme.css 追加 sheet 样式段**

在 `src/resource/theme/theme.css` 文件末尾追加：

```css
/* ===== 危险操作确认底部卡片（ConfirmSheet，右侧面板内） ===== */
.sheet-scrim { -fx-background-color: rgba(0,0,0,0.55); }
.sheet-card {
    -fx-background-color: #1a1d24;
    -fx-background-radius: 12;
    -fx-border-color: #2a2f3a;
    -fx-border-radius: 12;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 24, 0, 0, 8);
}
.sheet-accent { -fx-background-color: #e3b341; -fx-pref-height: 3; -fx-background-radius: 12 12 0 0; }
.sheet-warn { -fx-text-fill: #e3b341; -fx-font-size: 16px; }
.sheet-title { -fx-text-fill: #f0f2f6; -fx-font-size: 14px; -fx-font-weight: bold; }
.sheet-message {
    -fx-background-color: #13161c;
    -fx-background-radius: 8;
    -fx-padding: 10 12 10 12;
    -fx-text-fill: #d3d7de;
    -fx-font-size: 13px;
}
.sheet-hint { -fx-text-fill: #7a828e; -fx-font-size: 11px; }
.btn-approve {
    -fx-background-color: linear-gradient(to bottom, #3fb950, #2ea043);
    -fx-text-fill: white;
    -fx-font-weight: bold;
    -fx-background-radius: 6;
    -fx-padding: 6 14 6 14;
    -fx-cursor: hand;
}
.btn-approve:hover { -fx-background-color: linear-gradient(to bottom, #4cc95e, #3fb950); }
```

- [ ] **Step 5: 编译 + 回归测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS；`GuiConfirmUiTest` 两用例通过（无 FX 环境 ISE→REJECT 不挂死）。

- [ ] **Step 6: Commit**

先 Write `.git/COMMIT_MSG.txt`：

```
feat: 高危确认改为右侧底部卡片（ConfirmSheet 替代 Alert 版 ConfirmDialog）
```

再执行：

```bash
git add src/main/java/com/minion/gui/dialog/ConfirmSheet.java src/main/java/com/minion/gui/confirm/GuiConfirmUi.java src/resource/theme/theme.css && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

（ConfirmDialog 的删除已由 Step 3 的 `git rm` 进入暂存区，随本 commit 一并提交）

---

### Task 2: MainWindow 接入（右侧 StackPane 宿主）

**Files:**
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（右侧面板包 StackPane + 注册宿主）

**Interfaces:**
- Consumes: `ConfirmSheet.setHost(StackPane)`（Task 1 定义）
- Produces: 右侧面板栈（Task 3 的 tabs-bar 加在此栈内的 VBox 中）

- [ ] **Step 1: 修改 MainWindow**

两处改动：

1）import 区补两行（`import javafx.scene.layout.StackPane;` 与 `import com.minion.gui.dialog.ConfirmSheet;`）：

```java
import com.minion.core.config.WorkspaceConfig;
import com.minion.gui.chat.ChatView;
import com.minion.gui.dialog.ConfirmSheet;
import com.minion.gui.dialog.SettingsDialog;
```
```java
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
```

2）右侧面板构造改为 StackPane 承载（原 `SplitPane split = new SplitPane(); split.setDividerPositions(0.25); split.getItems().addAll(sidebar, right); root.setCenter(split);` 段）：

```java
        // 右侧面板外包 StackPane：ConfirmSheet 遮罩与卡片挂其顶层（遮罩范围即右侧，不越分隔线）
        StackPane rightStack = new StackPane(right);
        ConfirmSheet.setHost(rightStack);

        SplitPane split = new SplitPane();
        split.setDividerPositions(0.25); // 需求 5：左右比例 1:3
        split.getItems().addAll(sidebar, rightStack);
        root.setCenter(split);
```

- [ ] **Step 2: 编译 + 回归测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 手工验收（本任务局部）**

`minion.bat` 启动 → 对话中触发高危操作（如要求模型执行 `rm -rf` 类危险 Bash 命令）：
1. 卡片从右侧底部滑入、遮罩仅压暗右侧、侧栏可见
2. 点遮罩无反应；点侧栏无反应；Esc 关闭=拒绝；Enter=批准；批准按钮为绿色
3. 批准/拒绝后对应决策生效（工具执行或被拒，聊天区可见反馈）

- [ ] **Step 4: Commit**

先 Write `.git/COMMIT_MSG.txt`：

```
feat: 主窗口右侧面板注册 ConfirmSheet 宿主（StackPane 承载遮罩卡片）
```

再执行：

```bash
git add src/main/java/com/minion/gui/MainWindow.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 3: 会话页签移位（标题栏一行化 + 右侧页签栏）

**Files:**
- Modify: `src/main/java/com/minion/gui/TitleBar.java`（去 center 参数，弹性 Region 占位）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（tabs 移入右侧 VBox 顶部 tabs-bar + 空页签隐藏 + TitleBar 调用瘦身）
- Modify: `src/resource/theme/theme.css`（`.tabs-bar` 新增；表头背景透明化；`.tab-content-area` 压零）

**Interfaces:**
- Consumes: Task 2 的 `rightStack`/`right` 结构；`tabs` 字段（TabPane）
- Produces: 无（后续任务不依赖本任务产物）

- [ ] **Step 1: TitleBar 瘦身**

`src/main/java/com/minion/gui/TitleBar.java` 三处：

1）import 补 `import javafx.scene.layout.Region;`

2）构造函数签名去 center：

```java
    public TitleBar(Stage stage, Label modelLabel,
                    Runnable openSettings, Runnable confirmClose) {
        this.stage = stage;
        this.modelLabel = modelLabel; // 设置窗关闭后 MainWindow 刷新顶部模型名用
        getStyleClass().add("topbar");
        setSpacing(10);

        Label app = new Label("minion");
        app.getStyleClass().add("topbar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS); // 原页签区弹性占位：模型名与右侧按钮之间留白
```

3）children 行改为：

```java
        getChildren().addAll(app, modelLabel, spacer, gear, min, max, close);
```

双击最大化判断保持原样（`e.getTarget() == this || app || modelLabel`），注释中「页签文本也是 Label」的排除说明可简化为「页签已移至右侧页签栏，标题栏仅剩自身与两个 Label」。

- [ ] **Step 2: MainWindow 接线**

`src/main/java/com/minion/gui/MainWindow.java` 四处：

1）import 补 `import javafx.collections.ListChangeListener;`

2）字段区（`private TitleBar titleBar;` 附近）加：

```java
    private HBox tabsBar; // 右侧顶部页签栏（无会话时整行隐藏）
```

3）TitleBar 构造调用瘦身：

```java
        titleBar = new TitleBar(stage, modelLabel, this::openSettings, this::confirmClose);
```

4）右侧 VBox 首行插入页签栏（原 `right.getChildren().setAll(chatScroll, inputView);` 行替换）：

```java
        // 页签栏（右侧顶部，下带 1px 分隔线；页签为空时整行隐藏）
        tabsBar = new HBox(tabs);
        tabsBar.getStyleClass().add("tabs-bar");
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> {
            boolean empty = tabs.getTabs().isEmpty();
            tabsBar.setVisible(!empty);
            tabsBar.setManaged(!empty);
        });
        right.getChildren().setAll(tabsBar, chatScroll, inputView);
```

（`tabs` 监听器注册于 `rebuildTabs()` 首次调用之前——`show()` 末尾才调用 `rebuildTabs()`，监听先就位，启动/切空间补齐页签时可见性随之联动。页签选中激活/关闭删除/呼吸点等既有逻辑零改动，仅换容器。）

- [ ] **Step 3: theme.css 页签栏样式**

`src/resource/theme/theme.css` 三处：

1）原规则改为透明表头背景（消除叠加在右侧 panel-dark 上的 #12141a 色带）：

```css
.tab-pane .tab-header-area .tab-header-background { -fx-background-color: transparent; }
```

2）在其后追加空内容区压零（页签是纯导航，无内容区——标题栏那「页签独占一行」的视觉带根源）：

```css
.tab-pane > .tab-content-area { -fx-min-height: 0; -fx-pref-height: 0; -fx-max-height: 0; }
```

3）文件末尾追加页签栏分隔线：

```css
/* ===== 右侧顶部页签栏（tabs-bar，正文上方分隔线） ===== */
.tabs-bar {
    -fx-padding: 4 8 0 8;
    -fx-border-color: transparent transparent #232733 transparent;
    -fx-border-width: 0 0 1 0;
}
```

- [ ] **Step 4: 编译 + 回归测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 手工验收（本任务局部）**

`minion.bat` 启动：
1. 标题栏只有一行（minion | 模型名 | 留白 | ⚙ | — | □ | ✕），无页签带
2. 页签在右侧顶部，下方有 1px 分隔线，正文下移一行
3. 点击页签激活会话、✕ 关闭删除（有确认弹窗）、呼吸点正常；双击标题栏空白最大化正常
4. 新建一个空工作空间并切换：无会话时页签行（含分隔线）隐藏；建会话后恢复显示

- [ ] **Step 6: Commit**

先 Write `.git/COMMIT_MSG.txt`：

```
feat: 会话页签移至右侧正文上方（标题栏一行化，分隔线，空页签隐藏）
```

再执行：

```bash
git add src/main/java/com/minion/gui/TitleBar.java src/main/java/com/minion/gui/MainWindow.java src/resource/theme/theme.css && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 4: 文字模糊修复①——基础设置页去 ScrollPane

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`（basicPane 去 ScrollPane）

**Interfaces:**
- Consumes: 无
- Produces: 无

根因（设计文档 §5.1）：JavaFX 8 对 ScrollPane 裁剪内文字回退灰阶抗锯齿，125% 缩放放大柔边。基础设置页是唯一套 ScrollPane 的设置页（内容高约 453px，设置窗固定 620×500 放得下）。

- [ ] **Step 1: 修改 basicPane 尾部**

`src/main/java/com/minion/gui/dialog/SettingsDialog.java` 原：

```java
        VBox contentBox = new VBox(10);
        contentBox.getChildren().addAll(rows, save);
        contentBox.setPadding(new Insets(12));
        ScrollPane sp = new ScrollPane(contentBox); // 窗口小时可滚动，选项不再被裁剪
        sp.setFitToWidth(true);
        return sp;
    }
```

替换为：

```java
        VBox contentBox = new VBox(10);
        contentBox.getChildren().addAll(rows, save);
        contentBox.setPadding(new Insets(12));
        // 去 ScrollPane：JavaFX 8 裁剪内文字回退灰阶 AA 是整页发虚根因；
        // 内容高约 453px，620x500 固定窗放得下，无需滚动
        return contentBox;
    }
```

- [ ] **Step 2: 清理 import**

删除 `import javafx.scene.control.ScrollPane;`（本文件其余位置已无 ScrollPane 引用——Step 1 是唯一使用点）。

- [ ] **Step 3: 编译 + 回归测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 手工验收（本任务局部）**

`minion.bat` 启动 → ⚙ 打开设置 → 基础设置页：右侧全部文字（标签/输入框/勾选项）目测锐利（与左侧导航对比）。若仍发虚 → 按 Task 5/6 继续（聊天区与本页共享 ScrollPane 根因，本页应已恢复）。

- [ ] **Step 5: Commit**

先 Write `.git/COMMIT_MSG.txt`：

```
fix: 基础设置页去 ScrollPane（JavaFX 8 裁剪内文字回退灰阶 AA 致整页发虚）
```

再执行：

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 5: 文字模糊修复②——聊天区显式 LCD

**Files:**
- Modify: `src/main/java/com/minion/gui/chat/ChatView.java`（根节点加 `.chat-content` 类）
- Modify: `src/resource/theme/theme.css`（`.chat-content` 显式 `-fx-font-smoothing-type: lcd`）

**Interfaces:**
- Consumes: 无
- Produces: 无

原理：`-fx-font-smoothing-type` 为可继承 Font 属性；显式指定 lcd 优先于 Prism 在 ScrollPane 裁剪下的灰阶回退。**本任务有效性需真机目测**；若无效进入 Task 6（条件任务）。

- [ ] **Step 1: ChatView 根节点加类**

`src/main/java/com/minion/gui/chat/ChatView.java` 构造函数内，`getStyleClass().add("panel-dark");` 之后补一行：

```java
        getStyleClass().add("chat-content"); // 显式 LCD 用（ScrollPane 裁剪下 JavaFX 8 默认回退灰阶 AA → 发虚）
```

- [ ] **Step 2: theme.css 追加**

文件末尾追加：

```css
/* ===== 聊天正文显式 LCD：ScrollPane 裁剪下 JavaFX 8 默认回退灰阶 AA（正文发虚根因），显式指定优先 ===== */
.chat-content { -fx-font-smoothing-type: lcd; }
```

- [ ] **Step 3: 编译 + 回归测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 手工验收（本任务局部）**

`minion.bat` 启动 → 查看聊天正文（用户消息/AI 回复/代码块）：目测是否比改前锐利。
- 有效 → Step 5 commit；Task 6 跳过
- 无效 → 回滚本任务改动（`git checkout -- src/main/java/com/minion/gui/chat/ChatView.java src/resource/theme/theme.css` 仅撤销本任务两处，theme.css 需手动删 `.chat-content` 段——直接用 git checkout 整个文件会连带撤销 Task 1/3 的样式，**必须只删 `.chat-content` 段并保留其余**，ChatView 同理只删新增行），然后执行 Task 6

- [ ] **Step 5: Commit（仅当 Step 4 有效）**

先 Write `.git/COMMIT_MSG.txt`：

```
fix: 聊天正文显式 LCD 抗锯齿（ScrollPane 裁剪下灰阶回退致文字发虚）
```

再执行：

```bash
git add src/main/java/com/minion/gui/chat/ChatView.java src/resource/theme/theme.css && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 6（条件任务）: minion.bat 换 T2K 光栅器

**前置条件**：Task 5 Step 4 真机验证无效（LCD 显式指定被 Prism 裁剪规则压制）。

**Files:**
- Modify: `minion.bat`（`-jar` 前加 `-Dprism.text=t2k`）

- [ ] **Step 1: 修改启动参数**

`minion.bat` 原最后一行：

```bat
"%MINION_JAVA%" -jar "%~dp0target\minion-0.1.0.jar" %*
```

改为：

```bat
"%MINION_JAVA%" -Dprism.text=t2k -jar "%~dp0target\minion-0.1.0.jar" %*
```

- [ ] **Step 2: 手工验收**

`minion.bat` 启动 → 聊天正文目测。有效 → Step 3；仍模糊 → 回滚本行，并在 README「使用说明」补一句：*Windows 显示缩放 125% 下 JavaFX 8 文字渲染存在平台局限，建议将缩放调至 100% 或接受轻微柔边*，然后 Step 3 commit（提交 README 说明）。

- [ ] **Step 3: Commit**

先 Write `.git/COMMIT_MSG.txt`（有效时）：

```
fix: 启动参数换 T2K 光栅器修复 125% 缩放下文字发虚
```

（无效回退时用：`docs: README 记录 JavaFX 8 125% 缩放文字渲染平台局限`）

再执行：

```bash
git add minion.bat README.md && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```

---

### Task 7: 文档同步 + 全量验收

**Files:**
- Modify: `CLAUDE.md`（gui/dialog 包注释）
- Modify: `docs/ARCHITECTURE.md`（MainWindow/TitleBar/dialog/confirm 行）
- Modify: `README.md`（快捷操作补确认弹窗交互说明）

**Interfaces:**
- Consumes: Task 1-6 的全部产物
- Produces: 无

- [ ] **Step 1: CLAUDE.md 包结构**

第 22 行：

```markdown
    │   ├── dialog/           SettingsDialog（设置窗三页签）、ConfirmDialog（高危确认弹窗）
```

改为：

```markdown
    │   ├── dialog/           SettingsDialog（设置窗三页签）、ConfirmSheet（高危确认底部卡片）
```

- [ ] **Step 2: ARCHITECTURE.md 同步四处**

1）第 33 行 MainWindow 行「标题栏页签 selectedItem 监听激活会话」改为「右侧顶部页签栏（tabs-bar，空页签整行隐藏）selectedItem 监听激活会话」；行首描述「右侧消息区+输入区」改为「右侧页签栏+消息区+输入区（外包 StackPane 承载 ConfirmSheet）」。

2）第 40 行 `dialog/SettingsDialog、ConfirmDialog` 行：改为 `dialog/SettingsDialog、ConfirmSheet`；「基础设置 HBox 行布局标签固定 160 宽 + ScrollPane 防裁剪」改为「基础设置 HBox 行布局标签固定 160 宽（去 ScrollPane——裁剪内灰阶 AA 致发虚）」；行尾「高危操作确认弹窗」改为「高危操作确认底部卡片（右侧底部滑入，遮罩仅右侧，Esc 拒绝/Enter 批准，并发串行排队）」。

3）第 42 行 GuiConfirmUi 行：「工具线程 ask → FutureTask 投递 FX 线程弹窗 → 阻塞等待（无 GUI 环境防御性 REJECT）」改为「工具线程 ask → Platform.runLater 投递 ConfirmSheet → poll(3s) 阻塞等待（不阻塞 FX 线程；无 GUI 环境/超时防御性 REJECT）」。

4）第 99 行确认交互 bullet：「GuiConfirmUi 用 FutureTask 把弹窗投到 FX 线程并阻塞工具线程等结果」改为「GuiConfirmUi 经 Platform.runLater 投递 ConfirmSheet，工具线程 poll 等结果（不阻塞 FX 线程）」。

- [ ] **Step 3: README 快捷操作**

在第 30 行（`- 关闭会话页签 = 删除会话（有确认）；删除会话/切换工作空间后右侧自动清空`）之后补一条：

```markdown
- 高危操作确认卡片：右侧底部弹出，Enter 批准 / Esc 拒绝，点遮罩或侧栏不关闭
```

（若 Task 6 走的是「回退+记录」分支，另按 Task 6 Step 2 补 125% 缩放说明。）

- [ ] **Step 4: 全量回归 + 构建**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: BUILD SUCCESS（含 GuiConfirmUiTest）。

Run: `JAVA_HOME="E:/javame/jdk8" mvn clean package`
Expected: BUILD SUCCESS，产物 target/minion-0.1.0.jar。

- [ ] **Step 5: 手工验收总清单（设计文档 §7 全项）**

`minion.bat` 启动后逐项确认：

1. 触发高危操作 → 卡片右侧底部滑入；遮罩仅右侧、侧栏可见；点遮罩无反应；点侧栏无反应；Esc=拒绝；Enter=批准；批准按钮为绿色
2. 两个会话并发触发确认 → 不叠卡，依次弹出
3. 设置窗基础设置页文字锐利；聊天正文锐利
4. 页签在右侧顶部、下方有分隔线、标题栏只有一行；点击页签激活会话、✕ 关闭删除、呼吸点正常；无会话时页签行（含分隔线）隐藏

- [ ] **Step 6: Commit**

先 Write `.git/COMMIT_MSG.txt`：

```
docs: 同步 CLAUDE.md/ARCHITECTURE/README（ConfirmSheet、页签栏、确认交互说明）
```

再执行：

```bash
git add CLAUDE.md docs/ARCHITECTURE.md README.md && git commit -F .git/COMMIT_MSG.txt && rm .git/COMMIT_MSG.txt
```
