# 高危确认：无限等待 + 两行紧凑卡（2026-08-15）

## 1. 背景（用户报告）

1. **逻辑 bug**：高危操作弹框点了「同意」后，结果仍是「拒绝」——执行逻辑顺序有问题。
2. **UI 缺陷**：弹框铺满整个页面；要求只占两行高度、底部显示、距底部 1 行。

## 2. 根因（systematic-debugging 证据链）

### 2.1 「点了同意却说拒绝」——固定 3 秒超时与用户点击竞争

调用链：`ConfirmGate.check` → `GuiConfirmUi.ask` → 工具线程 `q.poll(3s)`；
FX 线程经 `Platform.runLater` 展示 `ConfirmSheet` 卡片。用户读消息、移鼠标、
点击「同意」超过 3 秒（或点击在 2.9s 后因 120ms 淡出动画在超时后才送达），
`ask()` 已返回 REJECT，点击结果 `q.offer` 被丢弃 → `AgentLoop` 报
「用户拒绝了该操作」。

设计文档 2026-08-13 的「已知边界 4.5」明记此缺陷（排队中的确认若工具侧
已 3 秒超时，卡片仍会随后弹出、点击无效果）——设计时接受、实际被触发。
多会话并发时更严重：排队中的第二个确认，其 3 秒计时在等前一张卡片时即耗尽。

### 2.2 「铺满整个页面」——宿主 StackPane 拉伸卡片

`MainWindow` 右侧面板外包 `StackPane`（ConfirmSheet 宿主），StackPane 默认把
可缩放子节点拉伸铺满自身。卡片 VBox 的 `maxHeight` 无界（默认 MAX_VALUE）
→ 卡片撑满整个右侧面板、内容贴顶，而非设计稿中的底部小卡片。
（滑入动画 fromY=卡片高度也随之为整页高度，进一步证实卡片被拉伸。）

## 3. 设计决策

### 3.1 无限等待（用户拍板：60 秒有界 vs 无限等待 → 无限等待）

`GuiConfirmUi.ask`：`q.poll(3s)` → `q.take()`（无限阻塞）。

- **点击即送达**：工具线程一直等到用户点击（或会话关闭中断 → REJECT），
  不存在超时竞态；「点了同意」永远生效。恢复旧版 Alert showAndWait 语义。
- 无 GUI 环境（无 toolkit）的防御不变量保留：JDK8 实测 `Platform.runLater`
  抛 `IllegalStateException` → 立即 REJECT 不挂死（GuiConfirmUiTest 验证）。
- 代价（用户已知悉）：GUI 渲染异常时工具调用可能永久阻塞。
- 无需 ConfirmSheet 侧改动（无 deadline/cancel/过期丢弃机制）——串行排队
  本身即正确：后到的确认等前一张卡片结决后再展示、再等用户点击。

### 3.2 UI：两行紧凑卡、距底 1 行

- **不再拉伸**：`card.setMaxHeight(Region.USE_PREF_SIZE)`——StackPane 拉伸到
  maxHeight 为止，卡片紧贴内容高度（根因 2.2 的直接修复）。
- **两行**：消息行 + 按钮行；去掉快捷键提示行「Enter 同意 · Esc 拒绝」
  （Esc/Enter 功能保留，Esc 经 Scene 事件过滤器、Enter 经默认按钮）。
  保留 3px 琥珀饰条（告警语义，不计行）。
- **距底 1 行**：`StackPane.setMargin` 底边距 12px → 24px。「1 行」取项目
  行高单位 24px（输入框 `setMaxHeight(6 * 24)` 即一行 24px）。

## 4. 实现

| 文件 | 改动 |
|---|---|
| gui/confirm/GuiConfirmUi.java | `q.poll(3, SECONDS)` → `q.take()`；注释同步 |
| gui/dialog/ConfirmSheet.java | buildCard：去提示行、padding 10→8、`setMaxHeight(USE_PREF_SIZE)`；display：底边距 12→24 |
| test GuiConfirmUiTest | 注释修正为实测行为（无 toolkit 抛 ISE 立即 REJECT） |

## 5. 验证

- 全量 `mvn test`：434 通过（含 GuiConfirmUiTest 无 FX 环境 REJECT 不挂死）。
- ConfirmSheet 属 GUI 组件，布局/动画需手动验证：高危操作触发弹框，
  确认卡片为两行紧凑卡、贴底部、距底约一行；点击「同意」后工具正常执行。

## 6. 文档同步

- ARCHITECTURE.md：ConfirmSheet 职责（3 行小卡 → 两行紧凑卡、距底 1 行）、
  GuiConfirmUi（poll(3s) → take() 无限等待）、常量表删除「弹窗兜底超时 3s」行。
- 2026-08-13 设计文档：4.3/4.5 标注变更与指向本文档。
