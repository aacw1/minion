# 输入块（Chip）设计

日期：2026-08-15
状态：已确认（用户审阅通过前为草案）

## 背景与问题

当前补全确认（/ 命令、@ 文件）把文本直接插入 TextArea：

1. **点击回弹干扰**：确认插入后，若用户点击到该 `/xxx` 词上（光标移入），`onTextChanged` 重新解析出 SLASH 模式再次弹窗，干扰输入。现有 `suppressed` 机制只能抑制「词未被修改」时的回弹，点击词内即失效。
2. **粘贴长文本无反馈**：复制长文本直接堆进输入框，无可读性、无字符数提示。

参照 Claude Code：反显内容变成「块」（chip），不可编辑，只能删除。

## 目标

- 弹层确认的 / 命令、/skill 技能、@ 文件 → 变成输入框上方的块，不可编辑、可删除（✕ 或 Backspace）。
- 粘贴 ≥100 字符（含换行）的文本 → 变成块，显示「粘贴内容，N 字符」。
- 修复点击块内容不再触发补全弹窗（块不在 TextArea 内，天然不触发）。
- 手工输入 `/xxx` 仍是普通文本，打字触弹层行为不变。

## 非目标

- 不做内联富文本编辑器（块在文字流中间、光标绕行）——JavaFX TextArea 无富文本，工作量大、风险高，已否决。
- 不做块内容的编辑/修改。
- 不处理右键菜单粘贴、拖拽文本（绕过事件过滤器，直接插入文本，见边界）。

## 关键约束

**JavaFX TextArea 无法渲染内联样式化块**（无富文本、无子区间样式）。因此块必须以组件形式放在 TextArea 之外 → 输入大框内部改为「上方块行 + 下方 TextArea」的 VBox 布局。

## 数据模型

新增纯模型类 `InputChip`（src/main/java/com/minion/gui/input/InputChip.java，可脱离 JavaFX 单测）：

```java
enum ChipType { COMMAND, SKILL, FILE, PASTE }
class InputChip {
    ChipType type;
    String content;  // 发送用的完整文本（/help、@path、粘贴全文）
    String display;  // 块上显示的文本（粘贴块 = "粘贴内容，N 字符"）
}
```

- 命令块 display = content（`/help` 等）；文件块 display = 相对路径；粘贴块 display = `粘贴内容，N 字符`（N = 字符数，含换行）。
- 弹层插入文本仍是 `insertionText` 规则：@ 模式补回 @ 前缀（复用现有静态方法）。

## 消息组成规则

纯静态 `compose(List<InputChip> chips, String text)`（InputView 静态方法，可单测）：

- 块按列表顺序用单个空格连接；
- 文本区内容非空时，在块之后补一个空格再接文本；
- 无块时返回文本区内容原样。

例：`[/help] 块` + 文本 `foo` → `/help foo`（斜杠命令保持首词，CommandDispatcher 语义不变）。

## UI 布局

- 输入大框（`frame`，HBox）左侧单元格改为 VBox：**块行 FlowPane（顶部）+ TextArea（下方）**；竖分割线与按钮不变。
- 块行 FlowPane：无块时高 0 不占位；有块时自然换行（`hgap/vgap` 8）。
- 块 = HBox（Label + ✕ 按钮），样式类 `input-chip`（theme.css 新增）：
  - 深色圆角胶囊背景（#2a3344 系，参照 suggest-list selected）；
  - 命令/技能块左缘加语义色（蓝 #3b6fe0），文件块绿，粘贴块灰（与消息标签语义一致）；
  - ✕ 悬停变红；块 tooltip = 完整 content（超长时仍可见全文）。
- ✕ 点击：移除该块 → 焦点还回 TextArea（防后续键盘事件落入按钮）。

## 交互改动（全部在 InputView 内）

### 确认路径（鼠标点击 + 键盘 Enter/Tab）

- 弹层确认 → 不再替换文本，改为：按确认文本建块（mode 决定 type：SLASH→COMMAND、SLASH_SKILL→SKILL、FILE→FILE）→ 追加到块列表尾部 → 隐藏弹层 → 焦点回 TextArea。
- `lastToken` 仍用于模式判断与 keyboard 路径的延迟插入（KEY_PRESSED 派发期间不可改文本的老问题不涉及 TextArea 文本了，但 runLater 时序保留以复用统一路径）。
- **删除 `suppressed` 机制**：确认文本不再进入 TextArea，回弹问题天然消失；连带删除 `onTextChanged` 中的 suppressed 分支。

### 粘贴拦截

- KEY_PRESSED 过滤器（现有）新增：Ctrl+V / Shift+Insert → `Clipboard.getSystemClipboard().getString()`：
  - 非空且长度 ≥100（含换行）→ 建 PASTE 块（追加尾部），consume 事件；
  - 否则不 consume，走 TextArea 默认粘贴。
- 阈值常量 `PASTE_CHIP_THRESHOLD = 100`（InputView 静态可测）。

### 删除

- ✕ 按钮（见 UI 节）。
- Backspace/Delete：文本区 `isEmpty` 且光标在最前（caret == 0）时删除最后一个块（consume 事件；无块时不拦截）。

### 发送/补充/回答路径

- `hasContent()`：块列表非空 或 文本区非空 → true。
- `onSend()` / SUPPLEMENT / ANSWER：取 `compose(...)`，发送后块列表清空 + 文本区清空。
- 块列表变化（增/删）时调用 `updateButton()`（现有 textProperty 监听只覆盖文本）。

## 边界与取舍（已确认）

- 中段粘贴长文本：块追加到块列表**尾部**而非光标位置（顺序语义仍正确，简化实现）。
- 右键菜单粘贴、拖拽文本：绕过 KEY_PRESSED 过滤器，不生成块，直接插入文本（可接受，后续需要再加）。
- 手工输入 `/xxx`（非弹层确认）仍为普通文本，照常触弹层——打字补全体验不变。
- 多个粘贴块各自显示「粘贴内容，N 字符」，无合并。
- 块不参与撤销/重做。

## 测试

- 纯逻辑单测（不依赖 JavaFX）：
  - `compose`：无块/有块/块+文本/空文本组合；
  - 粘贴阈值判定（≥100 变块、99 不变、含换行计数）；
  - 块类型映射（mode → ChipType）。
- 现有 `CompletionParserTest`、`SuggestionPopupTest` 不受影响。
- 手工验证：确认 /help 后点击块区域不弹窗；粘贴长文成块；Backspace 删块；发送文本顺序正确。

## 文件改动

| 文件 | 改动 |
|---|---|
| `gui/input/InputChip.java` | 新增：模型 + 类型 + 阈值/组成纯逻辑（含 compose、阈值判定） |
| `gui/input/InputView.java` | 布局 VBox 化；确认路径改建块；粘贴拦截；删除；compose 发送；移除 suppressed |
| `resource/theme/theme.css` | 新增 `input-chip` 系样式（胶囊、语义色、✕ 悬停） |
| `test/.../InputChipTest.java` | 新增：compose / 阈值 / 类型映射单测 |
