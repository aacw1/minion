# SVG 图标替换（Win7 方块修复）设计

日期：2026-08-23
状态：已确认

## 背景与动机

- Win7 下大量界面图标显示为方块（tofu）：左侧页签、上面页签、正文工具输出、设置窗图标。
- 根因：这些图标是 Unicode 字符/emoji（⚙✕✎✅❌❓▶✓⛭▾▸⏱❐□ 等），字形不在 Win7 字体回退链中（Segoe UI Symbol 需更新且 JavaFX 回退链未必包含）。
- 用户要求：列出全部涉及图标，用 SVG 替换；图形可按用途重新设计，要求美观。

## 需求

1. 全部 15 处 Unicode 字符图标替换为矢量图形（JavaFX SVGPath），Win7 不再依赖字体字形。
2. 风格统一：Material Symbols Outlined（24×24，Apache-2.0），与现有 InputView 发送/停止/回形针、RunningIndicator 齿轮同族。
3. 图标集中管理，颜色由 CSS 控制（主题化），可 hover 变色。
4. 纯展示层改动：不触碰消息历史/事件流/存储格式/core 层格式（StatsLine 不动）。

## 图标清单（现状 → 新图标）

| 位置 | 现状字符 | 用途 | 新图标（Material outlined） | 色 | 显示尺寸 |
|------|----------|------|------------------------------|----|----------|
| TitleBar | ⚙ | 设置 | settings | 灰→hover 白 | 14 |
| TitleBar | — | 最小化 | remove | 灰→hover 白 | 14 |
| TitleBar | □ | 最大化 | crop_square | 灰→hover 白 | 14 |
| TitleBar | ❐ | 还原 | filter_none | 灰→hover 白 | 14 |
| TitleBar | ✕ | 关闭 | close | 灰→hover 红底白 | 14 |
| SessionListView | ✎ | 重命名 | edit | 灰→hover 主色 | 13 |
| SessionListView | ✕ | 删除 | delete（垃圾桶） | 灰→hover 红 | 13 |
| WorkspaceListView | ⚙ | 修改 | settings | 灰→hover 主色 | 13 |
| WorkspaceListView | ✕ | 删除 | delete（垃圾桶） | 灰→hover 红 | 13 |
| WorkspaceListView | ● | 当前空间标记 | dot（实心圆） | 主色 | 8 |
| ChatView | ❓ | 模型向你提问 | help | 琥珀 #e6a23c | 14 |
| ChatView | ✅ | 工具成功 | check_circle | 绿 #4caf50 | 14 |
| ChatView | ❌ | 工具失败 | error | 红 #e05b5b | 14 |
| ChatView | ▶ | 子任务开始 | play_arrow | 蓝 #4f8cff | 14 |
| ChatView | ✓ | 子任务完成 | check | 绿 #4caf50 | 14 |
| ChatView | ⛭ | 工具调用摘要 | build（扳手） | 灰蓝 #8a8f98 | 14 |
| CollapsibleText | ▾ / ▸ | 收起/展开 | expand_more / chevron_right | 次要灰 | 12 |
| InputView | ✕ | 块删除 | close | 灰→hover 白 | 11 |
| SettingsDialog | ● | 当前模型标记 | dot | 主色 | 8 |
| SettingsDialog | ● | MCP 状态点 | dot | 主色 | 8 |
| 统计行（GUI 渲染层） | ⏱ | 耗时前缀 | timer | 次要灰 | 13 |

语义变化（唯一一处）：行内删除按钮从「叉」改为「垃圾桶」——符合 Material 惯例，并区分「关闭=叉 / 删除=垃圾桶」。

## 方案：集中式 IconFactory

JavaFX 8 `Image` 不支持加载 .svg 文件，矢量只能走 `SVGPath`（内嵌 path 数据）——现有 InputView/RunningIndicator 已用此方式，功能等价、零新依赖。path 数据来源：Material Symbols（Apache-2.0），注释标注图标名。

### 新增 gui/icon/IconFactory.java（新包 com.minion.gui.icon）

- 集中存放全部 path 常量 + 语义工厂方法（settings()/close()/success()/error()/build()/dot()…）。
- 每个方法返回 SVGPath：自动设置语义样式类（icon-settings/icon-success/…）、统一 24×24 viewport 显示缩放。
- 颜色一律 CSS `-fx-fill` 控制（outlined 变体为轮廓填充型 path），不内联 setFill，便于主题化。
- 迁移已有 4 处 SVG 集中管理：InputView 发送/停止/回形针（回形针统一为 outlined attach_file 填充版）、RunningIndicator 齿轮；迁移只搬位置与统一风格，不改变行为。

### 各文件改动

| 文件 | 改动 |
|------|------|
| TitleBar.java | 5 个窗口按钮 setGraphic（设置/最小化/最大化/还原/关闭） |
| SessionListView.java | 重命名 edit、删除 delete |
| WorkspaceListView.java | 修改 settings、删除 delete；当前标记 ● → nameLabel.setGraphic(dot) + ContentDisplay.RIGHT（零结构改动） |
| ChatView.java | 摘要行改 HBox[图标, 文本]；toolCallSummary 去 ⛭ 前缀返回纯文本；STATS 分支剥离 "⏱ " 前缀后加 timer 图标 |
| CollapsibleText.java | toggle 改 HBox[chevron 图标, 摘要 Label]，折叠态切换 expand_more/chevron_right，保持可点击 |
| InputView.java | 块删除 ✕ → close；3 个已有 SVG 改用工厂 |
| SettingsDialog.java | 模型当前标记、MCP 状态点 ● → dot |
| theme.css | 新增全部 icon-* 样式类与 hover 规则；清理 L281 字形宽度兼容注释 |
| StatsLine.java | 不动（core 层保持 ⏱ 前缀，单测断言原样） |

### 正文段呈现

- 摘要行从纯文本 Label 改为 HBox[SVGPath, Label]（Seg 结构不变：tag 列【工具】等不动，仅摘要节点类型变化）。
- 新增 `appendCollapsible(tag, class, Node summary, String text)` 重载，原 String 版委托保留（其他调用点零改动）。
- STATS 分支：文本以 "⏱ " 开头 → 剥离前缀渲染 [timer, 文本]；否则原样整行显示（兼容旧数据）。
- 历史会话重放自动生效：重放走同一 onEvent → ChatView 渲染路径，无额外改动。

## 样式（theme.css）

- 默认色：按钮图标 #a8b0bb（hover 白/主色/红），正文语义色见上表。
- hover 规则：`.btn-ghost:hover .icon-x { -fx-fill: ... }`（SVGPath 不随 Button 的 text-fill 变色，须显式规则）。
- 现有 .icon-send/.icon-stop/.icon-upload/.running-indicator-gear 保留或并入统一命名。

## 测试

- 新增 IconFactoryTest：全部工厂方法 path 非空、new SVGPath().setContent 不抛、样式类正确。
- 更新 ChatViewToolBodyTest：toolCallSummary 断言去 ⛭ 前缀（"Edit → src/foo.java"）。
- StatsLine 相关测试不动。
- 检查 CollapsibleText/其它受影响测试并同步。
- 回归：JAVA_HOME="E:/javame/jdk8" mvn compile + 相关测试通过。

## 文档同步

- README.md：图标方案简述。
- docs/ARCHITECTURE.md：包结构加 gui/icon。

## 不做（YAGNI）

- 不做图标尺寸/颜色设置项（CSS 常量即可）。
- 不替换用户 markdown 内容中的 emoji（正文渲染的是用户/模型文本，非界面图标）。
- 不引入图标字体/外部库（FontAwesomeFX 等）——SVGPath 零依赖已满足。
- 不改窗口/侧栏布局结构（仅按钮 graphic 与摘要行节点）。
