# 2026-08-13 UI 打磨设计（3 项：确认底部弹框 / 页签移位 / 文字模糊修复）

## 1. 背景与目标

本轮三处 UI 打磨，全部为界面与行为改动，不涉及 LLM 协议层：

1. **危险操作确认弹框**：现状是居中系统风格 `Alert`（系统标题栏、emoji 按钮 ✅❌），与
   无边框深色主窗口「不协调」；用户要求改为**右侧面板底部弹出**的卡片，且弹出范围
   不得越过左右分隔线。
2. **会话页签移位**：页签 TabPane 现整体塞在标题栏同一行内（表头+空内容区把标题栏
   撑出「页签独占一行」的视觉带）；要求移到右侧正文区上方、正文下移一行、带分隔线，
   标题栏收回一行。
3. **文字模糊**：设置窗「基础设置」页右侧全部文字、主窗口聊天正文均「边缘发虚、不锐利」
   （左侧导航/模型页/关于页清晰）；Windows 显示缩放 125%。

## 2. 方案取舍（已与用户确认）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 弹框形态 | 主窗口内 bottom sheet（遮罩+卡片挂在右侧 StackPane 顶层），不做独立窗口 | 用户明确「弹出范围不能超过左右分隔线，需要在右侧」；卡内 Scene 自动继承主题，无系统边框 |
| 遮罩范围 | 仅右侧压暗；弹窗期间**全窗点击拦截**（Scene 事件过滤器） | 用户确认；侧栏保持可见（可看到会话状态）但不可点 |
| 批准按钮配色 | 绿色通过（`.btn-approve` 绿色渐变 #3fb950→#2ea043，呼应状态点绿） | 用户确认（备选红/蓝被否） |
| 其余按钮 | 拒绝/本次会话批准/批准并记住 = `btn-ghost` 灰描边，去 emoji | 用户确认「需要有审美」，emoji 是「不协调」来源之一 |
| 遮罩点击 | **无反应**（必须按按钮关闭） | 用户确认（保守，防误触） |
| 键盘 | Esc=拒绝；Enter=激活当前焦点按钮，弹出时焦点默认置「批准」按钮；Tab 焦点圈定卡片内 | Enter=批准、Esc=拒绝由用户确认；Tab 拦截防焦点逃逸到侧栏 |
| 并发确认 | 串行队列（一个在展示时，后续排队依次弹出） | 多会话同时弹确认不叠卡 |
| 弹框超时语义 | GuiConfirmUi 保留 3 秒超时 REJECT（拒绝比错误批准安全）；超时后卡片仍在，用户点击结果丢弃 | 与现状 Alert 超时行为一致，不引入新语义 |
| 文字模糊根因 | JavaFX 8 对 ScrollPane 裁剪内文字回退灰阶抗锯齿（LCD 被禁），Windows 125% 缩放放大柔边 | 证据：全部模糊面（基础设置页、聊天正文）均在 ScrollPane 内；全部清晰面（ListView 导航/模型页、关于页 VBox）不在 |
| 模糊修法 | 基础设置页去 ScrollPane + 聊天区显式 `-fx-font-smoothing-type: lcd` +（备选）T2K 光栅器 | 分步验证，每步用户真机目测；均无效则接受平台限制并记录 |
| 页签移位 | 标题栏去掉 TabPane（弹性 Region 占位）；右侧 VBox 顶部加 `tabs-bar`（页签+1px 底分隔线） | 用户确认「正文往下一行、有分隔线、去掉页签独占的一行」 |

## 3. 架构改动总览

```
gui/
├── MainWindow            改造：右侧包 StackPane（ConfirmSheet 宿主）+ tabs-bar 行；TitleBar 调用瘦身
├── TitleBar              改造：去 center 参数，弹性 Region 占位
├── dialog/
│   ├── ConfirmSheet（新）右侧底部确认卡片（替代 ConfirmDialog）
│   └── ConfirmDialog     删除
├── confirm/GuiConfirmUi  改造：FutureTask+Alert → BlockingQueue.poll(3s)+ConfirmSheet
└── theme/theme.css       新增 .sheet-*/.btn-approve/.tabs-bar；聊天区 LCD 显式化
SettingsDialog            改造：基础设置页去 ScrollPane
minion.bat                （备选步骤）加 -Dprism.text=t2k
```

## 4. 需求 1：危险操作确认 → 右侧底部 Bottom Sheet

### 4.1 新类 `gui/dialog/ConfirmSheet.java`

- `static void setHost(StackPane host)`：MainWindow 在 show() 时注册右侧面板栈
- `static void show(String message, Consumer<ConfirmUi.Decision> callback)`：
  **必须在 FX 线程调用**（GuiConfirmUi 经 Platform.runLater 保证）：
  1. host 未注册（无 GUI 环境）→ stderr 警告 + 立即回调 REJECT（防御，不挂死）
  2. 已有卡片在展示 → 请求入队，关闭后依次弹出（串行）
  3. 构建遮罩 Region（CSS `.sheet-scrim`，仅盖右侧宿主栈）+ 卡片 VBox（CSS `.sheet-card`），
     加入宿主顶层
  4. 动画：遮罩 FadeTransition 150ms；卡片 TranslateTransition 180ms
     （fromY=卡片高度→0，ease-out）；关闭 120ms 淡出
  5. 拦截：`scene.addEventFilter(MouseEvent.ANY, ...)`——坐标落卡片外一律 consume
     （覆盖右侧遮罩区与左侧侧栏，实现全窗点击拦截）；`addEventFilter(KeyEvent.ANY, ...)`
     ——ESC→REJECT；ENTER 放行（触发焦点按钮）；其余按键（含 Tab）一律 consume，
     焦点圈定卡片内
  6. 焦点默认置「批准」按钮（Enter 即批准，与现状 Alert 默认按钮语义一致）
  7. 按钮点击 → 移除过滤器与节点、播放淡出、回调 Decision、出队展示下一个

### 4.2 卡片视觉（CSS `.sheet-*` 段落，均在 Scene 内自动继承主题）

```
╭─────────────────────────────╮
─ 3px 琥珀色饰条 #e3b341 ───────   ← 告警语义（呼应 msg-warning），危险感由饰条+图标承担
│ ⚠ 高危操作确认               │  ← 14px 粗体 #f0f2f6
│ ┌─────────────────────────┐ │
│ │ ! 高危操作 Bash →       │ │  ← 内嵌面板 #13161c 圆角 8，正文 13px #d3d7de
│ │   rm -rf ./build        │ │
│ └─────────────────────────┘ │
│ 拒绝  本次会话批准  批准并记住  [批准] │  ← 批准=绿色渐变；其余 btn-ghost；右对齐
│        Enter 批准 · Esc 拒绝      │  ← 11px #7a828e 弱化提示
╰─────────────────────────────╯
```

- 卡片定位：底部居中、左右边距 16、底边距 12、最大宽 640、圆角 12、边框 #2a2f3a、
  深色投影 dropshadow(0,8,24,#000,0.4)
- `.btn-approve`（新）：linear-gradient(#3fb950→#2ea043) 白字加粗，hover 提亮
- 标题文案固定「高危操作确认」；message 原样展示（ConfirmGate 传入的「! 高危操作 Bash → …」）

### 4.3 GuiConfirmUi 改造（线程语义）

```java
public Decision ask(String message) {
    final LinkedBlockingQueue<Decision> q = new LinkedBlockingQueue<>(1);
    try {
        Platform.runLater(() -> ConfirmSheet.show(message, q::offer));
    } catch (IllegalStateException e) {
        return Decision.REJECT; // FX toolkit 未启动（无 GUI 环境），防御性拒绝
    }
    try {
        Decision d = q.poll(3, TimeUnit.SECONDS);
        return d != null ? d : Decision.REJECT; // 超时兜底，拒绝比错误批准安全
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Decision.REJECT;
    }
}
```

- 原 FutureTask+Alert 模式删除；FX 线程不再阻塞（消除 showAndWait 嵌套事件循环）
- 3 秒超时语义保留；超时后卡片仍在展示，用户点击结果被丢弃（与现状 Alert 一致）
- ConfirmDialog 删除（全代码库调用点仅 GuiConfirmUi 一处）

### 4.4 MainWindow 接入

- 右侧 `VBox right` 外包一层 `StackPane`（chatScroll/inputView 在下层，遮罩卡片在上层），
  `ConfirmSheet.setHost(stack)` 注册；遮罩范围即右侧面板（含 tabs-bar/聊天/输入区，不越分隔线）

### 4.5 已知边界（记录不修）

- 排队中的确认若工具侧已 3 秒超时，其卡片仍会随后弹出，用户点击无效果——
  无「等待者已超时」信号回传，与现有 Alert 超时行为同源，接受

## 5. 需求 2：文字模糊修复（分步验证）

### 5.1 根因（systematic-debugging 证据链）

| 区域 | 容器 | 用户观测 |
|---|---|---|
| 基础设置页 | ScrollPane | 模糊（边缘发虚） |
| 主窗口聊天正文 | chatScroll（ScrollPane） | 有点朦 |
| 左侧导航/模型页 | ListView（VirtualFlow 虚拟化渲染） | 清晰 |
| 关于页 | VBox | 清晰 |

全部模糊面均在 ScrollPane 内、全部清晰面不在 → 根因锁定 **JavaFX 8 在 Windows 下对
ScrollPane 裁剪内文字禁用 LCD 子像素渲染、回退灰阶抗锯齿**；125% 显示缩放把灰阶柔边
放大 1.25 倍，观感「朦」。逐页排查已排除：样式表对比度（#e6e8ee 够亮）、StackPane
小数坐标居中（其他页同布局但清晰）、字体族（同字体区域有清晰者）。

### 5.2 修法（每步真机目测验证后再决定是否进入下一步）

1. **基础设置页去 ScrollPane**：[SettingsDialog.basicPane] 内容高约 453px，
   设置窗固定 620×500 放得下 → 删 ScrollPane 直接返回 contentBox（该页立即清晰）
2. **聊天区显式 LCD**：theme.css 给聊天正文根节点（ChatView 根挂 `.chat-content` 类）
   加 `-fx-font-smoothing-type: lcd`，验证 Prism 是否在裁剪下尊重显式 LCD
3. **T2K 光栅器（备选）**：若步骤 2 无效，minion.bat 加 `-Dprism.text=t2k`
   （T2K 自有光栅化路径，可能不受裁剪回退规则约束），再验证
4. **接受并记录**：若均无效，视为 JavaFX 8 + 125% 缩放平台限制，在设计文档/README
   记录，不做 hack

- 验收标准：基础设置页 + 聊天正文目测「锐利」（用户真机判断；能提供前后截图更稳）

## 6. 需求 3：会话页签移位（标题栏一行化）

### 6.1 TitleBar 瘦身

- 构造签名去掉 `Node center` 参数：`TitleBar(Stage, Label modelLabel, Runnable openSettings, Runnable confirmClose)`
- 模型名与 ⚙ 之间加弹性 `Region`（`HBox.setHgrow(…, ALWAYS)`）占位原页签空间
- 双击最大化排除逻辑保留 `==` 收窄判断（页签 Label 已不在标题栏，注释简化）

### 6.2 MainWindow 布局

- `right.getChildren().setAll(tabsBar, chatScroll, inputView)`：
  - `tabsBar` = HBox(tabs) + styleClass `tabs-bar`（CSS：内边距 4 8 0 8、
    底部 1px 边框 #232733 作分隔线）
  - 页签为空时整行隐藏（`tabs.getTabs()` 加 ListChangeListener → visible/managed 联动）
- 页签交互逻辑零改动（选中激活/关闭确认/删除联动/呼吸点/rebuildTabs 全部照旧，仅换容器）

### 6.3 CSS

- `.tabs-bar` 分隔线（上）；`.tab-pane .tab-header-area .tab-header-background` 背景改
  transparent（叠加在右侧 panel-dark 上，消除原 #12141a 色带）
- `.tab-pane > .tab-content-area` 三高归零（min/pref/max height 0）——压掉 TabPane
  空内容区，标题栏那「独占一行」的视觉带随之消失
- 页签自身样式（#1a1d24 圆角、选中 #232a38）沿用不动

## 7. 测试与验收

- 单测：GuiConfirmUiTest 两用例语义不变继续通过（无 FX 线程 → REJECT 不挂死）；
  GUI 视觉部分无自动化测试（项目惯例：无 TestFX），验证 = 编译 + 既有测试全过 + 手工清单
- 回归：`JAVA_HOME="E:/javame/jdk8" mvn test` 全量通过
- 构建：`JAVA_HOME="E:/javame/jdk8" mvn clean package`
- 手工验收清单：
  1. 触发高危操作（如 Bash 危险命令）→ 卡片从右侧底部滑入；遮罩仅右侧、侧栏可见；
     点遮罩无反应；点侧栏无反应（拦截）；Esc=拒绝；Enter=批准；批准按钮为绿色
  2. 两个会话并发触发确认 → 不叠卡，依次弹出
  3. 设置窗基础设置页文字锐利；聊天正文锐利（目测对比改前）
  4. 页签在右侧顶部、下方有分隔线、标题栏只有一行；点击页签激活会话、✕ 关闭删除、
     呼吸点正常；无会话时页签行（含分隔线）隐藏

## 8. 文档同步

- CLAUDE.md 包结构：gui/dialog 注释「ConfirmDialog（高危确认弹窗）」改为
  「ConfirmSheet（高危确认底部卡片）」
- ARCHITECTURE.md：gui 包结构同步（ConfirmSheet 替代 ConfirmDialog、TitleBar 瘦身）
- README：快捷操作补充确认弹窗交互（Enter 批准 / Esc 拒绝）；如需补充 125% 缩放说明
  （仅当步骤 5.2.4 触发）

## 9. 范围外（YAGNI）

- 不做：其他 Alert 弹窗（退出确认/删除会话/删除模型/设置窗）的 sheet 化推广——
  本轮只看高危确认效果，满意再推广
- 不做：125% 缩放下的全局渲染方案（平台限制，见 5.2.4）
- 不做：页签拖拽排序/固定、页签栏横向滚动按钮
- 不做：模糊修复的自动化测试（GUI 无头环境不可测，验收靠真机目测）
