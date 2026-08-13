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
    public final List<String> asksStarted = new ArrayList<String>();
    public final List<String> asksDone = new ArrayList<String>();
    public final List<String> supplements = new ArrayList<String>();

    // 并行工具执行时 onToolCall 来自多个线程，需同步（ArrayList 非线程安全）
    @Override public synchronized void onThinking(String delta) { thinking.add(delta); }
    @Override public synchronized void onContent(String delta) { contentParts.add(delta); }
    @Override public synchronized void onToolCall(String name, JsonObject args) { toolCalls.add(name); }
    @Override public synchronized void onToolResult(String name, ToolResult result) { toolResults.add(name); }
    @Override public synchronized void onWarning(String message) { warnings.add(message); }
    @Override public synchronized void onError(String message) { errors.add(message); }
    @Override public synchronized void onAskUserStart(String question) { asksStarted.add(question); }
    @Override public synchronized void onAskUserDone(String answer) { asksDone.add(answer); }
    @Override public synchronized void onUserSupplement(String text) { supplements.add(text); }
}
