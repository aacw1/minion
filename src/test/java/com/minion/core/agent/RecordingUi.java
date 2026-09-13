package com.minion.core.agent;

import com.minion.core.llm.Usage;
import com.minion.core.tools.ToolResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class RecordingUi implements AgentUi {
    public final List<String> contentParts = new ArrayList<String>();
    public final List<String> thinking = new ArrayList<String>();
    public final List<String> toolCalls = new ArrayList<String>();
    public final List<String> toolResults = new ArrayList<String>();
    public final List<String> warnings = new ArrayList<String>();
    public final List<String> errors = new ArrayList<String>();
    public final List<Usage> usages = new ArrayList<Usage>();
    public final List<String> statsLines = new ArrayList<String>();
    public final List<String> asksStarted = new ArrayList<String>();
    public final List<String> asksDone = new ArrayList<String>();
    public final List<String> supplements = new ArrayList<String>();
    public final List<String> userMessages = new ArrayList<String>();

    // 并行工具执行时 onToolCall 来自多个线程，需同步（ArrayList 非线程安全）
    @Override public synchronized void onUserMessage(String text) { userMessages.add(text); }
    @Override public synchronized void onThinking(String delta) { thinking.add(delta); }
    @Override public synchronized void onContent(String delta) { contentParts.add(delta); }
    @Override public synchronized void onToolCall(String name, JsonObject args) { toolCalls.add(name); }
    @Override public synchronized void onToolResult(String name, ToolResult result) { toolResults.add(name); }
    @Override public synchronized void onWarning(String message) { warnings.add(message); }
    @Override public synchronized void onError(String message) { errors.add(message); }
    @Override public synchronized void onStatsLine(String line) { statsLines.add(line); }
    @Override public synchronized void onAskUserStart(String question) { asksStarted.add(question); }
    @Override public synchronized void onAskUserDone(String answer) { asksDone.add(answer); }
    @Override public synchronized void onUserSupplement(String text) { supplements.add(text); }

    public final List<Boolean> compressing = new ArrayList<Boolean>();

    @Override public synchronized void onCompressingChanged(boolean compressing) {
        this.compressing.add(compressing);
    }

    public final List<int[]> ctxStats = new ArrayList<int[]>();

    @Override public synchronized void onContextStats(int used, int max) {
        ctxStats.add(new int[]{used, max});
    }

    public final List<RetryProgress> retryProgress = new ArrayList<RetryProgress>();

    /** 瞬时错误长重试进度（attempt ≥ 1 进入/更新；0 退出） */
    @Override public synchronized void onRetryProgress(RetryProgress p) { retryProgress.add(p); }

    /** 重试次数序列（attempt 值，0=复位），供既有断言使用 */
    public synchronized List<Integer> retryAttempts() {
        List<Integer> out = new ArrayList<Integer>();
        for (RetryProgress p : retryProgress) out.add(p.attempt);
        return out;
    }

    // ===== 子代理专用通道（设计 2026-09-13：与主代理分道；主通道断言空 = 未串台）=====
    public final List<String> subStarts = new ArrayList<String>();     // "编号:描述"
    public final List<String> subThinking = new ArrayList<String>();
    public final List<String> subDeltas = new ArrayList<String>();
    public final List<String> subToolCalls = new ArrayList<String>();  // "编号:工具名"
    public final List<String> subToolResults = new ArrayList<String>();
    public final List<String> subDones = new ArrayList<String>();
    public final List<String> subNotices = new ArrayList<String>();

    @Override public synchronized void onSubAgentStart(int no, String d) { subStarts.add(no + ":" + d); }
    @Override public synchronized void onSubAgentThinking(int no, String d) { subThinking.add(d); }
    @Override public synchronized void onSubAgentDelta(int no, String d) { subDeltas.add(d); }
    @Override public synchronized void onSubAgentToolCall(int no, String n, JsonObject a) { subToolCalls.add(no + ":" + n); }
    @Override public synchronized void onSubAgentToolResult(int no, String n, ToolResult r) { subToolResults.add(no + ":" + n); }
    @Override public synchronized void onSubAgentDone(int no, String s) { subDones.add(no + ":" + s); }
    @Override public synchronized void onSubAgentNotice(int no, String m) { subNotices.add(m); }
}
