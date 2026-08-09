package com.minion.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** DeepSeek Chat Completions 流式客户端（SSE）。 */
public class DeepSeekClient implements LlmClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT = 30;
    private static final int READ_TIMEOUT = 300;

    private final String url;
    private final String apiKey;
    private final String model;
    private final boolean thinking;
    private final String reasoningEffort;
    private final OkHttpClient http;

    public DeepSeekClient(String url, String apiKey, String model,
                          boolean thinking, String reasoningEffort) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.thinking = thinking;
        this.reasoningEffort = reasoningEffort;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }

    private Request buildRequest(List<Message> messages, List<JsonObject> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        if (thinking) {
            JsonObject th = new JsonObject();
            th.addProperty("type", "enabled");
            body.add("thinking", th);
            body.addProperty("reasoning_effort", reasoningEffort);
        }
        JsonArray msgs = new JsonArray();
        for (Message m : messages) msgs.add(m.toApiJson());
        body.add("messages", msgs);
        if (tools != null && !tools.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (JsonObject t : tools) arr.add(t);
            body.add("tools", arr);
        }
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSON, body.toString()));
        return rb.build();
    }

    /** 全部 in-flight 请求（多子 agent 并行时不止一个） */
    private final Set<okhttp3.Call> inFlightCalls = ConcurrentHashMap.newKeySet();

    /** 中断进行中的请求（Ctrl+C / 用户打断）：取消全部 in-flight 请求 */
    public void cancel() {
        for (okhttp3.Call c : inFlightCalls) {
            c.cancel();
        }
    }

    @Override
    public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException {
        List<ToolCall> acc = new ArrayList<ToolCall>();
        StringBuilder content = new StringBuilder();
        StringBuilder thinkingSb = new StringBuilder();
        Usage usage = null;
        String finish = "stop";
        okhttp3.Call call = http.newCall(buildRequest(messages, tools));
        inFlightCalls.add(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw LlmException.of(response.code(), responseBody(response));
            if (response.body() == null) throw new LlmException(LlmException.Type.OTHER, "空响应", false);
            BufferedSource source = response.body().source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                JsonArray choices = chunk.has("choices") && chunk.get("choices").isJsonArray()
                        ? chunk.getAsJsonArray("choices") : null;
                if (choices != null && choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                            ? choice.getAsJsonObject("delta") : null;
                    if (delta != null) {
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                            String d = delta.get("reasoning_content").getAsString();
                            thinkingSb.append(d);
                            handler.onThinking(d);
                        }
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String d = delta.get("content").getAsString();
                            content.append(d);
                            handler.onContent(d);
                        }
                        if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                            accumulateToolCalls(delta.getAsJsonArray("tool_calls"), acc);
                        }
                    }
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                        finish = choice.get("finish_reason").getAsString();
                    }
                }
                if (chunk.has("usage") && chunk.get("usage").isJsonObject()
                        && !chunk.get("usage").isJsonNull()) {
                    usage = Usage.fromJson(chunk.getAsJsonObject("usage"));
                }
            }
        } catch (IOException e) {
            if (isTimeout(e)) {
                throw new LlmException(LlmException.Type.TIMEOUT, "请求超时: " + e.getMessage(), true);
            }
            throw new LlmException(LlmException.Type.NETWORK, "网络错误: " + e.getMessage(), true);
        } finally {
            inFlightCalls.remove(call);
        }
        if (usage == null) {
            usage = Usage.estimate(messages, content.toString());
        }
        if (finish.equals("tool_calls") && acc.isEmpty()) {
            throw new LlmException(LlmException.Type.BAD_REQUEST,
                    "模型声明工具调用但未返回工具参数", false);
        }
        handler.onUsage(usage);
        handler.onFinish(finish, usage, acc);
    }

    private void accumulateToolCalls(JsonArray deltas, List<ToolCall> acc) {
        for (int i = 0; i < deltas.size(); i++) {
            JsonObject d = deltas.get(i).getAsJsonObject();
            int index = d.has("index") ? d.get("index").getAsInt() : 0;
            while (acc.size() <= index) {
                ToolCall tc = new ToolCall();
                tc.id = "";
                tc.arguments = "";
                acc.add(tc);
            }
            ToolCall tc = acc.get(index);
            if (d.has("id") && !d.get("id").isJsonNull()) tc.id = d.get("id").getAsString();
            if (d.has("type") && !d.get("type").isJsonNull()) tc.type = d.get("type").getAsString();
            if (d.has("function") && d.get("function").isJsonObject()) {
                JsonObject fn = d.getAsJsonObject("function");
                if (fn.has("name") && !fn.get("name").isJsonNull()) tc.name = fn.get("name").getAsString();
                if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                    tc.arguments = tc.arguments == null ? "" : tc.arguments
                            + fn.get("arguments").getAsString();
                }
            }
        }
    }

    @Override
    public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
        final StringBuilder out = new StringBuilder();
        final LlmException[] err = new LlmException[1];
        List<Message> all = new ArrayList<Message>();
        all.add(Message.system(systemPrompt));
        all.addAll(messages);
        streamChat(all, null, new StreamHandler() {
            @Override
            public void onContent(String delta) { out.append(delta); }
            @Override
            public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
            @Override
            public void onError(LlmException e) { err[0] = e; }
        });
        if (err[0] != null) throw err[0];
        return out.toString();
    }

    private boolean isTimeout(IOException e) {
        return e instanceof java.net.SocketTimeoutException
                || (e.getCause() != null && e.getCause() instanceof java.net.SocketTimeoutException);
    }

    private String responseBody(Response r) {
        try { return r.body() != null ? r.body().string() : ""; }
        catch (IOException e) { return ""; }
    }
}
