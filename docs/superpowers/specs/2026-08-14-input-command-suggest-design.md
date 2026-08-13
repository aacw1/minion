# 输入区大框 + 斜杠命令恢复 + 补全弹层设计

日期：2026-08-14
状态：已实施（2026-08-14）

## 1. 背景与问题

1. **按钮状态机误显**：模型边输出回复边调用 ask_user 提问时，输入框为空 → 按钮显示红色方块（终止）。
   此时模型实为「等待用户回答」而非忙碌，方块诱导误点终止。
2. **技能失效**：CLI 移除（a5d57b8）时 CommandDispatcher 一并删除，`AgentLoop.loadSkill()` 成为死代码；
   输入 `/skills`、`/skill <名>` 只作为普通消息发给模型，技能永远无法加载。
3. **缺少 `/` 命令补全**：需要输入 `/` 反显命令与技能、上下键/鼠标选择、滚轮，参考 Claude Code VSCode 效果。
4. **缺少 `@` 文件补全**：输入 `@` 按工作空间文件名反显、上下键/鼠标选择、滚轮。
5. **输入框 UI**：文字发虚（与聊天正文同根因：JavaFX 8 裁剪下灰阶 AA 回退）；输入区需占右侧面板 4/9 宽居中；
   输入框与发送按钮包进一个带分割线的大框（参考 Claude Code VSCode）。
6. **设置窗**：「应用」按钮单独在左侧，需与「关闭」按钮相邻。

## 2. 需求确认（用户逐项确认的决策）

- 按钮卡死场景：用户**未回答**模型提问，仅看到回复文本，按钮显示方块属误显 → 提问挂起时显示回答箭头（空输入变淡），终止入口改为 Esc。
- 斜杠命令范围：`/help`、`/skills`、`/skill <名>`、`/compact`、`/tokens`（/clear 不需要；/exit /resume /model 有 GUI 替代不恢复）。
- `@` 选中文件插入**工作区根相对路径**（如 `src/main/java/com/minion/Main.java`）。
- 大框布局：**左输入右按钮 + 中间竖分割线**，4/9 宽居中；补全弹层锚定大框上方、同宽。
- 方案：B1（Popup + ListView 通用补全弹层）+ C1（客户端本地命令分发，命令永不发给 LLM）。

## 3. 组件与架构

### 3.1 SuggestionPopup（gui/input/ 新增）

通用补全弹层：`Popup` + `ListView<Suggestion>`。

- `Suggestion`：{ label 显示文本, insertText 插入文本, desc 描述, type 命令/技能/文件 }。
- 锚定输入大框**正上方**、同宽（Claude Code 风格）；Popup 不抢焦点，键盘事件由 TextArea 拦截转发。
- 交互：↑↓ 移动选中（ListView 选中态）、Enter/Tab 确认、Esc 关闭；鼠标悬停高亮（CSS :hover）、点击确认；
  滚动条/滚轮由 ListView 自带，maxHeight 200px。
- 样式：深色圆角卡片 `suggest-popup`、条目 `suggest-cell`；显式 `-fx-font-smoothing-type: lcd`（防灰阶回退发虚）。
- 过滤/排序为纯静态方法（可单测）：大小写不敏感 contains；排序：前缀匹配优先 → 路径短优先 → 字典序。

### 3.2 补全提供器

- 斜杠提供器：内置 5 命令（/help /skills /skill /compact /tokens，各带中文描述）+
  技能条目（label `/skill <名>`，desc 取自 SKILL.md frontmatter description）。
  `/skill ` 后继续输入 → 只过滤技能名。
- 文件提供器：遍历工作空间 workDir（跳过 `.git` 与点开头目录，上限 200 条，
  输出工作区根相对路径、`/` 分隔），每工作空间缓存 10 秒（新 `@` 词打开时过期重扫）。
- 数据来源：SessionManager 新增 `skills()`（allSkills）与 `currentWorkspaceDir()` 访问器。

### 3.3 CommandDispatcher（gui/command/ 新增，恢复 CLI 语义）

```java
/** 返回 null = 非命令（按普通消息发给模型）；非 null = 已本地执行的命令（返回展示文本） */
String dispatch(SessionHandle h, String input)
```

- 命令表：`/help`、`/skills`、`/skill <名>`（大小写不敏感精确匹配）、`/compact`、`/tokens`。
- 以 `/` 开头但未知 → 返回错误文本「未知命令 /xxx（/help 查看）」，**不发给模型**。
- 执行位置：
  - `/help` `/skills` `/skill` `/tokens` 为瞬时操作，直接在当前线程执行；
  - `/compact` 含阻塞 LLM 调用 → `h.pool` 提交执行，绝不在 FX 线程跑。
- 结果渲染：SessionManager.dispatchCommand 发两个事件——USER_MESSAGE（命令回显，仅展示不入 LLM 历史）
  + SYSTEM（结果文本）。
- 线程安全：`AgentLoop.loadSkill` 加 `synchronized`（FX 线程加载技能与会话线程读 loadedSkills 并发）。

### 3.4 EventList / ChatView / SessionController

- EventList.Kind 新增 `SYSTEM`；ChatView 渲染为系统行（`msg-thinking` 样式，同统计行）。
- SessionController 新增 `onSystem(String)`（不扩 AgentUi 接口，仅 GUI 层命令路径使用）。
- 命令结果随事件流缓冲/重放：会话未激活时入缓冲、激活后重放（继承现有机制）。

### 3.5 InputView 重构

- 布局：外层 GridPane 3 列 27.8% / 44.4% / 27.8%（中列 = 4/9），中列放 `input-frame` 大框。
- 大框：HBox[ TextArea(Hgrow ALWAYS) | 竖分割线 Region(1px) | 按钮容器(VBox Vgrow ALWAYS + CENTER，按钮垂直居中) ]；
  大框带圆角/背景/边框/padding（CSS `input-frame`），TextArea 透明内嵌（`.input-frame .text-area` 去背景去边框）。
- 抗锯齿：`.input-frame`、`.suggest-popup` 显式 `-fx-font-smoothing-type: lcd`（聊天正文同款修法 77b0116）。
- 补全触发：textProperty + caretPosition 监听 → 提取光标所在「词」（空白分隔，多行同语义）：
  - 词以 `/` 开头 → 斜杠弹层（query = 词去掉 `/`；`/skill xxx` 时按空格再拆，技能名过滤）；
  - 词以 `@` 开头（`@` 在词首，避免邮箱误触）→ 文件弹层（query = 词去掉 `@`）；
  - 否则隐藏弹层；发送/清空后隐藏。
- 选中替换：TextArea.replaceText(词起点, 词终点, insertText) 后移动光标。
- 键盘路由（keyPressed，弹层优先于 Esc 终止）：
  - 弹层可见：↑↓ 移动、Enter/Tab 确认（consume）、Esc 仅关弹层；
  - 弹层不可见：Esc → `manager.stop(current)`（仅 running 时）。
- 按钮状态机修复：`BtnMode` 新增 `ANSWER_DIM`，判定改为：

```
!running   → SEND / SEND_DIM
askPending → hasContent ? ANSWER : ANSWER_DIM（提示「输入回答后发送」）
running    → hasContent ? SUPPLEMENT : STOP
```

即提问挂起时**不显示方块**，模型在等回答而非忙碌；终止入口为 Esc。

### 3.6 设置窗按钮归位

- applyType 由 ButtonData.OTHER 改 **APPLY**（Windows 8u181 下 OTHER 落在左区、APPLY 落在右区与关闭相邻）。
- 实施修正：本机 jfxrt 无 `DialogPane.getButtonBar()`（8u60+ 才有），`setButtonOrder("A C")` 一行省略——
  ButtonData 归区本身已达成 [应用][关闭] 相邻（A 在右区、C 在中区末位，中右两区靠右并排）。
- 兜底：若实测布局仍不符，则自绘按钮行（隐藏 ButtonBar，把两个按钮放进内容区底部右对齐 HBox）——
  用户下次启动时目验。

### 3.7 SessionManager 增补

```java
public List<Skill> skills()                     // 补全弹层/技能列表用
public String currentWorkspaceDir()             // 文件补全遍历根（无当前空间返回 null）
public void dispatchCommand(SessionHandle h, String input)
// 内部：dispatcher.dispatch → null 则按普通消息走 send；非 null 则发 USER_MESSAGE 回显 + SYSTEM 结果事件
```

## 4. 数据流

- 输入 `/` → 弹层列出命令+技能 → 过滤 → 选中插入完整命令（如 `/skill brainstorming`）。
- 输入 `@` → 弹层列出工作空间文件 → 过滤 → 选中替换 `@query` 为相对路径。
- 提交（Ctrl+Enter/按钮）：InputView 先问 SessionManager.dispatchCommand：
  命中命令 → 本地执行 + 系统行反馈，**不进 LLM 历史**；非命令 → 原样 send。
- `/skill` 加载的技能随会话存活（loop.loadedSkills），下一轮请求生效。

## 5. 错误处理

- 未知命令 → 系统行「未知命令 /xxx（/help 查看）」，不发给模型。
- `/skill` 缺参数 → 「用法: /skill <技能名>（/skills 查看列表）」。
- 技能名未命中 → 「未找到技能: xxx（/skills 查看列表）」。
- `/skills` 无技能（目录缺失/空）→ 提示「未发现可用技能，请检查 设置 → skills.dir」。
- `/compact` 未启用压缩（contextManager null）→ 走现有 onWarning 提示。
- 文件遍历 IO 异常 → 静默返回空列表（补全为增强体验，不打断输入）。
- `/compact` 会话运行中 → h.pool 排队，回合结束后执行（不打断运行）。
- 弹层在任何异常路径下不得影响正常输入（try/catch 兜底隐藏）。

## 6. 测试策略（TDD）

| 目标 | 测试 |
|---|---|
| buttonMode 状态机（含 ANSWER_DIM） | InputViewTest（纯静态，脱离 JavaFX） |
| 词提取（`/` `@` 触发、邮箱不触发、多行、光标位置） | InputViewTest 静态 helper |
| 补全过滤/排序（前缀优先、路径短优先、大小写不敏感） | SuggestionPopup 静态方法测试 |
| 命令分发（/help /skills /skill /tokens /compact、未知命令、参数缺省） | CommandDispatcherTest（FakeLlmClient 构造 AgentLoop） |
| EventList Kind.SYSTEM 缓冲/重放 | EventListTest 扩展 |
| SessionController.onSystem 事件 | 轻量测试 |
| 弹层视觉效果（定位/样式）、大框布局、设置按钮 | run 技能启动人工目验（提交前至少验证无异常启动） |

## 7. 已知限制

- 命令结果与已加载技能不落盘，重启后丢失（继承 CLI 时期行为）。
- `/compact` 在会话运行中需等当前回合结束（单线程池排队）。
- 文件补全首次打开有遍历延迟（大目录 ≤1s 量级，10 秒缓存兜底），上限 200 条。
- 补全弹层不支持多选（一次选中一个词；多个 `@` 可依次输入）。

## 8. 实施记录

- 实施计划：docs/superpowers/plans/2026-08-14-input-command-suggest.md，9 任务全部完成并提交（见 git log 2026-08-14，共 9 个提交：设计 7a3871d → 计划 8c6f1fb → 任务 6df4309/4f365d0/df6edc1/a80ef5c/5826690/727555a/f89c60d/3d4575f/ffdbfe0）。
- 测试：全量 `mvn test` 360/360 通过（含既有 InputViewButtonTest 对齐新语义、移除重复 InputViewTest）。
- 与设计的偏差：
  1. `setButtonOrder("A C")` 省略（本机 jfxrt 无 DialogPane.getButtonBar()，8u60+ 才有；ButtonData.APPLY 归区已达成目标）——见 §3.6 修正；
  2. CSS 类名由 `suggest-popup`/`suggest-cell` 落为 `suggest-list`/`suggest-label`/`suggest-desc`（ListView 类名直贴 ListView，cell 内容用标签类，避免 CSS 选择器歧义）；
  3. CompletionParser 提为独立类（非 InputView 内部 static helper），便于纯静态单测。
- 待用户目验（实施时用户的 GUI 实例正在运行旧 jar，`mvn clean package` 因 jar 被锁未执行、新 jar 未启动目验）：
  1. `mvn clean package`（关闭运行中的 minion 后）
  2. 输入大框 4/9 居中、竖分割线、文字清晰
  3. `/` 与 `@` 补全弹层交互（↑↓/鼠标/Enter/Tab/Esc/滚轮）
  4. `/skills` 系统行显示
  5. 设置窗 [应用][关闭] 相邻（Windows 8u181 ButtonBar 归区行为）
