# minion GUI 化改造 — 设计文档

日期：2026-08-11

## 1. 需求

用华丽的 GUI（质感、科技感，仿 codebuddy 风格）代替命令行模式。GUI 为唯一入口，CLI（REPL、-c、-r）彻底移除。

已确认的关键决策：

| 决策点 | 结论 |
|---|---|
| 技术栈 | JavaFX 8 原生控件 + CSS 深色主题（JDK 8 + Win7 兼容硬约束） |
| CLI 去留 | 彻底移除全部 CLI（交互 REPL / -c 单次 / -r 恢复） |
| 后台语义 | 真并行：切走的会话在后台线程继续跑完，切回时 UI 增量补显 |
| 杂项配置 | config.properties 保留（browser/confirm/paths），模型与工作空间配置迁往 json |
| 消息渲染 | flexmark（JDK8 兼容）Markdown 渲染，代码块深色底+等宽字体，不做语法高亮 |
| 模型弹窗 | 右上角 ⚙ 弹窗：模型列表 + 切换 + 新建 + **修改**（含 skill 路径、上下文压缩参数） |
| 迁移策略 | 不自动搬旧 config.properties 值，直接生成默认 json |
| 会话目录 | `jarDir/session/<workSpaceName>/` |

## 2. 整体布局

```
┌──────────────────────────────────────────────────────────────┐
│ 顶部栏：minion 标识 | 当前模型标识 | 会话页签区 | ⚙ 选项      │
├──────────┬───────────────────────────────────────────────────┤
│ 左侧 1/5 │  右边 4/5                                          │
│ ┌────────┐ ┌───────────────────────────────────────────────┐ │
│ │会话管理 │ │  消息区（当前页签的对话流）                    │ │
│ │ [＋新建]│ │  · 用户消息（右对齐）                          │ │
│ │ · 会话A │ │  · 助手消息（Markdown 渲染 + 代码块）          │ │
│ │ · 会话B │ │  · 工具调用卡片（可折叠）                      │ │
│ │ · 会话C │ │  · 思考过程（暗色可折叠块）                    │ │
│ ├────────┤ ├───────────────────────────────────────────────┤ │
│ │工作空间 │ │  ┌───────────────────────────────────────────┐ │
│ │ [＋新建]│ │  │ 输入区：多行 TextArea + 发送/终止按钮       │ │
│ │ · 项目A │ │  │  [⤒ 发送 ▸]  Ctrl+Enter 发送 · 悬停提示    │ │
│ │ · 项目B │ └──┴───────────────────────────────────────────┘ │
│ └────────┘                                                    │
└──────────────────────────────────────────────────────────────┘
```

- 左侧上区（会话管理）：会话列表（标题 + 最后消息摘要 + 运行状态徽标），选中切换；每项悬停/右键菜单：重命名、删除；顶部"新建会话"按钮
- 左侧下区（工作空间管理）：工作空间列表（名称 + 运行状态徽标）；顶部"新建工作空间"；每项菜单：切换、重命名、修改、删除
- 右侧底部输入区：多行 TextArea（自适应 1→6 行）；发送按钮悬停提示"发送 (Ctrl+Enter)"；会话运行中变为红色"■ 终止"
- 右上角 ⚙：模型管理弹窗（切换/新建/修改/删除）
- 会话页签（顶部）：标题 + 运行状态点 + 关闭按钮（关闭 = 删除会话，带确认）；新建会话时无页签、右边为干净页面

## 3. 配置结构

三个配置文件均在 jar 同目录。

### 3.1 workspace.json（工作空间列表）

```json
{
  "workspaces": [
    {
      "workSpaceName": "minion",
      "workDir": "d:\\javame\\code2\\minion",
      "projectMd": "d:\\javame\\code2\\minion\\project.md"
    }
  ],
  "currentWorkspaceName": "minion"
}
```

- 移除 `skills.dir`（skill 路径归模型管理）与 `session.dir`（会话目录自动派生）
- 会话存储路径：`jarDir/session/<workSpaceName>/<sessionId>.json`
- 工作空间重命名 → 会话目录同步迁移（目录 rename）；重名/非法字符（`\/:*?"<>|`）拒绝
- project.md 允许配置为工作空间目录外的绝对路径（PathsGuard 按绝对路径放行，机制同现有 skillsDir）

### 3.2 model.json（模型列表）

```json
{
  "models": [
    {
      "displayName": "deepseek-v4-flash",
      "url": "https://api.deepseek.com/v1/chat/completions",
      "apiKey": "sk-...",
      "modelName": "deepseek-v4-flash",
      "provider": "deepseek",
      "thinking": true,
      "reasoningEffort": "max",
      "maxContextTokens": 131072,
      "compressThreshold": 0.8,
      "keepRecentMessages": 10,
      "skillsDir": "./skills"
    }
  ],
  "currentModelName": "deepseek-v4-flash"
}
```

- 至少保留一个模型，删除最后一个拒绝
- 切换模型：新消息用新模型；`Session.modelName` 记录会话创建时的模型名，历史消息不变
- 上下文压缩参数（maxContextTokens/compressThreshold/keepRecentMessages）在模型级，切模型即切换压缩配置

### 3.3 config.properties（保留，仅杂项）

- 保留键：`browser.*`、`confirm.*`、`paths.read.allowOutside`；`ui.color` 移除（GUI 下无意义）
- 移除键：`model.*`、`context.*`、`work.dir`、`project.md.path`、`skills.dir`、`session.dir`
- 首启自动生成机制不变；workspace.json / model.json 不存在时生成默认文件（默认工作空间 workDir="."、默认模型参数）

### 3.4 配置解析与持久化

- 新增 `WorkspaceManager`、`ModelManager`（core/config 包）：JSON 读写（gson，原子写复用 SessionStore 的 tmp+rename 思路）、损坏时备份 `.bak` 并重新生成默认
- Config 类保留但裁剪（去掉已迁移的 getter）

## 4. 生命周期与线程模型

### 4.1 运行模型

```
WorkspaceManager（单例）
├── workspace A ── SessionManager A
│   ├── 会话1 → AgentLoop #1 → 后台线程（运行中）
│   ├── 会话2 → AgentLoop #2 → 后台线程（空闲）
├── workspace B ── SessionManager B
│   └── 会话3 → AgentLoop #3 → 后台线程（运行中）
```

- 每会话：一个 `AgentLoop` + 一个工作线程（复用现有 pool，`runUserTurn` 在线程池执行）
- 每工作空间：独立的 `ToolRegistry`、`Workspace`、`SkillManager`、`SessionStore`（指向 `session/<名>/`）、`ConfirmGate`——切换工作空间 = 整套上下文切换
- 全局共享：Chrome 浏览器实例（共享一个 BrowserSession；多会话同时用浏览器工具限制为每会话串行）；各 AgentLoop 持有独立 LlmClient 实例（多会话并行请求互不干扰）

### 4.2 切换语义（真并行）

1. 会话运行中点击其他会话/页签 → 原会话 AgentLoop 线程不打断，后台继续；`AgentUi` 事件进入该会话的 `EventBuffer`（消息缓冲），不触发界面刷新
2. 切回时 `Platform.runLater` 批量补显缓冲增量
3. 运行状态徽标：运行中 → 呼吸动画圆点
4. 工作空间切换：正在运行的会话同样转后台；右侧整体换绑（页签、列表、消息区、输入区）；输入草稿按"工作空间+会话"维度暂存，切回恢复

### 4.3 并发上限

无硬性上限；每 AgentLoop 自带 4 线程工具池，多会话工具执行互不干扰。

### 4.4 新建会话（LLM 摘要标题）

```
点击"新建会话" → 干净页面（无页签）→ 输入 → Ctrl+Enter
→ ① 摘要请求：当前模型一次性请求（max_tokens≈64，指令：对首条用户消息生成 ≤20 字标题；复用现有流式接口收集完整输出即可，不新增接口）
→ ② 界面显示"正在生成标题…"（发送按钮禁用态）
→ ③ 成功 → 创建 Session（title=摘要）→ 创建页签 → ④ 正式 runUserTurn
→ 失败/超时(10s) → fallback 用户消息前 30 字作标题，继续正式任务
```

- 摘要请求独立、不入会话消息、不耗上下文
- `Session` 新增 `title` 字段（已有 id/createdAt/workDir/modelName/cwd/messages/todos/usage）

### 4.5 终止按钮

- 运行中：发送按钮变红色"■ 终止"→ `AgentLoop.interrupt()`（复用现有 interrupted 标志语义；需核实其是否真正中断流式请求，若否在本次实施中补齐）→ 本轮结束，可修改输入重发

### 4.6 删除/关闭语义

- 删除会话：确认弹窗 → 删文件 + 释放 AgentLoop（运行中先终止）
- 删除工作空间：确认弹窗（提示其下所有会话一并删除）→ 终止所有运行中会话 → 删 `session/<名>/` 目录
- 关闭窗口：存在运行中会话 → 确认弹窗"仍在运行，确认退出？"

## 5. 界面交互细节

### 5.1 AgentUi 事件 → 界面渲染

| 事件 | 界面表现 |
|---|---|
| onUserMessage | 用户消息（右对齐气泡/卡片） |
| onThinking(delta) | 思考块（暗色折叠区，流式追加） |
| onContent(delta) | 助手正文增量渲染 |
| onToolCall | 工具卡片（图标+名称+参数，默认折叠） |
| onToolResult | 卡片展开显示结果摘要（✅/❌） |
| onSubAgentStart/Delta/Done | 子任务卡片（可折叠） |
| onStatsLine | 底部状态栏（耗时/入出 token/上下文占用） |
| onError/onWarning | 红色错误横幅 / 橙色警告横幅 |

- 所有 UI 更新经 `Platform.runLater`，不阻塞后台流；`EventBuffer` 按会话隔离，切走时只进缓冲不刷 UI
- Markdown 增量渲染：每次 delta 整段重渲染（消息体 ≤ 几 KB 性能无压力）
- 消息区自动滚动到底，用户上滚时暂停自动滚动
- 思考块、工具卡片默认折叠，点击展开

### 5.2 确认交互 GUI 化

- `ConfirmUi` 现有 CLI 实现（ConfirmReader）删除，新增 GUI 实现：弹窗 + 按钮（批准/拒绝/全部批准本次会话/全部拒绝）+ 可勾选"记住并加入白名单"（写回 config.properties `confirm.whitelist.*`，复用 `appendWhitelist`）
- 弹窗用 FutureTask 异步阻塞工具线程，不冻结 FX 主线程

### 5.3 输入区

- 多行 TextArea，自动增高 1→6 行；Ctrl+Enter 发送（Enter 单独 = 换行）
- 发送中：按钮变红色"■ 终止"（呼吸动画）；输入区保留草稿直至摘要+第一轮结束，供终止后修改再发
- Tooltip：发送"发送 (Ctrl+Enter)"、终止"终止当前运行"
- 当前模型未配置 key → 弹窗引导去 ⚙ 配置

## 6. 新增依赖

| 依赖 | 用途 | 引入方式 |
|---|---|---|
| JavaFX 8（jfxrt.jar） | GUI 框架 | 编译期 pom `system scope` 引用 `${java.home}/lib/ext/jfxrt.jar`；运行时依赖 JDK 8 自带 ext 加载，不打进 fat jar（避免双份冲突）。README 注明需 Oracle JDK 8 / 含 JavaFX 的发行版 |
| flexmark 0.64.x | Markdown 渲染 | flexmark-core + flexmark-ext-tables + flexmark-ext-strikethrough |

- 不做语法高亮（YAGNI）；不做 JNA/JNI
- 以上均满足 JDK 8 兼容规约（CONVENTIONS.md 第 1 条），理由见本表

## 7. 包结构变化

```
com.minion
├── Main                    入口：装配 GUI（替代 CLI 装配）
├── gui/                    新增：JavaFX 界面层
│   ├── MainWindow          主窗口与三区布局
│   ├── theme/              深色主题 CSS
│   ├── sidebar/            会话列表、工作空间列表
│   ├── chat/               消息区、Markdown 渲染、工具卡片、思考块
│   ├── input/              输入区、发送/终止按钮
│   ├── dialog/             模型管理弹窗、确认弹窗、新建/重命名对话框
│   └── controller/         WorkspaceController/SessionController（事件路由）
├── core/
│   ├── config/             Config（裁剪）+ WorkspaceManager + ModelManager（新增）
│   ├── agent/              Session 加 title；AgentLoop 补 interrupt 语义核实
│   └── (其余不动)
├── cli/                    删除（Repl/CommandDispatcher/Renderer/StatsLine/
│                           StartupBanner/ConfirmReader）
└── util/                   Ansi 移除或保留（GUI 不再需要，随 cli 一并清理）
```

## 8. 错误处理

| 场景 | 处理 |
|---|---|
| LLM 错误（LlmException） | 红色错误横幅 + 状态复位；可修改输入重发 |
| json 配置损坏 | 弹窗提示 → 备份 `.bak` → 重新生成默认 |
| 摘要请求失败/超时 | fallback 前 30 字标题，正式任务照跑 |
| 会话文件损坏 | SessionStore 跳过损坏条目；GUI 列表标"已损坏" |
| 模型无 key | 发送时弹窗引导配置 |
| 工作空间重名/非法字符 | 就地红色提示，拒绝操作 |

## 9. 测试

- 核心层现有测试全部保留（回归保障）
- 新增纯逻辑测试（不依赖 GUI）：
  - `WorkspaceManagerTest`：CRUD、重命名迁移目录、非法名/重名拒绝、默认生成
  - `ModelManagerTest`：读写、删最后一个拒绝、损坏备份
  - `TitleGeneratorTest`：摘要 prompt 纯函数 + fallback 决策
  - `EventBufferTest`：增量事件缓冲与补显合并
  - `ConfirmUi` 接口 mock 单测
- GUI 视觉与交互：手动验证清单（README 附）

## 10. 实施顺序（里程碑）

1. **配置重构**：WorkspaceManager + ModelManager + Config 裁剪（纯逻辑，先行可测）
2. **GUI 骨架**：JavaFX 启动、深色主题 CSS、三区布局
3. **会话外壳**：SessionManager + AgentUi 事件路由 + 页签/列表 + EventBuffer
4. **消息渲染**：flexmark 接入、流式渲染、思考块/工具卡片
5. **输入与发送**：输入区、Ctrl+Enter、发送/终止、摘要标题流程
6. **工作空间与模型弹窗**：空间切换/CRUD、⚙ 模型管理弹窗
7. **确认与收尾**：ConfirmGate 弹窗、删除/关闭语义、移除 CLI 与 Ansi、README/设计文档同步

## 11. 不做的（YAGNI）

- 代码语法高亮；附件/图片上传；语音；多窗口；CLI 保留任何入口
- 自动迁移旧 config.properties 值
