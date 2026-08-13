# minion UI 修复（第二轮）设计

日期：2026-08-13
状态：待实施

## 背景

第一轮 UI 修复（2026-08-13-ui-fixes-enhancements）合入后，用户复测报告 5 组遗留/新增问题：侧栏字体看不清、工作空间拖拽失效、消息时间不显示、自动滚动仍失效、设置窗改造（左列导航 + 基础设置选项不显示）。均已定位根因，本文档给出设计与实施边界。

## 需求列表

| # | 需求 | 根因 |
|---|------|------|
| 1 | 左侧工作空间、会话列表字体黑色看不清 | cell 内 Label 无样式类，继承 JavaFX 默认黑色文字；CSS `.list-view .list-cell` 的 `-fx-text-fill` 只作用于 cell 自身文本，不作用于 graphic 内部 Label |
| 2 | 工作空间无法拖动调换顺序 | WorkspaceListView.onDragOver 条件写反：`e.getGestureSource() != this` 恒真，所有目标 cell 一律 return 拒绝 drop，拖拽从未生效 |
| 3 | 工作空间悬停按钮 3 个减为 2 个，重命名并入修改 | 现为 ✎ 重命名 / ⚙ 修改 / ✕ 删除三按钮；用户确认 ✎ 删除，重命名并入 ⚙ 修改弹窗，重复名须被拒 |
| 4 | 会话相对时间（5m/3h/2d）不显示 | ①旧会话数据 ts=0 不显示；②`cell-time` 颜色 #7a828e 过暗。用户担心时间信息传入上下文——已验证不传 |
| 5 | 正文自动滚动问题未解决 | 竞态：vmax 监听捕获旧值 + runLater 延迟 setVvalue，期间内容再增长 → vvalue(旧) < vmax(新) → 被误判"离开底部"→ pinned 永不复原 |
| 6 | 设置窗内基础设置/模型/关于作为左边一列 | JavaFX 8 的 TabPane 用 Side.LEFT 文字旋转 90°（历史"字倒了"根因），须自绘左列导航 |
| 7 | 基础设置选项不显示/截断 | GridPane + ColumnConstraints 方案在 JavaFX 8 下仍挤压截断（b7cd506 修复未生效），改确定性行布局 |

## 节 1 侧栏字体颜色（需求 1）

theme.css 新增 `.cell-text` 样式类：

```css
.cell-text { -fx-text-fill: #d3d7de; }
.list-view .list-cell:selected .cell-text { -fx-text-fill: #f0f2f6; }
```

- [SessionListView](src/main/java/com/minion/gui/sidebar/SessionListView.java) 名称 Label、[WorkspaceListView](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) 名称 Label 显式添加该样式类。
- **不用**后代选择器 `.list-view .list-cell .label`：其 CSS 特异性 (0,0,2,1) 高于 `.section-title` (0,0,1,0)，会把会话摘要行的颜色一并覆盖。

## 节 2 工作空间拖拽与按钮合并（需求 2、3）

### 2.1 拖拽修复

[WorkspaceListView](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) 的 `onDragOver` 条件反转：

```java
setOnDragOver(e -> {
    if (e.getGestureSource() == this) return; // 跳过拖起源自身
    e.acceptTransferModes(TransferMode.MOVE);
    e.consume();
});
```

其余拖拽链路（dragDetected 携带名称 / drop 调 moveWorkspace + refresh / 持久化）已正确，不动。

### 2.2 按钮合并（✎ 重命名并入 ⚙ 修改）

- `WsCell` 删除 renameBtn，悬停按钮组只剩 ⚙ 修改、✕ 删除。
- `doEdit` 弹窗顶部加「名称」输入框（预填现名）；OK 时：
  - 名称未变 → 直接保存 work.dir / project.md；
  - 名称已变 → 先 `manager.renameWorkspace(oldName, newName)`，返回 false（名称非法或已存在，现有校验）→ 弹错「名称非法或已存在」并中止；成功后按新名称 `manager.updateWorkspace(newName, workDir, projectMd)`。
- 弹窗头注明：「重命名会同步迁移会话目录；work.dir/project.md 修改对新会话生效」。
- `doRename` 方法删除（逻辑并入 doEdit）。

## 节 3 会话相对时间显示（需求 4）

**时间信息不进上下文（已验证）**：[Message.toApiJson](src/main/java/com/minion/core/llm/Message.java) 只输出 role/content/reasoning_content/tool_calls/tool_call_id/name，`ts` 不参与请求体；token 统计走 content，与 ts 无关。

修复两点：

- theme.css `.cell-time` 提亮：#7a828e → #98a0ab（与 msg-thinking 一致，深底可读）。
- 刷新时机增强：
  - [MainWindow](src/main/java/com/minion/gui/MainWindow.java) `onSessionActivated` 回调中加 `sessionList.refresh()`（激活即更新该会话时间）；
  - [SessionListView](src/main/java/com/minion/gui/sidebar/SessionListView.java) 加 60 秒周期刷新（`javafx.animation.Timeline`，FX 线程，随应用退出自然停止），时间不再停留初始值。

旧数据 ts=0 仍不显示（无时间数据可显示，行为不变）。

## 节 4 自动滚动修复（需求 5）

[AutoScrollPolicy](src/main/java/com/minion/gui/session/AutoScrollPolicy.java) 纯逻辑正确，**不动**（其单测继续通过）。只改 [MainWindow.setupAutoScroll](src/main/java/com/minion/gui/MainWindow.java) 的 vmax 监听接线：

```java
chatScroll.vmaxProperty().addListener((obs, ov, nv) -> {
    if (policy.shouldFollow()) {
        Platform.runLater(() -> {
            // 执行时重读当前 vmax（不捕获监听时的旧值），并二次确认仍贴底
            if (policy.shouldFollow()) chatScroll.setVvalue(chatScroll.getVmax());
        });
    }
});
```

语义（用户确认）：贴底才跟随，滚离底部暂停，拖回底部恢复。

## 节 5 设置窗左列导航 + 基础设置布局（需求 6、7）

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java) 重构：

### 5.1 左列导航

- 弃用 TabPane，改为 `HBox(左侧导航 ListView<String>（基础设置/模型/关于，prefWidth 约 120，默认选中「基础设置」）, 右侧 StackPane 内容区)`。
- 导航点击 → 切换 StackPane 当前 pane。复用现有 `.list-view` 样式，无需新 CSS。
- 三个内容构建器由 Tab 改为 VBox pane：`modelTab` → `modelPane`、`basicTab` → `basicPane`、`aboutTab` → `aboutPane`；模型页的列表/增删改/实时生效（applyModelChanged）逻辑原样保留。
- 窗口尺寸 560×480 → 620×500（左列占宽后内容区仍有足够空间）。

### 5.2 基础设置行布局

GridPane + ColumnConstraints 弃用，改确定性行布局：

- 每行 = `HBox(标签 Label, 输入控件)`：标签 `setMinWidth(160)` + `setPrefWidth(160)` 固定宽度；输入控件 `HBox.setHgrow(ALWAYS)` + `setMaxWidth(MAX_VALUE)` 铺满。
- 行竖向排入 VBox，整体包进 `ScrollPane`（fitToWidth）——窗口小时可滚动，选项不再被裁剪或截断。
- 行内容与保存逻辑不变（skills.dir / 两白名单 / 读逃逸 / 确认开关 / 浏览器五项，setInt 校验）。

## 测试计划

自动化（junit4）：本次修复均为 GUI 接线/CSS 布局，纯逻辑类未变，现有 `AutoScrollPolicyTest`、`WorkspaceManagerTest`、`TimeFormatterTest` 继续通过即可，无新增单测。

手工验证清单（GUI）：

1. 侧栏会话/工作空间列表文字浅色可读，选中态更亮，摘要行颜色不变。
2. 工作空间拖拽排序成功，重启后顺序保持。
3. 工作空间悬停只有 ⚙/✕ 两按钮；修改弹窗改名成功、重复名报「名称非法或已存在」、改名后会话目录随迁。
4. 会话列表非悬停显示 5m/3h/2d 且颜色可读；等待 1 分钟后数值刷新。
5. 流式输出：贴底自动跟随、上翻暂停、拖回底部恢复跟随。
6. 设置窗左列三项导航（默认基础设置），点击切换内容；模型页切换模型仍实时生效。
7. 基础设置所有选项完整可见、可编辑、保存生效；窗口缩小时可滚动。

验收：`JAVA_HOME="E:/javame/jdk8" mvn clean package` + 手工清单全过。

## 文档同步

- README.md：工作空间修改弹窗含重命名（如涉及使用说明）。
- docs/ARCHITECTURE.md：`.cell-text` 样式类、SettingsDialog 左列导航结构。
