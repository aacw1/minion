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
    /** 上下文压缩进行中（true=开始，false=结束；同步阻塞压缩前后成对发出） */
    default void onCompressingChanged(boolean compressing) { }
    /** 上下文统计推送（used/max = 估算 token；关键节点：消息入历史/回复完成/工具结果/压缩完成/轮次结束） */
    default void onContextStats(int used, int max) { }
    /** 429 长重试进度（attempt ≥ 1 进入/更新重试态，显示"429限流，正在重试中...N次"；attempt == 0 退出重试态恢复轮换） */
    default void onRetryProgress(int attempt) { }
}
