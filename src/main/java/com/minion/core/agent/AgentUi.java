package com.minion.core.agent;

public interface AgentUi {
    default void onUserMessage(String text) { }
    default void onThinking(String delta) { }
    default void onContent(String delta) { }
    default void onToolCall(String name, com.google.gson.JsonObject args) { }
    default void onToolResult(String name, com.minion.core.tools.ToolResult result) { }
    default void onSubAgentStart(int no, String description) { }
    /** 子代理思考增量（渲染为【子代理N】思考段；与主代理 onThinking 分道，防串台） */
    default void onSubAgentThinking(int no, String delta) { }
    /** 子代理正文增量（渲染为【子代理N】回复段） */
    default void onSubAgentDelta(int no, String delta) { }
    default void onSubAgentToolCall(int no, String name, com.google.gson.JsonObject args) { }
    default void onSubAgentToolResult(int no, String name, com.minion.core.tools.ToolResult result) { }
    default void onSubAgentDone(int no, String summary) { }
    /** 子代理运行提示（重试/中断/异常等；no 用于消息区【子代理N】标识，不驱动主代理重试指示器） */
    default void onSubAgentNotice(int no, String message) { }
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
    /** 瞬时错误长重试进度（attempt ≥ 1 进入/更新重试态，httpCode/body 为最近一次失败信息；
     *  attempt == 0 退出重试态恢复轮换） */
    default void onRetryProgress(RetryProgress p) { }
}
