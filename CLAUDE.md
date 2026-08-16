# CLAUDE.md — minion 开发指引

minion：类 Claude Code 的代码开发助手（GUI），Java 实现，对接多供应商 LLM（deepseek/qwen，OpenAI 兼容协议）。
JDK 8 + Maven 单模块。GUI 为唯一界面（JavaFX 8，JDK 自带 jfxrt）。依赖：gson、okhttp 3.14、okhttp-sse 3.14.9、snakeyaml、flexmark 0.62.2（测试：junit4、mockwebserver）。

## 常用命令

    JAVA_HOME="E:/javame/jdk8" mvn clean package   # 构建（产物 target/minion-0.1.0.jar，含依赖；必须 JDK8 含 JavaFX）
    JAVA_HOME="E:/javame/jdk8" mvn test            # 运行测试
    java -jar target/minion-0.1.0.jar                # 启动 GUI（jar 自举：自动开控制台、自动探测/切换 JDK 8；双击 jar 亦可）

## 包结构（详见 docs/ARCHITECTURE.md）

    com.minion
    ├── Boot        自举入口（shade mainClass）：PRISM/控制台/JDK8 探测与自动切换 → Main
    ├── Main         入口：装配配置/技能/浏览器/确认 UI → SessionManager → MinionApp；退出钩子统一收口（manager.shutdown + chrome.stop）
    ├── gui/         JavaFX 界面
    │   ├── MainWindow        主窗口（无边框自绘标题栏 TitleBar、GridPane 25%/75% 不可拖拽、ResizeHelper 缩放、状态点呼吸动画 StatusDot）
    │   ├── MinionApp         Application 启动（静态注入 Config/WorkspaceManager/ModelManager/SessionManager）
    │   ├── sidebar/          SessionListView（会话列表）、WorkspaceListView（工作空间列表）
    │   ├── chat/             ChatView（消息区）、MarkdownRenderer + BlockNodeFactory（flexmark 渲染）
    │   ├── input/            InputView（0.618 黄金比例居中大框+竖分割线+@//补全弹层）、SuggestionPopup、CompletionParser
    │   ├── command/          CommandDispatcher（斜杠命令本地分发，结果入事件流不发 LLM）
    │   ├── dialog/           SettingsDialog（设置窗三页签）、ConfirmSheet（高危确认底部卡片）
    │   ├── theme/            Theme（弹窗深色挂载）
    │   ├── confirm/          GuiConfirmUi（Platform.runLater 投递 ConfirmSheet，poll 等待）
    │   └── session/          SessionManager（多会话并行/工作空间 CRUD）、SessionHandle、EventList
    └── core/
        ├── agent/   AgentLoop（主循环，构造器自动注册 TaskTool/TodoWriteTool）、SubAgentLoop、Session、TodoList
        ├── llm/     DeepSeekClient（SSE 流式，close() 释放 okhttp）、LlmClient 接口（cancel/close 默认空实现）、Message（reasoningContent 原样回传）
        ├── tools/   Tool 接口 + 13 个工具 + ToolRegistry + browser/（CDP 浏览器）、mcp/（McpProxyTool）、SchemaGenerator、ConfirmGate、PathsGuard
        ├── mcp/     MCP 客户端核心：McpManager（状态机/惰性连接/路由）、StdioMcpClient、SseMcpClient、McpStore（mcp.json）、McpServer、JsonRpc
        ├── skills/  SkillManager（skills/<名>/SKILL.md 自动发现）
        ├── context/ 上下文压缩、token 统计
        ├── storage/ 会话落盘（原子写）
        └── config/  Config（config.properties）、WorkspaceManager（workspace.json 原子写）、ModelManager（model.json）

## 扩展点

- 新增工具：实现 `Tool`（name/description/schema/execute/isHighRisk）→ SessionManager.newRegistry 注册；高危加 isHighRisk
- 新增技能：`skills/<名>/SKILL.md` + YAML frontmatter（name/description/metadata），无需代码
- 新增 GUI 界面：gui/ 内新组件；跨线程回调一律 Platform.runLater 包装

## 核心规约（详见 docs/CONVENTIONS.md）

1. JDK 8 兼容；新依赖必须 JDK8 兼容且在设计文档写明理由（flexmark 用 0.62.2，0.64.x 为 Java 11 字节码）
2. 新代码落位：工具→core/tools、界面→gui、模型→core/llm；core 内经接口+构造注入，不新增循环依赖
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
