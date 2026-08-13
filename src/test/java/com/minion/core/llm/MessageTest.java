package com.minion.core.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class MessageTest {

    private final Gson gson = new Gson();

    @Test
    public void assistantWithReasoning_roundTripsReasoningContent() {
        Message m = Message.assistant("hello");
        m.reasoningContent = "think think";
        JsonObject api = m.toApiJson();
        assertEquals("assistant", api.get("role").getAsString());
        assertEquals("think think", api.get("reasoning_content").getAsString());

        // 落盘往返（含 reasoningContent）
        String json = gson.toJson(m);
        Message back = gson.fromJson(json, Message.class);
        assertEquals("think think", back.reasoningContent);
        assertEquals("hello", back.content);
    }

    @Test
    public void assistantWithToolCalls_emitsToolCalls_noContent() {
        ToolCall tc = new ToolCall();
        tc.id = "call_1";
        tc.type = "function";
        tc.name = "Read";
        tc.arguments = "{\"path\":\"a.txt\"}";
        Message m = Message.assistant(null);
        m.toolCalls = Collections.singletonList(tc);
        JsonObject api = m.toApiJson();
        assertFalse(api.has("content"));
        assertEquals("call_1", api.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .get("id").getAsString());
    }

    @Test
    public void toolResult_emitsToolCallId() {
        Message m = Message.toolResult("call_1", "Read", "file content");
        JsonObject api = m.toApiJson();
        assertEquals("tool", api.get("role").getAsString());
        assertEquals("call_1", api.get("tool_call_id").getAsString());
        assertEquals("Read", api.get("name").getAsString());
    }

    @Test
    public void toolCall_roundTrip() {
        ToolCall tc = new ToolCall();
        tc.id = "c1"; tc.type = "function"; tc.name = "Bash";
        tc.arguments = "{\"command\":\"ls\"}";
        String json = gson.toJson(tc);
        ToolCall back = gson.fromJson(json, ToolCall.class);
        assertEquals("c1", back.id);
        assertEquals("Bash", back.name);
        assertEquals("{\"command\":\"ls\"}", back.arguments);

        JsonObject api = tc.toApiJson();
        assertEquals("Bash", api.get("function").getAsJsonObject().get("name").getAsString());
        ToolCall fromApi = ToolCall.fromApi(api);
        assertEquals("Bash", fromApi.name);
    }

    /** 消息创建时间戳：工厂打点 ts>0；默认 0（旧数据兼容） */
    @Test
    public void factory_stampsCreationTimestamp() {
        assertTrue(Message.user("u").ts > 0);
        assertTrue(Message.assistant("a").ts > 0);
        assertTrue(Message.toolResult("tc", "ReadTool", "ok").ts > 0);
        assertTrue(Message.system("s").ts > 0);
        Message plain = new Message();
        assertEquals(0L, plain.ts);
    }

    /** 运行中补充消息：supplement=true；toApiJson 不输出该字段（API 零污染）；Gson 往返保留 */
    @Test
    public void userSupplement_flagsAndRoundTrips() {
        Message m = Message.userSupplement("补充内容");
        assertTrue(m.supplement);
        assertEquals(Message.Role.USER, m.role);
        JsonObject api = m.toApiJson();
        assertFalse("supplement 不得进入 API 请求体", api.has("supplement"));
        assertEquals("补充内容", api.get("content").getAsString());

        String json = gson.toJson(m);
        Message back = gson.fromJson(json, Message.class);
        assertTrue(back.supplement);
    }

    /** 普通 user 消息 supplement=false */
    @Test
    public void userMessage_supplementFalseByDefault() {
        assertFalse(Message.user("普通").supplement);
    }
}
