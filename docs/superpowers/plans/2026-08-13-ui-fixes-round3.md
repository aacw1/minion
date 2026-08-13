# minion UI 修复第三轮实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复设置窗左列导航被压塌消失的问题，并给 skills.dir 增加目录浏览选择按钮。

**Architecture:** 全部改动集中在 [SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java)（GUI 接线/CSS 布局，无 core 改动）：导航 ListView 加 minWidth 防 HBox 空间不足时塌缩；白名单 TextArea 列宽调小使基础页偏好宽回归合理值；skills.dir 行加「浏览…」按钮复用 work.dir 的 DirectoryChooser 模式。依据设计文档 docs/superpowers/specs/2026-08-13-ui-fixes-round3-design.md。

**Tech Stack:** JDK 8 + JavaFX 8（jfxrt）、Maven。

## Global Constraints

- JDK 8 兼容；本机 JDK 路径为 `D:/javame/jdk1.8`（含 JavaFX；CLAUDE.md 中的 E:/javame/jdk8 在本机不存在），所有命令使用 `JAVA_HOME="D:/javame/jdk1.8"`。
- 构建/测试命令：`JAVA_HOME="D:/javame/jdk1.8" mvn clean package` / `JAVA_HOME="D:/javame/jdk1.8" mvn test`；现有 285 个测试必须全部通过。
- 本次均为 GUI 接线/CSS 布局改动，纯逻辑类未变，**不新增单元测试**（设计文档测试计划明确）。
- 资源目录是 `src/resource`（非 src/main/resources）；本次不动资源。
- 文档、注释、commit 均用中文；commit 用 conventional 格式并带 `Co-Authored-By: Claude <noreply@anthropic.com>` 结尾。
- 在新分支 `ui-fixes3` 上实施（从 main 切出），完成后合并回 main。
- 保存逻辑、模型页逻辑、其他 10 行表单行均不改动（只动设计文档指定的三处）。

---

### Task 1: 设置窗左列导航 minWidth + TextArea 列宽

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`

**Interfaces:**
- Consumes: 无（本任务独立）。
- Produces: 无（后续任务不依赖本任务的签名）。

**背景**：JavaFX HBox 空间不足时按 HGrow 增长优先级分配——右侧 content 设了 `HGrow.ALWAYS`（优先级最高）先吃掉全部剩余空间，导航 ListView 无 HGrow（NEVER 优先级）被压到最小宽度（实测 2.0px，整列不可见）。触发条件：真实 basicPane 偏好宽 794 > 窗口 620，因 TextArea 默认 `prefColumnCount=40`（≈624px 偏好宽）。真实 pane 复现已验证：加 minWidth 后 nav 实际宽 120.0px。

- [ ] **Step 1: 切分支**

```bash
git checkout main && git pull && git checkout -b ui-fixes3
```

- [ ] **Step 2: 导航列加 minWidth**

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java) 的 `show()` 方法中，把：

```java
        final ListView<String> nav = new ListView<String>();
        nav.getItems().addAll("基础设置", "模型", "关于");
        nav.setPrefWidth(120);
```

改为：

```java
        final ListView<String> nav = new ListView<String>();
        nav.getItems().addAll("基础设置", "模型", "关于");
        nav.setPrefWidth(120);
        nav.setMinWidth(120); // HBox 空间不足时按 HGrow 优先级分配，无 HGrow 的子项会被压到最小宽度；minWidth 保证导航列不被压塌
```

- [ ] **Step 3: 两个白名单 TextArea 列宽调小**

同一文件中 `basicPane` 方法，把：

```java
        TextArea toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
        toolWhitelist.setPrefRowCount(2);
        TextArea cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
        cmdWhitelist.setPrefRowCount(2);
```

改为：

```java
        TextArea toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
        toolWhitelist.setPrefRowCount(2);
        toolWhitelist.setPrefColumnCount(20); // 默认 40 列偏好宽 ≈624px 把基础页撑到 794，触发 HBox 压缩导航列；20 列后偏好宽 ~500 与内容区匹配
        TextArea cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
        cmdWhitelist.setPrefRowCount(2);
        cmdWhitelist.setPrefColumnCount(20);
```

- [ ] **Step 4: 编译 + 全量测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn compile && JAVA_HOME="D:/javame/jdk1.8" mvn test`
Expected: BUILD SUCCESS，285 个测试全部通过（无新增）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "fix: 设置窗左列导航加 minWidth 防塌缩；白名单 TextArea 列宽调至 20"
```

（commit 消息结尾加 Co-Authored-By 行）

---

### Task 2: skills.dir 目录浏览按钮

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`

**Interfaces:**
- Consumes: 无。
- Produces: `basicPane(final Config config, final Window owner)` 新签名；`row(String, Region)` 新签名（仅本文件内部使用）。

**背景**：复用 [WorkspaceListView](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) work.dir 浏览的既有模式（第 138-153 行）：「浏览…」按钮（btn-ghost）+ DirectoryChooser，初始目录 = 当前输入值（若为已存在目录）。

- [ ] **Step 1: 加导入**

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java) 导入区，在 `import javafx.scene.layout.Priority;` 后加一行、`import javafx.scene.layout.VBox;` 后加一行（保持字母序）：

```java
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
```

```java
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
```

- [ ] **Step 2: show() 传 owner**

`show()` 方法中，把：

```java
        final Node basic = basicPane(config);
```

改为：

```java
        final Node basic = basicPane(config, owner);
```

- [ ] **Step 3: basicPane 签名与 skillsDir 浏览行**

`basicPane` 方法中，把签名和第一行：

```java
    private static Node basicPane(final Config config) {
        TextField skillsDir = new TextField(config.skillsDir());
```

改为：

```java
    private static Node basicPane(final Config config, final Window owner) {
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

（`skillsDir` 变量名不变，保存按钮 `config.set("skills.dir", skillsDir.getText().trim())` 照旧。）

- [ ] **Step 4: 表单行换用 skillsBox**

同一方法中，`rows.getChildren().addAll(...)` 的第一行，把：

```java
                row("技能目录 skills.dir:", skillsDir),
```

改为：

```java
                row("技能目录 skills.dir:", skillsBox),
```

- [ ] **Step 5: row() 参数类型放宽为 Region**

把：

```java
    private static HBox row(String labelText, javafx.scene.control.Control control) {
```

改为：

```java
    private static HBox row(String labelText, Region control) {
```

（`Region` 有 `setMaxWidth`，`HBox.setHgrow` 接受 Node；方法体不变。）

- [ ] **Step 6: 编译 + 全量测试**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn compile && JAVA_HOME="D:/javame/jdk1.8" mvn test`
Expected: BUILD SUCCESS，285 个测试全部通过。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "feat: 基础设置 skills.dir 支持目录浏览选择"
```

（commit 消息结尾加 Co-Authored-By 行）

---

### Task 3: 文档同步

**Files:**
- Modify: `README.md`（第 20 行 config.properties 说明）
- Modify: `docs/ARCHITECTURE.md`（第 40 行 SettingsDialog 说明）

**Interfaces:**
- Consumes: Task 1、Task 2 的改动内容（导航 minWidth、skills.dir 浏览）。

- [ ] **Step 1: README 更新 config.properties 行**

[README.md](README.md) 把：

```
| `config.properties` | browser（CDP 浏览器）、confirm（高危确认开关/白名单）、paths（读逃逸）、skills.dir（技能目录）；⚙ 设置窗「基础设置」页可改（浏览器项重启生效） |
```

改为：

```
| `config.properties` | browser（CDP 浏览器）、confirm（高危确认开关/白名单）、paths（读逃逸）、skills.dir（技能目录）；⚙ 设置窗「基础设置」页可改，skills.dir 可用目录选择器浏览选取（浏览器项重启生效） |
```

- [ ] **Step 2: ARCHITECTURE 更新 SettingsDialog 行**

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 把：

```
| dialog/SettingsDialog、ConfirmDialog | 设置窗（左列 ListView 导航：基础设置/模型/关于 + StackPane 内容切换；基础设置 HBox 行布局标签固定 160 宽 + ScrollPane 防裁剪）；高危操作确认弹窗 |
```

改为：

```
| dialog/SettingsDialog、ConfirmDialog | 设置窗（左列 ListView 导航：基础设置/模型/关于 + StackPane 内容切换；导航列 minWidth 120 防 HBox 空间不足时被 HGrow 内容压塌；基础设置 HBox 行布局标签固定 160 宽 + ScrollPane 防裁剪，skills.dir 可浏览选取）；高危操作确认弹窗 |
```

- [ ] **Step 3: 打包验收**

Run: `JAVA_HOME="D:/javame/jdk1.8" mvn clean package`
Expected: BUILD SUCCESS（产物 target/minion-0.1.0.jar）。

- [ ] **Step 4: Commit**

```bash
git add README.md docs/ARCHITECTURE.md
git commit -m "docs: 同步设置窗导航修复与 skills.dir 浏览说明"
```

（commit 消息结尾加 Co-Authored-By 行）

---

## 最终手工验证清单（用户执行，GUI）

1. 打开设置窗：左列可见「基础设置/模型/关于」三项（默认选中基础设置），点击可切换三个内容页；模型页增删改/切换仍实时生效。
2. 基础设置所有选项完整显示（白名单 TextArea 不再把页面撑出窗口），窗口缩小时可滚动。
3. 技能目录行点「浏览…」弹出目录选择器，选定后填入路径；当前值已是目录时选择器初始定位到该目录；保存后重启生效。
