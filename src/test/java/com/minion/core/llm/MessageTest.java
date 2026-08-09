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
}
