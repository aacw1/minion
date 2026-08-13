# minion UI 修复（第四轮）设计

日期：2026-08-13
状态：已确认待实施

## 背景

第三轮修复合入后，用户 GUI 验收报告 6 个问题：①设置窗"保存"按钮位置与文案；②会话列表横向滚动条；③浏览器路径需文件选择；④左右分隔白色粗线；⑤token 统计消失；⑥自动滚动失效。其中 ⑤⑥ 已定位根因（⑤ 移除 CLI 时统计输出一并删除；⑥ vmax 变化时贴底状态不同步的竞态），本文档给出设计与实施边界。

## 需求列表

| # | 需求 | 根因 |
|---|------|------|
| 1 | 基础设置"保存"按钮移到"关闭"左侧，文案改"应用" | 保存按钮现嵌在 basicPane 内容底部，不在按钮栏 |
| 2 | 会话管理列表不显示（横向）滚动条，过长截断 | SessionCell 标题/摘要 Label 按内容撑宽，超出视口 → ListView 出现横向滚动条 |
| 3 | 浏览器路径增加文件选择方式 | browser.path 现为纯文本输入；无 FileChooser 浏览 |
| 4 | 左右分隔白色粗线去掉，宽度固定（严格整体 1/4），不可拖拽调整 | SplitPane 默认 divider 样式（theme.css 无覆盖）渲染为亮线；divider 可拖拽 |
| 5 | 每次回复后 token 统计信息加回 | `a5d57b8` 移除 CLI 时删掉 cli/StatsLine，GUI 无人调用 `ui.onStatsLine`，STATS 事件管道仍在但无来源 |
| 6 | 滚轮在底部时正文不能随回复自动滚动 | vmax 增长时 `pinned` 只由 vvalue 监听器更新；流式增长中 vvalue 被设到旧 vmax，后续 vmax 增长后按旧值计算 → 误判"离开底部"→ 跟随永久失效（表现为"滚轮往上跳了一点"） |

## 节 1 设置窗"应用"按钮（需求 1）

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java) 两处修改：

1. 基础设置页抽为内部类 `BasicPane`（持有各控件 + `apply()` 方法），原 `basicPane()` 静态方法并入；`rows` 底部不再放保存按钮。
2. `show()` 中在按钮栏 CLOSE 左侧插入自定义按钮：

```java
ButtonType apply = new ButtonType("应用", ButtonBar.ButtonData.OTHER);
d.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CLOSE);
((Button) d.getDialogPane().lookupButton(apply)).addEventFilter(ActionEvent.ACTION, e -> {
    basic.apply();
    e.consume();
});
```

- 点击"应用" = 执行原保存逻辑（全部配置项写入），**窗口不关闭**；"关闭"直接关窗（未保存的修改丢弃，与原行为一致）。
- `browser.port` / `browser.timeoutMs` 非法时弹错且该项不写（`setInt` 既有行为不变）。
- **实施修正（2026-08-13，经用户确认）**：ButtonBar 按平台 ButtonData 顺序串重排视觉位置，`APPLY`(A) 在 Win/Mac 顺序串里排在 `CANCEL_CLOSE`(C) 之后，无法保证「应用」在「关闭」左侧；且 DialogPane 对任意按钮点击都触发关窗，`setOnAction` 无法阻止。故改用 `ButtonData.OTHER`(U，三套平台顺序串均先于 C) + `addEventFilter(ActionEvent.ACTION)` 捕获阶段先 `apply()` 再 `consume()`（JDK 8u181/Windows 探针实证：[应用][关闭]、应用保存不关窗、关闭仍关窗）。

## 节 2 会话列表横向滚动条（需求 2）

[SessionListView](src/main/java/com/minion/gui/sidebar/SessionListView.java) 的 `SessionCell`：

- 根因：cell 内 HBox 的 prefWidth = 标题全文宽（spacer 的 HGrow 只吸收多余空间，不能收缩），超出视口即出现横向滚动条。
- 修复：cell 图形（cellBox）宽度绑定 cell 宽度，标题/摘要/时间 Label 设 `OverrunStyle.ELLIPSIS`：

```java
cellBox.maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
        () -> getWidth() - getInsets().getLeft() - getInsets().getRight() - 4,
        widthProperty(), insetsProperty()));
name.setTextOverrun(OverrunStyle.ELLIPSIS);
sum.setTextOverrun(OverrunStyle.ELLIPSIS);
```

- 标题 Label 配合 HBox Hgrow 占满剩余宽度，长标题截断为省略号；摘要行同理。右侧时间/悬停按钮固定宽不被截断。
- JavaFX 8 ListView 无公开 API 设置横向滚动条策略，靠"内容永不超出视口"根除横向滚动条；纵向滚动（ListView 默认）不受影响。
- 范围仅会话列表；工作空间列表未报告问题，不动。
- **实施修正（2026-08-13，经用户确认）**：初版 `subtract(4)` 未抵消 cell padding（theme.css `.list-cell` 左右 24px），探针实测 hbar 仍可见且首选宽持续增长；改 `getInsets` 动态抵消后仍失败——CSS 异步应用，updateItem 绑定时刻 getInsets 恒为 0，且 subtract 为快照语义不重算。最终用 `Bindings.createDoubleBinding` 依赖 `widthProperty + insetsProperty` 随 CSS 应用重算（探针实证 237/241、hbar 隐藏）。

## 节 3 浏览器路径文件选择（需求 3）

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java) 基础页 `browser.path` 行改造为 HBox：TextField + 「浏览…」按钮（`btn-ghost`），与 skills.dir 行风格一致：

```java
HBox browserPathBox = new HBox(6);
TextField browserPath = new TextField(config.browserPath());
HBox.setHgrow(browserPath, Priority.ALWAYS);
Button browseExe = new Button("浏览…");
browseExe.getStyleClass().add("btn-ghost");
browseExe.setOnAction(e -> {
    FileChooser fc = new FileChooser();
    fc.setTitle("选择浏览器程序");
    fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("可执行文件", "*.exe"),
            new FileChooser.ExtensionFilter("所有文件", "*.*"));
    // 当前值若是存在的文件，初始定位到其父目录
    String cur = browserPath.getText().trim();
    java.io.File f = new java.io.File(cur);
    if (f.isFile() && f.getParentFile() != null && f.getParentFile().isDirectory())
        fc.setInitialDirectory(f.getParentFile());
    java.io.File file = fc.showOpenDialog(owner);
    if (file != null) browserPath.setText(file.getAbsolutePath());
});
browserPathBox.getChildren().addAll(browserPath, browseExe);
```

- 保存逻辑不变：`config.set("browser.path", browserPath.getText().trim())`。
- `row(...)` 行辅助方法参数已是 `Region`，HBox 可直接传入。

## 节 4 分隔线去除 + 侧栏固定 1/4（需求 4）

[MainWindow](src/main/java/com/minion/gui/MainWindow.java)：移除 SplitPane，改为 GridPane 两列固定 25% / 75%：

```java
GridPane center = new GridPane();
ColumnConstraints c1 = new ColumnConstraints();
c1.setPercentWidth(25);
ColumnConstraints c2 = new ColumnConstraints();
c2.setPercentWidth(75);
center.getColumnConstraints().addAll(c1, c2);
center.add(sidebar, 0, 0);
center.add(right, 1, 0);
root.setCenter(center);
```

- 无分隔线节点、无拖拽手柄；侧栏严格占整体 1/4，窗口缩放时按比例同步。
- `split.setDividerPositions(0.25)` 与 SplitPane import 一并删除；sidebar `setMinWidth(200)` 保留（窗口最小宽 960 时 1/4=240px 不冲突）。
- theme.css 无 split-pane 相关样式，无需清理；ResizeHelper 窗口边缘缩放不受影响。

## 节 5 恢复 token 统计（需求 5）

1. 新增 [StatsLine](src/main/java/com/minion/core/agent/StatsLine.java) 工具类（移植已删的 cli/StatsLine，去掉 CLI 的 `*` 前缀）：

```java
public static String format(UsageTracker usage, long elapsedMillis, int currentCtx, int maxCtx) {
    double secs = elapsedMillis / 1000.0;
    int pct = maxCtx > 0 ? (int) Math.round(currentCtx * 100.0 / maxCtx) : 0;
    return String.format(Locale.ROOT, "⏱ %.1fs · in %s · out %s · thinking %s · ctx %s/%s (%d%%)",
            secs, formatTokens(usage.sessionInput()), formatTokens(usage.sessionOutput()),
            formatTokens(usage.sessionThinking()), formatTokens(currentCtx),
            formatTokens(maxCtx), pct);
}
public static String formatTokens(int n) { /* 与旧实现一致：<1000 原样 / 整千 "900k" / ≥10万 整k / 其余 "7.8k" */ }
```

2. [AgentLoop](src/main/java/com/minion/core/agent/AgentLoop.java) `runUserTurn`：方法入口记录 `long start`，方法末尾（`persistSession()` 之后）统一发射统计。所有退出路径（正常/轮数上限/错误/中断/异常）都穿透到方法末尾，无需 finally：

```java
long start = System.currentTimeMillis();
// ... 既有 try/catch 与中断清洗、落盘逻辑不变 ...
persistSession();
long elapsed = System.currentTimeMillis() - start;
int currentCtx = contextManager != null
        ? contextManager.estimate(session.messages)
        : TokenCounter.estimateMessages(session.messages);
int maxCtx = contextManager != null ? contextManager.maxTokens() : 0;
ui.onStatsLine(StatsLine.format(session.usage, elapsed, currentCtx, maxCtx));
```

- 末尾发射位于 `scrubHalfTurn`/`persistSession` 之后，中断路径的 ctx 估算是清洗半轮后的准确值。
- 统计为会话累计值（usage.sessionInput 等），与旧 CLI 一致。
- GUI 端无需改动：SessionController.onStatsLine → STATS 事件 → [ChatView](src/main/java/com/minion/gui/chat/ChatView.java) 第 99-101 行已渲染为 msg-thinking 行；恢复历史会话 replayHistory 不含 STATS，天然不重放。
- "⏱" 字形：旧 CLI 在 mintty 下渲染为 ? 的顾虑不适用于 JavaFX（系统字体渲染）；如需保险可换 "耗时" 文案，实施时以 GUI 实测为准。

## 节 6 自动滚动根因修复 + 半屏容差（需求 6）

### 6.1 AutoScrollPolicy 修正

[AutoScrollPolicy](src/main/java/com/minion/gui/session/AutoScrollPolicy.java)（纯逻辑）：

- 容差 `EPSILON` 0.001 → **0.5**：距底半屏内视为贴底。固定容差不随内容变长（用户提议的 90% 方案容差 = 10% 内容高度，长对话阅读历史时会被拽回底部，弃用）。
- 新增 `sync(double vvalue, double vmax)`：`pinned = vvalue >= vmax - EPSILON`。vvalue 监听器与 **vmax 监听器都调用**——修复 vmax 变化时 pinned 不重算的根因。
- 新增 `forceFollow()`：`pinned = true`（用户发消息时调用）。
- 内容未超一屏时 vmax=0，pinned 恒真，行为不变。

### 6.2 MainWindow 接线

[MainWindow](src/main/java/com/minion/gui/MainWindow.java) `setupAutoScroll`：

```java
chatScroll.vvalueProperty().addListener((obs, ov, nv) ->
        policy.sync(nv.doubleValue(), chatScroll.getVmax()));
chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
    policy.sync(chatScroll.getVvalue(), nv.doubleValue()); // 用新 vmax 重算贴底
    if (policy.shouldFollow()) {
        Platform.runLater(() -> { // 保留既有修复：执行时重读 vmax 二次确认
            if (policy.shouldFollow()) chatScroll.setVvalue(chatScroll.getVmax());
        });
    }
});
```

[ChatView](src/main/java/com/minion/gui/chat/ChatView.java) 新增可选回调 `scrollBottomRequest`（构造后注入，如 `Consumer<Object>` 或 Runnable）；`USER_MESSAGE` 分支末尾调用。MainWindow 注入：

```java
chatView.setScrollBottomRequest(() -> {
    policy.forceFollow();
    Platform.runLater(() -> chatScroll.setVvalue(chatScroll.getVmax())); // 布局完成后置底
});
```

- 发送消息必然想看底部 → 强制贴底；此后流式增长的每次 vmax 变化经 sync 重算（半屏容差覆盖布局微调），跟随不再失效。
- 切换会话 bind(true) 重放 USER_MESSAGE 也会触发贴底——切会话停在最新消息，符合预期。

## 测试计划

自动化（junit4）：

- 新增 `src/test/java/com/minion/core/agent/StatsLineTest.java`（移植旧 cli 测试：formatTokens 边界 999/1000/9000/100000/131072、format 前缀改为无 `*`）。
- 新增 `src/test/java/com/minion/gui/session/AutoScrollPolicyTest.java`（贴底/半屏容差/离开底部暂停/forceFollow 恢复/vmax 增长时 sync 重算——覆盖本轮根因场景）。
- 现有 285 个测试继续通过（EventList/SessionController 未改动）。

手工验证清单（GUI）：

1. 设置窗：底部按钮栏从左到右「应用」「关闭」；修改任意项点"应用"后窗口不关闭，重启后生效；port 填非法值点"应用"弹错且该项未写入。
2. 会话列表：长标题/长摘要会话截断显示省略号，无横向滚动条；纵向滚动正常；悬停按钮（✎/✕）仍可见可点。
3. 基础设置 browser.path 行点「浏览…」弹出文件选择器（exe 过滤），选定填入；当前值有效时初始定位父目录。
4. 左右无分隔线；窗口缩放时侧栏始终占 1/4；侧栏与消息区之间不可拖拽。
5. 发送一条消息，回复结束后消息区末尾出现 `⏱ x.xs · in … · out … · thinking … · ctx …`；工具轮次/中断也有该行；切换历史会话不出现统计行。
6. 长对话：发送后自动滚到底并随流式内容持续跟随；向上翻半屏以上后回复不再拽回；翻回底半屏内恢复跟随。

验收：`JAVA_HOME="E:/javame/jdk8" mvn clean package` + 手工清单全过。

## 文档同步

- README.md：设置窗"应用"按钮、browser.path 浏览按钮、token 统计行、自动滚动策略说明（如涉及）。
- docs/ARCHITECTURE.md：AgentLoop 统计行发射、MainWindow 布局改 GridPane 固定 25%、AutoScrollPolicy 行为说明。
