# minion UI 修复（第三轮）设计

日期：2026-08-13
状态：已实施（2026-08-13）

## 背景

第二轮 UI 修复合入后，用户 GUI 验收报告 2 个问题：①设置窗左列导航（基础设置/模型/关于）整列消失，窗口直接显示基础设置且不可切换；②技能目录 skills.dir 需支持文件浏览选择目录。均已复现定位根因（含真实 pane 复现验证），本文档给出设计与实施边界。

## 需求列表

| # | 需求 | 根因 |
|---|------|------|
| 1 | 设置窗左列导航整列消失，无法切换模型/关于 | JavaFX HBox 空间不足时按 HGrow 增长优先级分配：右侧 content 设了 `HGrow.ALWAYS`（优先级最高）先吃掉全部剩余空间，导航 ListView 未设 HGrow（NEVER 优先级）被压到最小宽度 2px。触发条件：真实 basicPane 偏好宽度 794 > 窗口 620——TextArea 默认 `prefColumnCount=40` 列（≈624px 偏好宽）把基础页撑宽 |
| 2 | 技能目录 skills.dir 用文件浏览选择 | 现为纯文本输入；work.dir 已有 DirectoryChooser 浏览模式可复用 |

## 节 1 设置窗左列导航修复（需求 1）

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java) 两处修改：

### 1.1 导航列永不塌缩

```java
final ListView<String> nav = new ListView<String>();
nav.getItems().addAll("基础设置", "模型", "关于");
nav.setPrefWidth(120);
nav.setMinWidth(120); // HBox 空间不足时按 HGrow 优先级分配，无 HGrow 的子项被压到 min；minWidth 保证导航列不被压塌
```

真实 pane 复现验证：修复前 nav 实际宽 2.0px（整列不可见），修复后 120.0px。

### 1.2 基础页偏好宽度回归合理值

两个 TextArea（确认白名单工具/命令）加 `setPrefColumnCount(20)`（≈312px），基础页偏好宽由 794 回到 ~500，与内容区 476 匹配，输入控件不再被压缩。TextArea 仍然允许横向滚动，长内容不受影响。

## 节 2 技能目录文件浏览（需求 2）

skills.dir 行改造：输入框旁加「浏览…」按钮（`btn-ghost`），用 DirectoryChooser 选目录填入；初始目录 = 当前输入值（若为已存在目录）。实现复用 [WorkspaceListView](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) work.dir 浏览的既有模式（第 138-153 行）：

```java
HBox skillsBox = new HBox(6);
TextField skillsDir = new TextField(config.skillsDir());
HBox.setHgrow(skillsDir, Priority.ALWAYS);
Button browse = new Button("浏览…");
browse.getStyleClass().add("btn-ghost");
browse.setOnAction(e -> {
    DirectoryChooser dc = new DirectoryChooser();
    String cur = skillsDir.getText().trim();
    if (!cur.isEmpty()) {
        java.io.File f = new java.io.File(cur);
        if (f.isDirectory()) dc.setInitialDirectory(f);
    }
    java.io.File dir = dc.showDialog(owner);
    if (dir != null) skillsDir.setText(dir.getAbsolutePath());
});
skillsBox.getChildren().addAll(skillsDir, browse);
```

- 行辅助方法 `row(String, Control)` 参数类型放宽为 `Region`（HBox 是 Region），skillsDir 行传 skillsBox，其余 10 行不变。
- `basicPane` 签名加 `Window owner`（show() 的入参传入），浏览弹窗归属主窗口（设置窗为 Dialog 非 Stage；DirectoryChooser 应用级模态，行为一致）。
- 保存逻辑不变：保存按钮 `config.set("skills.dir", skillsDir.getText().trim())` 照旧生效。

## 测试计划

自动化（junit4）：均为 GUI 接线/CSS 布局，纯逻辑类未变，现有 285 个测试继续通过即可，无新增单测。

手工验证清单（GUI）：

1. 打开设置窗：左列可见「基础设置/模型/关于」三项（默认选中基础设置），点击可切换三个内容页；模型页增删改/切换仍实时生效。
2. 基础设置所有选项完整显示（白名单 TextArea 不再把页面撑出窗口），窗口缩小时可滚动。
3. 技能目录行点「浏览…」弹出目录选择器，选定后填入路径；当前值已是目录时选择器初始定位到该目录；保存后重启生效。

验收：`JAVA_HOME="D:/javame/jdk1.8" mvn clean package` + 手工清单全过。

## 文档同步

- README.md：设置窗左列导航修复说明（如涉及）；基础设置 skills.dir 浏览按钮。
- docs/ARCHITECTURE.md：SettingsDialog 导航列 minWidth 约束说明。
