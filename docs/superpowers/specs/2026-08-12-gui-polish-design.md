# 2026-08-12 GUI 打磨设计（16 项：无边框窗口/设置窗三页签/行为修复/视觉优化）

## 1. 背景与目标

minion 已从命令行迁移为 JavaFX 唯一界面（GUI 时代），本轮对 GUI 做一轮专业打磨，解决
视觉不统一、配置不生效、交互缺省等 16 项问题。全部为界面与行为改动，不涉及 LLM 协议层。

关键现状（探索确认）：
- 主窗口标准系统标题栏 + 固定 220px 侧栏；消息区无条件自动滚到底（vmax 监听）
- 所有弹窗（模型管理/工作空间/确认/错误）走 JavaFX 默认浅色皮肤——Dialog 不继承 Scene 样式表
- `AgentLoop.setLlm()` 已存在但从未被调用 → 切模型不生效（需求 13 根因）；ContextManager 压缩参数 final 不可变
- 标题生成走 LLM 摘要（titlePool 单线程 + 10s 超时），失败回退前 30 字
- 流式中断已有 `appendPartialAssistant` 把部分回复入历史（需求 15 主要为验证+补测试）
- CLI 代码残留已基本清除（jline/Ansi 早前提交移除）；README 仍有 Win7 控制台代码页段落

## 2. 方案取舍（已与用户确认）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 弹窗边框 | 仅主窗口无边框自绘按钮；弹窗保留系统边框但深色主题 | 改动可控；Alert 类自绘标题栏复杂且收益低 |
| 配置生效范围 | 模型修改全量生效（含运行中会话，下一轮请求生效）；工作空间 workDir/projectMd 仅新会话生效 | 热更新运行中会话的工具注册表风险高 |
| 设置窗口形态 | 单个 Dialog + 左侧 TabPane(SIDE_LEFT) 三页签 | TabPane 原生支持，布局最简单 |
| 弹窗深色收口 | Theme 工具类统一给所有 Dialog 挂样式表 | 一处收口，新弹窗不会漏 |
| 基础设置页范围 | 常用项（技能目录/白名单/读逃逸/确认跳过，实时生效）+ 浏览器项（重启生效并注明） | 用户选择"常用项+浏览器项" |
| 关于页信息 | 作者 尹承；联系方式 258915527@qq.com；开发语言 Java 8 + JavaFX | 用户提供 |
| 模型实时生效机制 | SessionManager 统一监听/触发 propagate | 变更源只有设置窗一处，收口不易漏 |
| 主窗口缩放 | 无边框 Stage + 边缘/四角拖拽监听自绘缩放 | 无边框 Stage 失去系统缩放，只能自绘 |

## 3. 架构改动总览

```
gui/
├── MainWindow            改造：无边框装配、SplitPane、自动滚动暂停、清空右侧
├── TitleBar（新）        自绘标题栏：拖动/双击最大化/最小化/最大化/关闭
├── ResizeHelper（新）    无边框窗口边缘/四角拖拽缩放
├── theme/Theme（新）     CSS 常量 + Dialog 深色挂载工具
├── dialog/
│   ├── SettingsDialog（新，替代 ModelDialog）  左侧三页签：模型/基础设置/关于
│   └── ModelDialog       删除（内容迁入 SettingsDialog 模型页）
├── chat/ChatView         不变（clear() 已提供占位能力）
├── input/InputView       发送清空、无会话自动创建、按钮靠下
└── session/SessionManager 标题本地截取、模型变更 propagate、旧客户端延迟回收
```

## 4. 需求 1：主窗口无边框 + 窗口内按钮

- `MainWindow.show()`：`stage.initStyle(StageStyle.UNDECORATED)`（show 之前），
  去除系统标题栏；`stage.setTitle` 保留（任务栏/Alt-Tab 显示）
- 原 topbar 升级为自绘标题栏行（新类 `gui/TitleBar.java`），从左到右：
  应用名「minion」| 模型标签 | 会话页签区（HBox 弹性占位）| ⚙ 设置按钮 | 窗口按钮组
  （⚙ 保留现有行为：打开设置窗，关闭后刷新顶部模型名）
- 窗口按钮组：`—` 最小化（`stage.setIconified(true)`）、`□`/`❐` 最大化还原
  （`stage.setMaximized(!maximized)`，按钮图标随状态切换）、`✕` 关闭
- 关闭确认逻辑抽取为独立方法 `confirmClose()`：有运行中会话先弹确认再
  `stage.close()`——自定义 ✕ 按钮与 `stage.setOnCloseRequest` 共用（注意
  `stage.close()` 不触发 onCloseRequest，只响应系统关闭事件）
- 标题栏行为：鼠标按下记录偏移拖动 `stage.setX/Y`；空白处双击切换最大化
- **边缘缩放**（新类 `gui/ResizeHelper.java`）：无边框 Stage 失去系统缩放边框，
  在根布局覆盖 8 个透明边缘区域（四边厚 ~5px、四角 ~12px 见方，CSS 类
  `resize-edge`/`resize-corner`，cursor 按方向设置），鼠标按下拖动时按偏移
  `setX/setY/setWidth/setHeight` 调整窗口；最小窗口尺寸受 `stage.setMinWidth/MinHeight`
  约束（缩放时 clamp）
- 顶部模型名刷新逻辑保留（设置窗关闭后同步）

## 5. 需求 2：设置窗口左侧页签（模型/基础设置/关于）

新类 `gui/dialog/SettingsDialog.java` 替代 ModelDialog（ModelDialog 删除）：

```
Dialog<Void>
└── BorderPane
    ├── left: TabPane(SIDE_LEFT, tabMinWidth~120)  页签：模型 / 基础设置 / 关于
    └── center: 当前页签内容
```

**模型页**：迁移现 ModelDialog 全部能力——列表（当前模型 ● 标记）、单击切换、
新建/修改/删除（表单含 thinking/effort/maxContextTokens/compressThreshold/
keepRecentMessages）。操作后触发模型变更 propagate（见需求 13）。

**基础设置页**（保存按钮写回 config.properties）：
- 技能目录 `skills.dir`（TextField）
- 确认白名单 `confirm.whitelist.tools` / `confirm.whitelist.commands`（TextArea，逗号分隔）
- 读逃逸 `paths.read.allowOutside`（CheckBox）
- 确认跳过 `confirm.skip`（CheckBox）
- 浏览器项 `browser.path` / `browser.port` / `browser.headless` /
  `browser.userDataDir` / `browser.timeoutMs`——页面上注明「浏览器配置需重启后生效」
- 保存即写外部 config.properties（新 Config 写回方法，见需求 13）

**关于页**（只读信息）：
- 作者：尹承
- 联系方式：258915527@qq.com
- 开发语言：Java 8 + JavaFX

## 6. 需求 3：工作空间新建/修改用系统文件夹选择框

- MainWindow 新建工作空间弹窗与 WorkspaceListView 修改弹窗的 work.dir 行改为：
  TextField（可手输）+「浏览…」按钮 → `DirectoryChooser`
- 初始目录：修改弹窗设为当前值所在目录（存在时）；新建弹窗不设（系统默认）
- 选中后路径回填 TextField；用户仍可手改；不做目录存在性强制校验
  （空串/相对路径沿用现有语义：workDir 传空由 Workspace 按相对解析）

## 7. 需求 4/14：发送按钮靠下 + 发送后清空

- InputView 布局：TextArea 在上（弹性），按钮行在下——行内左提示文案（可选）、
  右侧「⤒ 发送」/「■ 终止」按钮靠右下（HBox + Region 弹性填充）
- `onSend()`：文本非空时先 `input.clear()` 再 `manager.send(...)`；
  删除 `lastSent` 草稿保留逻辑与 `onRunningChanged` 中的清空判断
  （本轮结束清草稿的行为已不需要）

## 8. 需求 5：左右比例 1:3

- 侧栏 + 右侧消息区改为 `SplitPane`（垂直分隔），`setDividerPositions(0.25)`，
  用户可拖动调整；`setMinWidth` 保留（侧栏 200 下限）
- 删除固定 `setPrefWidth(220)`

## 9. 需求 6：弹窗深色主题

- 新工具类 `gui/theme/Theme.java`：
  - `static final String STYLESHEET = "/theme/theme.css"`
  - `static void style(Dialog d)`：`d.getDialogPane().getStylesheets().add(STYLESHEET)`
- theme.css 新增 dialog-pane 段：`.dialog-pane`（背景 #15181f、边框、内边距）、
  `.dialog-pane .label`（#e6e8ee）、`.dialog-pane .button`（复用 btn-ghost 观感）、
  `.dialog-pane .text-field/.text-area`（复用 input-area 观感）、
  `.dialog-pane .check-box`（选中色 #4f8cff）
- 全部弹窗创建点统一 `Theme.style(d)`：新建/修改/重命名工作空间、删除确认、
  ConfirmDialog、错误 Alert、退出确认、重命名会话、设置窗；Alert 与
  TextInputDialog 同为 Dialog 子类，getDialogPane() 可用

## 10. 需求 7：命令行残留清理

- README 删除「Win7 说明」段落（控制台代码页/GBK 乱码说明——GUI 已不受影响）；
  保留 JDK8+JavaFX 启动与 minion.bat 说明
- 确认 ARCHITECTURE.md / CONVENTIONS.md / CLAUDE.md 无 CLI 残留描述（已核查基本干净）
- 代码侧无删除项：BashTool 输出编码探测（UTF-8/GBK）是命令执行工具的功能本体，
  保留；jline/Ansi/ConsoleIo 已在早前提交移除
- 归档设计文档（docs/superpowers/specs 历史）属记录，不动

## 11. 需求 8：标题本地截取前 20 字（去掉 LLM 摘要）

- `SessionManager.send()`：`titlePending` 时同步置标题，不再走 titlePool：
  `h.title = TitleGenerator.localTitle(text)`；删除 `titlePool` 线程池、
  `generateTitle()`、`TitleGenerator.buildPrompt()` / `clean()`
- `TitleGenerator` 简化为纯本地：`localTitle(String)`——去换行/首尾空白，
  截取前 20 字；空输入回退「新会话」（保留 MAX_TITLE_LEN=20 常量）
- `TitleGeneratorTest` 重写为本地截取用例

## 12. 需求 9：文字可读性与专业优化（theme.css）

- 基础字号 13px → 14px；`.section-title` 11px → 12px；代码块 12px → 13px
- 对比度提升：正文 #e6e8ee → #f0f2f6；次要 #8b949e → #a8b0bb；
  msg-thinking #6b7280 → #98a0ab；提示占位 #5a6270 → #7a828e
- 列表：cell 内边距 7/10 → 9/12、行高提升；hover/selected 背景对比增强
  （#1c2029 → #20242e、#232a38 → #2a3344）；滚动条加宽（thumb 8px）
- 输入区、页签、状态点、卡片、代码块留白微调；页签文字提亮
- 原则：只调颜色/字号/留白，不动布局结构

## 13. 需求 10：自动滚动——左键拖动滚动条时暂停

- 现实现：`chatScroll.vmaxProperty()` 监听无条件 `setVvalue(max)` 滚到底
- 改为：`chatScroll.skinProperty()` 就绪后 `lookupAll(".scroll-bar")` 取垂直滚动条；
  滚动条 `setOnMousePressed`（左键）→ `dragging=true`、`setOnMouseReleased` → `false`
- vmax 监听内 `if (dragging) return;`；释放后下一次 vmax 更新（新内容到达）自然续滚
- 轨道点击（非拖拽）不暂停——仅物理拖动 thumb 期间暂停

## 14. 需求 11/16：删除会话、切换工作空间后清空右侧

- MainWindow 新增 `clearChatPane()`：chatView 解绑（`bind(false)`）、
  内容 `clear()` 回「输入消息开始新的会话」占位、`inputView.bindSession(null)`、
  `chatView = null`
- 删除路径两处接入：页签关闭（addTab 的 onCloseRequest 确认后）与
  侧栏右键删除（onDeleted 回调），若删除的是当前展示会话则清空
- `onWorkspaceChanged` 回调：先 `clearChatPane()` 再刷新列表/重建页签
  （事件顺序保证工作空间切换后右侧不再残留上个空间的内容）
- 复用 ChatView.clear()（已提供占位文案），无需新增渲染能力

## 15. 需求 12：无激活会话时发送自动建会话

- `InputView.onSend()`：`current == null` 时先 `manager.createSession(null)` +
  `manager.activateSession(h)` 再发送（激活触发 onSessionActivated → MainWindow
  绑定 ChatView 与输入区，随后 send 经会话池异步执行，顺序安全）
- 删除 `if (current == null) return;` 静默丢弃

## 16. 需求 13：模型/参数修改实时生效

### 16.1 模型切换与参数修改 propagate

- `ContextManager`：压缩参数字段改非 final + `update(int maxTokens, double threshold,
  int keepRecent)` 方法
- `AgentLoop`：`contextManager` 改非 final + `setContextManager(...)`；`setLlm` 已存在
- `SessionManager.applyModelChanged()`：遍历所有工作空间全部会话：
  1. `newLlm(models.current())` 建新客户端
  2. `loop.setLlm(new)` + `contextManager.setLlm(new)` + `cm.update(新参数)`
  3. 旧客户端**登记待回收**（不立即 close——close 会 cancel 运行中请求）
- 触发点：设置窗模型页切换/修改/删除后、新建模型成为当前后——统一调用
  `manager.applyModelChanged()`
- `SessionHandle`：`llm` 字段改 volatile + `retiredLlms` 列表；删除会话/工作空间
  删除/shutdown 时对全部（含待回收）close，防 JVM 残留
- 会话空闲时（running 转 false 的监听路径）顺带回收待回收客户端

### 16.2 工作空间配置

- workDir/projectMd 修改维持「仅新会话生效」（用户确认），`updateWorkspace`
  不变；修改弹窗提示文案改为「修改对新会话生效」（删除误导性的"重启后生效"）

### 16.3 Config 运行时写回

- Config 增加 `set(key, value)`：更新内存 + 写外部 config.properties（原子写，
  复用 appendWhitelist 的文件读写模式）；供基础设置页保存用
- 实时生效路径核对：ConfirmGate 读 `config.confirmSkip()/whitelistTools()/
  whitelistCommands()` 在每次 check 时调用 → 白名单/跳过开关保存后立即生效；
  `readAllowOutside` 由 PathsGuard 读 → 同样实时；skills.dir 由新会话
  buildCtx 时读取 → 新建会话生效（不重建已运行会话的 registry，符合 16.2 口径）

## 17. 需求 15：中断逻辑验证（截断前回复进下次上下文）

- 现状已具备：流式中断（cancel → LlmException）路径 `appendPartialAssistant`
  把已收内容/思考入历史后 break；工具阶段中断 `scrubHalfTurn` 剥离不完整 toolCalls
  但保留 assistant 内容；所有中断退出路径 `persistSession()`
- 本项工作：补 AgentLoopTest 用例——流式中断后部分回复在 `session.messages` 中
  （含 reasoningContent），且下次 runUserTurn 请求携带；测试暴露缺口再修

## 18. 测试与验收

- 单测（纯逻辑，无 JavaFX）：
  - TitleGeneratorTest 重写（本地截取：去换行/20 字截断/空回退）
  - SessionManagerTest 新增：发送自动建会话（send 前无会话）、
    applyModelChanged 后全部会话 llm 替换且旧客户端进待回收列表
  - AgentLoopTest 新增：流式中断部分回复保留
- 回归：mvn test 全量通过
- 构建：`JAVA_HOME="D:/javame/jdk1.8" mvn clean package`
- 人工验收清单（GUI 无头测试受限）：
  1. 无边框窗口：拖动/双击最大化/最小化/关闭/边缘缩放
  2. 设置窗三页签切换；模型修改后运行中会话下一轮生效（观察请求参数）
  3. 新建/修改工作空间浏览选目录
  4. 弹窗全部深色
  5. 发送清空、无会话自动建会话、标题为前 20 字
  6. 拖动滚动条时新内容不自动滚；释放后恢复
  7. 删除会话/切换工作空间右侧清空
  8. 中段打断后追问，模型能接着被打断的内容继续（上下文携带验证）

## 19. 文档同步

- README：删除 Win7 控制台说明、快捷操作新增「无会话时发送自动建会话」、
  配置三件套表格注明设置窗可改
- ARCHITECTURE.md：gui 包新增 TitleBar/ResizeHelper/Theme/SettingsDialog
  类路径；SessionManager 模型 propagate 说明
- CLAUDE.md 包结构同步（如需）

## 20. 范围外（YAGNI）

- 不做：弹窗自绘标题栏（已确认保留系统边框）、运行中会话工作目录热更新、
  ContextManager 之外的模型参数生效追溯（历史会话旧参数不回填）、
  浏览器配置热生效（页面注明重启生效）
