# MCP 表单弹窗 UI 修正实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正新建/编辑 MCP 服务器弹窗的三处 UI 问题：加宽至约 600px、URL 标签去掉括号提示、请求头字段挂 tooltip 注释。

**Architecture:** 纯 UI 声明式调整，全部改动收敛在 `com.minion.gui.dialog.SettingsDialog` 的 `form()` 方法及其辅助（新增 1 个私有静态标签工厂 `formLabel`、引入 Tooltip import）。不动 GridPane 行结构、传输联动显隐逻辑与任何核心逻辑。

**Tech Stack:** Java 8 / JavaFX 8（jfxrt）/ Maven

## Global Constraints

- JDK 8 兼容（`mvn package` 需 JDK8 含 JavaFX；不要使用 var/lambda 之外的新语法——项目本身用 Java 8 lambda，保持风格）
- 参考设计文档 `docs/superpowers/specs/2026-09-03-mcp-settings-form-design.md`（已批准）
- 不引入 ColumnConstraints / Hgrow 方案（项目历史：JavaFX 8 下列挤压截断）
- 不重构 GridPane 为 BasicPane 的 HBox 表单行模式
- 注释、commit 中文，commit 用 conventional 格式

---

### Task 1: SettingsDialog.form() 三处 UI 修正

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`
  - import 区（约 28 行附近，`import javafx.scene.control.TextField;` 之后）
  - `form()` 方法（约 404-446 行）
  - `showRows()` 方法（约 537-552 行）
- Test（无新增测试文件，纯 UI 布局改动，验证靠既有回归测试 + 编译，见 Step 6）

**Interfaces:**
- Consumes: `form()` 内现有局部变量（name/command/url/argsArea/envArea/headerArea/urlLabel/transportGroup/grid 等）；`McpServer.STREAMABLE` 常量；`showRows(GridPane, String, Set<McpFormPolicy.Field>, Label)` 现有签名
- Produces: 新增类级私有静态方法 `private static Label formLabel(String text)`（返回 150px 宽的标签）；`showRows` 签名与调用链**保持不变**，仅内部文案收敛

- [ ] **Step 1: 新增 Tooltip import**

在 `src/main/java/com/minion/gui/dialog/SettingsDialog.java` 的 import 区，于 `import javafx.scene.control.TextField;`（28 行）与 `import javafx.scene.control.ToggleGroup;`（29 行）之间插入：

```java
import javafx.scene.control.Tooltip;
```

- [ ] **Step 2: 新增 formLabel 标签工厂**

在 `form()` 方法声明行（`private static McpServer form(McpServer s, final Window owner) {`）之前插入类级私有静态方法：

```java
    /** 表单标签统一 150px 宽：输入控件列起点对齐；150 可容纳最长标签（环境变量(KEY=VALUE): 约 140px）不截断 */
    private static Label formLabel(String text) {
        Label l = new Label(text);
        l.setPrefWidth(150);
        return l;
    }
```

- [ ] **Step 3: URL 标签去括号提示（form() 构造处）**

将 `form()` 内（原约 418-420 行）：

```java
        Label urlLabel = new Label(McpServer.STREAMABLE.equals(t0)
                ? "URL(MCP 端点，如 http://host:port/mcp):"
                : "URL(SSE 端点，如 http://host:port/sse):");
```

替换为：

```java
        Label urlLabel = formLabel("URL:");
```

注意：`t0` 变量仍被下方 `selectTransport(transportGroup, t0);` 使用，保持不变。

- [ ] **Step 4: 统一行标签与控件宽度、请求头 tooltip、删除 prefColumnCount**

将 `form()` 内（原约 421-446 行）：

```java
        TextField url = new TextField(s == null ? "" : s.url);
        TextArea headerArea = new TextArea(s == null ? "" : pairLines(s.headers));
        // TextArea 默认 pref 高 231px/宽 683px，3 个会把表单撑到 ~900px 超屏；
        // 与基础设置页白名单一致压到 2 行 20 列（表单高 ~400px 放得下）
        argsArea.setPrefRowCount(2);
        argsArea.setPrefColumnCount(20);
        envArea.setPrefRowCount(2);
        envArea.setPrefColumnCount(20);
        headerArea.setPrefRowCount(2);
        headerArea.setPrefColumnCount(20);

        grid.addRow(0, new Label("名称:"), name);
        grid.addRow(1, new Label("传输:"), transportBox);
        grid.addRow(2, new Label("命令:"), command);
        grid.addRow(3, new Label("参数(每行一个):"), argsArea);
        grid.addRow(4, new Label("环境变量(KEY=VALUE):"), envArea);
        grid.addRow(5, urlLabel, url);
        grid.addRow(6, new Label("请求头(K:V):"), headerArea);
```

替换为：

```java
        TextField url = new TextField(s == null ? "" : s.url);
        TextArea headerArea = new TextArea(s == null ? "" : pairLines(s.headers));
        // 宽度：标签列 150 + hgap 8 + 输入 430 + 内边距 20 ≈ 600（原自适应 ~350 偏窄，看不全）；
        // 高度：TextArea 压 2 行（3 个各默认 ~231px 会把表单撑到 ~900px 超屏，表单高 ~400px 放得下）
        argsArea.setPrefRowCount(2);
        envArea.setPrefRowCount(2);
        headerArea.setPrefRowCount(2);
        name.setPrefWidth(430);
        command.setPrefWidth(430);
        url.setPrefWidth(430);
        argsArea.setPrefWidth(430);
        envArea.setPrefWidth(430);
        headerArea.setPrefWidth(430);

        grid.addRow(0, formLabel("名称:"), name);
        grid.addRow(1, formLabel("传输:"), transportBox);
        grid.addRow(2, formLabel("命令:"), command);
        grid.addRow(3, formLabel("参数(每行一个):"), argsArea);
        grid.addRow(4, formLabel("环境变量(KEY=VALUE):"), envArea);
        grid.addRow(5, urlLabel, url);
        Label headerLabel = formLabel("请求头(K:V):");
        headerLabel.setTooltip(new Tooltip("K:V 或\nKEY=VALUE\n每行一条\n空行忽略"));
        grid.addRow(6, headerLabel, headerArea);
```

说明：
- `setPrefColumnCount(20)` 三行删除——列宽已由显式 `setPrefWidth(430)` 决定（显式值优先于 skin 计算），原列数约束失去意义；
- `setPrefRowCount(2)` 保留（控制高度）；
- 传输行（row 1）的 radio 组不设宽，其自然宽度 < 430，不撑列。

- [ ] **Step 5: showRows() URL 文案收敛 + 注释更新**

将 `showRows()`（原约 537-552 行）方法注释与 urlLabel 赋值：

```java
    /** 按联动口径显隐行：命令组(2-4) / URL(5) / 请求头(6)；隐藏行清空；URL 文案按传输区分 sse 与 streamable */
    private static void showRows(GridPane grid, String transport, Set<McpFormPolicy.Field> keep, Label urlLabel) {
```

与（该方法内尾部）：

```java
        urlLabel.setText(McpServer.STREAMABLE.equals(transport)
                ? "URL(MCP 端点，如 http://host:port/mcp):"
                : "URL(SSE 端点，如 http://host:port/sse):");
```

替换为：

```java
    /** 按联动口径显隐行：命令组(2-4) / URL(5) / 请求头(6)；隐藏行清空；URL 标签固定文案（括号示例已去掉） */
    private static void showRows(GridPane grid, String transport, Set<McpFormPolicy.Field> keep, Label urlLabel) {
```

```java
        urlLabel.setText("URL:");
```

`transport` 参数与 `keep` 参数保留（既有调用链 applyTransport Runnable / transportGroup 监听不动），此时 `transport` 仅剩占位语义——不删，保持签名稳定，避免连锁改动。

- [ ] **Step 6: 编译 + 回归测试验证**

运行：

```bash
mvn compile
mvn test -Dtest=SettingsDialogTest,McpFormPolicyTest
```

预期：BUILD SUCCESS，两个测试类全绿（本次改动为纯 UI 布局与文案，无覆盖断言；SettingsDialogTest 覆盖的 parsePairs/shorten 等纯逻辑未受影响）。

再跑全量测试确认无意外回归：

```bash
mvn test
```

预期：全部通过。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -m "fix: MCP 表单弹窗加宽至 600px、URL 标签去括号提示、请求头挂 tooltip 注释"
```

- [ ] **Step 8: GUI 手动核对（需人工/GUI 环境）**

启动 `minion-0.1.0.jar`（或 IDE 运行），设置 → MCP → 新建/编辑：
1. 弹窗内容区宽约 600px，各输入控件起点对齐；
2. URL 标签显示 `URL:`（无括号示例）；切换传输为 stdio 时 URL 行隐藏逻辑不变，切回 SSE/Streamable 显示 `URL:`；
3. 鼠标悬停「请求头(K:V):」出现深色 tooltip，4 行内容：`K:V 或` / `KEY=VALUE` / `每行一条` / `空行忽略`，每行 ≤10 字；
4. 表单总高不超屏（~400px+按钮区），请求头 TextArea 2 行高、横向可容纳长 KEY=VALUE 不挤压。

> 注：本环境无 GUI 显示条件时，第 4 项核对由用户进行或标记为待人工验证项。
