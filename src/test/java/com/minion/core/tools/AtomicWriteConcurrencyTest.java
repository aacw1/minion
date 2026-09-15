package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;

/**
 * Write/Edit 并发写入保护（原子性 + 路径锁）的回归测试。
 *
 * 对应事故：同一条消息里两个 Edit 并行改同一文档，导致一处改动丢失 + 文件末尾多出孤立字节 0x89
 * （既非合法 UTF-8 也非合法 GBK）。根因与实测证据见
 * docs/superpowers/specs/2026-09-15-文件写入原子性与并发保护-design.md
 *
 * 并发参数刻意与 AgentLoop 的工具线程池保持一致（4 线程），起跑用 CyclicBarrier 对齐，
 * 以保证"两次 read 都早于任一 write"这一丢更新条件稳定复现（探针实测：单轮丢更新率 100%）。
 */
public class AtomicWriteConcurrencyTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private Workspace ws;
    private WriteTool write;
    private EditTool edit;

    @org.junit.Before
    public void setup() {
        work = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(work);
        write = new WriteTool(ws);
        edit = new EditTool(ws);
    }

    // ---------------------------------------------------------------- T1

    /** 4 线程并发 Edit 各改一处不同标记：所有改动都必须生效，不得丢更新 */
    @Test
    public void concurrentEdits_noLostUpdate() throws Exception {
        List<RoundResult> rounds = runEditRounds(3, 4);
        List<String> bad = new ArrayList<String>();
        for (int r = 0; r < rounds.size(); r++) {
            if (!rounds.get(r).errors.isEmpty()) {
                bad.add("第" + (r + 1) + "轮工具报错: " + rounds.get(r).errors);
            }
            String text = new String(rounds.get(r).bytes, StandardCharsets.UTF_8);
            for (int i = 0; i < 4; i++) {
                if (!text.contains("标记" + i + ": 已改")) bad.add("第" + (r + 1) + "轮 标记" + i);
            }
        }
        assertTrue("并发 Edit 丢更新（未生效：）" + bad, bad.isEmpty());
    }

    // ---------------------------------------------------------------- T2

    /** 4 线程并发 Edit 后，文件必须始终是严格合法的 UTF-8（不得出现撕裂写入的孤立字节） */
    @Test
    public void concurrentEdits_fileStaysValidUtf8() throws Exception {
        List<RoundResult> rounds = runEditRounds(8, 4);
        List<String> bad = new ArrayList<String>();
        for (int r = 0; r < rounds.size(); r++) {
            if (!rounds.get(r).errors.isEmpty()) {
                bad.add("第" + (r + 1) + "轮工具报错: " + rounds.get(r).errors);
            }
            if (!legalUtf8(rounds.get(r).bytes)) {
                bad.add("第" + (r + 1) + "轮 size=" + rounds.get(r).bytes.length);
            }
        }
        assertTrue("并发 Edit 产出非法 UTF-8（撕裂写入）：" + bad, bad.isEmpty());
    }

    // ---------------------------------------------------------------- T3

    /** 4 线程并发 Write 覆盖写不等长内容：最终文件必须恰好等于某一次的完整内容，不得混杂或残留尾部 */
    @Test
    public void concurrentWrites_finalContentIsOneCompleteWrite() throws Exception {
        int threads = 4;
        int rounds = 40;
        List<String> bad = new ArrayList<String>();
        for (int r = 0; r < rounds; r++) {
            Path target = p("over.txt");
            Files.write(target, "初始内容".getBytes(StandardCharsets.UTF_8));
            final List<String> contents = new ArrayList<String>();
            for (int i = 0; i < threads; i++) {
                contents.add("# 内容" + i + "\n" + repeat("写", 400 + i * 300) + "\n");
            }
            List<String> errors = runConcurrently(threads, new TaskFactory() {
                public Callable<ToolResult> create(int idx, final CyclicBarrier barrier) {
                    final String content = contents.get(idx);
                    return new Callable<ToolResult>() {
                        public ToolResult call() throws Exception {
                            barrier.await(20, TimeUnit.SECONDS);
                            JsonObject a = new JsonObject();
                            a.addProperty("path", "over.txt");
                            a.addProperty("content", content);
                            return write.execute(a);
                        }
                    };
                }
            });
            byte[] fin = Files.readAllBytes(target);
            boolean match = false;
            for (String c : contents) {
                if (Arrays.equals(c.getBytes(StandardCharsets.UTF_8), fin)) match = true;
            }
            if (!match) bad.add("第" + (r + 1) + "轮 size=" + fin.length);
            if (!errors.isEmpty()) bad.add("第" + (r + 1) + "轮工具报错: " + errors);
        }
        assertTrue("并发 Write 产出混杂内容/残留尾部（不等于任何一次完整内容）：" + bad, bad.isEmpty());
    }

    // ---------------------------------------------------------------- T4

    /** 写入完成后目录内不得残留临时文件 */
    @Test
    public void atomicWrite_leavesNoTmpFiles() throws Exception {
        write.execute(args("{\"path\":\"out.txt\",\"content\":\"内容\"}"));
        edit.execute(args("{\"path\":\"out.txt\",\"oldString\":\"内容\",\"newString\":\"内容2\"}"));
        List<String> leftovers = new ArrayList<String>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(tmp.getRoot().toPath());
        try {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                if (n.endsWith(".tmp") || n.contains(".mt")) leftovers.add(n);
            }
        } finally {
            ds.close();
        }
        assertTrue("写入后不应残留临时文件：" + leftovers, leftovers.isEmpty());
    }

    // ---------------------------------------------------------------- T5

    /** 目标文件被读句柄占用时写入仍须成功（Windows 上 ATOMIC_MOVE 会失败，必须降级兜底） */
    @Test
    public void atomicWrite_targetLockedByReader_fallsBack() throws Exception {
        Path target = p("locked.txt");
        Files.write(target, "旧内容".getBytes(StandardCharsets.UTF_8));
        ToolResult r;
        FileChannel ch = FileChannel.open(target, StandardOpenOption.READ);
        try {
            r = write.execute(args("{\"path\":\"locked.txt\",\"content\":\"新内容覆盖\"}"));
        } finally {
            ch.close();
        }
        assertTrue("目标被读句柄占用时写入仍应成功（ATOMIC_MOVE 失败需降级 REPLACE_EXISTING）："
                + r.output, r.ok);
        assertEquals("新内容覆盖", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- T6

    /** 编辑已损坏文件（UTF-8 与 GBK 均不可解码）须返回明确失败，不得抛 unchecked 异常穿透给 AgentLoop */
    @Test
    public void edit_corruptFile_returnsClearError() throws Exception {
        Files.write(p("corrupt.md"), corruptBytes());
        ToolResult r;
        try {
            r = edit.execute(args("{\"path\":\"corrupt.md\",\"oldString\":\"正文内容\","
                    + "\"newString\":\"改后\"}"));
        } catch (RuntimeException e) {
            fail("损坏文件导致 unchecked 异常穿透（应返回失败 ToolResult）：" + e);
            return;
        }
        assertFalse("损坏文件应返回失败结果", r.ok);
        assertTrue("错误信息应说明编码已损坏，便于模型自救：" + r.output, r.output.contains("编码已损坏"));
    }

    // ---------------------------------------------------------------- T7

    /** 目录内混入损坏文件时 Grep 仍应完成搜索并返回其他文件的命中 */
    @Test
    public void grep_corruptFile_doesNotAbort() throws Exception {
        Files.write(p("ok.txt"), "命中关键词\n".getBytes(StandardCharsets.UTF_8));
        Files.write(p("corrupt.txt"), corruptBytes());
        GrepTool grep = new GrepTool(ws);
        ToolResult r = grep.execute(args("{\"pattern\":\"命中关键词\",\"path\":\".\"}"));
        assertTrue("含损坏文件时 Grep 应仍完成搜索：" + r.output, r.ok);
        assertTrue("应返回 ok.txt 的命中：" + r.output, r.output.contains("ok.txt"));
    }

    // ---------------------------------------------------------------- T9

    /** 目标路径的锁被其他线程持有时，工具须在超时后返回明确失败，而不是永久挂死 */
    @Test
    public void lockTimeout_returnsError() throws Exception {
        final Path target = p("busy.txt");
        Files.write(target, "原始内容".getBytes(StandardCharsets.UTF_8));
        long oldWait = PathLocks.WAIT_SECONDS;
        PathLocks.WAIT_SECONDS = 1;                     // 注入短超时，避免测试等 30 秒
        ReentrantLock held = PathLocks.forPath(target);
        held.lock();
        ExecutorService pool = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "atomic-test-lock");
                t.setDaemon(true);
                return t;
            }
        });
        try {
            Future<ToolResult> f = pool.submit(new Callable<ToolResult>() {
                public ToolResult call() throws Exception {
                    return write.execute(args("{\"path\":\"busy.txt\",\"content\":\"新内容\"}"));
                }
            });
            ToolResult r = f.get(15, TimeUnit.SECONDS);
            assertFalse("锁被占用超时应返回失败结果而非挂死", r.ok);
            assertTrue("错误信息应提示稍后重试：" + r.output, r.output.contains("正被其他操作占用"));
        } finally {
            pool.shutdownNow();
            held.unlock();
            PathLocks.WAIT_SECONDS = oldWait;
        }
    }

    // ================================================================ 辅助

    private interface TaskFactory {
        Callable<ToolResult> create(int idx, CyclicBarrier barrier);
    }

    /** 一轮并发执行的结果：结束后的文件字节 + 工具上报的错误（含抛出的异常） */
    private static final class RoundResult {
        final byte[] bytes;
        final List<String> errors;
        RoundResult(byte[] bytes, List<String> errors) {
            this.bytes = bytes;
            this.errors = errors;
        }
    }

    /**
     * 并发跑 rounds 轮 Edit：每轮重置为含 4 个标记的长文档，4 线程各改一处不同标记
     * （newString 长度不等，1:1 复刻事故场景）。
     */
    private List<RoundResult> runEditRounds(int rounds, final int threads) throws Exception {
        List<RoundResult> results = new ArrayList<RoundResult>();
        for (int r = 0; r < rounds; r++) {
            final Path target = p("doc.md");
            Files.write(target, docWithMarkers(threads).getBytes(StandardCharsets.UTF_8));
            List<String> errors = runConcurrently(threads, new TaskFactory() {
                public Callable<ToolResult> create(int idx, final CyclicBarrier barrier) {
                    final int i = idx;
                    return new Callable<ToolResult>() {
                        public ToolResult call() throws Exception {
                            barrier.await(20, TimeUnit.SECONDS);
                            return edit.execute(args("{\"path\":\"doc.md\",\"oldString\":\"标记"
                                    + i + ": 原始内容\",\"newString\":\"标记" + i + ": 已改"
                                    + repeat("改", i * 40 + 1) + "\"}"));
                        }
                    };
                }
            });
            results.add(new RoundResult(Files.readAllBytes(target), errors));
        }
        return results;
    }

    /** 用 threads 个线程 + CyclicBarrier 对齐起跑执行任务；返回所有"失败结果/抛出异常"的描述 */
    private static List<String> runConcurrently(int threads, TaskFactory factory) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads, new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "atomic-test");
                t.setDaemon(true);
                return t;
            }
        });
        List<String> errors = new ArrayList<String>();
        List<Future<ToolResult>> fs = new ArrayList<Future<ToolResult>>();
        try {
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(factory.create(i, barrier)));
            }
            for (int i = 0; i < fs.size(); i++) {
                try {
                    ToolResult res = fs.get(i).get(60, TimeUnit.SECONDS);
                    if (res == null || !res.ok) errors.add("任务" + i + " 失败: "
                            + (res == null ? "null" : res.output));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    errors.add("任务" + i + " 抛出 " + cause.getClass().getSimpleName()
                            + ": " + cause.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return errors;
    }

    /** 长文档（约 25KB 中文，与事故文件同量级），在若干深度插入不重复的标记行 */
    private static String docWithMarkers(int markerCount) {
        StringBuilder sb = new StringBuilder("# 设计文档\n\n");
        int sections = 150;
        int step = sections / (markerCount + 1);
        int next = 0;
        for (int i = 0; i < sections; i++) {
            if (next < markerCount && i == step * (next + 1)) {
                sb.append("标记").append(next).append(": 原始内容\n");
                next++;
            }
            sb.append("第").append(i).append("节：本节描述诊断过程、压力测试与资源配置的细节与理由，"
                    + "用于验证并发写入的原子性与完整性，确保文档在多次编辑后仍保持自洽。\n");
        }
        return sb.toString();
    }

    /** 复刻事故现场：合法文本 + 孤立续接字节 0x89 + 换行 —— 既非合法 UTF-8 也非合法 GBK */
    private static byte[] corruptBytes() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("# 事故复刻\n正文内容\n".getBytes(StandardCharsets.UTF_8));
        bos.write(0x89);
        bos.write('\n');
        return bos.toByteArray();
    }

    /** 严格 UTF-8 解码：非法序列/不可映射字符即判定为损坏 */
    private static boolean legalUtf8(byte[] b) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private Path p(String rel) { return Paths.get(work, rel); }
}
