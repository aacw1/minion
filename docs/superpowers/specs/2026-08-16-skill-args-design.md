# 2026-08-16 技能调用参数（skill args）设计

## 背景与现状

minion 的技能加载有两条路径，均不支持参数：

1. **手动路径**：`/skill <技能名>` 斜杠命令 → `CommandDispatcher.dispatchSkill` 只取 `parts[1]` 当技能名，**尾随文字（`parts[2:]`）被静默丢弃**。用户想给技能附带说明时（如 `/skill brainstorming 帮我设计一个设置页`），后半句毫无效果，体验与预期不符。
2. **模型路径**：`Skill` 工具（`SkillTool`）schema 只有 `name` 一个属性，模型无法在加载技能时附带上下文。

参考行为（Claude Code）：技能以自身名字作斜杠命令，命令后的尾随文字作为参数传入，在技能正文中可用 `$ARGUMENTS` 引用；参数是**自由文本**，不要求技能声明参数 schema。Claude Code 同样防重复加载同一技能。

## 目标

- `/skill <技能名> [参数...]`：尾随文字整体拼接为参数，随技能一起注入会话
- 参数以 `用户参数: <文本>` 段落附加在 `<skill>` 标签之后（同一用户消息内，透明可审计，下轮请求生效，语义不变）
- `Skill` 工具 schema 增加**可选** `args` 属性并透传，两条路径行为一致
- 保持按**技能名**去重不变（同一技能带不同参数连调，第二次跳过，参数以第一次为准）

## 方案

改动 3 个文件，不新增类文件：

### 1. CommandDispatcher.dispatchSkill（`gui/command/CommandDispatcher.java`）

- `parts.length > 2` 时把 `parts[2:]` 用空格拼接为 args 字符串
- 调用 `h.loop.offerSkillLoad(s, args)`（新增重载）
- 返回文案区分是否带参数：`已加载技能: <名>（含参数: <args>，下一轮请求生效）` / 原无参文案

### 2. AgentLoop（`core/agent/AgentLoop.java`）

- `pendingSkillLoads` 由 `List<Skill>` 改为 `List<SkillLoad>`，新增私有内部类 `SkillLoad { Skill skill; String args; }`（JDK8 无 record）
- 重载：
  - `offerSkillLoad(Skill)` → 委托 `offerSkillLoad(skill, null)`（现有调用方零改动）
  - `offerSkillLoad(Skill, String args)` → 原去重逻辑（按 `skill.name`）不变，入队 `SkillLoad(skill, args)`
- `drainPendingSkillLoads`：注入时若 args 非空，在 `<skill>` 标签闭合后拼 `"\n\n用户参数: " + args`，消息格式：
  ```
  <skill name="brainstorming">
  指令正文
  </skill>
  用户参数: 帮我设计一个设置页
  ```
  参数放标签外：标签内严格等于 SKILL.md 正文（技能定义不变量——幂等/判重/正文独立处理不受参数干扰），参数属调用上下文
  其余（pinned、`ui.onUserMessage` 注入即发 UI 事件、中断轮不注入）不变

### 3. SkillTool（`core/tools/SkillTool.java`）

- schema `properties` 增加 `args`（string，可选，不进 required）：`给技能附带的参数/说明，自由文本`
- `execute` 读取 args（缺省为 null），传给 `loop.offerSkillLoad(skill, args)`
- description 提及可附带参数

## 取舍说明

| 决策点 | 选择 | 理由 |
|---|---|---|
| 参数载体 | 自由文本拼入 `<skill>` 消息，不做 schema 声明 | 对齐 Claude Code `$ARGUMENTS` 语义；技能无需声明参数即可接收 |
| 去重 | 按技能名（参数以第一次为准） | 与现有防重复语义一致；参数不同视为新调用的收益低、破坏「别重复加载」约定 |
| 参数格式 | `用户参数: <原文>` 段落紧跟 `<skill>` 标签外（同一消息） | 标签内 = SKILL.md 正文不变量，幂等/判重/正文独立处理不受参数干扰；参数属调用上下文，紧邻技能块保持关联 |

## 测试计划

- **CommandDispatcherTest**：
  - `/skill brainstorming 帮我设计一个设置页` → 返回含参数文案；`offerSkillLoad` 收到 args（后续注入含参数）
  - 多词参数原样拼接（含空格）
  - 无参 `/skill brainstorming` → 原行为不变
- **AgentLoopTest**：
  - `offerSkillLoad(skill, "参数")` → 下轮请求注入的 `<skill>` 消息含 `用户参数: 参数`，首轮请求即携带
  - 同名去重：同名不同参连调 → 仅注入一次
  - `offerSkillLoad(skill)` 无参重载行为不变（现有用例兜底）
- **SkillToolTest**：带 `args` 调用 → 注入含参数；无 `args` → 原行为

## 兼容性

- JDK 8：不使用 record/var，内部小类 + 重载
- 不新增依赖
- 消息类型、pinned 语义、tool_call↔tool 契约、会话落盘格式均不变（`pendingSkillLoads` 为内存态，`restoreSession` 不恢复，现状如此）
- `/skill` 帮助文案与 README 同步更新
