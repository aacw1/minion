package com.minion.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DeepSeekClientTest {

    private MockWebServer server;

    @Before
    public void setup() { server = new MockWebServer(); }

    @After
    public void teardown() throws Exception { server.shutdown(); }

    private DeepSeekClient newClient() {
        return newClient("deepseek", true, "max");
    }

    private DeepSeekClient newClient(String provider, boolean thinking, String reasoningEffort) {
        return new DeepSeekClient(server.url("/").toString(),
                "sk-test", "deepseek-v4-flash", thinking, reasoningEffort, provider);
    }

    @Test
    public void streamChat_parsesDeltasAndFinish() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考片段\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"completion_tokens_details\":{\"reasoning_tokens\":20}}}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final List<Object> out = new ArrayList<Object>();

        newClient().streamChat(Collections.<Message>singletonList(Message.user("hi")),
                null, new StreamHandler() {
                    @Override
                    public void onThinking(String delta) { thinking.append(delta); }
                    @Override
                    public void onContent(String delta) { content.append(delta); }
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) {
                        out.add(finishReason);
                        out.add(usage);
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("思考片段", thinking.toString());
        assertEquals("你好，世界", content.toString());
        assertEquals("stop", out.get(0));
        Usage usage = (Usage) out.get(1);
        assertEquals(100, usage.inputTokens);
        assertEquals(50, usage.outputTokens);
        assertEquals(20, usage.reasoningTokens);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("deepseek-v4-flash", json.get("model").getAsString());
        assertTrue(json.get("stream").getAsBoolean());
        assertEquals("max", json.get("reasoning_effort").getAsString());
        assertEquals("enabled", json.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(json.has("stream_options")); // deepseek 零回归：不发送 stream_options
    }

    @Test
    public void streamChat_parsesToolCallDeltas() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"a\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\".txt\\\"}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final CountDownLatch done = new CountDownLatch(1);
        final List<Object> out = new ArrayList<Object>();
        newClient().streamChat(Collections.<Message>singletonList(Message.user("读文件")),
                null, new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) {
                        out.add(finishReason);
                        out.add(toolCalls);
                        done.countDown();
                    }
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("tool_calls", out.get(0));
        List<ToolCall> tcs = (List<ToolCall>) out.get(1);
        assertEquals(1, tcs.size());
        assertEquals("call_1", tcs.get(0).id);
        assertEquals("Read", tcs.get(0).name);
        assertEquals("{\"path\":\"a.txt\"}", tcs.get(0).arguments);
    }

    @Test
    public void request_roundTripsReasoningContent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        Message assistant = Message.assistant("已分析");
        assistant.reasoningContent = "历史思考";
        List<Message> messages = new ArrayList<Message>();
        messages.add(Message.user("继续"));
        messages.add(assistant);
        newClient().streamChat(messages, null, new StreamHandler() {
            @Override
            public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
        });
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("历史思考", json.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("reasoning_content").getAsString());
    }

    @Test
    public void httpError_mapsToLlmException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
        DeepSeekClient client = newClient();
        try {
            client.streamChat(Collections.<Message>singletonList(Message.user("x")), null,
                    new StreamHandler() {
                        @Override
                        public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                    });
            fail("should throw");
        } catch (LlmException e) {
            assertEquals(LlmException.Type.RATE_LIMIT, e.type);
            assertTrue(e.retryable);
        }
    }

    @Test
    public void completeChat_returnsContent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"摘要结果\"}}]}\n\ndata: [DONE]\n\n", 1));
        String r = newClient().completeChat(Collections.<Message>singletonList(Message.user("压缩")), "你是一个压缩器");
        assertEquals("摘要结果", r);
    }

    @Test
    public void cancel_killsAllInFlightRequests() throws Exception {
        // 两个并发请求都延迟响应体，保持 in-flight；cancel() 必须同时取消两者（多子 agent 并行安全）
        // 注：body 延迟 2s，确保 cancel 后 server 侧线程在 teardown 5s 等待窗口内自然结束
        server.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS)
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n", 1));
        server.enqueue(new MockResponse().setBodyDelay(2, TimeUnit.SECONDS)
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"y\"}}]}\n\ndata: [DONE]\n\n", 1));
        final DeepSeekClient client = newClient();
        final List<String> outcomes = Collections.synchronizedList(new ArrayList<String>());
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    client.streamChat(Collections.<Message>singletonList(Message.user("x")), null,
                            new StreamHandler() {
                                @Override
                                public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                            });
                    outcomes.add("ok");
                } catch (LlmException e) {
                    outcomes.add(e.type.name());
                }
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        // 两个请求都已到达 server（= 都已进入 execute，call 已在 in-flight 集合中）
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        client.cancel(); // 必须同时取消两个在途请求
        t1.join(5000);
        t2.join(5000);
        assertFalse(t1.isAlive());
        assertFalse(t2.isAlive());
        assertEquals(2, outcomes.size());
        for (String o : outcomes) assertEquals("NETWORK", o);
    }

    /**
     * qwen 流式每 chunk 的 delta 同时携带 content 与 reasoning_content 字段（另一个为空字符串，
     * 非 null）。空字符串增量不得转发给 handler——否则 ChatView 每正文 chunk 追加一段，
     * 界面表现为"同一段问候语不停重复"（qwen3.8-max 线上实证）。
     */
    @Test
    public void streamChat_ignoresEmptyStringDeltas() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"\",\"reasoning_content\":\"We\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"\",\"reasoning_content\":\" need answer\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"Hello! How\",\"reasoning_content\":\"\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\" can I help you\",\"reasoning_content\":\"\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"\",\"reasoning_content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final List<String> thinkingDeltas = new ArrayList<String>();
        final List<String> contentDeltas = new ArrayList<String>();
        final CountDownLatch done = new CountDownLatch(1);
        newClient("qwen", true, "max").streamChat(
                Collections.<Message>singletonList(Message.user("asdasd")), null,
                new StreamHandler() {
                    @Override public void onThinking(String delta) {
                        thinking.append(delta);
                        thinkingDeltas.add(delta);
                    }
                    @Override public void onContent(String delta) {
                        content.append(delta);
                        contentDeltas.add(delta);
                    }
                    @Override public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) {
                        done.countDown();
                    }
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        // 累积不变（空串 append 无影响），但增量不得把空字符串转发给 UI
        assertEquals("We need answer", thinking.toString());
        assertEquals("Hello! How can I help you", content.toString());
        for (String d : thinkingDeltas) assertFalse("思考增量不得为空串: " + d, d.isEmpty());
        for (String d : contentDeltas) assertFalse("正文增量不得为空串: " + d, d.isEmpty());
    }

    /** S7：reasoning_tokens 为 null 时不得崩溃（getAsInt 抛 UOE → 整轮流中断） */
    @Test
    public void streamChat_reasoningTokensNull_doesNotCrash() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"completion_tokens_details\":{\"reasoning_tokens\":null}}}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final CountDownLatch done = new CountDownLatch(1);
        final StringBuilder content = new StringBuilder();
        final Usage[] usage = new Usage[1];
        newClient().streamChat(Collections.<Message>singletonList(Message.user("hi")),
                null, new StreamHandler() {
                    @Override
                    public void onContent(String delta) { content.append(delta); }
                    @Override
                    public void onFinish(String finishReason, Usage u, List<ToolCall> toolCalls) {
                        usage[0] = u;
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("你好", content.toString());
        assertEquals(100, usage[0].inputTokens);
        assertEquals(50, usage[0].outputTokens);
        assertEquals(0, usage[0].reasoningTokens); // null 守卫：不解析为 0
    }

    @Test
    public void streamChat_emptyChoicesChunkStillParsesUsage() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"completion_tokens_details\":{\"reasoning_tokens\":20}}}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final CountDownLatch done = new CountDownLatch(1);
        final List<Object> out = new ArrayList<Object>();
        final StringBuilder content = new StringBuilder();
        newClient().streamChat(Collections.<Message>singletonList(Message.user("hi")),
                null, new StreamHandler() {
                    @Override
                    public void onContent(String delta) { content.append(delta); }
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) {
                        out.add(finishReason);
                        out.add(usage);
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("你好", content.toString());
        assertEquals("stop", out.get(0));
        Usage usage = (Usage) out.get(1);
        assertEquals(100, usage.inputTokens);
        assertEquals(50, usage.outputTokens);
        assertEquals(20, usage.reasoningTokens);
    }

    // ===== 多供应商：thinkingParams 纯函数 =====

    @Test
    public void thinkingParams_deepseekEnabled() {
        JsonObject p = DeepSeekClient.thinkingParams("deepseek", true, "max");
        assertNotNull(p);
        assertEquals("enabled", p.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("max", p.get("reasoning_effort").getAsString());
    }

    @Test
    public void thinkingParams_deepseekDisabled_returnsNull() {
        assertNull(DeepSeekClient.thinkingParams("deepseek", false, "max"));
    }

    @Test
    public void thinkingParams_qwenEnabled() {
        JsonObject p = DeepSeekClient.thinkingParams("qwen", true, "max");
        assertNotNull(p);
        assertTrue(p.get("enable_thinking").getAsBoolean());
        assertFalse(p.has("thinking"));
        assertFalse(p.has("reasoning_effort"));
    }

    @Test
    public void thinkingParams_qwenDisabled() {
        JsonObject p = DeepSeekClient.thinkingParams("qwen", false, "max");
        assertNotNull(p);
        assertFalse(p.get("enable_thinking").getAsBoolean());
    }

    @Test
    public void thinkingParams_unknownProvider_fallsBackToDeepseek() {
        JsonObject p = DeepSeekClient.thinkingParams("other", true, "max");
        assertNotNull(p);
        assertEquals("enabled", p.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("max", p.get("reasoning_effort").getAsString());
    }

    // ===== 多供应商：qwen 请求体 =====

    @Test
    public void request_qwenEnabled_sendsEnableThinkingAndStreamOptions() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        newClient("qwen", true, "max").streamChat(
                Collections.<Message>singletonList(Message.user("hi")), null,
                new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                });
        RecordedRequest req = server.takeRequest();
        JsonObject json = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertTrue(json.get("enable_thinking").getAsBoolean());
        assertFalse(json.has("thinking"));
        assertFalse(json.has("reasoning_effort"));
        assertTrue(json.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
    }

    @Test
    public void request_qwenDisabled_sendsEnableThinkingFalse() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        newClient("qwen", false, "max").streamChat(
                Collections.<Message>singletonList(Message.user("hi")), null,
                new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                });
        RecordedRequest req = server.takeRequest();
        JsonObject json = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertFalse(json.get("enable_thinking").getAsBoolean());
        assertTrue(json.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
    }

    @Test
    public void request_unknownProvider_fallsBackToDeepseekBody() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        newClient("other", true, "max").streamChat(
                Collections.<Message>singletonList(Message.user("hi")), null,
                new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                });
        RecordedRequest req = server.takeRequest();
        JsonObject json = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertEquals("enabled", json.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("max", json.get("reasoning_effort").getAsString());
        assertFalse(json.has("enable_thinking"));
        assertFalse(json.has("stream_options"));
    }

    @Test
    public void request_deepseekDisabled_sendsNoThinkingParams() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        newClient("deepseek", false, "max").streamChat(
                Collections.<Message>singletonList(Message.user("hi")), null,
                new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                });
        RecordedRequest req = server.takeRequest();
        JsonObject json = JsonParser.parseString(req.getBody().readUtf8()).getAsJsonObject();
        assertFalse(json.has("thinking"));
        assertFalse(json.has("reasoning_effort"));
        assertFalse(json.has("enable_thinking"));
        assertFalse(json.has("stream_options"));
    }
}
