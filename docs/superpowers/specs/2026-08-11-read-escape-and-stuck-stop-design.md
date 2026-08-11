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
  - **复用现有全局 `sessionBypass`**（用户确认）：A 的既有语义即"本会话所有确认
    （高危操作 + 越界读）全部跳过"，不做 per-tool/per-path 改造——越界读与高危操作
    语义统一为全局会话放行，实现最简
  - 确认动作：Y 放行本次；N 返回现拒绝文案；A/W 置位 `sessionBypass` 后放行
    （W 对越界读也按会话放行处理，不落持久化白名单，不新增配置键）
  - 高危操作 `check()` 逻辑**不变**
- 实现位置：ReadTool / GrepTool / GlobTool 构造注入 ConfirmGate（与现有 skillsDir 注入同模式），
  越界分支从 `return guard` 改为调 `checkReadOutside` 决定放行/拒绝
- Main.java 需将 ConfirmGate 构造提前到读工具注册之前（当前在注册之后，Main.java:96）；
  工具构造签名变为 `(Workspace, String skillsDir, ConfirmGate)`
- 配置同步：`Config.readAllowOutside()` + config.properties 新增
  `paths.read.allowOutside=false`（带注释）
- **不做系统提示联动**（用户指定）：模型天然会尝试越界读（现状只是失败），
  提示"可读外部文件"反而会过度鼓励越界读；改造目标仅让越界读成功

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
| `src/main/java/com/minion/core/tools/confirm/ConfirmGate.java` | 新增 `checkReadOutside(tool, args, path)`：开关开→放行；关→弹确认复用 Y-N-A；A/W 置位现有 `sessionBypass`（本会话全部确认跳过）；高危 `check()` 不变 |
| `src/main/java/com/minion/core/tools/ReadTool.java` | 构造注入 ConfirmGate；越界分支改走确认 |
| `src/main/java/com/minion/core/tools/GrepTool.java` | 同上 |
| `src/main/java/com/minion/core/tools/GlobTool.java` | 新增可选 `path` 参数（越界时走确认）；构造注入 ConfirmGate |
| `src/main/java/com/minion/core/agent/SystemPromptBuilder.java` | BUILTIN 追加规则 7（卡住止损） |
| `src/main/java/com/minion/core/agent/AgentLoop.java` | `DEFAULT_ROUND_LIMIT=1000`；连续失败计数 + ≥30 注入系统提醒 |
| `src/main/java/com/minion/Main.java` | ConfirmGate 构造提前到读工具注册前；读工具构造传 ConfirmGate |
| `src/test/.../confirm/ConfirmGateTest.java` | 新增 `checkReadOutside` 测试（开关开/关 × Y/N/A） |
| `src/test/.../tools/FileToolsTest.java` | 越界读路径回归（开关关弹确认经 FakeConfirmUi 批准后放行 / 拒绝） |
| `src/test/.../agent/AgentLoopTest.java` | 连续 30 次失败注入提醒；不足不注入；成功清零；轮数上限 |

## 测试

- ConfirmGateTest：开关开→放行不弹；开关关→Y 放行 / N 拒绝 / A 置位 sessionBypass 放行 /
  W 同 A；A 后高危操作与越界读均免确认（与既有 sessionBypass 语义一致，回归）
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
