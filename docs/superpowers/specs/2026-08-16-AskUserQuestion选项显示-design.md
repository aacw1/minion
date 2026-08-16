# minion AskUserQuestion 选项显示 设计

日期：2026-08-16
状态：已确认（用户：先修复，问题1 ❌ 失败打印无复现暂缓）

## 背景

模型调用 `AskUserQuestion` 提问时，消息区只显示「❓ 模型向你提问\n<question>」——
`options` 参数（2-4 个 label/description 选项）从未被渲染。superpowers 流程中模型问
"用哪种方式执行？"并附选项（如 subagent-driven-development / executing-plans），
用户只看得到问题文本、看不到选项列表，无从选择。

## 现状分析（根因）

[ChatView.java](src/main/java/com/minion/gui/chat/ChatView.java) `askQuestionOf(Object data)`
只解析 `question` 字段返回，`options` 数组被忽略；TOOL_CALL 分支渲染为
`"❓ 模型向你提问\n" + askQuestionOf(e.data)`。

## 方案

### 1. askQuestionOf 渲染 question + options

```java
/** AskUserQuestion 工具调用参数 → 展示文本：问题 + 选项列表（2-4 个，label/description；解析失败回空串） */
static String askQuestionOf(Object data) {
    // question 文本；
    // options 数组逐项渲染 "[N] label — description"（每项一行；description 缺失只显示 label；
    //   非对象元素跳过；数字编号 1 起）
}
```

- `private` → `package-private`（与 StreamBuffer 同模式，纯逻辑可单测）
- 无 options 时输出与现状一致（仅 question），无行为回归
- 解析失败仍回空串（与现状一致）

### 2. 渲染位置

ChatView TOOL_CALL 分支不变：`"❓ 模型向你提问\n" + askQuestionOf(e.data)`，
options 自然出现在问题下方。InputView 回答模式 prompt 不改（消息区已完整展示选项，
输入框摘要无增量价值；跨线程传 options 徒增复杂度，YAGNI）。

## 测试

`ChatViewAskQuestionOfTest`（或并入 ChatViewStreamBufferTest 同目录）：

1. 仅 question → 返回问题文本（回归）
2. question + 2 个 options（label+description）→ "问题\n[1] A — 说明A\n[2] B — 说明B"
3. options 缺 description → 只显示 "[N] label"
4. 非法 JSON → 空串（回归）

## 影响面

- 纯显示层：仅 ChatView.askQuestionOf 一个方法；不改变 LLM 消息历史与工具行为
- AskUserQuestionTool schema 已含 options（2-4 个 label/description），无需变更
