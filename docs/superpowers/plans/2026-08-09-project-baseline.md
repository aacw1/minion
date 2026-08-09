# 项目基线文档（ARCHITECTURE / CONVENTIONS / CLAUDE.md）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产出三份文档：`docs/ARCHITECTURE.md`（功能类路径）、`docs/CONVENTIONS.md`（开发规约）、根目录 `CLAUDE.md`（精简入口），供后续开发指引。

**Architecture:** 纯文档任务，无代码改动。内容从实际代码提取（包树、类名、注册点、常量均已核实），每份文档一份任务，每任务"写 → 核对 → commit"。核对以 grep/find 对照 `src/main/java` 实际结构为准。

**Tech Stack:** Markdown（中文），无构建影响。

## Global Constraints

- 三份产物内容必须与 `src/main/java` 实际结构一一对应（类名、注册点、常量值可核对）
- 不改动已有文件（README.md、既有 specs/plans、pom.xml 等）
- CLAUDE.md ≤ 100 行
- 文档用中文；commit 用 conventional 格式
- 依据 spec：`docs/superpowers/specs/2026-08-09-project-baseline-design.md`（§2/§3/§4 定义了三份文档的内容结构）

---

### Task 1: 创建 docs/ARCHITECTURE.md（功能类路径）

**Files:**
- Create: `docs/ARCHITECTURE.md`

**Interfaces:**
- Produces: 文件 `docs/ARCHITECTURE.md`（Task 3 的 CLAUDE.md 将引用它）；文档中链接 `../README.md` 与 `docs/superpowers/specs/2026-08-08-minion-design.md`

- [ ] **Step 1: 创建文件，写入以下完整内容**

````markdown
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
    ├── tools/              Tool 接口、ToolRegistry、9 个工具、SchemaGenerator、ConfirmGate、PathsGuard
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

- `Tool` 接口（name/description/schema/execute/isHighRisk）→ 9 个实现：Read、Write、Edit、Glob、Grep、Bash、WebFetch、Task、TodoWrite
- `ToolRegistry`：注册表（name 小写索引）；`schemas()` 生成 OpenAI function calling 格式
- `SchemaGenerator`：Java 结构 → JSON Schema
- `ConfirmGate`：高危确认（Write 覆盖已有文件 / Edit 始终 / Bash 命中危险命令表）
- `PathsGuard`：文件工具工作路径限制
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
````

- [ ] **Step 2: 与代码核对**

```bash
# 1) 包树/类名核对：文中每个类名应能在此输出中找到
find src/main/java/com/minion -name "*.java" | sed 's|src/main/java/com/minion/||; s|/|.|g; s|\.java$||'
# 2) 注册点核对
grep -n "register(new" src/main/java/com/minion/Main.java src/main/java/com/minion/core/agent/AgentLoop.java
# 3) 常量核对
grep -n "DEFAULT_ROUND_LIMIT\|DEFAULT_TIMEOUT\|MAX_OUTPUT\|CONNECT_TIMEOUT\|READ_TIMEOUT" \
  src/main/java/com/minion/core/agent/AgentLoop.java src/main/java/com/minion/core/tools/BashTool.java src/main/java/com/minion/core/llm/DeepSeekClient.java
```

Expected: 类名全部命中；注册点行号与文中一致（Main.java:55-82 内、AgentLoop.java:79）；常量值一致（10000 / 120 / 30000 / 30 / 300）。

- [ ] **Step 3: 提交**

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: architecture map (package structure, extension points, constants)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 创建 docs/CONVENTIONS.md（开发规约）

**Files:**
- Create: `docs/CONVENTIONS.md`

**Interfaces:**
- Produces: 文件 `docs/CONVENTIONS.md`（Task 3 的 CLAUDE.md 将引用它）；文档中链接 `ARCHITECTURE.md`

- [ ] **Step 1: 创建文件，写入以下完整内容**

````markdown
# minion 开发规约

> 类路径与分层图见 [ARCHITECTURE.md](ARCHITECTURE.md)。本文聚焦写码与协作规范。

## 1. 类路径与分层

- 新代码落位：工具 → `core/tools`；界面/命令 → `cli`；模型交互 → `core/llm`；通用能力 → 按职责进 core 子包
- 依赖方向：`cli` 可依赖 `core` 任意包；core 内包之间经接口 + 构造注入协作；新增循环依赖须在设计文档说明理由
- 跨包访问仅经 public 接口；工具统一实现 `Tool` 接口，不另起机制
- 工具注册：普通工具在 `Main`；会话相关（TodoWrite/Task）在会话创建处注册

## 2. 代码风格与质量

### JDK 8 兼容

- 不引入 JDK9+ API（`var`、`List.of`、`Optional.stream` 等）；pom 已设 `maven.compiler.source/target=1.8`

### 依赖约束

- 新依赖必须 JDK8 兼容、避免 Kotlin/重量级依赖（okhttp 选 3.x 而非 4.x 是既有教训）
- 加依赖须在设计文档写明理由

### 错误处理

- LLM/网络错误：抛 `LlmException`，上层统一处理
- 工具错误：返回失败态 `ToolResult`（模型可据错误信息自调方案），不把原始异常抛给界面
- 用户取消/拒绝：返回明确失败信息，让模型调整方案

### 配置

- 新增配置项必须同步：`src/resource/config.properties` 默认值 + 首次运行外部生成逻辑（Config 与 README 同步）

### 外部 API 契约（防回归教训）

- DeepSeek 思考模式：历史 assistant 消息的 `reasoning_content` 必须原样回传，否则 400
- tool_call ↔ tool 结果消息必须完整配对回传，拆散会 400
- 修改 Message 序列化格式时保持这两条不变

### 其他

- 写死常量集中在类顶部 `static final` 并加注释；改动走设计讨论（见 ARCHITECTURE.md §6）
- 注释与文档用中文；公共 API 的 javadoc 简要说明用途与调用方式

## 3. AI 协作规约（Claude Code 开发流程）

- **设计先行**：功能先写 `docs/superpowers/specs/YYYY-MM-DD-<主题>-design.md` → 用户确认 → writing-plans 出实施计划 → 按计划实施
- 动手前先读相关文件与既有设计文档；存在适用的 superpowers 技能必须先调用
- 完成前自查：`mvn compile` 通过 + 相关测试通过；改动同步更新 README 与设计文档
- Commit：conventional 格式（`feat/fix/docs/debug/chore` + 中文描述），一个逻辑改动一个 commit
- 不自证完成：验证（编译/测试/运行）通过才算完成
````

- [ ] **Step 2: 核对内容完整性**

对照 spec 检查（`docs/superpowers/specs/2026-08-09-project-baseline-design.md` §3）：三个章节齐全——「类路径与分层」（§3.1）、「代码风格与质量」（§3.2，含 JDK8/依赖/错误处理/配置/API 契约）、「AI 协作规约」（§3.3）；与 ARCHITECTURE.md 无矛盾（依赖方向、常量位置表述一致）。

- [ ] **Step 3: 提交**

```bash
git add docs/CONVENTIONS.md
git commit -m "docs: development conventions (layering, style, AI collaboration)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 创建根目录 CLAUDE.md（精简入口）

**Files:**
- Create: `CLAUDE.md`（仓库根目录）

**Interfaces:**
- Consumes: `docs/ARCHITECTURE.md`（Task 1）、`docs/CONVENTIONS.md`（Task 2）——CLAUDE.md 中的链接必须指向这两个已存在文件

- [ ] **Step 1: 创建文件，写入以下完整内容**

````markdown
# CLAUDE.md — minion 开发指引

minion：类 Claude Code 的命令行代码开发助手，Java 实现，对接 DeepSeek（thinking max）。
JDK 8 + Maven 单模块。依赖：gson、okhttp 3.x、jline、snakeyaml（测试：junit4、mockwebserver）。

## 常用命令

    mvn clean package         # 构建（产物 target/minion-0.1.0.jar，含依赖）
    mvn test                  # 运行测试
    minion                    # 交互模式；minion -c "任务" 单次执行；minion -r 恢复会话

## 包结构（详见 docs/ARCHITECTURE.md）

    com.minion
    ├── Main         入口：参数解析、工具注册（8 个，Main.java:55-82）
    ├── cli/         REPL、Renderer、CommandDispatcher（/命令）、确认提示
    └── core/
        ├── agent/   AgentLoop（主循环）、SubAgentLoop、Session、TodoList
        ├── llm/     DeepSeekClient（SSE 流式）、Message（reasoningContent 原样回传）
        ├── tools/   Tool 接口 + 9 个工具 + ToolRegistry、SchemaGenerator、ConfirmGate、PathsGuard
        ├── skills/  SkillManager（skills/<名>/SKILL.md 自动发现）
        ├── context/ 上下文压缩、token 统计
        ├── storage/ 会话落盘  ├── config/ Config  └── util/ Ansi、ConsoleIo

## 扩展点

- 新增工具：实现 `Tool`（name/description/schema/execute/isHighRisk）→ Main 注册；高危加 isHighRisk
- 新增技能：`skills/<名>/SKILL.md` + YAML frontmatter（name/description/metadata），无需代码
- 新增 /命令：cli/CommandDispatcher 加 case

## 核心规约（详见 docs/CONVENTIONS.md）

1. JDK 8 兼容；新依赖必须 JDK8 兼容且在设计文档写明理由
2. 新代码落位：工具→core/tools、界面→cli、模型→core/llm；core 内经接口+构造注入，不新增循环依赖
3. 错误处理：LLM 错误抛 LlmException；工具错误返回失败 ToolResult 给模型自调
4. API 契约（防回归）：reasoning_content 原样回传；tool_call↔tool 消息完整配对，否则 400
5. 新增配置项同步 src/resource/config.properties 默认值与外部生成逻辑
6. 设计先行：功能先写 docs/superpowers/specs/<日期>-<主题>-design.md，用户确认后再实施
7. 完成前自查：mvn compile + 相关测试通过；改动同步更新 README 与设计文档

## 文档与约定

- 架构/类路径：docs/ARCHITECTURE.md；开发规约：docs/CONVENTIONS.md；使用说明：README.md
- 资源目录是 src/resource（非 src/main/resources，pom 已配置）
- 文档、注释、commit 均用中文（commit 用 conventional 格式）
- 设计文档在 docs/superpowers/specs/，实施计划在 docs/superpowers/plans/
````

- [ ] **Step 2: 验证**

```bash
# 行数 ≤ 100
wc -l CLAUDE.md
# 引用的文件存在
ls docs/ARCHITECTURE.md docs/CONVENTIONS.md README.md
# 引用的类/注册点可核对（抽查 Task 1 的核对结果应一致）
grep -n "register(new" src/main/java/com/minion/Main.java | head -3
```

Expected: 行数 ≤ 100；四个文件均存在；Main 注册点行号在 55-82 范围内。

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: root CLAUDE.md entry (structure + conventions summary)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## 自检记录

- **Spec 覆盖**：spec §2（ARCHITECTURE.md 六节）→ Task 1；spec §3（CONVENTIONS.md 三节）→ Task 2；spec §4（CLAUDE.md）→ Task 3；spec §5 验收标准（三份产物、内容与代码一一对应、CLAUDE.md ≤100 行、不改已有文件）→ 各任务 Step 2 核对 + Global Constraints
- **占位符扫描**：无 TBD/TODO；每份文档的完整内容已内嵌于 Step 1，核对命令在 Step 2 给出
- **一致性**：三份文档共用同一事实集（注册点 Main.java:55-82 与 AgentLoop.java:79、常量 10000/120/30000/30/300、9 个工具清单、依赖方向、扩展点），Task 3 引用的文件路径与 Task 1/2 产物一致
