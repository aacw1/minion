package com.minion.core.agent;

public interface AgentUi {
    default void onUserMessage(String text) { }
    default void onThinking(String delta) { }
    default void onContent(String delta) { }
    default void onToolCall(String name, com.google.gson.JsonObject args) { }
    default void onToolResult(String name, com.minion.core.tools.ToolResult result) { }
    default void onSubAgentStart(String description) { }
    default void onSubAgentDelta(String delta) { }
    default void onSubAgentDone(String summary) { }
    default void onStatsLine(String line) { }
    default void onError(String message) { }
    default void onWarning(String message) { }
}
