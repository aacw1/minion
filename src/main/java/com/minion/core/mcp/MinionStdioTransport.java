package com.minion.core.mcp;

import com.ajaxjs.mcp.client.transport.McpTransport;
import com.ajaxjs.mcp.common.JsonUtils;
import com.ajaxjs.mcp.protocol.McpRequest;
import com.ajaxjs.mcp.protocol.ProtocolVersion;
import com.ajaxjs.mcp.protocol.initialize.InitializationNotification;
import com.ajaxjs.mcp.protocol.initialize.InitializeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * minion 自实现的 stdio 传输（继承库公开抽象基类 {@link McpTransport}，不覆盖第三方类、不改库代码）。
 *
 * <p>相对库 StdioTransport 的差异，全部为实测缺陷修复：
 * <ol>
 *   <li><b>读循环容错</b>：服务器 stdout 混入非 JSON 行（banner/日志/空行）时记录后跳过，
 *       不再抛 RuntimeException 杀死读线程、不再把全部 pending 请求误判为「stdout closed」；</li>
 *   <li><b>三步握手脱离读线程</b>：initialize 响应到达后的 initialized 通知改由专用写线程发送，
 *       服务器「响应后立即退出」不再被误判为握手失败；</li>
 *   <li><b>UTF-8 显式</b>：读写流显式指定 UTF-8，中文内容零损耗（不受平台默认编码影响）；</li>
 *   <li><b>诊断环形缓冲</b>：stderr 末尾与非协议行各自留存最近 {@value #TAIL_MAX} 行，
 *       供 {@code AjMcpClient} 在失败原因中附加（排障用）。</li>
 * </ol>
 *
 * <p>与库 1.5 的语义对齐：{@code start} 的已启动/已关闭保护、{@code sendRequestWithResponse} 的
 * 初始化校验、{@code sendRequestWithoutResponse} 不校验、{@code close} 幂等并失败未决请求。
 */
public class MinionStdioTransport extends McpTransport {

    private static final Logger log = LoggerFactory.getLogger(MinionStdioTransport.class);

    /** 诊断环形缓冲容量（stderr 与非协议行各留最近 N 行） */
    private static final int TAIL_MAX = 20;

    /** 单行日志/诊断的截断长度 */
    private static final int LOG_LINE_MAX = 300;

    public static final class Builder {
        private List<String> command;
        private Map<String, String> environment;
        private boolean logEvents;

        public Builder command(List<String> command) { this.command = command; return this; }

        public Builder environment(Map<String, String> environment) { this.environment = environment; return this; }

        public Builder logEvents(boolean logEvents) { this.logEvents = logEvents; return this; }

        public MinionStdioTransport build() {
            return new MinionStdioTransport(command, environment, logEvents);
        }
    }

    public static Builder builder() { return new Builder(); }

    private final List<String> command;
    private final Map<String, String> environment;
    private final boolean logEvents;

    private volatile Process process;
    private volatile PrintStream out;
    private volatile Thread stdoutThread;
    private volatile Thread stderrThread;
    private volatile boolean closed;

    private final Deque<String> stderrTail = new ArrayDeque<String>();
    private final Deque<String> noiseTail = new ArrayDeque<String>();

    /** 专发 initialized 通知的写线程（守护单线程）——关键：不在 stdout 读线程上写 stdin */
    private final ExecutorService notifyExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "mcp-stdio-notify");
            t.setDaemon(true);
            return t;
        }
    });

    MinionStdioTransport(List<String> command, Map<String, String> environment, boolean logEvents) {
        this.command = command == null ? new ArrayList<String>() : new ArrayList<String>(command);
        this.environment = environment;
        this.logEvents = logEvents;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public synchronized void start(Map<Long, CompletableFuture<JsonNode>> pendingRequests) {
        if (closed) throw new IllegalStateException("MCP stdio 传输已关闭");
        if (process != null) throw new IllegalStateException("MCP stdio 传输已启动");
        setPendingRequests(pendingRequests);
        if (command.isEmpty()) throw new IllegalStateException("MCP stdio 命令为空");
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<String>(command));
        if (environment != null && !environment.isEmpty()) pb.environment().putAll(environment);
        log.info("启动 MCP stdio 进程: {}", command);
        Process p;
        try {
            p = pb.start();
            this.out = new PrintStream(p.getOutputStream(), true, "UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException("无法启动 MCP 服务器进程: " + command + "（" + e.getMessage() + "）", e);
        }
        this.process = p;
        startStdoutReader(p);
        startStderrReader(p);
    }

    /** stdout 读线程：任何非 JSON 行/协议帧异常都只记录不杀线程（真断流才 EOF） */
    private void startStdoutReader(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        dispatch(line);
                    }
                } catch (IOException e) {
                    // close() 主动关流或进程退出：进入下方 EOF 分支
                } finally {
                    if (!closed) {
                        failPendingRequests(new IOException("MCP 服务器进程已退出（stdout EOF）"));
                    }
                }
            }
        }, "mcp-stdio-out");
        t.setDaemon(true);
        t.start();
        this.stdoutThread = t;
    }

    private void startStderrReader(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        record(stderrTail, line);
                        log.warn("MCP 服务器 stderr: {}", abbreviate(line));
                    }
                } catch (IOException e) {
                    // 进程退出，忽略
                }
            }
        }, "mcp-stdio-err");
        t.setDaemon(true);
        t.start();
        this.stderrThread = t;
    }

    /** 单行协议帧分发：解析失败 → 记入噪声缓冲并继续（核心修复） */
    private void dispatch(String line) {
        JsonNode node;
        try {
            // 直接用 Jackson（不经库的 JsonUtils 封装：其内部对每次解析失败都打一条带堆栈的 WARN，噪音大）
            node = JsonUtils.OBJECT_MAPPER.readTree(line);
        } catch (IOException e) {
            if (!line.trim().isEmpty()) {
                record(noiseTail, line);
                log.warn("MCP stdio 跳过非 JSON 协议行: {}", abbreviate(line));
            }
            return;
        } catch (RuntimeException e) {
            record(noiseTail, "parse-error: " + e);
            log.warn("MCP stdio 解析协议行异常（继续读取）", e);
            return;
        }
        if (node == null || node.isNull() || node.isMissingNode()) return;
        if (logEvents) log.info("MCP stdio <- {}", abbreviate(line));
        try {
            handle(node);
        } catch (RuntimeException e) {
            record(noiseTail, "handler-error: " + e);
            log.warn("MCP stdio 处理协议帧异常（继续读取）", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        PrintStream o = out;
        if (o != null) {
            try { o.close(); } catch (RuntimeException ignored) { }   // 先送 EOF，给服务器优雅退出的机会
        }
        Process p = process;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        joinQuietly(stdoutThread, 500);
        joinQuietly(stderrThread, 500);   // 等读线程收尾，保证 stderr 尾巴/噪声行完整（诊断用）
        try {
            failPendingRequests(new IOException("MCP stdio 连接已关闭"));
        } catch (RuntimeException ignored) { }
        notifyExecutor.shutdownNow();
    }

    @Override
    public void checkHealth() {
        Process p = process;
        if (closed || p == null || !p.isAlive()) throw new IllegalStateException("MCP stdio 进程未运行");
    }

    // ---------------------------------------------------------------- 请求发送

    @Override
    public CompletableFuture<JsonNode> initialize(final InitializeRequest request) {
        final CompletableFuture<JsonNode> response = execute(JsonUtils.toJson(request), numericId(request.getId()));
        final CompletableFuture<JsonNode> done = new CompletableFuture<JsonNode>();
        response.whenComplete(new BiConsumer<JsonNode, Throwable>() {
            @Override public void accept(final JsonNode node, Throwable err) {
                if (err != null) {
                    done.completeExceptionally(err);
                    return;
                }
                try {
                    notifyExecutor.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                checkProtocolVersion(node);
                                // 通知发送失败（服务器响应后旋即退出）不影响握手结论：后续请求会以连接层错误暴露
                                CompletableFuture<JsonNode> notified = execute(JsonUtils.toJson(new InitializationNotification()), null);
                                if (notified.isCompletedExceptionally()) {
                                    log.warn("MCP initialized 通知未送达（进程可能已退出），握手仍视为成功");
                                }
                                done.complete(node);
                            } catch (RuntimeException e) {
                                done.completeExceptionally(e);
                            }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    done.completeExceptionally(e);   // close() 之后
                }
            }
        });
        return done;
    }

    @Override
    public CompletableFuture<JsonNode> sendRequestWithResponse(McpRequest request) {
        requireInitialized();
        return execute(JsonUtils.toJson(request), numericId(request.getId()));
    }

    @Override
    public void sendRequestWithoutResponse(McpRequest request) {
        execute(JsonUtils.toJson(request), null);
    }

    @Override
    protected void sendJson(JsonNode node) {
        execute(JsonUtils.toJson(node), null);
    }

    /** 发送一行 JSON（id 为 null 表示通知/响应，无需等待回包）；语义与库 execute 对齐 */
    private CompletableFuture<JsonNode> execute(String json, Long id) {
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>();
        Process p = process;
        PrintStream o = out;
        if (closed || p == null || o == null || !p.isAlive()) {
            future.completeExceptionally(new IOException("MCP stdio 进程未运行"));
            return future;
        }
        if (id != null) saveRequest(id, future);
        try {
            if (logEvents) log.info("MCP stdio -> {}", abbreviate(json));
            o.println(json);
            if (o.checkError() || !p.isAlive()) {
                IOException failure = new IOException("写入 MCP 服务器 stdin 失败（进程已退出）");
                failPendingRequests(failure);
                future.completeExceptionally(failure);
                return future;
            }
            if (id == null) future.complete(null);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** 协议版本校验（在发送 initialized 通知之前） */
    private static void checkProtocolVersion(JsonNode response) {
        JsonNode v = response.path("result").path("protocolVersion");
        String version = v.isMissingNode() || v.isNull() ? null : v.asText();
        if (version == null || !ProtocolVersion.supportedVersions().contains(version)) {
            throw new IllegalStateException("MCP 服务器协议版本不受支持: " + version
                    + "（客户端支持: " + ProtocolVersion.supportedVersions() + "）");
        }
    }

    // ---------------------------------------------------------------- 诊断

    /** 服务器 stderr 最后若干行（快照，供失败原因附加） */
    public List<String> stderrTail() { return snapshot(stderrTail); }

    /** 被跳过的非协议输出最后若干行（快照） */
    public List<String> noiseTail() { return snapshot(noiseTail); }

    // ---------------------------------------------------------------- 内部工具

    private static void record(Deque<String> tail, String line) {
        if (line == null) return;
        String s = line.trim();
        if (s.isEmpty()) return;
        synchronized (tail) {
            tail.addLast(abbreviate(s));
            while (tail.size() > TAIL_MAX) tail.pollFirst();
        }
    }

    private static List<String> snapshot(Deque<String> tail) {
        synchronized (tail) {
            return new ArrayList<String>(tail);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= LOG_LINE_MAX ? t : t.substring(0, LOG_LINE_MAX) + "…";
    }

    private static void joinQuietly(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
