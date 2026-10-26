package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Grep/Glob 遍历有界化（WalkBudget）用例：
 * 大文件跳过、二进制跳过、条目/时间预算耗尽、中断终止、目录深度与目录环防护、
 * visitFileFailed 不整次失败。设计见 docs/superpowers/specs/2026-10-26-grep-glob-遍历有界化-design.md
 */
public class GrepGlobBudgetTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Workspace ws;
    private Path tmpDir;

    @org.junit.Before
    public void setup() throws Exception {
        ws = new Workspace(tmp.getRoot().getAbsolutePath());
        tmpDir = tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private Path p(String rel) { return tmp.getRoot().toPath().resolve(rel); }

    private void write(String rel, String text) throws Exception {
        Path f = p(rel);
        if (f.getParent() != null) Files.createDirectories(f.getParent());
        Files.write(f, text.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- Grep ----------

    @Test
    public void grep_skips_large_file() throws Exception {
        byte[] big = new byte[(int) GrepTool.MAX_FILE_BYTES + 16];
        Arrays.fill(big, (byte) 'a');
        System.arraycopy("needle".getBytes(StandardCharsets.UTF_8), 0, big, 0, 6);
        Files.write(p("big.txt"), big);

        GrepTool grep = new GrepTool(ws, null, tmpDir.toString(), null);
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.output, r.ok);
        assertFalse("超大文件必须跳过：" + r.output, r.output.contains("big.txt:"));
        assertTrue(r.output, r.output.contains("大于 5MB 的文件"));
    }

    @Test
    public void grep_skips_binary_file() throws Exception {
        Files.write(p("bin.dat"), new byte[]{'n', 'e', 'e', 'd', 'l', 'e', 0, 'x'});

        GrepTool grep = new GrepTool(ws, null, tmpDir.toString(), null);
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("二进制文件"));
        assertFalse(r.output.contains("bin.dat:"));
    }

    @Test
    public void grep_entry_budget_exhausted() throws Exception {
        for (int i = 0; i < 20; i++) write("f" + i + ".txt", "needle " + i);
        GrepTool grep = new GrepTool(ws, null, tmpDir.toString(), null);
        grep.budgetFactory = () -> new WalkBudget(5, 60_000L, 64);

        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.output, r.ok);
        assertTrue("预算耗尽必须给出截断说明：" + r.output, r.output.contains("遍历已截断"));
        assertTrue(r.output, r.output.contains("条目预算"));
    }

    @Test
    public void grep_time_budget_exhausted() throws Exception {
        write("a.txt", "needle");
        GrepTool grep = new GrepTool(ws, null, tmpDir.toString(), null);
        // 负预算：强制首个检查点即耗尽，专测时间预算文案分支
        grep.budgetFactory = () -> new WalkBudget(200_000, -1L, 64);

        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("时间预算"));
        assertTrue(r.output, r.output.contains("遍历已截断"));
    }

    @Test
    public void grep_interrupted_terminates() throws Exception {
        write("a.txt", "needle");
        GrepTool grep = new GrepTool(ws, null, tmpDir.toString(), null);
        ToolResult r;
        Thread.currentThread().interrupt();
        try {
            r = grep.execute(args("{\"pattern\":\"needle\"}"));
        } finally {
            Thread.interrupted(); // 清中断位：否则污染后续用例（Surefire 同线程执行）
        }
        assertTrue(r.output, r.ok);
        assertTrue("中断必须终止遍历：" + r.output, r.output.contains("遍历已中断"));
    }

    // ---------- Glob ----------

    @Test
    public void glob_depth_limit_skips_deep_dirs() throws Exception {
        write("a/b/c/deep.txt", "x");
        write("top.txt", "x");
        GlobTool glob = new GlobTool(ws);
        glob.budgetFactory = () -> new WalkBudget(200_000, 60_000L, 1);

        ToolResult r = glob.execute(args("{\"pattern\":\"**/*.txt\"}"));
        assertTrue(r.output, r.ok);
        assertTrue("顶层文件应命中：" + r.output, r.output.contains("top.txt"));
        assertFalse("超过深度上限的目录必须跳过：" + r.output, r.output.contains("deep.txt"));
    }

    @Test
    public void glob_entry_budget_exhausted() throws Exception {
        write("sub/x.txt", "x");
        write("y.txt", "x");
        GlobTool glob = new GlobTool(ws);
        glob.budgetFactory = () -> new WalkBudget(1, 60_000L, 64);

        ToolResult r = glob.execute(args("{\"pattern\":\"**/*.txt\"}"));
        assertTrue(r.output, r.ok);
        assertTrue("预算耗尽必须给出截断说明：" + r.output, r.output.contains("遍历已截断"));
    }

    @Test
    public void glob_interrupted_terminates() throws Exception {
        write("a.txt", "x");
        GlobTool glob = new GlobTool(ws);
        ToolResult r;
        Thread.currentThread().interrupt();
        try {
            r = glob.execute(args("{\"pattern\":\"*.txt\"}"));
        } finally {
            Thread.interrupted();
        }
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("遍历已中断"));
    }

    // ---------- WalkBudget ----------

    @Test
    public void walkbudget_file_failed_counts_and_continues() {
        WalkBudget b = WalkBudget.defaults();
        assertEquals(FileVisitResult.CONTINUE, b.fileFailed());
        assertNotNull(b.summary());
        assertTrue(b.summary(), b.summary().contains("1 个无权限项"));
        assertFalse("仅跳过项不算预算截断", b.incomplete());
    }

    @Test
    public void walkbudget_same_real_dir_visited_twice_is_skipped() throws Exception {
        Path root = tmp.getRoot().toPath().toAbsolutePath().normalize();
        WalkBudget b = WalkBudget.defaults();
        assertEquals(FileVisitResult.CONTINUE, b.enterDir(root, root));
        // 同一真实目录第二次进入 = 目录环（junction 指回祖先）：跳过该子树，不重复遍历
        assertEquals(FileVisitResult.SKIP_SUBTREE, b.enterDir(root, root));
        assertNull("无跳过项与预算截断时 summary 为 null（零文案变化）", b.summary());
    }

    @Test
    public void walkbudget_defaults_have_expected_budget() {
        assertEquals(30_000L, WalkBudget.MAX_MILLIS);
        assertEquals(200_000, WalkBudget.MAX_ENTRIES);
        assertEquals(64, WalkBudget.MAX_DEPTH);
        assertEquals("30 秒", WalkBudget.timeText(30_000L));
        assertEquals("500 毫秒", WalkBudget.timeText(500L));
    }

    @Test
    public void walkbudget_new_root_shares_budget_state() {
        WalkBudget root = new WalkBudget(1, 60_000L, 64);
        WalkBudget second = root.newRoot();
        assertTrue(root.checkFile());   // entries = 1
        assertFalse("多根共享条目预算", second.checkFile()); // entries = 2 > 1
        assertTrue(second.summary(), second.summary().contains("遍历已截断"));
    }
}
