# minion 架构 — 功能类路径

> 后续开发的类路径与扩展点指引。开发规约见 [CONVENTIONS.md](CONVENTIONS.md)，用户使用见 [README.md](../README.md)。
> 若本文与代码不一致，以代码为准并更新本文。

## 1. 包结构总览

```
com.minion
├── Main                    入口：参数解析（-c/-r/交互）、工具注册与装配
├── cli/                    JLine REPL、ANSI 渲染、命令分发、确认提示、启动横幅、统计行
└── core/
    ├── agent/              AgentLoop（主循环）、SubAgentLoop（子 agent）、Session、TodoList、SystemPromptBuilder
    ├── llm/                DeepSeekClient（SSE 流式）、Message、ToolCall、Usage、UsageTracker
    ├── tools/              Tool 接口、ToolRegistry、9 个工具、SchemaGenerator、confirm/（ConfirmGate、ConfirmUi）、PathsGuard
    ├── skills/             SkillManager、Skill（YAML frontmatter 解析）
    ├── context/            ContextManager、TokenCounter
    ├── storage/            SessionStore
    ├── config/             Config
    └── util/               Ansi、ConsoleIo
```

## 2. 各包职责与关键类

### com.minion（根）

- `Main`：程序入口。参数解析（`-c` 单次执行 / `-r` 恢复会话 / 默认交互 REPL）；装配 Config、ToolRegistry、AgentLoop；注册除 Task 外的 8 个工具（Main.java:55-82，Task 由 AgentLoop 注册）。

### cli/

| 类 | 职责 |
|---|---|
| Repl | JLine 行编辑循环；Ctrl+C 首次取消当前流式/工具，再按退出 |
| CommandDispatcher | /命令分发：/help /exit /quit /skills /skill <名> /resume /compact /tokens /clear /model |
| Renderer / StatsLine | ANSI 渲染、每轮统计行（耗时/入出 token/上下文占用） |
| ConfirmReader | 高危操作确认按键读取（回车/Y/N/W/A） |
| StartupBanner | 启动横幅（模型、上下文、路径概览） |

### core/agent/

| 类 | 职责 |
|---|---|
| AgentLoop | 主循环：追加消息 → 估算/压缩 → 流式请求 → 工具执行 → 落盘；轮数上限 DEFAULT_ROUND_LIMIT=10000；TaskTool 在此注册 |
| SubAgentLoop | 子 agent：独立 system prompt + 消息数组 + 完整工具集，但不注册 task 工具（防无限递归）；无轮数/输出上限 |
| Session | 会话状态：消息列表、统计 |
| TodoList | 任务清单（TodoWrite 工具的后端） |
| SystemPromptBuilder | system prompt 组装：内置提示词 → project.md → 技能列表 → 已加载技能 |

### core/llm/

| 类 | 职责 |
|---|---|
| LlmClient / DeepSeekClient | SSE 流式请求；HTTP 连接 30s / 读取 300s（写死常量） |
| Message | 消息模型（role/content/reasoningContent/toolCalls/toolCallId/name/summary）；assistant 的 reasoningContent 必须原样回传（DeepSeek 硬性要求） |
| ToolCall / Usage / UsageTracker | 工具调用增量解析；按轮+会话累计 input/output/thinking |

### core/tools/

- `Tool` 接口（name/description/schema/execute/isHighRisk）→ 9 个实现：ReadTool、WriteTool、EditTool、GlobTool、GrepTool、BashTool、WebFetchTool、TaskTool、TodoWriteTool
- `ToolRegistry`：注册表（name 小写索引）；`schemas()` 生成 OpenAI function calling 格式
- `SchemaGenerator`：Java 结构 → JSON Schema
- `ConfirmGate`：高危确认（Write 覆盖已有文件 / Edit 始终 / Bash 命中危险命令表）
- `ConfirmGate` / `ConfirmUi` 位于 `core/tools/confirm/` 子包
- `PathsGuard`：文件工具路径限制（工作路径 + 技能目录；技能目录可配置为工作路径外的绝对路径）
- `example/ExampleTool`：新工具模板示例（未注册）

### core/skills/

- `SkillManager`：扫描 `skills/<名>/SKILL.md`（superpowers 格式）或 `skills/<名>.skill.md`，YAML frontmatter 解析
- `Skill`：技能模型

### core/context/ · core/storage/ · core/config/ · core/util/

- `ContextManager` / `TokenCounter`：上下文压缩（达 maxContextTokens×compressThreshold 触发，按完整回合链压缩，保留最近 keepRecentMessages 条）
- `SessionStore`：会话 JSON 落盘（原子写；每次 API 请求完成后写盘）
- `Config`：config.properties（classpath 默认值 + jar 同目录外部覆盖，首次运行自动生成）
- `Ansi` / `ConsoleIo`：ANSI 颜色、跨平台控制台 IO

## 3. 核心数据流

```
用户输入 → Repl → AgentLoop
  1. 追加 user 消息；TokenCounter 估算达阈值 → 自动压缩（/compact 手动）
  2. 流式请求（thinking 暗灰 / 正文 / tool_calls 增量）
  3. finish_reason==tool_calls → 执行工具（同回合并行；Bash 超时 120s）→ tool 消息回传 → 下一轮
  4. 无工具调用 → 本轮完成 → 落盘 + 统计行
```

细节时序见 `docs/superpowers/specs/2026-08-08-minion-design.md` §7。

## 4. 依赖方向

- `cli` 可依赖 `core` 任意包
- `core` 内包之间经接口 + 构造注入协作；唯一反向依赖：`TaskTool`（tools）→ `AgentLoop`（agent），经构造注入实现
- 新增工具/技能/命令走 §5 扩展点，不改变依赖方向

## 5. 扩展点

### 新增工具

1. 实现 `Tool` 接口（name/description/schema/execute + 默认 isHighRisk=false）
2. 涉及路径限制的用 `PathsGuard` 校验；高危操作实现 `isHighRisk`（走 ConfirmGate）
3. 在 `Main` 注册 `registry.register(new XxxTool(workDir))`；会话相关（如 TodoWrite）在会话创建后注册
4. 附测试 `src/test/java/com/minion/core/tools/XxxToolTest.java`（JDK8 语法、JUnit4）

### 新增技能

- 无需代码：`skills/<名>/SKILL.md` + YAML frontmatter（name/description/metadata），SkillManager 自动发现

### 新增 /命令

- `cli/CommandDispatcher` 的 dispatch 中加 case；补 `CommandDispatcherTest`

## 6. 关键写死常量

| 常量 | 值 | 位置 |
|---|---|---|
| 主循环工具轮数上限 DEFAULT_ROUND_LIMIT | 10000 | AgentLoop.java:31 |
| Bash 默认超时（timeoutSeconds 可覆盖） | 120s | BashTool.DEFAULT_TIMEOUT |
| Bash 输出截断 MAX_OUTPUT | 30k 字符 | BashTool.MAX_OUTPUT |
| HTTP 连接超时 CONNECT_TIMEOUT | 30s | DeepSeekClient.java:24 |
| HTTP 读取超时 READ_TIMEOUT | 300s | DeepSeekClient.java:25 |

> 改动以上常量须在设计阶段说明理由，不随手改。
