# minion ask_user 工具改名为 AskUserQuestion 设计

日期：2026-08-16
状态：已确认（用户补充：Java 类名一并改为 AskUserQuestionTool；**不做旧名兼容**，便于公开 skill 识别）

## 背景

用户要求 ask_user 工具改名为与 Claude Code 一致（Claude Code 中对应工具名为 `AskUserQuestion`）。
当前工具对外名 `ask_user`（AskUserTool.name()），模型 prompt、渲染判断、子 agent 防御均按该名匹配。

## 方案

### 1. 重命名

- `AskUserTool.java` → `AskUserQuestionTool.java`（git mv 保留历史），类名 `AskUserQuestionTool`，`name()` 返回 `"AskUserQuestion"`
- `AskUserToolTest.java` → `AskUserQuestionToolTest.java` 同步
- ToolRegistry 以小写索引（`name().toLowerCase()`），`get("AskUserQuestion")` / `get("askuserquestion")` 均命中，无改动
- schema 的 name 随 `schemas()` 生成，新请求即生效

### 2. 匹配点更新（不兼容旧名，全量替换）

| 位置 | 现状 | 改后 |
|---|---|---|
| ChatView TOOL_CALL 渲染判断 | `"ask_user".equals(e.text)` | `"AskUserQuestion".equals(e.text)` |
| SessionController replayHistory | `"ask_user".equals(m.name)` | `"AskUserQuestion".equals(m.name)` |
| SubAgentLoop schema 过滤 | `"ask_user".equals(...)` | `"AskUserQuestion".equals(...)` |
| SubAgentLoop 同名防御 | `"ask_user".equals(call.name)` + 错误消息 | `"AskUserQuestion"`；错误消息文案同步 |
| SystemPromptBuilder prompt | 三处 `ask_user` 文案 | `AskUserQuestion` |
| AgentLoop 字段/构造 | `com.minion.core.tools.AskUserTool` | `AskUserQuestionTool` |
| 注释（AgentUi/SessionManager/SessionHandle/InputView 等） | ask_user 字样 | AskUserQuestion |

### 3. 历史会话

不兼容旧名：已落盘会话中 `name="ask_user"` 的 TOOL 消息重演时不再特判回答行（按普通工具显示 ✅）。
用户明确不需要兼容（公开 skill 按新名识别）；内存挂起状态不落盘，无残留。

### 4. 测试更新

- AgentLoopTest：`registry.get("ask_user")` → `get("AskUserQuestion")`、q.name 同步
- SessionManagerTest / SubAgentLoopTest / SessionControllerTest / SystemPromptBuilderTest：工具名与断言同步
- AskUserQuestionToolTest：类名同步（无 name 字符串断言）

## 影响面

- LLM 请求中的 function name 由 `ask_user` 变 `AskUserQuestion`（DeepSeek 支持大小写混合名）
- 渲染、prompt、防御、重演全部走新名；旧会话 ask_user 回答行不再特判显示
