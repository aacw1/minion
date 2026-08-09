# 启动信息横幅 + 系统提示词优化 — 设计文档

日期：2026-08-09

## 需求

1. **启动信息横幅**：交互模式启动时，打印以下信息，每行一条：
   - 模型名
   - 最大上下文大小
   - 工作空间
   - 项目说明文件路径
   - 技能地址
   - 消息存储地址
2. **系统提示词优化**：更适合代码开发；用户输入不明确时不要猜测意图，先让用户补充信息。

## 设计决策（已与用户确认）

### 需求1：启动信息横幅

- **实现方式**：新增独立格式化器 `StartupBanner`（纯函数，可单元测试），在 `Repl.start()` 欢迎横幅前打印。
- **适用范围**：仅交互模式（REPL）打印；`-c` 脚本模式不打印，stdout 保持干净。
- **路径显示**：全部解析为绝对路径（`Paths.get(x).toAbsolutePath()`）；目录或文件不存在时追加 `(未创建)` 标注。
- **输出格式**（示例，实际以运行环境为准）：

  ```
  模型: deepseek-v4-flash
  上下文上限: 900000 tokens
  工作空间: E:\javame\code\minion
  项目说明: E:\javame\code\minion\project.md (未创建)
  技能目录: E:\javame\code\minion\skills (未创建)
  会话存储: E:\javame\code\minion\.minion\sessions
  ```

- **样式**：沿用现有青色加粗（`Renderer.wrapBanner`），与欢迎横幅一致。
- 数据来源均为 `Config` 现有 getter：`modelName()`、`maxContextTokens()`、`workDir()`、`projectMdPath()`、`skillsDir()`、`sessionDir()`。

### 需求2：系统提示词

- **实现方式**：在 `SystemPromptBuilder.BUILTIN` 规则列表**插入**一条新规则作为第 1 条（最显眼位置），原规则 1-5 顺延为 2-6，措辞与现有风格一致。
- **新规则文本**：

  > 1. 用户指令不明确、信息不足或存在多种可能理解时，先列出需要补充的问题，等待用户回答后再行动；不要猜测用户意图。

- 不做整体重写、不加配置项（用户未选择其他规则，YAGNI）。

## 组件改动

| 文件 | 改动 |
|---|---|
| `src/main/java/com/minion/cli/StartupBanner.java` | **新增**：`format(Config)` 返回多行字符串 |
| `src/main/java/com/minion/cli/Repl.java` | `start()` 欢迎横幅前调用 `StartupBanner` 打印 |
| `src/main/java/com/minion/core/agent/SystemPromptBuilder.java` | BUILTIN 插入澄清规则为第 1 条 |
| `src/test/java/com/minion/cli/StartupBannerTest.java` | **新增**：验证 6 行输出、绝对路径、`(未创建)` 标注 |
| `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java` | **修改**：新增断言验证澄清规则存在且为第 1 条 |

## 错误处理

- 路径不存在不是错误：显示绝对路径并标注 `(未创建)`，不抛异常。
- `toAbsolutePath()` 不会失败；`Files.exists` 返回 false 时仅影响标注。

## 测试

- `StartupBannerTest`：
  - 输出恰好 6 行，顺序为 模型 → 上下文上限 → 工作空间 → 项目说明 → 技能目录 → 会话存储
  - 路径行为绝对路径
  - 不存在的路径带 `(未创建)`
  - 存在的路径不带标注
- `SystemPromptBuilderTest`：
  - 构建出的系统提示包含澄清规则文本
  - 澄清规则位于规则列表第 1 条
