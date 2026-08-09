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
        ├── storage/ 会话落盘
        ├── config/  Config
        └── util/    Ansi、ConsoleIo

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
