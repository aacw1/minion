package com.minion.core.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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

    /** 图片消息：content 输出 OpenAI 标准数组（text + image_url），data URI 带 mime */
    @Test
    public void userWithImages_emitsContentArray() {
        ImagePart img = new ImagePart();
        img.mime = "image/png";
        img.base64 = "QUJD";
        img.name = "截图.png";
        Message m = Message.userWithImages("看这张图", Collections.singletonList(img));
        JsonObject api = m.toApiJson();
        JsonArray parts = api.getAsJsonArray("content");
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("看这张图", parts.get(0).getAsJsonObject().get("text").getAsString());
        JsonObject imgUrl = parts.get(1).getAsJsonObject().get("image_url").getAsJsonObject();
        assertEquals("data:image/png;base64,QUJD", imgUrl.get("url").getAsString());
    }

    /** 图片随 Gson 落盘往返（重启后仍可回传模型） */
    @Test
    public void userWithImages_roundTripsViaGson() {
        ImagePart img = new ImagePart();
        img.mime = "image/jpeg"; img.base64 = "QUJD"; img.name = "a.jpg";
        Message m = Message.userWithImages("图", Collections.singletonList(img));
        String json = gson.toJson(m);
        Message back = gson.fromJson(json, Message.class);
        assertEquals(1, back.images.size());
        assertEquals("image/jpeg", back.images.get(0).mime);
        assertEquals("QUJD", back.images.get(0).base64);
    }

    /** 无图零回归：content 仍为纯字符串；补充消息带图 supplement 标志保留 */
    @Test
    public void userWithImages_nullImages_keepsStringContent() {
        JsonObject api = Message.userWithImages("你好", null).toApiJson();
        assertEquals("你好", api.get("content").getAsString());
        ImagePart img = new ImagePart();
        img.mime = "image/png"; img.base64 = "QUJD"; img.name = "s.png";
        Message sup = Message.userSupplement("补充", Collections.singletonList(img));
        assertTrue(sup.supplement);
        assertFalse(sup.toApiJson().has("supplement"));
    }

    /** 技能加载消息：USER 角色 + pinned 标记（上下文压缩豁免）；toApiJson 不输出 pinned（本地标记，与 supplement 同理） */
    @Test
    public void skillMessage_isUserRoleAndPinned() {
        Message m = Message.skill("<skill name=\"review\">\n审查指令\n</skill>");
        assertEquals(Message.Role.USER, m.role);
        assertTrue(m.pinned);
        assertFalse(m.toApiJson().has("pinned"));
        assertEquals("审查指令", m.content.substring(m.content.indexOf("审查指令"),
                m.content.indexOf("审查指令") + 4));
    }
}
