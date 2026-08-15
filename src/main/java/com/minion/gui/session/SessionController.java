package com.minion.gui.session;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolResult;

import java.util.List;

/** AgentUi → EventList 路由：会话级事件缓冲 */
public class SessionController implements AgentUi {

    private final EventList events = new EventList();

    /** AskUserQuestion 挂起状态回调（非 null=开始挂起并携带问题；null=回答完成），SessionManager 注入 */
    private volatile java.util.function.Consumer<String> askStateListener;

    public void setAskStateListener(java.util.function.Consumer<String> l) { this.askStateListener = l; }

    public EventList eventList() { return events; }

    /** 恢复会话时把历史消息灌入事件流：USER→USER_MESSAGE、ASSISTANT→THINKING/CONTENT/TOOL_CALL、
     *  TOOL→TOOL_RESULT；SYSTEM 跳过。思考与工具过程随历史重演——重启恢复后正文/工具调用不缺失 */
    public void replayHistory(List<Message> messages) {
        for (Message m : messages) {
            if (m == null || m.role == null) continue;
            if (m.role == Message.Role.USER) {
                // 带图消息事件文本拼图片占位（聊天区不渲染图片本体）
                events.add(new EventList.Ev(m.supplement
                        ? EventList.Kind.USER_SUPPLEMENT : EventList.Kind.USER_MESSAGE,
                        ImagePart.displayText(m.images, m.content), null));
            } else if (m.role == Message.Role.ASSISTANT) {
                if (m.content != null && !m.content.trim().isEmpty()) {
                    // 思考内容先于正文重演：ChatView 的【思考】段只由 THINKING 事件驱动，
                    // 不重演则重启恢复后思考丢失（上下文完好，纯显示缺失）
                    if (m.reasoningContent != null && !m.reasoningContent.isEmpty()) {
                        events.add(new EventList.Ev(EventList.Kind.THINKING, m.reasoningContent, null));
                    }
                    events.add(new EventList.Ev(EventList.Kind.CONTENT, m.content, null));
                }
                // 工具调用逐个重演（含纯工具调用无正文消息），参数同运行时 onToolCall 格式
                if (m.toolCalls != null) {
                    for (ToolCall tc : m.toolCalls) {
                        if (tc == null) continue;
                        events.add(new EventList.Ev(EventList.Kind.TOOL_CALL, tc.name,
                                tc.arguments == null ? "{}" : tc.arguments));
                    }
                }
            } else if (m.role == Message.Role.TOOL && m.name != null) {
                // AskUserQuestion 的回答存于 TOOL 消息 output：先重演回答行（USER_SUPPLEMENT【输入】段）
                // 再 ✅ 行，恢复会话后提问与回答成对显示、顺序与运行时一致
                if ("AskUserQuestion".equals(m.name) && m.content != null && !m.content.trim().isEmpty()) {
                    events.add(new EventList.Ev(EventList.Kind.USER_SUPPLEMENT, m.content, null));
                }
                // 历史 TOOL 消息无成败标记（只存 output），统一按成功态重演（与运行时 ✅ 格式一致）
                events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, m.name, "ok"));
            }
        }
    }

    @Override public void onUserMessage(String text) {
        events.add(new EventList.Ev(EventList.Kind.USER_MESSAGE, text, null));
    }
    @Override public void onThinking(String delta) {
        events.add(new EventList.Ev(EventList.Kind.THINKING, delta, null));
    }
    @Override public void onContent(String delta) {
        events.add(new EventList.Ev(EventList.Kind.CONTENT, delta, null));
    }
    @Override public void onToolCall(String name, JsonObject args) {
        events.add(new EventList.Ev(EventList.Kind.TOOL_CALL, name,
                args == null ? "{}" : args.toString()));
    }
    @Override public void onToolResult(String name, ToolResult result) {
        events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, name,
                result == null ? "" : (result.ok ? "ok" : "error:" + result.output)));
    }
    @Override public void onSubAgentStart(String description) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_START, description, null));
    }
    @Override public void onSubAgentDelta(String delta) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DELTA, delta, null));
    }
    @Override public void onSubAgentDone(String summary) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DONE, summary, null));
    }
    @Override public void onStatsLine(String line) {
        events.add(new EventList.Ev(EventList.Kind.STATS, line, null));
    }
    @Override public void onError(String message) {
        events.add(new EventList.Ev(EventList.Kind.ERROR, message, null));
    }
    @Override public void onWarning(String message) {
        events.add(new EventList.Ev(EventList.Kind.WARNING, message, null));
    }
    @Override public void onUserSupplement(String text) {
        events.add(new EventList.Ev(EventList.Kind.USER_SUPPLEMENT, text, null));
    }
    /** 系统行（斜杠命令结果等 GUI 本地事件；非 AgentUi 接口方法，仅命令分发路径使用） */
    public void onSystem(String text) {
        events.add(new EventList.Ev(EventList.Kind.SYSTEM, text, null));
    }
    @Override public void onAskUserStart(String question) {
        if (askStateListener != null) askStateListener.accept(question);
    }
    @Override public void onAskUserDone(String answer) {
        if (askStateListener != null) askStateListener.accept(null);
        // 回答入事件流（USER_SUPPLEMENT 渲染为【输入】段）：消息区提问「❓ 模型向你提问」与回答成对显示；
        // 空回答不投递，与「输入为空不发」一致
        if (answer != null && !answer.isEmpty()) onUserSupplement(answer);
    }
}
