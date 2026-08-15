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
    /** 运行中用户补充（UI 事件在点击时发一次；历史注入不再发） */
    default void onUserSupplement(String text) { }
    /** AskUserQuestion 工具开始挂起等待回答 */
    default void onAskUserStart(String question) { }
    /** AskUserQuestion 收到回答（answer 为回答文本；中断路径不回调，由运行态复位兜底） */
    default void onAskUserDone(String answer) { }
}
