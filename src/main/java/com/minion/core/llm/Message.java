package com.minion.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** 会话消息。reasoningContent 必须持久化并在回传时带上（DeepSeek 400 要求）。 */
public class Message {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Role role;
    public String content;
    public String reasoningContent;   // 仅 assistant
    public List<ToolCall> toolCalls;  // 仅 assistant
    public String toolCallId;         // 仅 tool
    public String name;               // 仅 tool（工具名）
    public boolean summary;           // true = 压缩摘要消息，不再参与压缩
    /** true = 运行中用户补充消息（仅 user 角色使用）。标识只在本地历史与 UI 层，
     *  toApiJson 不输出——API content 原样发送，模型输入零污染 */
    public boolean supplement;
    /** 消息创建时间戳（毫秒；0 = 旧数据未打点） */
    public long ts;

    public static Message system(String content) {
        Message m = new Message();
        m.role = Role.SYSTEM;
        m.content = content;
        m.ts = System.currentTimeMillis();
        return m;
    }

    public static Message user(String content) {
        Message m = new Message();
        m.role = Role.USER;
        m.content = content;
        m.ts = System.currentTimeMillis();
        return m;
    }

    /** 运行中用户补充：USER 角色 + supplement=true（检查点注入/下次发送合并时使用） */
    public static Message userSupplement(String content) {
        Message m = user(content);
        m.supplement = true;
        return m;
    }

    public static Message assistant(String content) {
        Message m = new Message();
        m.role = Role.ASSISTANT;
        m.content = content;
        m.ts = System.currentTimeMillis();
        return m;
    }

    public static Message toolResult(String toolCallId, String name, String content) {
        Message m = new Message();
        m.role = Role.TOOL;
        m.toolCallId = toolCallId;
        m.name = name;
        m.content = content;
        m.ts = System.currentTimeMillis();
        return m;
    }

    /** 请求体消息 JSON。content 为 null 时不输出（assistant 工具调用消息无 content） */
    public JsonObject toApiJson() {
        JsonObject o = new JsonObject();
        o.addProperty("role", role.name().toLowerCase());
        if (content != null) o.addProperty("content", content);
        if (role == Role.ASSISTANT) {
            if (reasoningContent != null) o.addProperty("reasoning_content", reasoningContent);
            if (toolCalls != null && !toolCalls.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (ToolCall tc : toolCalls) arr.add(tc.toApiJson());
                o.add("tool_calls", arr);
            }
        }
        if (role == Role.TOOL) {
            o.addProperty("tool_call_id", toolCallId);
            o.addProperty("name", name);
        }
        return o;
    }
}
