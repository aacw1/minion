# minion ask_user 回答显示 设计

日期：2026-08-16
状态：已实施（2026-08-16，全量 452 测试通过；回答显示【输入】标签为用户确认方案）

## 背景

模型调用 `ask_user` 工具时，消息区显示「❓ 模型向你提问\n<问题>」（ChatView TOOL_CALL 分支）；但用户在输入区输入的回答**不显示**——消息区只有提问没有回答，对话记录不完整。

## 现状分析（根因）

回答数据流：

```
用户输入 → InputView 回答模式 → SessionManager.sendAnswer
  → AgentLoop.answerAskUser → AskUserTool.complete(answer)
  → fut.complete(answer) → execute 的 fut.get() 返回 → ui.onAskUserDone(answer)
  → SessionController.onAskUserDone(answer) → 仅 askStateListener.accept(null) 复位输入区状态
```

`onAskUserDone` 收到回答文本后**只复位状态、文本未入 EventList**，因此 ChatView 无从渲染。

## 方案（用户确认版：回答复用【输入】标签，不新增事件 Kind）

### 1. 回答投递事件流

[SessionController.java](src/main/java/com/minion/gui/session/SessionController.java) `onAskUserDone(String answer)`：

```java
@Override public void onAskUserDone(String answer) {
    if (askStateListener != null) askStateListener.accept(null);
    if (answer != null && !answer.isEmpty()) {
        onUserSupplement(answer);
    }
}
```

（状态复位保留；空回答不投递，与「输入为空不发」一致。回答以 USER_SUPPLEMENT 事件入流，ChatView 现有【输入】分支直接渲染，零界面改动。）

### 2. ChatView / 流式缓冲

无需改动：`USER_SUPPLEMENT` 已有【输入】渲染与轮次边界重置。

### 3. 历史重演

`replayHistory`：回答文本在会话 JSON 的 TOOL 消息 `output` 中（AskUserTool 把回答作为工具结果返回落盘），对 `name="ask_user"` 的 TOOL 消息重演回答：

```java
} else if (m.role == Message.Role.TOOL && m.name != null) {
    if ("ask_user".equals(m.name) && m.content != null && !m.content.trim().isEmpty()) {
        events.add(new EventList.Ev(EventList.Kind.USER_SUPPLEMENT, m.content, null));
    }
    events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, m.name, "ok"));
}
```

恢复会话后提问与回答成对显示（先回答行后 ✅ 行，与运行时顺序一致），与运行时一致。

## 测试

1. `SessionControllerTest`（或现有对应测试）：`onAskUserDone` 投递 USER_SUPPLEMENT 事件（kind/text 正确）、空回答不投递、状态复位仍触发
2. `replayHistory`：ask_user 的 TOOL 消息重演 USER_SUPPLEMENT 回答（先回答行后 TOOL_RESULT）；普通工具消息不受影响

## 影响面

- 事件流新增一种 Kind：ChatView 有 default 兜底，其余消费方（InputView 等按 Kind 匹配）不受影响
- 不改变 LLM 消息历史（回答仍仅存于 TOOL 消息 output，事件流为纯显示层）
