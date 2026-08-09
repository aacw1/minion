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
