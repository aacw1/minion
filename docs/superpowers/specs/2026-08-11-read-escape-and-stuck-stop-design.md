# 读逃逸开关 + 工具轮数上限 + 卡住止损 — 设计文档

日期：2026-08-11

## 需求

1. **读逃逸开关**：新增配置开关，工具文件**读取**可跳出工作空间限制；写入工具不变
2. **轮数上限**：工具调用轮数上限数值改为 1000
3. **卡住止损**：AI 无法完成任务时不应一直调用工具尝试解法，应停止并请求用户补充信息

## 现状

- [PathsGuard.java](src/main/java/com/minion/core/tools/PathsGuard.java) `errorIfOutside` 对越界路径一律直接拒绝；
  被 ReadTool / GrepTool / EditTool / WriteTool / BrowserScreenshotTool 调用；
  GlobTool 无 path 参数，只遍历 cwd + 技能目录
- [AgentLoop.java:32](src/main/java/com/minion/core/agent/AgentLoop.java) `DEFAULT_ROUND_LIMIT = 10000`
- [SystemPromptBuilder.java:19](src/main/java/com/minion/core/agent/SystemPromptBuilder.java) 规则 1 覆盖"指令不明确时先提问"，
  但缺少执行中途卡住（工具连续失败/缺信息）时的止损规则
- 参考 Claude Code 官方模型：cwd 边界内只读免审批；边界外逐次弹权限审批
  （可被 allow 规则 / additionalDirectories 预先放行）

## 设计决策（已与用户确认）

### 改动 1：读逃逸开关 `paths.read.allowOutside`

- 范围：**Read + Grep + Glob**（Glob 新增可选 `path` 参数，相对 cwd 解析，默认 `.`）
- 语义（写入类工具 Write/Edit/浏览器截图**维持现状**：越界直接拒绝）：

  | 开关状态 | 越界读（Read/Grep/Glob） | 越界写（Write/Edit/截图） |
  |---|---|---|
  | 开 | 自动放行，不弹确认 | 维持拒绝 |
  | 关（默认） | **弹确认**：Y=放行本次 / N=拒绝 / A=本会话放行 | 维持拒绝 |

- 开关关闭时的确认交互复用 ConfirmGate：
  - ConfirmGate 新增 `checkReadOutside(Tool tool, JsonObject args, String path)`：
    开关开 → 直接 true；关 → `ui.ask("越界读取 ...")` 复用 Y-N-A 决策
  - 会话放行采用 **per-path 粒度**（对齐 Claude Code 官方模型：弹窗"不再询问"记录的是
    带路径规则如 `Read(//d:/xxx/config.ini)`，同会话内只有命中同一条规则才免弹窗）：
    - ConfirmGate 维护 `Set<String> readSessionAllowed`（规范化绝对路径集合）
    - Y = 放行本次；N = 拒绝；A/W = 将该路径记入集合后放行（W 不新增持久化配置键，YAGNI）
    - 同路径再读 → 免弹窗；**其他路径或其他工具 → 仍弹窗**
  - **不复用高危操作的全局 `sessionBypass`**：那会把 A 的语义扩大到"本会话高危操作
    （Edit/Bash 危险命令）全部免确认"——既不符合用户意图，也不符合 Claude Code 的 per-rule 模型
  - 确认动作：Y/A/W 放行后本次越界读继续执行；N 返回现拒绝文案
- 实现位置：ReadTool / GrepTool / GlobTool 构造注入 ConfirmGate（与现有 skillsDir 注入同模式），
  越界分支从 `return guard` 改为调 `checkReadOutside` 决定放行/拒绝
- Main.java 需将 ConfirmGate 构造提前到读工具注册之前（当前在注册之后，Main.java:96）；
  工具构造签名变为 `(Workspace, String skillsDir, ConfirmGate)`
- 配置同步：`Config.readAllowOutside()` + config.properties 新增
  `paths.read.allowOutside=false`（带注释）
- 系统提示联动：开关开启时 SystemPromptBuilder 追加一句"已开启越界读取，可读取工作区外文件"，
  让模型知道可读外部绝对路径

### 改动 2：轮数上限 10000 → 1000

- [AgentLoop.java:32](src/main/java/com/minion/core/agent/AgentLoop.java) `DEFAULT_ROUND_LIMIT` 改为 1000，其余不变
- 测试用 `loop.roundLimit` 字段直接覆盖，不受影响；SubAgentLoop 无轮数上限（设计如此），不动

### 改动 3：卡住止损（提示词 + 代码兜底）

**3a. 提示词**（SystemPromptBuilder BUILTIN 追加规则 7）：

> 7. 当工具连续失败、或发现缺少完成任务所必需的信息/权限时，停止调用工具；向用户说明已尝试的方案、失败原因，并列出需要用户补充的信息或需要用户选择的方案，等待用户回复。不要反复重试同一方法。

（规则 1 只管"指令不明确"，本条管"执行中卡住"，互补）

**3b. 代码兜底**（AgentLoop）：
- 新增 `consecutiveToolErrors` 计数：工具结果 `isError` 时 +1，成功时归零
- 连续失败 **≥ 30 次**时，往 `session.messages` 注入一条特殊前缀的 user 消息
  （`[系统提醒] 你已连续 N 次工具调用失败…请停止调用工具，向用户说明已尝试的方案、失败原因与需要补充的信息`），
  注入后计数重置；不设硬性终止，`roundLimit`（1000 轮）为最外层兜底
- 注入用 user 消息而非 system：OpenAI 兼容 API 只接受首条 system，插在对话中间会 400

## 组件改动

| 文件 | 改动 |
|---|---|
| `src/resource/config.properties` | 新增 `paths.read.allowOutside=false` + 注释 |
| `src/main/java/com/minion/core/config/Config.java` | 新增 `readAllowOutside()` |
| `src/main/java/com/minion/core/tools/confirm/ConfirmGate.java` | 新增 `checkReadOutside(tool, args, path)`：开关开→放行；关→弹确认复用 Y-N-A；A/W 记入 per-path 会话集合 `readSessionAllowed`（Set<String>），同路径免弹窗、其他路径/工具仍弹窗 |
| `src/main/java/com/minion/core/tools/ReadTool.java` | 构造注入 ConfirmGate；越界分支改走确认 |
| `src/main/java/com/minion/core/tools/GrepTool.java` | 同上 |
| `src/main/java/com/minion/core/tools/GlobTool.java` | 新增可选 `path` 参数（越界时走确认）；构造注入 ConfirmGate |
| `src/main/java/com/minion/core/agent/SystemPromptBuilder.java` | BUILTIN 追加规则 7；开关开启时追加越界读说明 |
| `src/main/java/com/minion/core/agent/AgentLoop.java` | `DEFAULT_ROUND_LIMIT=1000`；连续失败计数 + ≥30 注入系统提醒 |
| `src/main/java/com/minion/Main.java` | ConfirmGate 构造提前到读工具注册前；读工具构造传 ConfirmGate |
| `src/test/.../confirm/ConfirmGateTest.java` | 新增 `checkReadOutside` 测试（开关开/关 × Y/N/A） |
| `src/test/.../tools/FileToolsTest.java` | 越界读路径回归（开关关弹确认经 FakeConfirmUi 批准后放行 / 拒绝） |
| `src/test/.../agent/AgentLoopTest.java` | 连续 30 次失败注入提醒；不足不注入；成功清零；轮数上限 |

## 测试

- ConfirmGateTest：开关开→放行不弹；开关关→Y 放行 / N 拒绝 / A 本会话该路径放行 / W 同 A；
  同一路径二次读不弹窗，**另一路径仍弹窗**；其他高危工具确认不受越界读 A 影响
- FileToolsTest：越界读开关关+N→拒绝文案；开关关+Y→读到内容；开关开→直接读到内容；
  Glob path 参数指向外部目录时同上；写入类越界仍拒绝（回归）
- AgentLoopTest：连续 30 次失败 → 消息数组中含 `[系统提醒]`；29 次不注入；成功一次计数清零；
  注入后计数重置（再失败 30 次会再注入）
- 现有测试全绿：`mvn test`

## 错误处理

- 确认被拒（N）：返回现拒绝文案（`路径在工作路径之外，已拒绝`），模型自调
- -c 单次执行模式：ConfirmGate 已用全放行 UI（Main.java:96-98 现状），越界读不弹窗直接放行——
  与"开关关弹确认"语义一致（脚本化模式本就全放行），无需额外处理
- 注入提醒后模型仍不停：roundLimit（1000）兜底终止，提示"达到工具轮数上限"
