package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 脚本化测试桩：addTurn/addTurnWithTools 依次出牌；completeChat 返回 compressResult */
public class FakeLlmClient implements LlmClient {

    public String compressResult = "【摘要】历史对话要点";
    /** 置 true 时 completeChat 抛 LlmException，模拟压缩请求异常（T16 Round1） */
    public boolean throwOnCompleteChat = false;
    private final List<ScriptedTurn> turns = new ArrayList<ScriptedTurn>();
    private int cursor = 0;
    public List<Message> lastRequestMessages = new ArrayList<Message>();
    /** 最近一次 streamChat 请求携带的工具 schema（T15 起记录） */
    public List<JsonObject> lastRequestTools = new ArrayList<JsonObject>();
    /** 每次 streamChat 请求的完整记录（消息 + 工具 schema），供测试断言请求序列 */
    public final List<RequestRecord> requests = new ArrayList<RequestRecord>();

    public static class ScriptedTurn {
        public final List<ToolCall> toolCalls;
        public final String content;
        public final String thinking;
        public ScriptedTurn(List<ToolCall> toolCalls, String content) {
            this(toolCalls, content, null);
        }
        public ScriptedTurn(List<ToolCall> toolCalls, String content, String thinking) {
            this.toolCalls = toolCalls;
            this.content = content;
            this.thinking = thinking;
        }
    }

    public static class RequestRecord {
        public final List<Message> messages;
        public final List<JsonObject> tools;
        public RequestRecord(List<Message> messages, List<JsonObject> tools) {
            this.messages = new ArrayList<Message>(messages);
            this.tools = tools == null ? new ArrayList<JsonObject>() : new ArrayList<JsonObject>(tools);
        }
    }

    public void addTurn(String content) { turns.add(new ScriptedTurn(null, content)); }

    public void addTurnWithTools(List<ToolCall> toolCalls, String content) {
        turns.add(new ScriptedTurn(toolCalls, content));
    }

    public void addTurnWithTools(List<ToolCall> toolCalls, String content, String thinking) {
        turns.add(new ScriptedTurn(toolCalls, content, thinking));
    }

    @Override
    public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) {
        lastRequestMessages = new ArrayList<Message>(messages);
        lastRequestTools = tools == null ? new ArrayList<JsonObject>() : new ArrayList<JsonObject>(tools);
        requests.add(new RequestRecord(messages, tools));
        ScriptedTurn turn = turns.get(Math.min(cursor, turns.size() - 1));
        cursor++;
        Usage u = new Usage();
        u.inputTokens = 10;
        u.outputTokens = 5;
        // thinking 先于 content/tool_calls 回调（与 DeepSeek SSE 顺序一致）
        if (turn.thinking != null) handler.onThinking(turn.thinking);
        if (turn.toolCalls != null && !turn.toolCalls.isEmpty()) {
            handler.onFinish("tool_calls", u, turn.toolCalls);
        } else {
            handler.onContent(turn.content);
            handler.onFinish("stop", u, new ArrayList<ToolCall>());
        }
    }

    @Override
    public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
        if (throwOnCompleteChat) {
            throw new LlmException(LlmException.Type.OTHER, "模拟压缩请求失败", false);
        }
        // 与 DeepSeekClient.completeChat 一致：system 置前，记录完整请求供断言
        List<Message> all = new ArrayList<Message>();
        all.add(Message.system(systemPrompt));
        all.addAll(messages);
        lastRequestMessages = new ArrayList<Message>(all);
        return compressResult;
    }

    /** close() 被调用次数（会话删除/退出时资源释放断言） */
    public int closeCount = 0;

    @Override
    public void close() {
        closeCount++;
    }
}
