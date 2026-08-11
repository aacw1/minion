package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDP 客户端:okhttp WebSocket 直连 Chrome 调试端点。
 * 命令/响应按自增 id 匹配;事件推入环形缓冲(上限 MAX_EVENTS)供 BrowserDebug 查询。
 */
public class CdpClient extends WebSocketListener {

    private static final int MAX_EVENTS = 500;

    private final int connectTimeoutMs;
    private final int commandTimeoutMs;
    private final List<JsonObject> events = new CopyOnWriteArrayList<JsonObject>();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<Integer, Pending>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile WebSocket socket;
    private volatile String error;
    private volatile boolean connected;
    private CountDownLatch openLatch;

    public CdpClient(int connectTimeoutMs, int commandTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public boolean isConnected() { return connected; }

    /** 连接(阻塞至握手完成或超时);失败抛 IOException */
    public void connect(String wsUrl) throws IOException {
        if (connected) return;
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接,命令层自行控制超时
                .build();
        openLatch = new CountDownLatch(1);
        socket = client.newWebSocket(new Request.Builder().url(wsUrl).build(), this);
        try {
            if (!openLatch.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("连接 Chrome 超时: " + wsUrl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("连接被中断");
        }
        if (error != null) throw new IOException("连接失败: " + error);
        connected = true;
    }

    /** 发送命令并等待响应;未连接/超时/协议错误/断线抛 IOException */
    public JsonObject command(String method, JsonObject params) throws IOException {
        if (!connected) throw new IOException("浏览器未连接,请先执行 Browser 工具");
        int id = nextId.getAndIncrement();
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        if (params != null) msg.add("params", params);
        Pending p = new Pending();
        pending.put(id, p);
        if (!socket.send(msg.toString())) {
            // send 返回 false 说明连接已断开(对端关闭/失败),立即失败而非等待超时
            pending.remove(id);
            String reason = error != null ? error : "连接已断开";
            throw new IOException(reason + ",命令发送失败: " + method);
        }
        synchronized (p) {
            long deadline = System.currentTimeMillis() + commandTimeoutMs;
            while (p.result == null && p.err == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    pending.remove(id);
                    throw new IOException("CDP 命令超时(" + commandTimeoutMs + "ms): " + method);
                }
                try { p.wait(remain); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending.remove(id);
                    throw new IOException("命令等待被中断: " + method);
                }
            }
        }
        pending.remove(id);
        if (p.err != null) throw new IOException(p.err);
        if (p.result.has("error")) {
            String msg2 = p.result.getAsJsonObject("error").has("message")
                    ? p.result.getAsJsonObject("error").get("message").getAsString() : "未知错误";
            throw new IOException("CDP 命令失败 " + method + ": " + msg2);
        }
        return p.result.has("result") ? p.result.getAsJsonObject("result") : new JsonObject();
    }

    /** 按方法名前缀取事件(网络/console 调试用) */
    public List<JsonObject> events(String methodPrefix) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonObject e : events) {
            if (e.has("method") && e.get("method").getAsString().startsWith(methodPrefix)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public void onOpen(WebSocket ws, Response response) {
        socket = ws;
        if (openLatch != null) openLatch.countDown();
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
        try {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            if (msg.has("id") && !msg.get("id").isJsonNull()) {
                Pending p = pending.get(msg.get("id").getAsInt());
                if (p != null) {
                    synchronized (p) { p.result = msg; p.notifyAll(); }
                }
            } else if (msg.has("method")) {
                events.add(msg);
                if (events.size() > MAX_EVENTS) events.remove(0);
            }
        } catch (Exception ignored) { } // 非 JSON 消息忽略
    }

    /** 对端发起关闭(如用户关闭浏览器窗口):回发 close 完成握手,否则 onClosed 不会触发 */
    @Override
    public void onClosing(WebSocket ws, int code, String reason) {
        ws.close(code, reason);
    }

    /** 对端正常关闭:标记断线并唤醒所有等待中的命令立即失败 */
    @Override
    public void onClosed(WebSocket ws, int code, String reason) {
        connected = false;
        error = "连接已关闭";
        if (openLatch != null) openLatch.countDown();
        for (Pending p : pending.values()) {
            synchronized (p) { p.err = error; p.notifyAll(); }
        }
        pending.clear();
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        connected = false;
        error = t == null ? "连接断开" : t.getMessage();
        if (openLatch != null) openLatch.countDown();
        for (Pending p : pending.values()) {
            synchronized (p) { p.err = error; p.notifyAll(); }
        }
        pending.clear();
    }

    private static class Pending {
        JsonObject result;
        String err;
    }
}
