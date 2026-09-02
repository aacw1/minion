package com.minion.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** OpenAI 兼容 Chat Completions 流式客户端（SSE），内置 deepseek/qwen 思考参数适配。 */
public class DeepSeekClient implements LlmClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT = 30;
    private static final int READ_TIMEOUT = 300;

    /** 首增量等待上限默认值：60s 内没收到任何有效增量即判定请求卡死 */
    private static final long DEFAULT_FIRST_TOKEN_TIMEOUT_MS = 60_000L;

    /** 首增量（首个非空 delta）超时阈值：超时即抛 TIMEOUT 交调用方长重试。
     *  包级可见供单测改小；与 CONNECT/READ_TIMEOUT 同风格，硬编码不进 config.properties */
    long firstTokenTimeoutMs = DEFAULT_FIRST_TOKEN_TIMEOUT_MS;

    private final String url;
    private final String apiKey;
    private final String model;
    private final boolean thinking;
    private final String reasoningEffort;
    private final String provider;
    private final OkHttpClient http;
    /** 看门狗调度池（实例级，随 close 释放）：单线程 daemon，取消即出队、空闲可回收 */
    private final ScheduledExecutorService watchdogScheduler = newWatchdogScheduler();
    private volatile boolean closed;

    private static ScheduledExecutorService newWatchdogScheduler() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "llm-first-token-watchdog");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);                     // 长流式期间不留已取消任务
        ex.setKeepAliveTime(60, TimeUnit.SECONDS);            // 配合下面允许核心线程超时回收
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    public DeepSeekClient(String url, String apiKey, String model,
                          boolean thinking, String reasoningEffort, String provider) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.thinking = thinking;
        this.reasoningEffort = reasoningEffort;
        this.provider = provider;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }

    private Request buildRequest(List<Message> messages, List<JsonObject> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        // 按供应商生成思考参数（deepseek: thinking/reasoning_effort；qwen: enable_thinking）
        JsonObject tp = thinkingParams(provider, thinking, reasoningEffort);
        if (tp != null) {
            for (Map.Entry<String, JsonElement> e : tp.entrySet()) body.add(e.getKey(), e.getValue());
        }
        // qwen 流式默认不返回 usage，需显式 include_usage；deepseek 不发送（零回归）
        if ("qwen".equalsIgnoreCase(provider)) {
            JsonObject so = new JsonObject();
            so.addProperty("include_usage", true);
            body.add("stream_options", so);
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

    /** 按供应商生成思考参数；deepseek/未知关闭思考时返回 null（不发参数）。
     *  qwen3 混合模型默认开思考，必须显式传 enable_thinking=false 才关，故关闭时也返回参数。 */
    static JsonObject thinkingParams(String provider, boolean thinking, String reasoningEffort) {
        if ("qwen".equalsIgnoreCase(provider)) {
            JsonObject o = new JsonObject();
            o.addProperty("enable_thinking", thinking);
            if (thinking) {
                o.addProperty("reasoning_effort", reasoningEffort);
            }
            return o;
        }
        if (!thinking) return null;
        JsonObject o = new JsonObject();
        JsonObject th = new JsonObject();
        th.addProperty("type", "enabled");
        o.add("thinking", th);
        o.addProperty("reasoning_effort", reasoningEffort);
        return o;
    }

    /** 全部 in-flight 请求（多子 agent 并行时不止一个） */
    private final Set<okhttp3.Call> inFlightCalls = ConcurrentHashMap.newKeySet();

    /** 中断进行中的请求（Ctrl+C / 用户打断）：取消全部 in-flight 请求 */
    public void cancel() {
        for (okhttp3.Call c : inFlightCalls) {
            c.cancel();
        }
    }

    /**
     * 释放底层资源：取消 in-flight 请求、关 dispatcher 线程、清空连接池。
     * 幂等；okhttp 3.14 无 OkHttpClient.close()，连接池的 keep-alive 清理线程
     * 是非 daemon 的（空闲 5 分钟才退出）——close 后连接池线程立即终止，
     * JVM 不再被拖住（关窗约 5 分钟残留的根因）。
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cancel();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    @Override
    public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException {
        List<ToolCall> acc = new ArrayList<ToolCall>();
        StringBuilder content = new StringBuilder();
        StringBuilder thinkingSb = new StringBuilder();
        Usage usage = null;
        String finish = "stop";
        final okhttp3.Call call = http.newCall(buildRequest(messages, tools));
        inFlightCalls.add(call);
        // 首增量看门狗：READ_TIMEOUT 管的是"两个 SSE chunk 之间的间隔"（300s），
        // 治不了"连上了但模型迟迟不产出"。此处独立计时，到点 cancel 解除阻塞并归为 TIMEOUT。
        // READ_TIMEOUT 保持 300s 不变，避免误杀正在持续吐字的慢流
        final boolean[] watchdogFired = new boolean[1];
        final ScheduledFuture<?> watchdog = watchdogScheduler.schedule(() -> {
            watchdogFired[0] = true;
            call.cancel();
        }, firstTokenTimeoutMs, TimeUnit.MILLISECONDS);
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
                        // 空字符串增量不转发：qwen 流式每 chunk 的 delta 同时携带 content 与
                        // reasoning_content 字段（另一个为空字符串，非 null）——若空串也回调，
                        // ChatView 每正文 chunk 追加一段，界面表现为"同一段回复不停重复"
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                            String d = delta.get("reasoning_content").getAsString();
                            if (!d.isEmpty()) {
                                watchdog.cancel(false); // 首个有效增量到达：计时职责交回 READ_TIMEOUT
                                thinkingSb.append(d);
                                handler.onThinking(d);
                            }
                        }
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String d = delta.get("content").getAsString();
                            if (!d.isEmpty()) {
                                watchdog.cancel(false);
                                content.append(d);
                                handler.onContent(d);
                            }
                        }
                        if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                            watchdog.cancel(false); // 工具调用增量同样是"模型已产出"的信号
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
            // 顺序敏感：okhttp 被 cancel 抛的是 IOException("canceled")，既不 instanceof SocketTimeout，
            // 也分不清是看门狗还是用户中断——故必须最先查 watchdogFired 标志
            if (watchdogFired[0]) {
                throw new LlmException(LlmException.Type.TIMEOUT,
                        "请求超时：" + thresholdText() + "内未收到模型输出", true);
            }
            if (isTimeout(e)) {
                throw new LlmException(LlmException.Type.TIMEOUT, "请求超时: " + e.getMessage(), true);
            }
            if (isDnsFailure(e)) {
                throw new LlmException(LlmException.Type.NETWORK,
                        "网络错误: " + e.getMessage() + "（域名无法解析，请检查设置中的 API 地址）", false);
            }
            throw new LlmException(LlmException.Type.NETWORK, "网络错误: " + e.getMessage(), true);
        } finally {
            watchdog.cancel(false); // 幂等兜底（正常完成/异常路径都要摘掉定时任务）
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

    /** 阈值展示文本：整秒说"N 秒"，不足秒说"N 毫秒"（单测把阈值改到亚秒级时文案不失真） */
    private String thresholdText() {
        return firstTokenTimeoutMs % 1000 == 0 && firstTokenTimeoutMs >= 1000
                ? (firstTokenTimeoutMs / 1000) + " 秒" : firstTokenTimeoutMs + " 毫秒";
    }

    /** 域名解析失败（API 地址配错 / 本机断网）：永久性故障，标记 retryable=false 交调用方短路。
     *  okhttp 可能直抛 UnknownHostException，也可能包成 IOException，故沿 cause 链查（限深 5 层防自环）。
     *  只认 DNS——ConnectException（服务重启期必然短暂拒连）与 SSLException 仍按可恢复处理 */
    private boolean isDnsFailure(Throwable e) {
        Throwable t = e;
        for (int i = 0; i < 5 && t != null; i++) {
            if (t instanceof java.net.UnknownHostException) return true;
            t = t.getCause();
        }
        return false;
    }

    private String responseBody(Response r) {
        try { return r.body() != null ? r.body().string() : ""; }
        catch (IOException e) { return ""; }
    }
}
