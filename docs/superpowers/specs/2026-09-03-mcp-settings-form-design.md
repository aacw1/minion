# MCP 服务器表单弹窗 UI 修正设计

日期：2026-09-03
状态：已获用户确认（设计对话内批准）
涉及文件：`src/main/java/com/minion/gui/dialog/SettingsDialog.java`（`form()` 方法）

## 背景与问题

设置 → MCP →「新建 / 编辑」弹出的 MCP 服务器配置弹窗存在三个问题：

1. **弹窗过窄**：GridPane 各输入控件未显式设宽，弹窗宽度由内容自适应计算，实际偏窄（用户反馈"看不全"）。
2. **URL 标签带括号示例提示**：`URL(SSE 端点，如 http://host:port/sse):` / `URL(MCP 端点，如 http://host:port/mcp):`，用户要求去掉提示。
3. **请求头字段无任何格式说明**：标签仅为 `请求头(K:V):`，用户不清楚填什么格式；需求是在该处提供注释——已确认采用 tooltip 悬停形式，注释内容分行展示、每行不超过 10 个字。

## 改动设计

全部改动位于 `SettingsDialog.form()`（新建/编辑 MCP 服务器弹窗），共三处：

### 1. 弹窗内容加宽至约 600px

- 表单 7 个行标签（名称/传输/命令/参数/环境变量/URL/请求头）统一 `setPrefWidth(150)`。
  - 现状：标签宽度按文本自适应，各输入控件起点参差；固定 150 后列起点对齐。
  - 150 可容纳最长标签"环境变量(KEY=VALUE):"（实测约 140px），不截断。
- 6 个输入控件（名称 TextField、命令 TextField、URL TextField、参数 TextArea、环境变量 TextArea、请求头 TextArea）统一 `setPrefWidth(430)`。
  - TextArea 保持现有 `setPrefRowCount(2)` 高度不变（表单总高 ~400px，无超高问题）。
- 列宽合计：150（标签）+ 8（hgap）+ 430（输入）+ 20（Insets 内边距）≈ 608px，符合"内容区约 600px"目标。
- **明确不采用**：GridPane 列约束（ColumnConstraints）/ Hgrow 扩展方案——项目历史注释（SettingsDialog 约 713 行）记录 JavaFX 8 下 GridPane 列在空间不足时挤压截断，弃用该做法；本次固定 pref 宽度即可达目标，不引入已知坑。
- 不改动 GridPane 行结构、传输切换显隐联动（`showRows`/`clearHiddenRows`/`clearRowText`）等既有逻辑，最小回归面。

### 2. URL 标签去掉括号提示

- 标签文案固定为 `URL:`，不再按传输类型（SSE / Streamable HTTP）区分示例文案。
- 涉及两处赋值：
  - `form()` 内 urlLabel 初始构造（现为三元按 `McpServer.STREAMABLE` 区分）；
  - `showRows()` 内切换传输时的 `urlLabel.setText(...)`。
- `showRows()` 的 urlLabel 参数与调用链保留不变，仅文案收敛为常量。
- 同步更新 `showRows()` 上方注释（原注释描述"URL 文案按传输区分 sse 与 streamable"）。

### 3. 请求头字段注释（tooltip）

- `请求头(K:V):` 标签从内联 `new Label(...)` 改为局部变量持有，并安装 Tooltip。
- 注释文案（4 行，每行 ≤10 字，已与用户确认）：

  ```
  K:V 或
  KEY=VALUE
  每行一条
  空行忽略
  ```

- Tooltip 样式自动套用 `src/resource/theme/theme.css` 既有 `.tooltip`（深底浅字），无需新增 CSS。
- 换行通过文案中的 `\n` 实现（JavaFX Tooltip 文本支持）。

## 不做的事

- 不改列表页/主设置窗尺寸（用户确认问题只在 MCP 表单弹窗）。
- 不把 GridPane 重构为 BasicPane 的 HBox 表单行模式——改动面过大、与需求不匹配。
- 环境变量等其它字段的提示文案不变（用户未提）。

## 验证

- `mvn compile` 通过。
- 运行相关单测：`SettingsDialogTest`、`McpFormPolicyTest`（改动仅 UI 布局与文案，无既有断言覆盖这两处，预期全绿）。
- 手动核对点（GUI 环境）：表单宽度约 600、URL 标签无括号、请求头标签悬停出现 4 行注释。
