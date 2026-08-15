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
    /** 图片 parts（仅 user 消息使用，其他角色忽略）；null = 无图 */
    public List<ImagePart> images;
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

    /** user 消息带图：images 为 null/空时行为与 user() 一致 */
    public static Message userWithImages(String content, List<ImagePart> images) {
        Message m = user(content);
        m.images = images;
        return m;
    }

    /** 运行中用户补充：USER 角色 + supplement=true（检查点注入/下次发送合并时使用） */
    public static Message userSupplement(String content) {
        Message m = user(content);
        m.supplement = true;
        return m;
    }

    /** 运行中用户补充（带图）：USER 角色 + supplement=true + images */
    public static Message userSupplement(String content, List<ImagePart> images) {
        Message m = userWithImages(content, images);
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
        // 图片消息：content 输出 OpenAI 视觉协议数组（text + image_url）；无图保持纯字符串零回归
        if (role == Role.USER && images != null && !images.isEmpty()) {
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", content == null ? "" : content);
            parts.add(textPart);
            for (ImagePart ip : images) {
                if (ip == null || ip.mime == null || ip.base64 == null) continue;
                JsonObject img = new JsonObject();
                img.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:" + ip.mime + ";base64," + ip.base64);
                img.add("image_url", url);
                parts.add(img);
            }
            o.add("content", parts);
        } else if (content != null) {
            o.addProperty("content", content);
        }
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
