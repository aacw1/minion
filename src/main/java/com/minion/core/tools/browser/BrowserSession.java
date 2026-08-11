package com.minion.core.tools.browser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * 浏览器会话:懒启动 Chrome、封装 CDP 命令、网络/console 事件查询。
 * 应用内单例(BrowserSession 非线程安全,工具执行已串行化)。
 */
public class BrowserSession {

    private final ChromeLauncher launcher;
    private final CdpClient client;
    private volatile String currentUrl = "";
    private volatile boolean domainsEnabled;
    private volatile boolean helperInjected;

    public BrowserSession(ChromeLauncher launcher, CdpClient client) {
        this.launcher = launcher;
        this.client = client;
    }

    // 注意:AgentLoop 同回合并行执行工具,公开方法用 synchronized 串行化,
    // 避免多个工具并发触发 ensureConnected 的双连接竞态。

    private void ensureConnected() throws IOException {
        if (client.isConnected()) return;
        String ws;
        try {
            ws = launcher.pageEndpoint();
        } catch (Exception e) {
            throw new IOException("浏览器启动失败: " + e.getMessage());
        }
        client.connect(ws);
    }

    /** Network/Runtime 事件域启用(幂等):网络记录与 console 日志的前提 */
    private void enableDomains() throws IOException {
        if (domainsEnabled) return;
        client.command("Network.enable", new JsonObject());
        client.command("Runtime.enable", new JsonObject());
        domainsEnabled = true;
    }

    /**
     * 注入页面级辅助函数(幂等):__minion_set_value(el, v) ——
     * React/Vue 受控组件填值:原生 value setter + 触发 input 事件,
     * 模型填表时直接用,不用手写事件细节。
     */
    private void ensureHelper() throws IOException {
        if (helperInjected) return;
        JsonObject params = new JsonObject();
        params.addProperty("expression",
                "window.__minion_set_value=function(el,v){var d=Object.getOwnPropertyDescriptor("
                + "Object.getPrototypeOf(el),'value');if(d&&d.set){d.set.call(el,v);}else{el.value=v;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));}");
        params.addProperty("returnByValue", true);
        client.command("Runtime.evaluate", params);
        helperInjected = true;
    }

    public synchronized String open(String url) throws IOException {
        ensureConnected();
        enableDomains();
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        client.command("Page.navigate", params);
        currentUrl = url;
        waitForPage(15000);
        return "已打开: " + url;
    }

    public synchronized String back() throws IOException {
        ensureConnected();
        enableDomains();
        client.command("Page.goBack", new JsonObject());
        waitForPage(15000);
        return "已后退";
    }

    public synchronized String refresh() throws IOException {
        ensureConnected();
        enableDomains();
        client.command("Page.reload", new JsonObject());
        waitForPage(15000);
        return "已刷新";
    }

    /** 等待页面加载完成(readyState=complete,上限 timeoutMs;未连接/SPA 无 load 事件时不阻塞) */
    public synchronized void waitForPage(int timeoutMs) throws IOException {
        if (!client.isConnected()) return; // 未连接由后续 ensureConnected 负责
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("expression", "document.readyState");
                params.addProperty("returnByValue", true);
                JsonObject r = client.command("Runtime.evaluate", params);
                if (r.has("result") && r.getAsJsonObject("result").has("value")
                        && "complete".equals(r.getAsJsonObject("result").get("value").getAsString())) {
                    return;
                }
            } catch (IOException ignored) { }
            try { Thread.sleep(300); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 执行 JS 并返回值;JS 异常附最近 3 条 console 错误 */
    public synchronized String evaluate(String expression) throws IOException {
        ensureConnected();
        enableDomains();
        ensureHelper();
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        JsonObject r = client.command("Runtime.evaluate", params);
        if (r.has("exceptionDetails")) {
            String text = r.getAsJsonObject("exceptionDetails").get("text").getAsString();
            return "JS 异常: " + text + consoleErrors(3);
        }
        JsonObject result = r.has("result") ? r.getAsJsonObject("result") : new JsonObject();
        if (!result.has("value")) return "(无返回值)";
        return String.valueOf(result.get("value"));
    }

    /** 截图(路径已由工具层守卫;fullPage=true 时 captureBeyondViewport 截全页) */
    public synchronized String screenshot(String absPath, boolean fullPage) throws IOException {
        ensureConnected();
        JsonObject params = new JsonObject();
        if (fullPage) params.addProperty("captureBeyondViewport", true);
        JsonObject r = client.command("Page.captureScreenshot", params);
        if (!r.has("data")) return "截图失败: 无数据";
        byte[] png = Base64.getDecoder().decode(r.get("data").getAsString());
        Files.write(Paths.get(absPath), png);
        return "截图已保存: " + absPath;
    }

    /** 网络请求汇总:method url → status,耗时 ms(按 requestId 关联) */
    public String debugNetwork(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JsonObject> sent = client.events("Network.requestWillBeSent");
        List<JsonObject> got = client.events("Network.responseReceived");
        int shown = 0;
        for (JsonObject e : sent) {
            if (shown >= limit) break;
            JsonObject req = e.getAsJsonObject("request");
            String method = req.get("method").getAsString();
            String url = req.get("url").getAsString();
            String tail = "";
            String id = e.get("requestId").getAsString();
            double t1 = e.has("timestamp") ? e.get("timestamp").getAsDouble() : 0;
            for (JsonObject g : got) {
                if (id.equals(g.get("requestId").getAsString())) {
                    String status = g.getAsJsonObject("response").get("status").getAsString();
                    double t2 = g.has("timestamp") ? g.get("timestamp").getAsDouble() : 0;
                    tail = " → " + status
                            + (t1 > 0 && t2 > 0 ? ", " + Math.round((t2 - t1) * 1000) + "ms" : "");
                    break;
                }
            }
            sb.append(method).append(' ').append(truncate(url, 120)).append(tail).append('\n');
            shown++;
            if (sb.length() > 20000) { sb.append("... 输出过长已截断\n"); break; }
        }
        return sb.toString().trim().isEmpty() ? "暂无网络记录(需先打开页面)" : sb.toString();
    }

    /** console 日志(错误标 [ERROR]) */
    public String debugConsole(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JsonObject> logs = client.events("Runtime.consoleAPICalled");
        int from = Math.max(0, logs.size() - limit);
        for (int i = from; i < logs.size(); i++) {
            JsonObject e = logs.get(i);
            String type = e.get("type").getAsString();
            StringBuilder args = new StringBuilder();
            JsonElement argsArr = e.get("args");
            if (argsArr != null && argsArr.isJsonArray()) {
                for (JsonElement a : argsArr.getAsJsonArray()) {
                    JsonObject o = a.getAsJsonObject();
                    if (o.has("value")) args.append(o.get("value")).append(' ');
                }
            }
            sb.append("error".equals(type) ? "[ERROR] " : "[").append(type).append("] ")
              .append(args).append('\n');
        }
        return sb.toString().trim().isEmpty() ? "暂无 console 日志" : sb.toString();
    }

    /** evaluate 异常时附带的 console 错误摘要 */
    private String consoleErrors(int n) {
        List<JsonObject> logs = client.events("Runtime.consoleAPICalled");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logs.size() - n);
        for (int i = from; i < logs.size(); i++) {
            JsonObject e = logs.get(i);
            if (!"error".equals(e.get("type").getAsString())) continue;
            JsonElement argsArr = e.get("args");
            if (argsArr != null && argsArr.isJsonArray()) {
                for (JsonElement a : argsArr.getAsJsonArray()) {
                    JsonObject o = a.getAsJsonObject();
                    if (o.has("value")) sb.append(' ').append(o.get("value"));
                }
            }
        }
        return sb.length() == 0 ? "" : "\n最近 console 错误:" + sb;
    }

    /** 当前页面信息:标题 + URL(已连接时实时查询;未连接提示先 open) */
    public String pageInfo() {
        if (!client.isConnected()) {
            return "当前页面: (未打开,可用 action=open 打开)";
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", "document.title + ' | ' + location.href");
            params.addProperty("returnByValue", true);
            JsonObject r = client.command("Runtime.evaluate", params);
            if (r.has("result") && r.getAsJsonObject("result").has("value")) {
                return "当前页面: " + r.getAsJsonObject("result").get("value").getAsString();
            }
        } catch (IOException ignored) { }
        return "当前页面: " + (currentUrl.isEmpty() ? "(未知)" : currentUrl);
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
