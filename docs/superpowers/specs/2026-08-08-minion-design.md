# minion — 代码开发助手 设计文档

日期：2026-08-08
状态：已获用户确认

## 1. 概述

minion 是一个类似 Claude Code 的生产级命令行代码开发助手，Java 实现，对接 DeepSeek 大模型。核心能力：

- 交互式 REPL（JLine）+ `-c` 单次执行 + `-r` 恢复会话
- 工具调用循环（9 个内置工具，模型自主调用）
- 子 agent：模型通过 `task` 工具自主派发，完整工具集、可并行、无轮数/输出限制
- 消息落盘持久化，可恢复会话
- 技能支持：兼容 superpowers 格式（`skills/<name>/SKILL.md` + YAML frontmatter）
- 上下文压缩：达阈值自动触发 + `/compact` 手动，不影响已加载技能
- 流式输出，美观交互；每轮输出 token（k）、上下文、百分比、耗时统计
- 高危操作（写/改/删文件、危险命令）执行前需用户确认，支持动态白名单

## 2. 技术栈与依赖

- JDK 1.8（实测 1.8.0_181），Maven 3.6.3，单模块
- 依赖（全部 JDK8 兼容）：
  - `com.google.code.gson:gson:2.10.1` — JSON 序列化/工具参数
  - `com.squareup.okhttp3:okhttp:3.14.9` — HTTP + SSE 流式（纯 Java，无 Kotlin 依赖）
  - `org.jline:jline:3.25.1` — 行编辑、历史、ANSI 渲染（Windows 控制台自适应）
  - `org.yaml:snakeyaml:2.2` — 技能 frontmatter 解析
  - `junit:junit:4.13.2`（仅 test scope）— 单元/集成测试
- 构建产物：`minion.jar`（maven-shade 打包，含依赖），启动脚本 `minion.bat`

## 3. 模型接入（DeepSeek）

- 模型名 `deepseek-v4-flash`（`deepseek-chat`/`deepseek-reasoner` 已于 2026-07-24 退役）
- 思考模式：`thinking: {"type":"enabled"}` + `reasoning_effort: "max"`（用户要求 max）
- 思考模式下不支持 temperature/top_p 等采样参数（不发送，发送无副作用）
- **关键约束**：思考模式 + 工具调用时，历史 `assistant` 消息的 `reasoning_content` 必须原样回传，否则 400。Message 模型与落盘格式均持久化该字段
- 流式响应解析：`delta.content`（正文）、`delta.reasoning_content`（思考）、`delta.tool_calls`（工具调用增量）

## 4. 配置

资源目录按用户指定为 `src/resource`（pom 配置）。`config.properties` 为 classpath 默认值；运行时**优先读 jar 同目录的外部 `config.properties`**，外部文件不存在时首次运行自动生成（含全部默认值 + 注释）——这是白名单写盘的前提。

```properties
# ===== 模型 =====
model.url=https://api.deepseek.com/v1/chat/completions
model.key=sk-your-key
model.name=deepseek-v4-flash
model.thinking=true
model.reasoningEffort=max
model.maxContextTokens=131072

# ===== 上下文压缩 =====
context.compressThreshold=0.8
context.keepRecentMessages=10

# ===== 路径 =====
work.dir=.
project.md.path=./project.md
skills.dir=./skills
session.dir=./.minion/sessions

# ===== 高危操作确认 =====
confirm.skip=false
confirm.whitelist.tools=
confirm.whitelist.commands=

# ===== UI =====
ui.color=true
```

说明：
- HTTP 超时写死：连接 30s、读取 300s（不占配置）
- 主循环工具轮数上限写死 10000（防模型失控无限循环烧钱，同时容纳长任务）；子 agent 不限轮数、不限输出

## 5. 包结构

```
com.minion
├── Main                   入口（参数解析：-c / -r / 默认交互）
├── cli/                   JLine REPL、Renderer（ANSI）、命令处理、确认提示
├── core/agent/            AgentLoop（主循环）、SubAgentLoop（子agent嵌套循环）、Session
├── core/llm/              DeepSeekClient（SSE 流式）、Message、ToolCall、UsageTracker
├── core/tools/            ToolRegistry、Tool 接口、9 个工具、SchemaGenerator、危险判定
├── core/skills/           SkillManager、Skill（frontmatter 解析）
├── core/context/          ContextManager、TokenCounter
├── core/storage/          SessionStore
├── core/config/           Config
└── core/util/             Ansi、Ioutil
```

## 6. 消息模型

```java
Message { role, content, reasoningContent, toolCalls[], toolCallId, name }
```

- `role`: system / user / assistant / tool
- 回传规则：assistant 消息带 `reasoningContent` 时原样回传（DeepSeek 硬性要求）；`tool` 消息带 `toolCallId` 与工具名
- 序列化：toolCalls 保存完整参数 JSON（id、type、function.name、arguments）
- 摘要消息带 `summary=true` 标记（压缩产物，不参与再次压缩）

## 7. Agent 循环

### 主循环（AgentLoop）

```
用户输入 → 追加 user 消息
  → TokenCounter 估算：达到 maxContextTokens × compressThreshold 则自动压缩
  → 流式请求（thinking 暗灰斜体 / 正文流式渲染）
  → finish_reason == tool_calls ?
      → 执行工具（同一回合多个 tool_calls 并行；Bash 有超时）
      → 工具结果作为 tool 消息回传 → 下一轮（上限 10000，写死，防失控烧钱同时容纳长任务）
  → 无工具调用 → 本轮完成 → 落盘 + 输出统计行
```

### 子 agent（SubAgentLoop）

- 模型通过 `task` 工具派发，参数：任务描述（及可选：期望返回格式）
- 独立 system prompt = 主 system prompt + 任务描述 + 「只负责该任务，完成后用最终文本总结」
- 独立 messages 数组、完整工具集（不嵌套子 agent——子 agent 内不再允许 task 工具，防止无限递归）
- 每个子 agent 一个工作线程，同一回合多个 `task` 调用可并行（有界线程池）
- 流式输出缩进 + `⌁` 前缀实时展示；结束后最终文本作为 `tool` 结果注入主会话
- 无轮数/输出上限（用户要求：避免做一半失败）

### System prompt 组装顺序

1. minion 内置系统提示词（角色、工具使用说明、安全规则）
2. project.md 内容（路径可配置，存在则拼接）
3. 技能列表提示（已发现技能的名称+描述，提示模型任务匹配时建议用户加载）
4. 已加载技能指令（`/skill <name>` 后追加，在项目介绍之后）

## 8. 工具（9 个）

| 工具 | 说明 |
|---|---|
| Read | 读文件（可带行号、偏移/上限） |
| Write | 写文件（覆盖已存在文件 = 高危，需确认） |
| Edit | 精确字符串替换（始终高危） |
| Glob | 路径模式匹配 |
| Grep | 正则内容搜索 |
| Bash | 命令执行（工作路径内、超时、输出截断 30k） |
| Task | 派发子 agent（完整工具集、可并行） |
| TodoWrite | 任务清单跟踪（plan 显示） |
| WebFetch | URL 抓取转 markdown（OkHttp 实现） |

- 工具 JSON Schema 由 SchemaGenerator 生成（OpenAI function calling 格式）
- 文件工具读写限制在工作路径内
- Bash 危险命令表（初始表，首 token 前缀匹配，实施时集中定义、可扩展）：`rm del rd rmdir rmdir /s format mkfs dd shutdown taskkill pkill killall fdisk mkfs.ext4`——删除/格式化/关机/进程杀灭类

## 9. 高危操作确认

### 判定

| 工具 | 判定 | 原因 |
|---|---|---|
| Write | 目标文件已存在（覆盖） | 破坏性 |
| Edit | 始终 | 修改现有代码 |
| Bash | 首命令 token 命中危险命令表 | 删除/格式化/关机类 |

### 交互（工具执行前暂停，独立确认行）

```
⚠ 高危操作  Edit → E:\...\src\Main.java
   [回车/Y] 确认本次   [N] 拒绝本次   [W] 确认并加入白名单   [A] 本会话内全部放行
```

- 回车/Y → 执行；N → 拒绝，工具返回「用户拒绝该操作」给模型（模型自调方案）
- W → 执行 + 自动追加白名单到**外部 config.properties**（工具名 → `confirm.whitelist.tools`；命令 → `confirm.whitelist.commands`），带注释写入
- A → 仅本会话免确认（不落盘）
- 白名单命中或 `confirm.skip=true` → 直接执行

## 10. 上下文压缩

- **TokenCounter**：启发式估算（中文 1 字 ≈ 0.7 token、英文 4 字符 ≈ 1 token，加权），用于触发判断；**统计展示优先用 API 返回的 usage，取不到才用估算**
- 触发：请求前估算 ≥ 阈值 → 自动压缩；`/compact` 手动
- 压缩流程：
  1. 从最早消息开始按**完整回合链**切块（`user → assistant(工具) → tool… → assistant(无工具)` 为一链），链为单位压缩，保证 tool_call ↔ tool 配对完整（拆散会 400）
  2. 保留最近 `keepRecentMessages` 条原文
  3. 对压缩链发**独立压缩请求**（独立 system prompt：压缩器指令，只输出摘要，不配工具、不带技能）
  4. 生成带 `summary` 标记的消息置于会话最前，替换原文；该消息不再参与后续压缩
- 技能在 system prompt，不进入任何压缩请求 → 不影响技能

## 11. 持久化与恢复

- 格式：`session.dir/<yyyyMMdd-HHmmss>.json`，内容：元数据（id、时间、工作路径、模型名）+ 全部消息（含 reasoningContent、toolCalls、summary 标记）+ 累计统计
- 原子写（临时文件 + rename）；每次 API 请求完成后写盘（含工具循环每步）
- `minion -r` 恢复最近会话；`/resume` 列出历史（时间 + 最后消息摘要）选择恢复
- 恢复时 project.md / 技能按当前配置重新加载

## 12. CLI 交互与统计

### UI

- Prompt `❯ ` 品牌色；JLine 行编辑、历史、补全（/命令、技能名）
- 流式：思考暗灰斜体；正文正常色流式；工具调用状态行 `🔧 Read → 文件` / `$ 命令`；子 agent `⌁` 缩进块实时显示；工具结果只显示首行+行数
- 每轮结束统计行：`⏱ 12.3s · in 8.2k · out 3.4k · thinking 2.1k · ctx 61.4k/128k (48%)`（单位 k，不足 1000 显示原始值）
- Ctrl+C 第一次取消当前流式/工具，再按退出

### 统计（UsageTracker）

- 按轮 + 会话累计：input / output / thinking / 总
- 优先 API usage 精确值，缺失估算兜底
- `/tokens` 显示明细与累计

### /命令

```
/help /exit /quit /resume /skills /skill <n> /compact /tokens /clear /model
```

## 13. 错误处理

| 场景 | 处理 |
|---|---|
| 401 key 错 | 红色提示检查 config.properties |
| 429 限流 | 提示 + 自动退避重试 1 次 |
| 400（多为思考消息回传问题） | 记录上下文日志，返回提示 |
| 网络/超时 | 自动重试 1 次后放弃，本轮已落盘 |
| 工具参数 JSON 解析失败 | 错误 tool 消息返回模型自纠 |
| Bash 非零退出 | 正常返回退出码+输出 |
| 落盘/压缩失败 | 告警不阻断，压缩下轮重试 |
| 中断 | 已落盘，可恢复 |

## 14. 测试

- 单测：frontmatter 解析（SnakeYAML）、TokenCounter、压缩链切块、危险命令判定、白名单读写、SchemaGenerator
- 集成：Bash 执行与超时、Edit 精确匹配、会话落盘/恢复往返
- 手测清单：流式渲染、确认交互、自动压缩、子 agent、Ctrl+C 中断、-c/-r 模式
