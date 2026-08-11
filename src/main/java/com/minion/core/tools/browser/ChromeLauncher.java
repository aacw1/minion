package com.minion.core.tools.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Chrome 进程管理:优先复用已在调试端口监听的实例,否则拉起新进程(有头/无头)。
 * 就绪判定:轮询 http://127.0.0.1:port/json 直至返回页面端点。
 */
public class ChromeLauncher {

    private final String chromePath;   // 空 = 自动探测
    private final int port;
    private final Path userDataDir;
    private final boolean headless;
    private final int readyTimeoutMs;
    private Process process;

    public ChromeLauncher(String chromePath, int port, Path userDataDir,
                          boolean headless, int readyTimeoutMs) {
        this.chromePath = chromePath;
        this.port = port;
        this.userDataDir = userDataDir;
        this.headless = headless;
        this.readyTimeoutMs = readyTimeoutMs;
    }

    /** 确保 Chrome 就绪并返回页面调试端点 ws://.../devtools/page/<id>;失败抛 IOException */
    public String pageEndpoint() throws Exception {
        // 1. 端口已有调试服务?直接复用(避免重复拉起进程)
        String json = fetchJsonList();
        if (json != null) {
            String ep = pageEndpoint(json);
            if (ep != null) return ep;
        }
        // 2. 启动新实例
        String chrome = chromePath != null && !chromePath.isEmpty() ? chromePath : findChrome();
        if (chrome == null) {
            throw new IOException("未找到 Chrome,请在 config.properties 配置 browser.path");
        }
        process = new ProcessBuilder(buildCommand(chrome))
                .redirectErrorStream(true).start();
        // 3. 轮询就绪
        long deadline = System.currentTimeMillis() + readyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            String j = fetchJsonList();
            if (j != null) {
                String ep = pageEndpoint(j);
                if (ep != null) return ep;
            }
        }
        throw new IOException("Chrome 启动超时(端口 " + port + "),请检查 browser.path 配置");
    }

    /** Chrome 命令行(包内可见,测试用) */
    List<String> buildCommand(String chrome) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(chrome);
        cmd.add("--remote-debugging-port=" + port);
        cmd.add("--user-data-dir=" + userDataDir.toAbsolutePath());
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        if (headless) cmd.add("--headless=new"); // 109 的 new headless 模式(旧模式在 109 已弃用)
        return cmd;
    }

    /** 从 /json 列表响应取第一个 type=page 的 webSocketDebuggerUrl;无 page/解析失败返回 null */
    static String pageEndpoint(String json) {
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if ("page".equals(o.get("type").getAsString())
                        && o.has("webSocketDebuggerUrl")) {
                    return o.get("webSocketDebuggerUrl").getAsString();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 查询调试端口 /json 列表;未监听/连接失败返回 null */
    String fetchJsonList() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build();
            Response r = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + port + "/json").build()).execute();
            return r.body() != null ? r.body().string() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 自动探测 Chrome 常见安装位置;找不到返回 null */
    static String findChrome() {
        String[] candidates = {
                envOrNull("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"};
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        return null;
    }

    private static String envOrNull(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    /** 退出时停止自启进程(minion 退出钩子调用;复用外部实例时 process 为 null,无操作) */
    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
