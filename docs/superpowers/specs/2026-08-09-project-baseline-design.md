# minion — 项目基线文档 设计文档

日期：2026-08-09
状态：已获用户确认

## 1. 概述

minion 已有完整代码（约 9 个工具、主/子 agent 循环、技能、会话持久化、上下文压缩），但缺少面向后续开发的指引文档。本设计为项目建立基线文档，交付三份产物：

| 产物 | 作用 | 读者 |
|---|---|---|
| `docs/ARCHITECTURE.md` | 功能类路径：包结构、各包职责、数据流、依赖方向、扩展点 | 开发者 + Claude Code |
| `docs/CONVENTIONS.md` | 开发规约：类路径与分层、代码风格与质量、AI 协作 | 开发者 + Claude Code |
| `CLAUDE.md` | 根目录入口：精简版类路径 + 规约要点，指向两份详文 | Claude Code（每次会话自动加载） |

用户已确认的决策：
- 交付形式：**CLAUDE.md + docs/ 详文**（方案 A，而非单文档或包内注释）
- 规约范围：**类路径与分层（默认包含）+ 代码风格与质量 + AI 协作规约**（未选：测试规约、Git 与流程规约——后两者仅在 AI 协作规约中作为完成前检查的要点带过，不设独立章节）

## 2. 产物一：docs/ARCHITECTURE.md（功能类路径）

全部内容从实际代码提取，不虚构。六节：

### 2.1 包结构总览

ASCII 树，来源为 `src/main/java` 实际目录：

```
com.minion
├── Main                    入口：参数解析（-c / -r / 默认交互）、工具注册、装配
├── cli/                    JLine REPL、Renderer（ANSI）、命令分发、确认提示、启动横幅、统计行
└── core/
    ├── agent/              AgentLoop（主循环）、SubAgentLoop（子 agent）、Session、TodoList、SystemPromptBuilder
    ├── llm/                DeepSeekClient（SSE 流式）、Message、ToolCall、Usage、UsageTracker
    ├── tools/              Tool 接口、ToolRegistry、9 个工具实现、SchemaGenerator、ConfirmGate、PathsGuard
    ├── skills/             SkillManager、Skill（frontmatter 解析）
    ├── context/            ContextManager、TokenCounter
    ├── storage/            SessionStore
    ├── config/             Config
    └── util/               Ansi、ConsoleIo
```

### 2.2 各包职责与关键类表

每包一段：职责、关键类、代表性设计。要点：

- `core/tools`：`Tool` 接口（name/description/schema/execute/isHighRisk）→ 9 个实现 → `ToolRegistry` 注册表；`SchemaGenerator` 生成 OpenAI function calling 格式 schema；`ConfirmGate`（高危确认）、`PathsGuard`（工作路径限制）为横切关注点
- `core/agent`：`AgentLoop` 主循环（工具轮数上限写死 10000）、`SubAgentLoop` 子 agent（无轮数上限、不嵌套 task 工具防递归）、`TaskTool` 在此注册（构造注入 AgentLoop）
- `core/llm`：DeepSeek SSE 流式；`Message` 持久化 `reasoningContent`（DeepSeek 硬性要求原样回传）
- 其余包按职责简短描述

### 2.3 核心数据流

入口 → REPL → AgentLoop 主循环 → LLM 流式/工具执行 → 落盘；引用既有设计文档 §7 的时序（不重复细节，给链接）。

### 2.4 依赖方向

- `cli` 可依赖 `core` 任意包
- `core` 内包之间经接口与构造注入协作，不产生编译期循环依赖（唯一例外：`TaskTool` 反向依赖 `core.agent`，通过构造注入实现，已在代码中如此）
- 新增工具/技能/命令遵循各自扩展点，不改变依赖方向

### 2.5 扩展点

三份"如何新增"：

1. **新增工具**：实现 `Tool` 接口 → 在 `Main` 注册（`registry.register(...)`）；高危操作实现 `isHighRisk` 走 ConfirmGate；涉及路径限制的继承 `PathsGuard` 检查
2. **新增技能**：`skills/<name>/SKILL.md` + YAML frontmatter（name/description/metadata），SkillManager 自动发现，无需注册代码
3. **新增 /命令**：`cli/CommandDispatcher` 注册分发

### 2.6 关键写死常量表

| 常量 | 值 | 位置 |
|---|---|---|
| 主循环工具轮数上限 | 10000 | AgentLoop |
| Bash 默认超时（可用 timeoutSeconds 覆盖） | 120s | BashTool |
| Bash 输出截断 | 30k 字符 | BashTool |
| HTTP 连接超时 | 30s | DeepSeekClient |
| HTTP 读取超时 | 300s | DeepSeekClient |

说明：改这些值需在设计阶段讨论，不随手改。

## 3. 产物二：docs/CONVENTIONS.md（开发规约）

三节：

### 3.1 类路径与分层规约

- 新代码落位判断：工具 → `core/tools`；界面/命令逻辑 → `cli`；模型交互 → `core/llm`；通用能力 → 按职责进 core 子包
- 依赖方向同 ARCHITECTURE.md §2.4；**新增循环依赖需在设计中说明理由**
- 跨包访问仅经 public 接口；工具实现统一继承 `Tool` 接口，不另起机制
- 工具注册点：普通工具在 `Main`，会话相关（TodoWrite、Task）在创建会话处注册

### 3.2 代码风格与质量

- JDK 1.8 兼容：不引入 JDK9+ API（`var`、`List.of`、`Optional.stream` 等）
- 新增依赖约束：必须 JDK8 兼容、避免 Kotlin/重量级依赖（okhttp 选 3.x 而非 4.x 是既有教训）；加依赖须在设计文档写明理由
- 错误处理：LLM/网络错误抛 `LlmException`；工具错误返回失败态 `ToolResult`（模型可据此自调方案），不把原始异常抛给界面
- 配置：新增配置项必须同步 `src/resource/config.properties` 默认值与首次运行外部生成逻辑
- 外部 API 契约（防回归教训）：
  - DeepSeek 思考模式：历史 assistant 消息的 `reasoning_content` 必须原样回传，否则 400
  - tool_call ↔ tool 结果消息必须完整配对回传，拆散会 400
- 常量集中、写死值标注说明（见 ARCHITECTURE §2.6）

### 3.3 AI 协作规约

- **设计先行**：功能先写 `docs/superpowers/specs/YYYY-MM-DD-<主题>-design.md` → 用户确认 → writing-plans 出实施计划 → 实施
- 动手前先读相关文件与既有设计文档；存在适用的 superpowers 技能必须先调用
- 完成前自查：`mvn compile` 通过 + 相关测试通过；改动同步更新 README 与设计文档
- Commit：沿用 conventional 格式（`feat/fix/docs/debug/chore` + 中文描述），一个逻辑改动一个 commit；注释与文档用中文

## 4. 产物三：CLAUDE.md（根目录入口）

约 60–80 行，精简：

- 项目一句话 + 技术栈（JDK8 / Maven 单模块 / DeepSeek / gson、okhttp 3.x、jline、snakeyaml）
- 常用命令：`mvn clean package`、`mvn test`、`minion` / `minion -c` / `minion -r`
- 包结构简图（10 行）+ 三个扩展点一句话（新增工具/技能/命令分别看哪里）
- 核心规约要点 ≤7 条（从 CONVENTIONS.md 提炼）
- 指向 `docs/ARCHITECTURE.md`、`docs/CONVENTIONS.md`；说明 specs/plans 流程目录
- 特殊约定：资源目录是 `src/resource`（非 `src/main/resources`，pom 已配置）、JDK8、中文文档

## 5. 验收标准

- 三份产物落地；ARCHITECTURE.md 内容与 `src/main/java` 实际结构一一对应（包、类、注册点可核对）
- CLAUDE.md ≤ 100 行；被 Claude Code 自动加载时给出正确的扩展点与规约指引
- 已有文档（README、设计文档）不改动
- `mvn compile` 不受影响（本次纯文档，无代码改动）

## 6. 实施步骤

1. 提取实际包结构/关键类/注册点/常量（已核实：工具注册于 Main.java:55-82 与 AgentLoop.java:79）
2. 写 `docs/ARCHITECTURE.md`
3. 写 `docs/CONVENTIONS.md`
4. 写根目录 `CLAUDE.md`
5. 自查：与代码核对 + 行数检查
