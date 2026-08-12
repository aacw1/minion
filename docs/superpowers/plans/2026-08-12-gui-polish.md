# GUI 打磨（16 项）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按设计文档 `docs/superpowers/specs/2026-08-12-gui-polish-design.md` 完成 16 项 GUI 打磨：无边框窗口、设置窗三页签、模型实时生效、弹窗深色、发送/滚动/清空等行为修复、视觉可读性优化。

**Architecture:** 核心改动分三块：① `gui/` 新增 TitleBar/ResizeHelper/Theme/SettingsDialog 四个组件，MainWindow 重排布局（无边框 + SplitPane 1:3）；② 行为修复集中在 MainWindow/InputView/ChatView 接线（清空右侧、自动滚动暂停、发送清空、自动建会话）；③ 模型热更新打通 core 链路（ContextManager 参数可变 + SessionManager.applyModelChanged 全量 propagate + SessionHandle 旧客户端延迟回收）。

**Tech Stack:** JDK 8 + JavaFX 8（jfxrt）、Maven 单模块、gson/okhttp/snakeyaml/flexmark（无新依赖）。资源目录 `src/resource/`。测试：junit4 + mockwebserver（新增测试全部为纯逻辑，无 JavaFX 依赖，可用 `mvn test` 跑）。

## Global Constraints

- JDK 8 兼容；**不引入新依赖**；JavaFX 8 不支持 CSS keyframe 动画（呼吸动画必须用 Timeline，已有 StatusDot 模式）
- 资源目录是 `src/resource`（非 `src/main/resources`）；样式表路径 `/theme/theme.css`
- 跨线程回调一律 `Platform.runLater` 包装；所有弹窗深色统一经 `Theme.style(dialog)` 收口
- `stage.close()` 不触发 `onCloseRequest`——关闭确认抽取 `confirmClose()`，✕ 按钮与系统关闭共用
- 无边框 Stage 失去系统缩放 → 必须挂 `ResizeHelper`；最小尺寸受 `stage.setMinWidth(960)/setMinHeight(640)` 约束
- 模型热更新：旧 LlmClient **不立即 close**（close 会 cancel 运行中请求），登记待回收，会话空闲（running→false）或删除/退出时回收
- 工作空间 workDir/projectMd 修改仅新会话生效（不热更新运行中会话）
- 注释/commit 均中文，commit 用 conventional 格式
- **commit 消息含中文时本机 bash wrapper 崩溃**：所有 commit 用「Write 工具写消息到 `docs/superpowers/.commit-msg`（UTF-8）→ `git commit -F docs/superpowers/.commit-msg` → 删除该文件」流程
- 构建/测试：`JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`（增量编译）、`JAVA_HOME="D:/javame/jdk1.8" mvn -q test -Dtest=XxxTest`（单测试类）、`JAVA_HOME="D:/javame/jdk1.8" mvn -q test`（全量）。首次执行若弹权限提示，允许即可；若提示崩溃（本环境已知问题），用 update-config 技能给用户 settings.json 加 `Bash(mvn *)` 规则后重试

---

### Task 1: Theme 工具类 + theme.css（弹窗深色基建 + 可读性优化，需求 6 基础 / 9）

**Files:**
- Create: `src/main/java/com/minion/gui/theme/Theme.java`
- Modify: `src/resource/theme/theme.css`（追加 dialog-pane 段 + 全局字号/对比度/留白调整）
- Modify: `src/main/java/com/minion/gui/dialog/ConfirmDialog.java`（接入 Theme.style 示范）

**Interfaces:**
- Produces: `Theme.STYLESHEET`（`"/theme/theme.css"`）、`Theme.style(Dialog)`——Task 2/8/9/10 所有弹窗创建点调用；CSS 类 `.dialog-pane`、`.btn-close`、`.resize-edge`（Task 9 用）

- [ ] **Step 1: 新建 Theme 工具类**

```java
package com.minion.gui.theme;

import javafx.scene.control.Dialog;

/** 主题工具：样式表路径常量 + 弹窗深色挂载（Dialog 不继承 Scene 样式表，须显式添加） */
public final class Theme {

    public static final String STYLESHEET = "/theme/theme.css";

    private Theme() { }

    /** 给弹窗挂深色样式表（Alert/TextInputDialog/Dialog 均为 Dialog 子类，getDialogPane() 可用） */
    public static void style(Dialog<?> d) {
        if (d != null && d.getDialogPane() != null) {
            d.getDialogPane().getStylesheets().add(STYLESHEET);
        }
    }
}
```

- [ ] **Step 2: theme.css 追加弹窗深色段**（文件末尾追加；`.dialog-pane` 选择器覆盖 Alert/Dialog 根面板，类名沿用 JavaFX 内置）

```css
/* ===== 弹窗深色（Dialog 不继承 Scene 样式表，经 Theme.style 挂载） ===== */
.dialog-pane {
    -fx-background-color: #15181f;
    -fx-border-color: #2a2f3a;
    -fx-border-width: 1;
}
.dialog-pane .header-panel { -fx-background-color: #15181f; }
.dialog-pane .label { -fx-text-fill: #e6e8ee; }
.dialog-pane .content.label { -fx-text-fill: #f0f2f6; }
.dialog-pane .text-field, .dialog-pane .text-area {
    -fx-background-color: #1a1d24;
    -fx-text-fill: #e6e8ee;
    -fx-prompt-text-fill: #7a828e;
    -fx-border-color: #232733;
    -fx-control-inner-background: #1a1d24;
}
.dialog-pane .check-box { -fx-text-fill: #e6e8ee; }
.dialog-pane .check-box .box { -fx-background-color: #1a1d24; -fx-border-color: #232733; }
.dialog-pane .check-box:selected .mark { -fx-background-color: #4f8cff; }
.dialog-pane .combo-box { -fx-background-color: #1a1d24; }
.dialog-pane .combo-box .list-cell { -fx-text-fill: #e6e8ee; }
.dialog-pane .button {
    -fx-background-color: transparent;
    -fx-text-fill: #a8b0bb;
    -fx-border-color: #232733;
    -fx-border-radius: 6;
    -fx-background-radius: 6;
    -fx-padding: 6 14 6 14;
    -fx-cursor: hand;
}
.dialog-pane .button:hover { -fx-text-fill: #e6e8ee; -fx-border-color: #4f8cff; }
.dialog-pane .button:default {
    -fx-background-color: linear-gradient(to bottom, #4f8cff, #3b6fe0);
    -fx-text-fill: white;
    -fx-font-weight: bold;
    -fx-border-color: transparent;
}
.dialog-pane .button:default:hover { -fx-background-color: linear-gradient(to bottom, #5d98ff, #4f8cff); }
```

- [ ] **Step 3: theme.css 可读性优化**（需求 9——替换现有规则，只调颜色/字号/留白，不动布局结构）

| 现有规则 | 改为 |
|---|---|
| `.root` 的 `-fx-font-size: 13px` | `14px` |
| `.topbar-title` `#e6e8ee` 14px | `#f0f2f6` 14px |
| `.topbar-model` `#8b949e` 12px | `#a8b0bb` 12px |
| `.btn-ghost` text-fill `#8b949e` | `#a8b0bb`；hover text-fill `#e6e8ee` → `#f0f2f6` |
| `.input-area` text-fill `#e6e8ee`、prompt `#5a6270` | text-fill `#f0f2f6`、prompt `#7a828e` |
| `.list-view .list-cell` padding `7 10`、text-fill `#c9cdd6` | padding `9 12`、text-fill `#d3d7de` |
| `.list-view .list-cell:hover` `#1c2029` | `#20242e` |
| `.list-view .list-cell:selected` `#232a38`、text `#e6e8ee` | `#2a3344`、text `#f0f2f6` |
| `.tab-pane .tab-label` `#8b949e` | `#a8b0bb`；selected `#e6e8ee` → `#f0f2f6`；`.tab-pane .tab` 加 `-fx-padding: 4 12;` |
| `.msg-assistant` text-fill `#e6e8ee` | `#f0f2f6` |
| `.msg-thinking` text-fill `#6b7280` | `#98a0ab` |
| `.code-block` font-size 12px、padding `8 12` | font-size 13px、padding `10 14` |
| `.card` 加 `-fx-padding: 10 12;` | — |
| `.scroll-bar:vertical` 无尺寸 | 加 `-fx-pref-width: 10px;`；`.scroll-bar:vertical .thumb` 加 `-fx-background-radius: 5;` |
| `.section-title` `#8b949e` 11px | `#a8b0bb` 12px |
| 新增 `.btn-close`（窗口关闭按钮） | `-fx-background-color: transparent; -fx-text-fill: #a8b0bb; -fx-background-radius: 6; -fx-cursor: hand;` + `:hover { -fx-background-color: #c93a39; -fx-text-fill: white; }` |
| 新增 `.resize-edge`（无边框窗口边缘，Task 9 用） | `-fx-background-color: transparent;` |

- [ ] **Step 4: ConfirmDialog 接入 Theme.style（示范点）**

```java
public class ConfirmDialog {
    public static ConfirmUi.Decision show(String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("确认操作");
        a.setHeaderText(message);
        Theme.style(a); // 弹窗深色
        ...
    }
}
```
（`import com.minion.gui.theme.Theme;`）

- [ ] **Step 5: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 6: Commit**

消息文本（Write 到 `docs/superpowers/.commit-msg`）：
`feat: 主题工具 Theme + 弹窗深色样式与可读性优化（需求 6/9 基建）`

```bash
git add src/main/java/com/minion/gui/theme/Theme.java src/resource/theme/theme.css src/main/java/com/minion/gui/dialog/ConfirmDialog.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 2: 存量弹窗全部接入 Theme.style（需求 6 收口）

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java`（3 处弹窗）
- Modify: `src/main/java/com/minion/gui/sidebar/SessionListView.java`（2 处弹窗）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（新建工作空间弹窗、新建失败 Alert——注意 Task 9 会重写 show()，此处改动会随 Task 9 保留；退出确认 Alert 在 Task 9 的 confirmClose() 里处理，本任务不动）

**Interfaces:**
- Consumes: `Theme.style(Dialog<?>)`（Task 1）
- 说明：MainWindow 的弹窗接入随 Task 9 重写时一并完成，本任务只处理侧栏两处 + ConfirmDialog（Task 1 已示范）

- [ ] **Step 1: WorkspaceListView 三处接入**（`doRename` 的 TextInputDialog、`doEdit` 的 Dialog、`doDelete` 与 `error` 的 Alert）

每个弹窗创建后紧跟一行 `Theme.style(d);` / `Theme.style(a);`。加 import `com.minion.gui.theme.Theme;`。TextInputDialog 是 Dialog 子类，`Theme.style(TextInputDialog)` 泛型匹配 `Dialog<?>`。

- [ ] **Step 2: SessionListView 两处接入**（重命名 TextInputDialog、删除确认 Alert，同 Step 1 模式）

- [ ] **Step 3: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

消息：`feat: 存量弹窗全部挂深色主题（需求 6 收口）`

```bash
git add src/main/java/com/minion/gui/sidebar/
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 3: 标题本地截取前 20 字（需求 8）

**Files:**
- Modify: `src/main/java/com/minion/core/agent/TitleGenerator.java`（删除 buildPrompt/clean/fallbackTitle，新增 localTitle）
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（send() 同步置标题；删 titlePool/generateTitle 及不再用的 import）
- Test: `src/test/java/com/minion/core/agent/TitleGeneratorTest.java`（重写）
- Test: `src/test/java/com/minion/gui/session/SessionManagerTest.java`（新增 send 标题断言）

**Interfaces:**
- Produces: `TitleGenerator.localTitle(String) -> String`（去换行/首尾空白、截前 20 字、空回退「新会话」）
- Consumes: 无（SessionManager.send 内部使用）

- [ ] **Step 1: 重写 TitleGeneratorTest（先写测试）**

```java
package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class TitleGeneratorTest {

    @Test
    public void localTitle_truncatesTo20Chars() {
        String longText = "帮我修复登录问题需要修改三个文件的位置和配置信息";
        String t = TitleGenerator.localTitle(longText);
        assertEquals(TitleGenerator.MAX_TITLE_LEN, t.length());
        assertEquals(longText.substring(0, 20), t);
    }

    @Test
    public void localTitle_normalizesNewlinesAndTrim() {
        assertEquals("修复乱码", TitleGenerator.localTitle("  修复乱码  "));
        assertEquals("a b", TitleGenerator.localTitle("a\nb"));
        assertEquals("a b c", TitleGenerator.localTitle("a\r\nb\nc"));
    }

    @Test
    public void localTitle_shortTextUnchanged() {
        assertEquals("修复登录", TitleGenerator.localTitle("修复登录"));
    }

    @Test
    public void localTitle_emptyFallsBackToNewSession() {
        assertEquals("新会话", TitleGenerator.localTitle(""));
        assertEquals("新会话", TitleGenerator.localTitle("   "));
        assertEquals("新会话", TitleGenerator.localTitle(null));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test -Dtest=TitleGeneratorTest`
Expected: FAIL——`localTitle` 不存在，编译错误

- [ ] **Step 3: 重写 TitleGenerator**

```java
package com.minion.core.agent;

/** 新会话标题生成：本地截取（前 MAX_TITLE_LEN 字），不再调 LLM 摘要 */
public class TitleGenerator {

    public static final int MAX_TITLE_LEN = 20;

    /** 本地标题：去换行/首尾空白，截取前 20 字；空输入回退「新会话」 */
    public static String localTitle(String text) {
        String t = text == null ? "" : text.trim().replace('\n', ' ').replace('\r', ' ');
        if (t.length() > MAX_TITLE_LEN) t = t.substring(0, MAX_TITLE_LEN);
        return t.isEmpty() ? "新会话" : t;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test -Dtest=TitleGeneratorTest`
Expected: PASS（4 个用例）

- [ ] **Step 5: SessionManager.send 同步置标题、删 LLM 摘要路径**

修改 `send()` 内（原 404-411 行区域）：

```java
if (h.titlePending) {
    h.title = TitleGenerator.localTitle(text); // 本地截取，不再走 LLM 摘要
    if (h.deleted) return; // 摘要期间被删除：不再落盘/通知
    h.titlePending = false;
    h.session.title = h.title;
    persist(h);
    notifyTitleChanged(h);
}
```

删除：`titlePool` 字段（80-84 行）、`generateTitle` 方法（436-462 行）、import `java.util.concurrent.ExecutionException`、`java.util.concurrent.TimeoutException`、`com.minion.core.llm.Message`、`com.minion.core.llm.LlmClient` 若不再使用（检查后删除——`LlmClient` 仅 generateTitle 用过）、`Future`/`TimeUnit` 若不再使用（generateTitle 用了 Future/TimeUnit；send 其他处无。逐一核对删除）。

- [ ] **Step 6: SessionManagerTest 新增 send 标题断言**

```java
/** 需求 8：send 后标题为本地截取的前 20 字（不再走 LLM 摘要） */
@Test
public void send_setsLocalTitleSynchronously() throws Exception {
    SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
    SessionHandle h = m.createSession(null);
    assertTrue(h.titlePending);
    final CountDownLatch titleSet = new CountDownLatch(1);
    m.addListener(new SessionManager.Listener() {
        @Override public void onSessionTitleChanged(SessionHandle h) { titleSet.countDown(); }
        @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
        @Override public void onSessionActivated(SessionHandle h) { }
        @Override public void onWorkspaceChanged() { }
        @Override public void onError(String message) { } // FakeLlmClient 无脚本 turn 会抛异常，走 onError，忽略
    });
    String longText = "帮我修复登录问题需要修改三个文件的位置和配置";
    m.send(h, longText);
    assertTrue("标题回调超时", titleSet.await(5, TimeUnit.SECONDS));
    assertFalse(h.titlePending);
    assertTrue(h.title.length() <= TitleGenerator.MAX_TITLE_LEN);
    assertEquals(longText.substring(0, 5), h.title.substring(0, 5));
}
```
注：测试方法需自建 SpyManager 局部变量（现有 `newManager()` 返回 SessionManager 基类）。FakeLlmClient 无脚本 turn 时 `streamChat` 抛 IndexOutOfBoundsException，被 AgentLoop 捕获走 onError——线程正常结束，不影响标题断言。

- [ ] **Step 7: 全量测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test`
Expected: PASS（TitleGeneratorTest 4 例 + SessionManagerTest 新增例 + 存量全绿）

- [ ] **Step 8: Commit**

消息：`feat: 标题改本地截取前 20 字，移除 LLM 摘要生成（需求 8）`

```bash
git add src/main/java/com/minion/core/agent/TitleGenerator.java src/main/java/com/minion/gui/session/SessionManager.java src/test/java/com/minion/core/agent/TitleGeneratorTest.java src/test/java/com/minion/gui/session/SessionManagerTest.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 4: InputView——发送清空 / 按钮靠下 / 无会话自动建会话（需求 4/12/14）

**Files:**
- Modify: `src/main/java/com/minion/gui/input/InputView.java`

**Interfaces:**
- Consumes: `SessionManager.createSession(String) -> SessionHandle`（可返回 null——当前工作空间删除中）、`SessionManager.activateSession(SessionHandle)`、`SessionManager.send(SessionHandle, String)`（均为现有签名）
- 说明：`bindSession` 在 MainWindow.onSessionActivated 的 FX 回调中设置 `current`——自动建会话时**直接传新句柄**给 send，不依赖字段时序

- [ ] **Step 1: 改造 InputView**

```java
package com.minion.gui.input;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** 底部输入区：多行 TextArea（自适应 1→6 行）+ 下方靠右发送/终止按钮；无会话时发送自动建会话 */
public class InputView extends VBox {

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button("⤒ 发送");
    private volatile SessionHandle current;

    public InputView(final SessionManager manager) {
        this.manager = manager;
        getStyleClass().add("panel-dark");
        setSpacing(8);
        setStyle("-fx-padding: 12 16 12 16;");

        input.getStyleClass().add("input-area");
        input.setWrapText(true);
        input.setPromptText("输入消息…  (Ctrl+Enter 发送)");
        input.setPrefRowCount(2);
        input.setMaxHeight(6 * 24);

        input.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onSend();
            }
        });

        sendButton.getStyleClass().add("btn-primary");
        updateButton(false);

        // 需求 4：TextArea 在上（弹性占高），按钮行在下、按钮靠右下（Region 弹性填充）
        HBox buttonRow = new HBox(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonRow.getChildren().addAll(spacer, sendButton);
        VBox.setVgrow(input, Priority.ALWAYS);
        getChildren().addAll(input, buttonRow);
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        Platform.runLater(() -> updateButton(h == null ? false : h.running));
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> updateButton(running));
    }

    private void updateButton(boolean running) {
        if (running) {
            sendButton.setText("■ 终止");
            sendButton.getStyleClass().remove("btn-primary");
            sendButton.getStyleClass().add("btn-danger");
            sendButton.setTooltip(new Tooltip("终止当前运行"));
            sendButton.setOnAction(e -> manager.stop(current));
        } else {
            sendButton.setText("⤒ 发送");
            sendButton.getStyleClass().remove("btn-danger");
            sendButton.getStyleClass().add("btn-primary");
            sendButton.setTooltip(new Tooltip("发送 (Ctrl+Enter)"));
            sendButton.setOnAction(e -> onSend());
        }
    }

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.clear(); // 需求 14：发送后清空输入框
        SessionHandle target = current;
        if (target == null) {
            // 需求 12：无激活会话时发送自动建会话（激活回调会绑定右侧面板；send 直接传新句柄）
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        manager.send(target, text);
    }
}
```

- [ ] **Step 2: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS（删除 `lastSent` 字段与 `onRunningChanged` 草稿逻辑后的编译通过；SessionListView/MainWindow 未引用这些成员）

- [ ] **Step 3: Commit**

消息：`feat: 输入区按钮靠下、发送即清空、无会话发送自动建会话（需求 4/12/14）`

```bash
git add src/main/java/com/minion/gui/input/InputView.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 5: 删除会话 / 切换工作空间后清空右侧面板（需求 11/16）

**Files:**
- Modify: `src/main/java/com/minion/gui/chat/ChatView.java`（加 `handle()` getter）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（clearChatPane + 三处接入点）

**Interfaces:**
- Produces: `MainWindow.clearChatPane()`（解绑 → 占位 → 解绑输入区 → 置 null）、`ChatView.handle() -> SessionHandle`
- Consumes: `ChatView.bind(boolean)`（现有）、`ChatView.clear()`（现有，回「输入消息开始新的会话」占位）、`InputView.bindSession(SessionHandle)`（Task 4 已支持 null）

- [ ] **Step 1: ChatView 加 getter**

```java
    /** 本视图绑定的会话句柄（MainWindow 判断「删除的是当前展示会话」用） */
    public SessionHandle handle() { return handle; }
```

- [ ] **Step 2: MainWindow 新增 clearChatPane 并接入三处**

```java
    /** 清空右侧面板：解绑事件流、回占位提示、解绑输入区（删除会话/切换工作空间后调用） */
    private void clearChatPane() {
        if (chatView != null) {
            chatView.bind(false);  // 分离监听器（EventList 缓冲保留，会话仍在时下次 bind(true) 重放）
            chatView.clear();      // 回「输入消息开始新的会话」占位
        }
        chatView = null;
        if (inputView != null) inputView.bindSession(null); // current=null → 下次发送自动建会话
    }
```

接入点 1——侧栏右键删除回调（原 83-84 行）：
```java
sessionList = new SessionListView(manager,
        h -> {
            removeTabById(h.id);
            if (chatView != null && chatView.handle() == h) clearChatPane(); // 删除当前展示会话 → 右侧清空
            sessionList.refresh();
        });
```

接入点 2——页签关闭路径（addTab 的 onCloseRequest 内，原 244-250 行区域）：
```java
if (bt == ButtonType.OK) {
    manager.deleteSession(h);
    tabs.getTabs().remove(t);
    if (chatView != null && chatView.handle() == h) clearChatPane();
    sessionList.refresh();
}
```

接入点 3——onWorkspaceChanged（原 176-178 行）：
```java
@Override public void onWorkspaceChanged() {
    Platform.runLater(() -> {
        clearChatPane(); // 需求 16：切换工作空间后右侧清空（先清再刷列表/页签）
        wsList.refresh();
        sessionList.refresh();
        rebuildTabs();
    });
}
```

- [ ] **Step 3: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

消息：`fix: 删除会话/切换工作空间后清空右侧面板（需求 11/16）`

```bash
git add src/main/java/com/minion/gui/chat/ChatView.java src/main/java/com/minion/gui/MainWindow.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 6: 自动滚动——左键拖动滚动条时暂停（需求 10）

**Files:**
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（chatScroll 装配处）

**Interfaces:**
- Consumes: JavaFX `ScrollPane.skinProperty()`（skin 就绪后 lookupAll 才有内容）、`ScrollBar`（`javafx.scene.control.ScrollBar`）
- 说明：Task 9 重写 show() 时必须保留 `setupAutoScroll()` 方法及调用

- [ ] **Step 1: 抽出 setupAutoScroll 并加拖拽检测**

替换现有 vmax 监听（原 146-153 行区域）：

```java
        chatScroll = new ScrollPane();
        chatScroll.setFitToWidth(true);
        chatScroll.setContent(new Region()); // 激活会话后换 ChatView
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        setupAutoScroll();
```

新增方法（MainWindow 内）：

```java
    /** 需求 10：消息区自动滚动到底；用户左键拖动滚动条期间暂停，释放后恢复 */
    private void setupAutoScroll() {
        final boolean[] dragging = new boolean[1];
        // skin 就绪后才有滚动条节点（lookupAll 在 CSS 应用前为空）
        chatScroll.skinProperty().addListener((obs, oldS, newS) -> {
            if (newS == null) return;
            for (Node n : chatScroll.lookupAll(".scroll-bar")) {
                if (!(n instanceof ScrollBar)) continue;
                ScrollBar sb = (ScrollBar) n;
                if (sb.getOrientation() != Orientation.VERTICAL) continue;
                sb.setOnMousePressed(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) dragging[0] = true; // 仅左键拖动暂停
                });
                sb.setOnMouseReleased(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) dragging[0] = false;
                });
            }
        });
        chatScroll.vmaxProperty().addListener((obs, oldV, newV) -> {
            if (dragging[0]) return; // 用户正在拖动滚动条：不抢滚动位置
            double max = newV == null ? 0 : newV.doubleValue();
            Platform.runLater(() -> chatScroll.setVvalue(max));
        });
    }
```

新增 import：`javafx.scene.control.ScrollBar`、`javafx.scene.input.MouseButton`、`javafx.geometry.Orientation`（Node 已有）。

- [ ] **Step 2: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

消息：`fix: 输入新内容自动滚到底，左键拖动滚动条时暂停（需求 10）`

```bash
git add src/main/java/com/minion/gui/MainWindow.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 7: 模型/参数修改实时生效——core 链路（需求 13）

**Files:**
- Modify: `src/main/java/com/minion/core/context/ContextManager.java`（参数/llm 字段改非 final + update/setLlm）
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`（contextManager 非 final + setContextManager）
- Modify: `src/main/java/com/minion/gui/session/SessionHandle.java`（llm 改 volatile + retiredLlms + retireLlm/closeAll/closeRetired）
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（applyModelChanged + notifyRunningChanged 空闲回收 + deleteSession/deleteWorkspace/shutdown 改 closeAll）
- Test: `src/test/java/com/minion/core/context/ContextManagerTest.java`（update/setLlm）
- Test: `src/test/java/com/minion/gui/session/SessionManagerTest.java`（applyModelChanged 替换与回收）

**Interfaces:**
- Produces: `ContextManager.update(int, double, int)`、`ContextManager.setLlm(LlmClient)`、`AgentLoop.setContextManager(ContextManager)`、`SessionHandle.retireLlm(LlmClient)`、`SessionHandle.closeAll()`、`SessionHandle.closeRetired()`、`SessionManager.applyModelChanged()`
- Consumes: `AgentLoop.setLlm(LlmClient)`（已存在）、`AgentLoop.contextManager()`（已存在）

- [ ] **Step 1: ContextManager 可变化**

```java
    private int maxContextTokens;      // final 移除
    private double threshold;
    private int keepRecent;
    private LlmClient llm;
    private final int systemTokens;    // system 提示词 token 估算在构造时固定，保持不变

    /** 模型参数热更新（设置窗修改后调用；运行时生效于下一轮压缩判断） */
    public void update(int maxContextTokens, double threshold, int keepRecent) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    /** 换 LLM 客户端（模型切换后调用；压缩请求走新客户端） */
    public void setLlm(LlmClient llm) { this.llm = llm; }
```
（maxTokens()/shouldCompress() 等现有方法无需改动，读取字段即可）

- [ ] **Step 2: AgentLoop 可变化**

```java
    private ContextManager contextManager; // null = 不启用压缩；final 移除

    /** 替换上下文管理器（模型参数热更新时用于换新实例；现有实例变更参数用 contextManager().update） */
    public void setContextManager(ContextManager cm) { this.contextManager = cm; }
```

- [ ] **Step 3: SessionHandle 旧客户端延迟回收**

```java
    /** 会话独享的 LLM 客户端（换模型时换新实例；删除/退出时 close 释放 okhttp 资源） */
    public volatile LlmClient llm; // 由 final 改 volatile：模型热更新允许换实例

    /** 已退役（换模型替换下来）的客户端：close 会 cancel 运行中请求，运行中不能立即关，登记待回收 */
    private final List<LlmClient> retiredLlms = new ArrayList<LlmClient>();

    /** 登记待回收客户端 */
    public synchronized void retireLlm(LlmClient old) {
        if (old != null && old != llm) retiredLlms.add(old);
    }

    /** 关闭全部客户端（当前 + 待回收）：会话删除/工作空间删除/应用退出时调用 */
    public synchronized void closeAll() {
        llm.close();
        for (LlmClient c : retiredLlms) c.close();
        retiredLlms.clear();
    }

    /** 会话空闲（running→false）时回收换模型遗留的旧客户端 */
    public synchronized void closeRetired() {
        for (LlmClient c : retiredLlms) c.close();
        retiredLlms.clear();
    }
```
（加 import `java.util.ArrayList`、`java.util.List`）

- [ ] **Step 4: SessionManager——applyModelChanged + 空闲回收 + 关闭路径**

```java
    /**
     * 模型/参数变更 propagate：全部工作空间全部会话换新 LLM 客户端 + 压缩参数热更新。
     * 旧客户端登记待回收（close 会 cancel 运行中请求，不可立即关）；会话空闲时回收。
     */
    public void applyModelChanged() {
        ModelConfig mc = models.current();
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                LlmClient fresh = newLlm(mc);
                h.retireLlm(h.llm); // 先登记旧客户端再换引用
                h.llm = fresh;
                h.loop.setLlm(fresh); // 下轮请求生效
                ContextManager cm = h.loop.contextManager();
                if (cm != null) {
                    cm.setLlm(fresh);
                    cm.update(mc.maxContextTokens, mc.compressThreshold, mc.keepRecentMessages);
                }
            }
        }
    }

    /** running 回调：会话空闲时顺带回收换模型遗留的旧客户端（防 okhttp 资源滞留） */
    private void notifyRunningChanged(SessionHandle h, boolean running) {
        if (!running) h.closeRetired();
        for (Listener l : listeners) l.onSessionRunningChanged(h, running);
    }
```

三处关闭路径 `h.llm.close()` → `h.closeAll()`：
- `deleteSession`（原 283 行）
- `deleteWorkspace` 循环内（原 357 行）
- `shutdown`（原 495 行）

- [ ] **Step 5: ContextManagerTest 新增 update/setLlm 用例**

```java
    @Test
    public void update_changesCompressionParams() {
        ContextManager cm = new ContextManager(1000, 0.8, 5, new FakeLlmClient(), 10);
        cm.update(2000, 0.5, 8);
        assertEquals(2000, cm.maxTokens());
        assertTrue(cm.shouldCompress(sampleHistory())); // 新阈值下必压缩
    }

    @Test
    public void setLlm_usedForCompressionRequests() {
        FakeLlmClient a = new FakeLlmClient();
        a.compressResult = "【摘要】A";
        FakeLlmClient b = new FakeLlmClient();
        b.compressResult = "【摘要】B";
        ContextManager cm = new ContextManager(100, 0.1, 0, a, 10); // 阈值极低：必触发压缩
        List<Message> out = cm.compress(sampleHistory());
        assertNotNull(a.lastRequestMessages); // 压缩走旧客户端
        cm.setLlm(b);
        out = cm.compress(sampleHistory());
        assertTrue(out.toString().contains("【摘要】B")); // 换客户端后走新客户端
    }
```
（sampleHistory() 为现有私有工具方法，直接复用）

- [ ] **Step 6: SessionManagerTest 新增 applyModelChanged 用例**

```java
    /** 需求 13：模型变更 propagate——全部会话换新客户端，旧客户端登记待回收不立即 close，删除时全关 */
    @Test
    public void applyModelChanged_replacesLlmAndRetiresOld() throws Exception {
        SpyManager m = new SpyManager(FAKE_UI, config, jar, ws, models);
        SessionHandle h1 = m.createSession(null);
        SessionHandle h2 = m.createSession(null);
        FakeLlmClient old1 = m.created.get(0);
        FakeLlmClient old2 = m.created.get(1);

        m.applyModelChanged();

        assertEquals(4, m.created.size()); // 两个会话各新建一个
        assertNotSame(old1, h1.llm);
        assertNotSame(old2, h2.llm);
        assertEquals(0, old1.closeCount); // 旧客户端不立即 close（可能 in-flight）
        assertEquals(0, old2.closeCount);

        m.deleteSession(h1); // 删除：当前 + 待回收全部关闭
        assertEquals(1, old1.closeCount);
        assertEquals(1, ((FakeLlmClient) h1.llm).closeCount);
    }
```
（测试文件内需在 `newManager()` 外新增局部 SpyManager 构建，沿用现有 `SpyManager` 内部类）

- [ ] **Step 7: 全量测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test`
Expected: PASS（新增 3 例 + 存量全绿）

- [ ] **Step 8: Commit**

消息：`feat: 模型/参数修改实时生效——applyModelChanged 全量 propagate + 旧客户端延迟回收（需求 13）`

```bash
git add src/main/java/com/minion/core/context/ContextManager.java src/main/java/com/minion/core/agent/AgentLoop.java src/main/java/com/minion/gui/session/SessionHandle.java src/main/java/com/minion/gui/session/SessionManager.java src/test/java/com/minion/core/context/ContextManagerTest.java src/test/java/com/minion/gui/session/SessionManagerTest.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 8: SettingsDialog 三页签设置窗 + Config 写回（需求 2 / 13 触发点）

**Files:**
- Create: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`
- Create: `src/main/java/com/minion/core/config/Config.java`（新增 set 方法——Modify）
- Delete: `src/main/java/com/minion/gui/dialog/ModelDialog.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（⚙ 按钮改调 SettingsDialog）
- Test: `src/test/java/com/minion/core/config/ConfigTest.java`（set 持久化）

**Interfaces:**
- Produces: `SettingsDialog.show(Window, ModelManager, SessionManager, Config)`（阻塞 showAndWait；关闭后 MainWindow 刷新顶部模型名）、`Config.set(String key, String value)`
- Consumes: `Theme.style`（Task 1）、`SessionManager.applyModelChanged()`（Task 7）、`ModelManager.list/get/add/update/remove/setCurrent`（现有）

- [ ] **Step 1: ConfigTest 新增 set 用例（先写测试）**

```java
    /** 需求 2/13：Config.set 更新内存并写回外部文件（设置窗基础设置页保存用） */
    @Test
    public void set_updatesMemoryAndPersists() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        c.set("confirm.skip", "true");
        assertTrue(c.confirmSkip());
        // 重载验证外部文件已写回
        Config c2 = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertTrue(c2.confirmSkip());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test -Dtest=ConfigTest`
Expected: FAIL——`set` 方法不存在，编译错误

- [ ] **Step 3: Config 新增 set 方法**

```java
    /**
     * 运行时写回配置：更新内存 + 重写外部 config.properties（保留注释行，替换/追加 key 行）。
     * 实时生效核对：confirmSkip/whitelist/readAllowOutside 每次使用即读 Config → 立即生效；
     * skills.dir 由新会话 buildCtx 读取 → 新会话生效。
     */
    public void set(String key, String value) {
        props.put(key, value);
        try {
            List<String> lines = Files.exists(externalFile)
                    ? Files.readAllLines(externalFile, StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            for (String line : lines) {
                if (line.trim().startsWith(key + "=")) {
                    sb.append(key).append('=').append(value).append('\n');
                    replaced = true;
                } else {
                    sb.append(line).append('\n');
                }
            }
            if (!replaced) sb.append(key).append('=').append(value).append('\n');
            Files.write(externalFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[minion] 写入配置失败: " + e.getMessage());
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test -Dtest=ConfigTest`
Expected: PASS

- [ ] **Step 5: 新建 SettingsDialog**

```java
package com.minion.gui.dialog;

import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/** 设置窗（右上角 ⚙）：左侧页签 模型 / 基础设置 / 关于；模型操作后触发 applyModelChanged 实时生效 */
public class SettingsDialog {

    public static void show(Window owner, final ModelManager models,
                            final SessionManager manager, final Config config) {
        Dialog<Void> d = new Dialog<Void>();
        d.initOwner(owner);
        d.setTitle("设置");
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Theme.style(d);

        TabPane tp = new TabPane();
        tp.setSide(javafx.geometry.Side.LEFT);
        tp.setTabMinWidth(100);
        tp.getTabs().add(modelTab(models, manager));
        tp.getTabs().add(basicTab(config));
        tp.getTabs().add(aboutTab());
        tp.setPrefSize(560, 480);
        d.getDialogPane().setContent(tp);
        d.showAndWait();
    }

    // ===== 模型页（迁移自 ModelDialog + propagate） =====

    private static Tab modelTab(final ModelManager models, final SessionManager manager) {
        final ListView<String> list = new ListView<String>();
        list.setCellFactory(lv -> new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item + (item.equals(models.currentName()) ? "  ●" : ""));
            }
        });
        refresh(list, models);
        list.setPrefSize(360, 240);

        list.setOnMouseClicked(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0 || e.getClickCount() != 1) return;
            String name = list.getItems().get(idx);
            if (!name.equals(models.currentName())) {
                models.setCurrent(name);
                manager.applyModelChanged(); // 需求 13：切换模型全量生效
            }
            refresh(list, models);
        });

        HBox actions = new HBox(8);
        Button add = new Button("新建");
        Button edit = new Button("修改");
        Button del = new Button("删除");
        add.getStyleClass().add("btn-ghost");
        edit.getStyleClass().add("btn-ghost");
        del.getStyleClass().add("btn-ghost");
        add.setOnAction(e -> {
            ModelConfig mc = form(null);
            if (mc != null) {
                if (!models.add(mc)) error("新建失败", "标识名非法或已存在");
                if (models.currentName().equals(mc.displayName)) manager.applyModelChanged();
            }
            refresh(list, models);
        });
        edit.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            ModelConfig mc = form(models.get(list.getItems().get(idx)));
            if (mc != null) {
                models.update(mc);
                manager.applyModelChanged(); // 参数修改实时生效（含运行中会话，下一轮生效）
            }
            refresh(list, models);
        });
        del.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            String name = list.getItems().get(idx);
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除模型「" + name + "」？", ButtonType.OK, ButtonType.CANCEL);
            Theme.style(a);
            a.setTitle("删除模型");
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                if (!models.remove(name)) error("删除失败", "至少保留一个模型");
                manager.applyModelChanged(); // 删除后 current 可能回落，统一 propagate
            }
            refresh(list, models);
        });
        actions.getChildren().addAll(add, edit, del);

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.getChildren().addAll(list, actions);
        Tab tab = new Tab("模型", box);
        tab.setClosable(false);
        return tab;
    }

    private static void refresh(ListView<String> list, ModelManager models) {
        list.getItems().clear();
        for (ModelConfig m : models.list()) {
            list.getItems().add(m.displayName);
        }
        int idx = list.getItems().indexOf(models.currentName());
        list.getSelectionModel().select(idx < 0 ? 0 : idx);
    }

    /** 新建（mc==null 带默认值）/ 修改（mc!=null 预填）表单；OK 返回配置，取消返回 null（迁移自 ModelDialog） */
    private static ModelConfig form(ModelConfig mc) {
        Dialog<ModelConfig> d = new Dialog<ModelConfig>();
        d.setTitle(mc == null ? "新建模型" : "修改模型");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        TextField displayName = new TextField(mc == null ? "" : mc.displayName);
        displayName.setPromptText("页签显示标识");
        if (mc != null) displayName.setDisable(true); // ModelManager.update 不复制 displayName
        TextField url = new TextField(mc == null ? "https://api.deepseek.com/v1/chat/completions" : mc.url);
        TextField apiKey = new TextField(mc == null ? "" : mc.apiKey);
        apiKey.setPromptText("sk-...");
        TextField modelName = new TextField(mc == null ? "" : mc.modelName);
        TextField provider = new TextField(mc == null ? "deepseek" : mc.provider);
        CheckBox thinking = new CheckBox("深度思考");
        thinking.setSelected(mc != null && mc.thinking);
        ComboBox<String> effort = new ComboBox<String>();
        effort.getItems().addAll("low", "medium", "high", "max");
        effort.setValue(mc == null ? "max" : mc.reasoningEffort);
        TextField maxCtx = new TextField(mc == null ? "900000" : String.valueOf(mc.maxContextTokens));
        TextField thr = new TextField(mc == null ? "0.8" : String.valueOf(mc.compressThreshold));
        TextField keep = new TextField(mc == null ? "10" : String.valueOf(mc.keepRecentMessages));

        grid.addRow(0, new Label("标识名:"), displayName);
        grid.addRow(1, new Label("URL:"), url);
        grid.addRow(2, new Label("API Key:"), apiKey);
        grid.addRow(3, new Label("模型名:"), modelName);
        grid.addRow(4, new Label("provider:"), provider);
        grid.addRow(5, new Label("思考:"), thinking);
        grid.addRow(6, new Label("effort:"), effort);
        grid.addRow(7, new Label("maxContextTokens:"), maxCtx);
        grid.addRow(8, new Label("compressThreshold:"), thr);
        grid.addRow(9, new Label("keepRecentMessages:"), keep);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            ModelConfig out = new ModelConfig();
            out.displayName = displayName.getText().trim();
            out.url = url.getText().trim();
            out.apiKey = apiKey.getText().trim();
            out.modelName = modelName.getText().trim();
            out.provider = provider.getText().trim();
            out.thinking = thinking.isSelected();
            out.reasoningEffort = effort.getValue() == null ? "max" : effort.getValue();
            out.maxContextTokens = parseInt(maxCtx.getText(), 900000);
            out.compressThreshold = parseDouble(thr.getText(), 0.8);
            out.keepRecentMessages = parseInt(keep.getText(), 10);
            return out;
        });
        Optional<ModelConfig> r = d.showAndWait();
        return r.isPresent() ? r.get() : null;
    }

    // ===== 基础设置页 =====

    private static Tab basicTab(final Config config) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        TextField skillsDir = new TextField(config.skillsDir());
        skillsDir.setPrefWidth(320);
        TextArea toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
        toolWhitelist.setPrefRowCount(2);
        TextArea cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
        cmdWhitelist.setPrefRowCount(2);
        CheckBox allowOutside = new CheckBox("允许读取工作区外文件（Read/Grep/Glob）");
        allowOutside.setSelected(config.readAllowOutside());
        CheckBox skipConfirm = new CheckBox("跳过高危操作确认");
        skipConfirm.setSelected(config.confirmSkip());

        grid.addRow(0, new Label("技能目录 skills.dir:"), skillsDir);
        grid.addRow(1, new Label("确认白名单\n(工具, 逗号分隔):"), toolWhitelist);
        grid.addRow(2, new Label("确认白名单\n(命令, 逗号分隔):"), cmdWhitelist);
        grid.addRow(3, new Label("读逃逸:"), allowOutside);
        grid.addRow(4, new Label("确认开关:"), skipConfirm);

        Label browserNote = new Label("浏览器配置（以下项需重启后生效）");
        browserNote.getStyleClass().add("msg-thinking");
        grid.addRow(5, new Label(""), browserNote);
        TextField browserPath = new TextField(config.browserPath());
        TextField browserPort = new TextField(String.valueOf(config.browserPort()));
        TextField browserUserData = new TextField(config.browserUserDataDir());
        CheckBox browserHeadless = new CheckBox("无头模式");
        browserHeadless.setSelected(config.browserHeadless());
        TextField browserTimeout = new TextField(String.valueOf(config.browserTimeoutMs()));
        grid.addRow(6, new Label("browser.path:"), browserPath);
        grid.addRow(7, new Label("browser.port:"), browserPort);
        grid.addRow(8, new Label("browser.userDataDir:"), browserUserData);
        grid.addRow(9, new Label("browser.headless:"), browserHeadless);
        grid.addRow(10, new Label("browser.timeoutMs:"), browserTimeout);

        Button save = new Button("保存");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            config.set("skills.dir", skillsDir.getText().trim());
            config.set("confirm.whitelist.tools", toolWhitelist.getText().trim());
            config.set("confirm.whitelist.commands", cmdWhitelist.getText().trim());
            config.set("paths.read.allowOutside", String.valueOf(allowOutside.isSelected()));
            config.set("confirm.skip", String.valueOf(skipConfirm.isSelected()));
            config.set("browser.path", browserPath.getText().trim());
            config.set("browser.port", browserPort.getText().trim());
            config.set("browser.userDataDir", browserUserData.getText().trim());
            config.set("browser.headless", String.valueOf(browserHeadless.isSelected()));
            config.set("browser.timeoutMs", browserTimeout.getText().trim());
        });
        HBox bottom = new HBox(10);
        bottom.getChildren().addAll(save);
        VBox box = new VBox(10);
        box.getChildren().addAll(grid, bottom);
        box.setPadding(new Insets(4));

        Tab tab = new Tab("基础设置", box);
        tab.setClosable(false);
        return tab;
    }

    // ===== 关于页 =====

    private static Tab aboutTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.getChildren().addAll(
                new Label("minion——类 Claude Code 的代码开发助手"),
                new Separator(),
                new Label("作者：尹承"),
                new Label("联系方式：258915527@qq.com"),
                new Label("开发语言：Java 8 + JavaFX"));
        Tab tab = new Tab("关于", box);
        tab.setClosable(false);
        return tab;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        Theme.style(a);
        a.showAndWait();
    }
}
```

- [ ] **Step 6: MainWindow ⚙ 按钮改调 SettingsDialog，删除 ModelDialog**

MainWindow 内 gear 按钮（原 67-73 行）：
```java
        Button gear = new Button("⚙");
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> {
            SettingsDialog.show(stage, manager.models(), manager, MinionApp.config());
            // 顶部模型名刷新（切换模型后显示新标识）
            modelLabel.setText("模型: " + manager.models().currentName());
        });
```
（import 改 `com.minion.gui.dialog.SettingsDialog`，删 `ModelDialog`；`MinionApp` 已在同包无需 import）

删除文件 `src/main/java/com/minion/gui/dialog/ModelDialog.java`（内容全部迁入 SettingsDialog 模型页）。

- [ ] **Step 7: 构建验证 + 全量测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile` 然后 `JAVA_HOME="D:/javame/jdk1.8" mvn -q test`
Expected: BUILD SUCCESS + 全部测试通过（ConfigTest 新增例在内）

- [ ] **Step 8: Commit**

消息：`feat: 设置窗三页签（模型/基础设置/关于）替代模型弹窗，Config 运行时写回（需求 2/13）`

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java src/main/java/com/minion/gui/dialog/ModelDialog.java src/main/java/com/minion/core/config/Config.java src/main/java/com/minion/gui/MainWindow.java src/test/java/com/minion/core/config/ConfigTest.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 9: 无边框窗口 + 自绘标题栏 + 边缘缩放 + 左右 1:3（需求 1/5）

**Files:**
- Create: `src/main/java/com/minion/gui/TitleBar.java`
- Create: `src/main/java/com/minion/gui/ResizeHelper.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（show() 重排：UNDECORATED + AnchorPane frame + TitleBar + SplitPane；confirmClose 抽取）
- Modify: `src/resource/theme/theme.css`（Task 1 已加 `.btn-close`/`.resize-edge` 规则，无需再动）

**Interfaces:**
- Produces: `TitleBar(Stage, Label modelLabel, Node center, Runnable openSettings, Runnable confirmClose)`、`ResizeHelper.attach(Stage, Pane)`
- Consumes: `Theme.style`（退出确认 Alert）、Task 5 的 `clearChatPane()`/Task 6 的 `setupAutoScroll()`（重写 show() 必须保留调用）、Task 8 的 `SettingsDialog.show`

- [ ] **Step 1: 新建 TitleBar**

```java
package com.minion.gui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

/**
 * 自绘标题栏（无边框窗口）：应用名 | 模型标签 | 页签区（弹性）| ⚙ 设置 | — 最小化 | □ 最大化 | ✕ 关闭。
 * 拖动标题栏移动窗口；双击空白区切换最大化。关闭走 confirmClose（与系统关闭共用退出确认）。
 */
public class TitleBar extends HBox {

    private final Stage stage;
    private double dragX;
    private double dragY;

    public TitleBar(Stage stage, Label modelLabel, Node center,
                    Runnable openSettings, Runnable confirmClose) {
        this.stage = stage;
        this.modelLabel = modelLabel; // 设置窗关闭后 MainWindow 刷新顶部模型名用
        getStyleClass().add("topbar");
        setSpacing(10);

        Label app = new Label("minion");
        app.getStyleClass().add("topbar-title");
        if (center != null) HBox.setHgrow(center, Priority.ALWAYS);

        Button gear = new Button("⚙");
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> openSettings.run());

        Button min = new Button("—");
        min.getStyleClass().add("btn-ghost");
        min.setOnAction(e -> stage.setIconified(true));

        final Button max = new Button("□");
        max.getStyleClass().add("btn-ghost");
        max.setOnAction(e -> {
            stage.setMaximized(!stage.isMaximized());
            max.setText(stage.isMaximized() ? "❐" : "□");
        });

        Button close = new Button("✕");
        close.getStyleClass().add("btn-close");
        close.setOnAction(e -> confirmClose.run());

        getChildren().addAll(app, modelLabel, center, gear, min, max, close);

        // 拖动移动窗口（记录按下偏移，拖拽按屏幕坐标差值移动）
        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
        // 双击标题栏空白处切换最大化（空白 = 点在标题栏自身或其 Label；按钮节点自己消费 click，不会命中此处）
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2
                    && (e.getTarget() == this || e.getTarget() instanceof Label)) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    /** 模型标签（MainWindow 设置窗关闭后刷新顶部模型名用） */
    public Label modelLabel() { return modelLabel; }

    private final Label modelLabel;
}
```

- [ ] **Step 2: 新建 ResizeHelper**

```java
package com.minion.gui;

import javafx.scene.Cursor;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * 无边框窗口边缘缩放：根布局上覆盖 8 个透明区域（四边厚 5px、四角 14px 见方），
 * 按下拖动按方向调整窗口几何。最小尺寸受 stage.setMinWidth/MinHeight 约束（clamp）。
 */
public final class ResizeHelper {

    private static final double EDGE = 5;
    private static final double CORNER = 14;
    /** 方向位掩码：N=上 S=下 W=左 E=右 */
    private static final int N = 1, S = 2, W = 4, E = 8;

    private ResizeHelper() { }

    /** 挂到根容器：frame 必须是 AnchorPane（region 用锚定定位），内部再放实际内容 */
    public static void attach(final Stage stage, final Pane frame) {
        final double[] sx = new double[1]; // 按下点屏幕坐标
        final double[] sy = new double[1];
        final double[] wx = new double[1]; // 按下时窗口几何
        final double[] wy = new double[1];
        final double[] ww = new double[1];
        final double[] wh = new double[1];

        Region top = region(frame, N, Cursor.V_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setTopAnchor(top, 0.0);
        AnchorPane.setLeftAnchor(top, CORNER);
        AnchorPane.setRightAnchor(top, CORNER);
        top.setPrefHeight(EDGE);

        Region bottom = region(frame, S, Cursor.V_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setBottomAnchor(bottom, 0.0);
        AnchorPane.setLeftAnchor(bottom, CORNER);
        AnchorPane.setRightAnchor(bottom, CORNER);
        bottom.setPrefHeight(EDGE);

        Region left = region(frame, W, Cursor.H_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setLeftAnchor(left, 0.0);
        AnchorPane.setTopAnchor(left, CORNER);
        AnchorPane.setBottomAnchor(left, CORNER);
        left.setPrefWidth(EDGE);

        Region right = region(frame, E, Cursor.H_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setRightAnchor(right, 0.0);
        AnchorPane.setTopAnchor(right, CORNER);
        AnchorPane.setBottomAnchor(right, CORNER);
        right.setPrefWidth(EDGE);

        Region nw = region(frame, N | W, Cursor.NW_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setTopAnchor(nw, 0.0);
        AnchorPane.setLeftAnchor(nw, 0.0);
        nw.setPrefSize(CORNER, CORNER);

        Region ne = region(frame, N | E, Cursor.NE_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setTopAnchor(ne, 0.0);
        AnchorPane.setRightAnchor(ne, 0.0);
        ne.setPrefSize(CORNER, CORNER);

        Region sw = region(frame, S | W, Cursor.SW_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setBottomAnchor(sw, 0.0);
        AnchorPane.setLeftAnchor(sw, 0.0);
        sw.setPrefSize(CORNER, CORNER);

        Region se = region(frame, S | E, Cursor.SE_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setBottomAnchor(se, 0.0);
        AnchorPane.setRightAnchor(se, 0.0);
        se.setPrefSize(CORNER, CORNER);
    }

    private static Region region(final Pane frame, final int dir, final Cursor cursor,
                                 final Stage stage, final double[] sx, final double[] sy,
                                 final double[] wx, final double[] wy,
                                 final double[] ww, final double[] wh) {
        Region r = new Region();
        r.getStyleClass().add("resize-edge");
        r.setCursor(cursor);
        r.setOnMousePressed(e -> {
            sx[0] = e.getScreenX();
            sy[0] = e.getScreenY();
            wx[0] = stage.getX();
            wy[0] = stage.getY();
            ww[0] = stage.getWidth();
            wh[0] = stage.getHeight();
        });
        r.setOnMouseDragged(e -> {
            double dx = e.getScreenX() - sx[0];
            double dy = e.getScreenY() - sy[0];
            double x = wx[0], y = wy[0], w = ww[0], h = wh[0];
            if ((dir & E) != 0) w = Math.max(stage.getMinWidth(), ww[0] + dx);
            if ((dir & S) != 0) h = Math.max(stage.getMinHeight(), wh[0] + dy);
            if ((dir & W) != 0) {
                w = Math.max(stage.getMinWidth(), ww[0] - dx);
                x = wx[0] + (ww[0] - w);
            }
            if ((dir & N) != 0) {
                h = Math.max(stage.getMinHeight(), wh[0] - dy);
                y = wy[0] + (wh[0] - h);
            }
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
        });
        frame.getChildren().add(r);
        return r;
    }
}
```

- [ ] **Step 3: MainWindow.show() 重排**

```java
    public void show() {
        stage.setTitle("minion");
        stage.initStyle(StageStyle.UNDECORATED); // 需求 1：隐藏系统标题栏
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        // 根容器：AnchorPane 承载内容 + 8 个缩放区域（ResizeHelper 覆盖在边缘）
        AnchorPane frame = new AnchorPane();
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        AnchorPane.setTopAnchor(root, 0.0);
        AnchorPane.setBottomAnchor(root, 0.0);
        AnchorPane.setLeftAnchor(root, 0.0);
        AnchorPane.setRightAnchor(root, 0.0);
        frame.getChildren().add(root);

        // 自绘标题栏：应用名 | 模型标签 | 页签 | ⚙ | 窗口按钮
        Label modelLabel = new Label("模型: " + manager.models().currentName());
        modelLabel.getStyleClass().add("topbar-model");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        titleBar = new TitleBar(stage, modelLabel, tabs, this::openSettings, this::confirmClose);
        root.setTop(titleBar);

        // 左侧 1/4 侧栏（会话/工作空间）+ 右侧 3/4（消息区 + 输入区）→ SplitPane，默认 1:3
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        Label sessionTitle = new Label("会话管理");
        sessionTitle.getStyleClass().add("section-title");
        sessionList = new SessionListView(manager,
                h -> {
                    removeTabById(h.id);
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
        Label wsTitle = new Label("工作空间");
        wsTitle.getStyleClass().add("section-title");
        final WorkspaceListView wsList = new WorkspaceListView(manager);
        VBox.setVgrow(wsList, Priority.ALWAYS);
        Button newWs = new Button("＋ 新建工作空间");
        newWs.getStyleClass().add("btn-ghost");
        newWs.setMaxWidth(Double.MAX_VALUE);
        newWs.setOnAction(e -> onNewWorkspace(wsList));
        VBox wsBox = new VBox(6);
        wsBox.getChildren().addAll(newWs, wsList);
        VBox.setVgrow(wsBox, Priority.ALWAYS);
        sidebar.getChildren().setAll(sessionTitle, sessionBox, wsTitle, wsBox);

        // 右侧：消息区（ChatView）+ 输入区
        VBox right = new VBox(8);
        right.getStyleClass().add("panel-dark");
        chatScroll = new ScrollPane();
        chatScroll.setFitToWidth(true);
        chatScroll.setContent(new Region()); // 激活会话后换 ChatView
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        setupAutoScroll();
        inputView = new InputView(manager);
        right.getChildren().setAll(chatScroll, inputView);

        SplitPane split = new SplitPane();
        split.setDividerPositions(0.25); // 需求 5：左右比例 1:3
        split.getItems().addAll(sidebar, right);
        root.setCenter(split);

        // 注册 manager 监听（Tab 维护；内容与 Task 5 一致，含 clearChatPane）
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
                    chatView = ChatView.forSession(h);
                    chatView.bind(true);
                    chatScroll.setContent(chatView);
                    if (inputView != null) inputView.bindSession(h);
                });
            }
            @Override public void onWorkspaceChanged() {
                Platform.runLater(() -> {
                    clearChatPane();
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
```

（注：原 show() 中 `onNewWorkspace` 的新建工作空间弹窗内联代码抽取为私有方法 `onNewWorkspace(WorkspaceListView)`，内容保持原样——Task 10 会在该方法内加「浏览…」按钮。本类新增字段 `private TitleBar titleBar;`，show() 中赋值 `titleBar = new TitleBar(...)`。）

- [ ] **Step 4: 抽取 confirmClose 与 openSettings 方法**

MainWindow 类新增字段（与 `chatView` 等并列）：

```java
    private TitleBar titleBar; // 自绘标题栏（openSettings 刷新顶部模型名用）
```

```java
    /** 右上角 ⚙：打开设置窗，关闭后刷新顶部模型名（TitleBar.modelLabel() 持有引用） */
    private void openSettings() {
        SettingsDialog.show(stage, manager.models(), manager, MinionApp.config());
        if (titleBar != null) {
            titleBar.modelLabel().setText("模型: " + manager.models().currentName());
        }
    }
```

```java
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
        Theme.style(a);
        a.setTitle("退出确认");
        a.showAndWait();
        if (a.getResult() == ButtonType.OK) {
            manager.shutdown();
            stage.close();
        }
    }
```

新 import（MainWindow）：`com.minion.gui.dialog.SettingsDialog`、`com.minion.gui.theme.Theme`、`javafx.scene.layout.AnchorPane`、`javafx.stage.StageStyle`。现有 import 全数保留（`Dialog`/`GridPane`/`TextField`/`Optional`/`WorkspaceConfig` 被抽取后的 `onNewWorkspace` 方法继续使用；`BorderPane`/`HBox`/`VBox`/`Priority`/`Region`/`Insets` 在 show() 内继续使用；`Node` 被 `runningIndicator`/`setupAutoScroll` 使用）。

- [ ] **Step 5: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

消息：`feat: 无边框窗口+自绘标题栏+边缘缩放，左右布局改 SplitPane 1:3（需求 1/5）`

```bash
git add src/main/java/com/minion/gui/TitleBar.java src/main/java/com/minion/gui/ResizeHelper.java src/main/java/com/minion/gui/MainWindow.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 10: 工作空间新建/修改用系统文件夹选择框（需求 3）

**Files:**
- Modify: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java`（doEdit 加浏览按钮 + 文案）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（onNewWorkspace 弹窗加浏览按钮）

**Interfaces:**
- Consumes: JavaFX `DirectoryChooser`（javafx.stage 内置）
- 前提：Task 9 已把新建弹窗抽为 `onNewWorkspace(WorkspaceListView)` 方法

- [ ] **Step 1: WorkspaceListView.doEdit 改造**

```java
    private void doEdit(String name) {
        WorkspaceConfig w = workspaces.get(name);
        Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
        d.setTitle("修改工作空间");
        d.setHeaderText("工作空间「" + name + "」（修改对新会话生效）");
        Theme.style(d);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
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
        TextField projectMd = new TextField(w.projectMd == null ? "" : w.projectMd);
        grid.addRow(0, new Label("work.dir:"), workDirBox);
        grid.addRow(1, new Label("project.md:"), projectMd);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            WorkspaceConfig out = new WorkspaceConfig();
            out.workSpaceName = name;
            out.workDir = workDir.getText().trim();
            out.projectMd = projectMd.getText().trim();
            return out;
        });
        Optional<WorkspaceConfig> result = d.showAndWait();
        if (!result.isPresent()) return;
        manager.updateWorkspace(name, result.get().workDir, result.get().projectMd);
    }
```
（新 import：`com.minion.gui.theme.Theme`、`javafx.scene.control.Button`、`javafx.stage.DirectoryChooser`；headerText 文案由「重启后生效」改「对新会话生效」）

- [ ] **Step 2: MainWindow.onNewWorkspace 加浏览按钮**（work.dir 行）

```java
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
        TextField pm = new TextField();
        pm.setPromptText("project.md（可空）");
        g.addRow(0, new Label("名称:"), n);
        g.addRow(1, new Label("work.dir:"), wdBox);
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
                Theme.style(a);
                a.setTitle("新建失败");
                a.showAndWait();
            }
            wsList.refresh();
        }
    }
```
（新 import：`com.minion.gui.theme.Theme`、`javafx.stage.DirectoryChooser`）

- [ ] **Step 3: 构建验证**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

消息：`feat: 工作空间新建/修改用系统文件夹选择框选 work.dir（需求 3）`

```bash
git add src/main/java/com/minion/gui/sidebar/WorkspaceListView.java src/main/java/com/minion/gui/MainWindow.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 11: 中断逻辑验证——部分回复进后续上下文（需求 15）

**Files:**
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`（新增测试客户端 + 用例）

**Interfaces:**
- Consumes: `LlmException(Type.OTHER, String, false)`（现有构造）、`FakeLlmClient`（现有）、AgentLoop 中断路径（appendPartialAssistant 已存在——本任务验证并锁定行为）

- [ ] **Step 1: 新增测试客户端与用例（先写测试）**

AgentLoopTest 内追加：

```java
    /** 需求 15：流式中断——第一轮进入后阻塞等 interrupt()，先回调部分内容再抛异常模拟取消 */
    public static class InterruptibleStreamLlm extends FakeLlmClient {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch cancelSignal = new CountDownLatch(1);

        @Override public void cancel() { cancelSignal.countDown(); }

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) {
            lastRequestMessages = new ArrayList<Message>(messages);
            requests.add(new RequestRecord(messages, tools));
            if (entered.getCount() > 0) { // 仅第一轮：阻塞等中断信号
                entered.countDown();
                try {
                    cancelSignal.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                handler.onThinking("已经分析到一半");
                handler.onContent("部分回复内容");
                throw new LlmException(LlmException.Type.OTHER, "模拟中断", false);
            }
            super.streamChat(messages, tools, handler); // 第二轮起正常回放
        }
    }

    /** 需求 15：流式中断后，中断前收到的部分回复（含思考）进入历史，且下次请求携带 */
    @Test
    public void interrupt_partialReplyKeptForNextTurn() throws Exception {
        InterruptibleStreamLlm p = new InterruptibleStreamLlm();
        AgentLoop loop = new AgentLoop(p, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.roundLimit = 10;
        Thread t = new Thread(() -> loop.runUserTurn("长任务"));
        t.start();
        assertTrue("streamChat 未进入", p.entered.await(5, TimeUnit.SECONDS));
        loop.interrupt(); // cancel → cancelSignal 打开 → 流抛异常走中断路径
        t.join(5000);
        assertFalse(t.isAlive());
        // 中断前收到的部分回复与思考进入历史（不含 toolCalls——切断的 tool_calls 流不可信）
        assertEquals(2, loop.messages().size());
        Message a = loop.messages().get(1);
        assertEquals(Message.Role.ASSISTANT, a.role);
        assertEquals("部分回复内容", a.content);
        assertEquals("已经分析到一半", a.reasoningContent);
        assertTrue(a.toolCalls == null || a.toolCalls.isEmpty());
        // 下一次请求携带部分回复（截断前的模型回复进入后续上下文）
        p.addTurn("继续处理");
        loop.runUserTurn("继续");
        boolean found = false;
        for (Message m : p.lastRequestMessages) {
            if (m.role == Message.Role.ASSISTANT && "部分回复内容".equals(m.content)) {
                found = true;
                break;
            }
        }
        assertTrue("部分回复应出现在后续请求中", found);
    }
```
（`requests` 字段在 `FakeLlmClient` 中为 `public final`——InterruptibleStreamLlm 可直接使用；第二轮走 super 正常回放，`p.addTurn` 出牌）

- [ ] **Step 2: 运行测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test -Dtest=AgentLoopTest`
Expected: PASS——需求 15 的保留路径已存在（appendPartialAssistant），本测试锁定行为；若失败则说明中断路径有缺口，按失败信息修复 AgentLoop

- [ ] **Step 3: 全量测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn -q test`
Expected: PASS

- [ ] **Step 4: Commit**

消息：`test: 流式中断部分回复保留并进入后续上下文的回归测试（需求 15）`

```bash
git add src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

### Task 12: 文档同步（需求 7 文档部分 / 19）

**Files:**
- Modify: `README.md`（删 Win7 段落、快捷操作、配置表格注明设置窗）
- Modify: `docs/ARCHITECTURE.md`（gui/ 类路径表）
- Modify: `CLAUDE.md`（包结构同步）

- [ ] **Step 1: README 修改**

1. 删除「## Win7 说明（2026-08-10）」整段（72-74 行：GUI 界面不受控制台代码页影响……不要加 -Dfile.encoding=UTF-8 启动参数）——需求 7：移除命令行编码/乱码相关文档。第 10 行的「Win7 用户注意 Win7 只支持到 8u251 之前的 Oracle 版本」是 JDK 版本说明（无乱码内容），保留。
2. 第 3 行介绍「解决公司内网 win7 不能使用编程助手的问题」保留（产品背景，非乱码文档）。
3. 快捷操作（22-27 行）更新为：

```markdown
## 快捷操作

- Ctrl+Enter 发送、Enter 换行
- ⚙ 设置（右上角）：模型 / 基础设置 / 关于；切换模型、修改参数即时生效（运行中会话下一轮生效）
- 无会话时直接发送自动新建会话；发送后输入框自动清空
- 拖动消息区滚动条期间暂停自动滚动，释放后恢复
- 关闭会话页签 = 删除会话（有确认）；删除会话/切换工作空间后右侧自动清空
- 关闭窗口时若有会话仍在运行会弹确认
```

4. 配置三件套表格（14-20 行）注明设置窗可改：

```markdown
| 文件 | 内容 |
|---|---|
| `workspace.json` | 工作空间（名称、work.dir、project.md）；界面「＋ 新建工作空间」创建（work.dir 可用系统文件夹选择框选） |
| `model.json` | 模型配置（多模型：url/apiKey/modelName/provider/thinking/maxContextTokens 等）；⚙ 设置窗「模型」页管理 |
| `config.properties` | browser（CDP 浏览器）、confirm（高危确认开关/白名单）、paths（读逃逸）、skills.dir（技能目录）；⚙ 设置窗「基础设置」页可改（浏览器项重启生效） |
```

- [ ] **Step 2: ARCHITECTURE.md 类路径更新**

gui/ 章节（28-37 行区域）：
- MainWindow 行改为：`主窗口：无边框自绘标题栏（TitleBar）+ SplitPane 1:3（左侧会话/工作空间，右侧消息区+输入区）；关闭确认 confirmClose 由 ✕ 按钮与系统关闭共用`
- dialog/ 行改为：`dialog/SettingsDialog（模型/基础设置/关于三页签）、ConfirmDialog`
- 新增行：`TitleBar：自绘标题栏（拖动/双击最大化/最小化/最大化/关闭，⚙ 设置入口）`、`ResizeHelper：无边框窗口边缘/四角拖拽缩放（8 个透明区域）`、`theme/Theme：弹窗深色样式挂载（Dialog 不继承 Scene 样式表）`
- 会话管理章节补一句：模型变更经 `SessionManager.applyModelChanged()` 全量 propagate（换 LlmClient + ContextManager.update，旧客户端延迟回收）

- [ ] **Step 3: CLAUDE.md 包结构同步**

gui/ 段：
- `dialog/` 行改为 `dialog/           SettingsDialog（设置窗三页签）、ConfirmDialog（高危确认弹窗）`
- 新增 `theme/` 行：`Theme（弹窗深色挂载）`
- MainWindow 行改为：`MainWindow        主窗口（无边框自绘标题栏 TitleBar、SplitPane 1:3、ResizeHelper 缩放、状态点呼吸动画 StatusDot）`

- [ ] **Step 4: 构建验证（无需编译——纯文档，验证无残留引用）**

Run: `grep -rn "ModelDialog" src/ docs/ README.md CLAUDE.md`（用 Grep 工具）
Expected: 无匹配（ModelDialog 已在 Task 8 删除）

Run: `grep -rn "Win7 说明\|代码页\|GBK" README.md`
Expected: 无匹配（第 10 行 Win7 版本提示不含这些词）

- [ ] **Step 5: Commit**

消息：`docs: README 去 Win7 控制台段落并同步设置窗/行为说明，ARCHITECTURE/CLAUDE.md 补新组件（需求 7/19）`

```bash
git add README.md docs/ARCHITECTURE.md CLAUDE.md
git commit -F docs/superpowers/.commit-msg
rm -f docs/superpowers/.commit-msg
```

---

## 最终验收（全部任务完成后）

1. `JAVA_HOME="D:/javame/jdk1.8" mvn -q test` 全量通过
2. `JAVA_HOME="D:/javame/jdk1.8" mvn clean package` 构建成功
3. `minion.bat` 启动后人工验收（规格 §18 清单）：
   - 无边框窗口：拖动/双击最大化/最小化/关闭/边缘缩放
   - 设置窗三页签切换；模型修改后运行中会话下一轮生效
   - 新建/修改工作空间浏览选目录
   - 弹窗全部深色
   - 发送清空、无会话自动建会话、标题为前 20 字
   - 拖动滚动条时新内容不自动滚；释放后恢复
   - 删除会话/切换工作空间右侧清空
   - 中断后追问，模型能接着被打断的内容继续
