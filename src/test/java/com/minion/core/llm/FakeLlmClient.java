package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 脚本化测试桩：addTurn/addTurnWithTools 依次出牌；completeChat 返回 compressResult */
public class FakeLlmClient implements LlmClient {

    public String compressResult = "【摘要】历史对话要点";
    /** 置 true 时 completeChat 抛 LlmException，模拟压缩请求异常（T16 Round1） */
    public boolean throwOnCompleteChat = false;
    /** 置入序号（1 起）的调用返回空串模拟压缩失败；其余返回 compressResult */
    public final List<Integer> failAtCompleteChat = new ArrayList<Integer>();
    /** 每次 completeChat 的 user 消息文本（最后一条），供断言批次内容 */
    public final List<String> completeChatRequests = new ArrayList<String>();
    private int completeChatCalls = 0;
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
        public final LlmException error;
        public final boolean throwOnCall; // true = streamChat 直接抛 error（响应码异常路径）
        /** 非 null：抛错/回调前先吐这段增量（模拟流式中途断流） */
        public final String partialContent;

        public ScriptedTurn(List<ToolCall> toolCalls, String content) {
            this(toolCalls, content, null, null, false);
        }
        public ScriptedTurn(List<ToolCall> toolCalls, String content, String thinking) {
            this(toolCalls, content, thinking, null, false);
        }
        public ScriptedTurn(List<ToolCall> toolCalls, String content, String thinking, LlmException error) {
            this(toolCalls, content, thinking, error, false);
        }
        public ScriptedTurn(List<ToolCall> toolCalls, String content, String thinking,
                            LlmException error, boolean throwOnCall) {
            this(toolCalls, content, thinking, error, throwOnCall, null);
        }
        public ScriptedTurn(List<ToolCall> toolCalls, String content, String thinking,
                            LlmException error, boolean throwOnCall, String partialContent) {
            this.toolCalls = toolCalls;
            this.content = content;
            this.thinking = thinking;
            this.error = error;
            this.throwOnCall = throwOnCall;
            this.partialContent = partialContent;
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

    /** 脚本化失败：streamChat 时以 onError 回调报错（模拟 API 400/500 等） */
    public void addTurnError(String message) {
        turns.add(new ScriptedTurn(null, null, null,
                new LlmException(LlmException.Type.OTHER, message, false)));
    }

    /** 脚本化 throw：streamChat 直接抛异常（模拟 429 等响应码错误，走调用方 catch 路径） */
    public void addTurnThrow(LlmException error) {
        turns.add(new ScriptedTurn(null, null, null, error, true));
    }

    /** 脚本化"已吐字后断流"：回调增量后抛异常（验证零增量闸门拦截长重试/快速重试） */
    public void addTurnPartialThenThrow(String delta, LlmException error) {
        turns.add(new ScriptedTurn(null, null, null, error, true, delta));
    }

    @Override
    public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException {
        lastRequestMessages = new ArrayList<Message>(messages);
        lastRequestTools = tools == null ? new ArrayList<JsonObject>() : new ArrayList<JsonObject>(tools);
        requests.add(new RequestRecord(messages, tools));
        ScriptedTurn turn = turns.get(Math.min(cursor, turns.size() - 1));
        cursor++;
        if (turn.error != null) {
            // 先吐增量再失败：与 DeepSeekClient 真实形态一致（readUtf8Line 抛 IOException → throw LlmException）
            if (turn.partialContent != null) handler.onContent(turn.partialContent);
            if (turn.throwOnCall) throw turn.error;
            handler.onError(turn.error);
            return;
        }
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
        completeChatCalls++;
        String batch = messages.isEmpty() ? ""
                : (messages.get(messages.size() - 1).content == null ? "" : messages.get(messages.size() - 1).content);
        completeChatRequests.add(batch);
        if (failAtCompleteChat.contains(completeChatCalls)) return "";
        return compressResult;
    }

    /** close() 被调用次数（会话删除/退出时资源释放断言） */
    public int closeCount = 0;

    @Override
    public void close() {
        closeCount++;
    }
}
