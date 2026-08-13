# minion UI 修复与增强设计

日期：2026-08-13
状态：待实施

## 背景

用户报告 10 项 UI 问题/需求（会话切换、历史渲染、消息颜色、设置窗、拖拽排序、自动滚动、页签溢出、悬停操作按钮、消息时间、project.md 文件选择）。均已定位根因，本文档给出设计与实施边界。

## 需求列表

| # | 需求 | 根因 |
|---|------|------|
| 1 | 标题栏页签/左侧列表点击切换会话，右侧正确显示该会话（含历史）消息 | ①TabPane 无选中监听；②恢复会话 EventList 为空；③启动时历史会话无页签 |
| 2 | 发送新消息后左侧自动出现消息页签 | 同 1③（启动不补齐页签；历史会话发消息无标题变更通知） |
| 3 | 最后消息从文字变成消息块时黑底黑字 | TextFlow/表格内 Text 默认黑色填充，不响应 `-fx-text-fill` |
| 4 | 设置窗左侧页签字体竖排（"倒了"），页签栏加宽 | SettingsDialog 用 `side=LEFT`，JavaFX 左侧页签文字旋转 90° |
| 5 | 基础设置页标签文字变成"..." | GridPane 无列约束，标签列被输入框挤压到省略号截断 |
| 6 | 工作空间可上下拖动改变顺序 | 无拖拽实现；workspace.json 数组顺序可持久化 |
| 7 | 流式回复时贴底不自动滚动 | 现有 setupAutoScroll 贴底判定与 setVvalue 时序不可靠 |
| 8 | 标题栏页签溢出不显示滚动条/箭头，超出部分不可见 | TabPane 溢出出现控制按钮 |
| 9 | 工作空间重命名/修改/删除改悬停小按钮（不用右键） | 现为右键 ContextMenu |
| 11 | 会话重命名/删除改悬停按钮；非悬停显示最近消息时间（5m/3h/2d） | 现为右键 ContextMenu；Message 无时间戳 |
| 12 | 工作空间弹窗 project.md 加文件系统选择（同 work.dir） | 现为纯文本输入 |

## 节 1 会话切换与历史渲染（需求 1、2）

### 1.1 历史消息灌入事件流

[SessonManager.restoreSessions](src/main/java/com/minion/gui/session/SessionManager.java) 恢复会话后，把 `session.messages` 转换为 `EventList.Ev` 灌入该会话的 EventList（经 [SessionController](src/main/java/com/minion/gui/session/SessionController.java) 新增方法 `replayHistory(List<Message>)`，在恢复构造函数后调用一次）。

转换规则：

| Message.role | Ev | 说明 |
|---|---|---|
| USER | USER_MESSAGE(content) | |
| ASSISTANT 且 content 非空 | CONTENT(content) | 流式重渲染同路径 |
| ASSISTANT 的 toolCalls | 跳过 | 历史只重演对话内容，不重演工具过程 |
| SYSTEM / TOOL | 跳过 | |

要点：

- EventList 未激活时 `add` 只入缓冲（恢复阶段 listener 为 null），无事件外泄。
- 渲染路径唯一：ChatView.bind(true) 先 clear 再重放缓存（含历史），切换会话天然正确。
- 运行时新消息走 AgentLoop → SessionController 回调，增量入流，与历史不重复。

### 1.2 标题栏页签选中激活

[MainWindow](src/main/java/com/minion/gui/MainWindow.java) 的 `tabs` 加 `selectedItemProperty` 监听：选中变化 → 从 `tab.getUserData()`（会话 id）在 `manager.sessions()` 中找 SessionHandle → `manager.activateSession(h)`。

- 找不到（删除竞态）→ 忽略。
- `activateSession` 幂等：重复激活同一会话仅重设 active + 通知，UI 重绑无副作用；`addTab` 内 `select(t)` 触发的监听与激活路径重叠安全。

### 1.3 启动补齐页签（需求 2 根因）

`show()` 注册 manager 监听后调用现有 `rebuildTabs()`：为当前工作空间所有 `title != null` 的会话建标题栏页签。

- 新建会话路径不变：发送后标题生成 → `notifyTitleChanged` → `updateTab` → `addTab`（已有逻辑）。
- 工作空间切换路径已有 `rebuildTabs`（onWorkspaceChanged），不重复改。

## 节 2 消息文字颜色（需求 3）

[BlockNodeFactory](src/main/java/com/minion/gui/chat/BlockNodeFactory.java) 中对 `Text` 节点显式 `setFill`（`Text` 响应 `-fx-fill`，不响应 `-fx-text-fill`，此为根因）：

- 段落/列表内行内 Text：普通文本 `#f0f2f6`，行内代码保留 `#79c0ff`（`spanText` 现有逻辑处补默认色）。
- 表格单元格 Text：`#c9d1d9`（与 `.code-block` 文字一致）。
- 主题色定义为类常量，不依赖 CSS 级联。

CODE 块（Label）与 HEADING（内联样式）已正确，不动。

## 节 3 设置窗（需求 4、5）

[SettingsDialog](src/main/java/com/minion/gui/dialog/SettingsDialog.java)：

- **页签横排**：`setSide(TOP)` 取代 `side=LEFT`，消除竖排文字；`setTabMinWidth(90)` 加宽页签栏。
- **基础设置标签截断**：`basicTab` 的 GridPane 加 `ColumnConstraints`：标签列（列 0）不收缩、宽度按内容；输入列（列 1）`HGrow ALWAYS` + `maxWidth Infinity`；TextField 移除固定 `prefWidth(320)`。标签完整显示、输入框铺满剩余宽度。

## 节 4 侧栏交互（需求 6、9、11、12）

### 4.1 工作空间拖拽排序（需求 6）

- [WorkspaceManager](src/main/java/com/minion/core/config/WorkspaceManager.java) 新增 `boolean move(String name, int newIndex)`：List 内移除+插入（越界返回 false）+ `save()` 持久化。workspace.json 数组顺序即显示顺序。
- [SessionManager](src/main/java/com/minion/gui/session/SessionManager.java) 新增 `moveWorkspace(name, newIndex)` 转发（**不发通知**——`notifyWorkspaceChanged` 会触发 MainWindow 的 `clearChatPane` 清空右侧聊天区，拖拽排序不应清内容）；[WorkspaceListView](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) drop 后自行 `refresh()`。
- [WorkspaceListView](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) 用 JavaFX 原生 DragAndDrop：
  - cell `setOnDragDetected`：`startDragAndDrop(MOVE)` + ClipboardContent（携带工作空间名）。
  - `setOnDragOver`：accept 拖放；目标索引 = 当前 cell 索引，作为插入位置。
  - `setOnDragDropped`：`manager.moveWorkspace(name, 目标索引)` + `refresh()`（选中态/当前标记随重建恢复）。
- 单击切换保留不改（拖拽与单击互不干扰）。

### 4.2 工作空间悬停按钮（需求 9）

`WsCell` 改为 graphic：`HBox(名称 Label（含 ● 当前标记）, Region 弹性, 按钮组)`；按钮组含重命名/修改/删除（`btn-ghost` 小按钮），初始 `visible=false` + `managed=false`；cell `setOnMouseEntered` 显示、`setOnMouseExited` 隐藏。**移除 ContextMenu**（用户明确不用右键）。

### 4.3 会话悬停按钮 + 消息创建时间（需求 11）

**Message 加时间戳**（[Message.java](src/main/java/com/minion/core/llm/Message.java)）：

- 新增 `public long ts;`（毫秒，默认 0）；四个工厂方法（user/assistant/toolResult/system）创建时 `ts = System.currentTimeMillis()`。已确认全代码库 Message 创建均走工厂，无遗漏。
- 随会话 JSON 落盘（gson 全字段序列化）；旧文件无 ts → 0，向后兼容。

**时间显示规则**（取最后一条非 TOOL 消息的 ts，`now - ts`）：

| 距离 | 显示 |
|---|---|
| < 1 分钟 | 1m |
| < 60 分钟 | 向下取整分钟数 + "m"（如 5m） |
| < 24 小时 | 向下取整小时数 + "h"（如 3h） |
| ≥ 24 小时 | 向下取整天数 + "d"（如 2d） |
| ts == 0（旧数据） | 不显示 |

**SessionCell 双态右区**：`HBox(状态点, 名称, 弹性, 右区)`；右区非悬停显示时间 Label，悬停切换为重命名/删除按钮（同 4.2 机制）。摘要第二行（`lastSummary`）保留。

刷新时机：随现有 `refresh()`（新建/删除会话、切换工作空间）；运行中不实时刷新（与摘要行为一致）。

### 4.4 project.md 文件选择（需求 12）

- [MainWindow.onNewWorkspace](src/main/java/com/minion/gui/MainWindow.java) 弹窗：project.md 输入框旁加"浏览…"按钮（`btn-ghost`），用 `FileChooser` 选文件填入路径（work.dir 仍为 `DirectoryChooser`）。
- [WorkspaceListView.doEdit](src/main/java/com/minion/gui/sidebar/WorkspaceListView.java) 修改弹窗：同样处理。
- FileChooser 初始目录：当前输入框路径的父目录（存在时），与 work.dir 浏览逻辑一致。

## 节 5 自动滚动与页签溢出（需求 7、8）

### 5.1 AutoScrollPolicy（新类，可单测）

位置 `com.minion.gui.session.AutoScrollPolicy`（纯逻辑，无 JavaFX 依赖）：

- `void onScroll(double vvalue, double vmax)`：更新"贴底"状态（`vvalue >= vmax - 0.001`）。
- `boolean shouldFollow()`：贴底时返回 true（供内容增长时判断）。

[MainWindow.setupAutoScroll](src/main/java/com/minion/gui/MainWindow.java) 改用该策略：

- `vvalueProperty` 监听 → `policy.onScroll`。
- `vmaxProperty` 监听 → `policy.shouldFollow()` 时 `Platform.runLater(() -> chatScroll.setVvalue(chatScroll.getVmax()))`——延迟到布局完成后设置，避免 setVvalue 被旧 vmax clamp 吞掉（现实现的失效根因）。

### 5.2 页签溢出不显示滚动条（需求 8）

theme.css 新增：

```css
.tab-pane > .tab-header-area > .control-buttons-tab { -fx-visibility: hidden; }
.tab-pane .tab { -fx-min-width: 0; }
```

- 隐藏溢出控制按钮（JavaFX 溢出时出现的下拉箭头），页签可压缩至最小宽度，超出部分由 header area 裁剪，不出现滚动条。
- 实施时需手工验证裁剪效果；若 JavaFX 8 下 CSS 无效或未裁剪，备选：监听页签数量，超出宽度隐藏多余页签（addTab 处维护可见页签集合）。

## 测试计划

自动化（junit4）：

- `AutoScrollPolicyTest`：贴底判定、离开底部不跟随、增长跟随。
- `SessionControllerTest`：`replayHistory` 转换规则（USER/CONTENT 灌入、TOOL/SYSTEM 跳过、空消息跳过）。
- `WorkspaceManagerTest`：`move` 顺序调整 + 越界返回 false + 持久化（重载后顺序保持）。
- `MessageTest`：工厂打点 ts > 0。

手工验证清单（GUI）：

1. 启动后历史会话出现在标题栏页签；点击页签/左侧列表，右侧显示该会话历史消息。
2. 新建会话发消息 → 页签自动出现；历史会话发消息 → 页签存在且不重复。
3. 流式回复末尾消息块文字浅色可读（正文/代码/表格）。
4. 设置窗页签横排；基础设置标签完整显示。
5. 工作空间拖拽排序，重启后顺序保持。
6. 流式输出中滚到底部，新内容到达自动跟随。
7. 页签多到溢出：无箭头/滚动条，超出部分不可见。
8. 悬停会话/工作空间项显示操作按钮；会话非悬停显示 5m/3h/2d 时间。
9. 工作空间新建/修改弹窗 project.md 可用文件选择器。

验收：`JAVA_HOME="E:/javame/jdk8" mvn clean package` + 手工清单全过。

## 文档同步

- README.md：侧栏悬停按钮、消息时间显示、工作空间拖拽排序、project.md 文件选择（如涉及使用说明）。
- docs/ARCHITECTURE.md：AutoScrollPolicy、SessionController.replayHistory、Message.ts 字段说明。
