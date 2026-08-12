package com.minion.gui.session;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;
import com.minion.core.tools.ToolResult;

/** AgentUi → EventList 路由：会话级事件缓冲 */
public class SessionController implements AgentUi {

    private final EventList events = new EventList();

    public EventList eventList() { return events; }

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
}
