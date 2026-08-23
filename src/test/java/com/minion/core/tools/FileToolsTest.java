package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class FileToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private Workspace ws;
    private ReadTool read;
    private GlobTool glob;
    private GrepTool grep;

    @org.junit.Before
    public void setup() throws Exception {
        work = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(work);
        read = new ReadTool(ws);
        glob = new GlobTool(ws);
        grep = new GrepTool(ws);
    }

    private JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void read_withLineNumbers() throws Exception {
        Files.write(p("a.txt"), "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"lineNumbers\":true}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("1: line1"));
        assertTrue(r.output.contains("3: line3"));
    }

    @Test
    public void read_outsideWorkDir_rejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-outside-test.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
    }

    @Test
    public void read_missingFile_error() throws Exception {
        ToolResult r = read.execute(args("{\"path\":\"nope.txt\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("文件不存在"));
    }

    /** 文件不存在且路径在工作区外：错误需提示"工作目录"，引导模型自纠（曾实测模型编造旧项目路径） */
    @Test
    public void read_missingOutsideWorkDir_hintsWorkDir() throws Exception {
        String missing = new File(System.getProperty("java.io.tmpdir"), "minion-no-such-file-xyz.txt").getAbsolutePath();
        ToolResult r = read.execute(args("{\"path\":\"" + missing.replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("文件不存在"));
        assertTrue(r.output.contains("工作目录"));
    }

    /** GBK 编码文件（如记事本 ANSI 保存）：UTF-8 解码失败后自动降级 GBK，内容正确并标注转码 */
    @Test
    public void read_gbkFile_autoDecoded() throws Exception {
        // 「阿诗丹顿」的 GBK 字节序列（8 字节 4 汉字）
        byte[] gbk = new byte[]{(byte) 0xB0, (byte) 0xA2, (byte) 0xCA, (byte) 0xAB,
                (byte) 0xB5, (byte) 0xA4, (byte) 0xB6, (byte) 0xD9};
        Files.write(p("gbk.txt"), gbk);
        ToolResult r = read.execute(args("{\"path\":\"gbk.txt\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("阿诗丹顿"));
        assertTrue(r.output.contains("[GBK 编码文件"));
    }

    /** UTF-8 文件：正常路径零打扰，不出现转码标注 */
    @Test
    public void read_utf8File_noDecodeBanner() throws Exception {
        Files.write(p("utf8.txt"), "你好".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"utf8.txt\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("你好"));
        assertFalse(r.output.contains("GBK"));
    }

    /** GBK 编码文件：搜索中文 pattern 应命中（现状静默跳过导致"未匹配"） */
    @Test
    public void grep_gbkFile_matches() throws Exception {
        Files.write(p("gbk.txt"), "阿诗丹顿".getBytes(Charset.forName("GBK")));
        ToolResult r = grep.execute(args("{\"pattern\":\"诗丹\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("gbk.txt"));
    }

    @Test
    public void glob_matches() throws Exception {
        Files.createDirectories(p("src/sub"));
        Files.write(p("src/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(p("src/sub/B.java"), "y".getBytes(StandardCharsets.UTF_8));
        Files.write(p("src/C.txt"), "z".getBytes(StandardCharsets.UTF_8));
        ToolResult r = glob.execute(args("{\"pattern\":\"**/*.java\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("A.java"));
        assertTrue(r.output.contains("B.java"));
        assertFalse(r.output.contains("C.txt"));
    }

    @Test
    public void grep_matchesWithContext() throws Exception {
        Files.write(p("a.java"), "public class A {}\nint count = 1;\n// count here".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("a.java:2:"));
        assertTrue(r.output.contains("a.java:3:"));
    }

    // ---- Round 1 review regression tests ----

    @Test
    public void read_traversal_rejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-outside-traversal.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"../minion-outside-traversal.txt\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
    }

    @Test
    public void read_invalidOffset_error() throws Exception {
        Files.write(p("a.txt"), "line1".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"offset\":\"abc\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("格式错误"));
    }

    @Test
    public void read_negativeOffset_error() throws Exception {
        Files.write(p("a.txt"), "line1".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"offset\":-1}"));
        assertFalse(r.ok);
    }

    @Test
    public void read_offsetOverflow_error() throws Exception {
        Files.write(p("a.txt"), "line1".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"offset\":2147483647,\"limit\":2000}"));
        assertFalse(r.ok);
    }

    @Test
    public void grep_outsideRejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-grep-outside.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret count value".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\",\"path\":\"../minion-grep-outside.txt\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
        ToolResult abs = grep.execute(args("{\"pattern\":\"count\",\"path\":\""
                + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertFalse(abs.ok);
        assertTrue(abs.output.contains("工作路径之外"));
    }

    @Test
    public void grep_invalidMaxResults_error() throws Exception {
        Files.write(p("a.java"), "count".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\",\"maxResults\":\"abc\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("格式错误"));
        ToolResult neg = grep.execute(args("{\"pattern\":\"count\",\"maxResults\":-1}"));
        assertFalse(neg.ok);
    }

    @Test
    public void glob_badPattern_error() throws Exception {
        ToolResult r = glob.execute(args("{\"pattern\":\"[\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("语法错误"));
    }

    // ---- 技能目录（工作路径外）放行 ----

    @Test
    public void skillsDir_allowsReadGlobGrep() throws Exception {
        Path skillsDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-skills-test-" + System.nanoTime());
        Path skillFile = skillsDir.resolve("debug").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.write(skillFile, "调试技能正文".getBytes(StandardCharsets.UTF_8));
        try {
            ReadTool r = new ReadTool(ws, skillsDir.toString());
            GlobTool g = new GlobTool(ws, skillsDir.toString());
            GrepTool gr = new GrepTool(ws, skillsDir.toString());
            String esc = skillFile.toString().replace("\\", "\\\\");

            ToolResult read = r.execute(args("{\"path\":\"" + esc + "\"}"));
            assertTrue(read.output, read.ok);
            assertTrue(read.output.contains("调试技能正文"));

            ToolResult glob = g.execute(args("{\"pattern\":\"**/SKILL.md\"}"));
            assertTrue(glob.output, glob.ok);
            assertTrue(glob.output, glob.output.contains("debug/SKILL.md")); // 技能目录内输出绝对路径

            ToolResult grep = gr.execute(args("{\"pattern\":\"技能\",\"path\":\""
                    + skillsDir.toString().replace("\\", "\\\\") + "\"}"));
            assertTrue(grep.output, grep.ok);
            assertTrue(grep.output, grep.output.contains("SKILL.md"));
        } finally {
            deleteRecursively(skillsDir);
        }
    }

    @Test
    public void skillsDir_notAllowed_whenNotConfigured() throws Exception {
        Path skillsDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-skills-test-" + System.nanoTime());
        Path skillFile = skillsDir.resolve("SKILL.md");
        Files.createDirectories(skillsDir);
        Files.write(skillFile, "secret".getBytes(StandardCharsets.UTF_8));
        try {
            // 未配置技能目录（单参构造）时，工作路径外的文件仍被拒绝
            ToolResult r = read.execute(args("{\"path\":\""
                    + skillFile.toString().replace("\\", "\\\\") + "\"}"));
            assertFalse(r.ok);
            assertTrue(r.output.contains("工作路径之外"));
        } finally {
            deleteRecursively(skillsDir);
        }
    }

    /** 读逃逸确认：构造可注入 ConfirmGate 的 Config（开关开/关由 allowOutside 控制） */
    private com.minion.core.config.Config readConfig(boolean allowOutside) throws Exception {
        com.minion.core.config.Config c = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        if (allowOutside) {
            Files.write(c.externalFile(),
                    "\npaths.read.allowOutside=true\n".getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            c = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        }
        return c;
    }

    // ---- 读逃逸：越界读确认放行 / 拒绝 / 开关自动放行 ----

    @Test
    public void read_outside_confirmApprove_allows() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-read-approve.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE)));
        ToolResult res = r.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("secret"));
    }

    @Test
    public void read_outside_confirmReject_rejects() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-read-reject.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.REJECT)));
        ToolResult res = r.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertFalse(res.ok);
        assertTrue(res.output.contains("工作路径之外"));
    }

    @Test
    public void read_outside_switchOn_autoAllows() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-read-switchon.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(true), new com.minion.core.tools.confirm.FakeConfirmUi()));
        ToolResult res = r.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("secret"));
    }

    @Test
    public void grep_outside_confirmApprove_allows() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-grep-approve.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret count value".getBytes(StandardCharsets.UTF_8));
        GrepTool g = new GrepTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE)));
        ToolResult res = g.execute(args("{\"pattern\":\"count\",\"path\":\""
                + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("secret count value"));
    }

    // ---- Glob path 参数：指定搜索根（工作区内直搜，工作区外走确认） ----

    @Test
    public void glob_pathParam_insideWork_finds() throws Exception {
        Files.createDirectories(p("src"));
        Files.write(p("src/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        ToolResult r = glob.execute(args("{\"pattern\":\"*.java\",\"path\":\"src\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output.contains("A.java"));
    }

    @Test
    public void glob_pathParam_outside_confirmApprove_allows() throws Exception {
        Path outside = java.nio.file.Files.createTempDirectory("minion-glob-outside");
        try {
            Files.write(outside.resolve("Z.java"), "x".getBytes(StandardCharsets.UTF_8));
            GlobTool g = new GlobTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                    readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                            com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE)));
            ToolResult res = g.execute(args("{\"pattern\":\"*.java\",\"path\":\""
                    + outside.toString().replace("\\", "\\\\") + "\"}"));
            assertTrue(res.output, res.ok);
            assertTrue(res.output.contains("Z.java"));
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    public void glob_pathParam_outside_confirmReject_rejects() throws Exception {
        Path outside = java.nio.file.Files.createTempDirectory("minion-glob-outside2");
        try {
            Files.write(outside.resolve("Z.java"), "x".getBytes(StandardCharsets.UTF_8));
            GlobTool g = new GlobTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                    readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                            com.minion.core.tools.confirm.ConfirmUi.Decision.REJECT)));
            ToolResult res = g.execute(args("{\"pattern\":\"*.java\",\"path\":\""
                    + outside.toString().replace("\\", "\\\\") + "\"}"));
            assertFalse(res.ok);
            assertTrue(res.output.contains("工作路径之外"));
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    public void glob_pathParam_missingPath_error() throws Exception {
        ToolResult r = glob.execute(args("{\"pattern\":\"*.java\",\"path\":\"./nope-dir\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("路径不存在"));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private Path p(String rel) {
        return java.nio.file.Paths.get(work, rel);
    }

    // ---- Grep 单行截断 + 全量落盘（工具输出截断落盘 Task 3） ----

    /** 超长单行：每条结果内容 ≤ 1000 字符 + 省略号，总字符可控（爆炸回归用例） */
    @Test
    public void grep_longLine_truncated() throws Exception {
        StringBuilder longLine = new StringBuilder("needle");
        for (int i = 0; i < 5000; i++) longLine.append('x');
        Files.write(Paths.get(work, "big.txt"), longLine.toString().getBytes("UTF-8"));
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.ok);
        String[] lines = r.output.split("\\r?\\n");
        assertEquals(1, lines.length); // 单文件单行
        // "big.txt:1: " 前缀 + 1000 内容 + "..." ≈ 1013
        assertTrue("结果行过长: " + lines[0].length(), lines[0].length() <= 1020);
        assertTrue(lines[0].endsWith("..."));
        // 不落盘（未超限）
        assertFalse(Files.exists(Paths.get(work, ".minion", "tmp")));
    }

    /** 超 250 条：显示 250 条 + 提示路径，落盘全量 */
    @Test
    public void grep_manyMatches_dumpWritten() throws Exception {
        for (int i = 0; i < 300; i++) {
            Files.write(Paths.get(work, "f" + i + ".txt"), ("needle line " + i).getBytes("UTF-8"));
        }
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.ok);
        int shown = r.output.split("needle", -1).length - 1;
        assertTrue("显示条数应 ≤250，实际 " + shown, shown <= 250);
        assertTrue(r.output.contains("完整结果已保存到 .minion/tmp/grep-"));
        assertTrue(r.output.contains("可用 Read 查看"));
        Path dumpDir = Paths.get(work, ".minion", "tmp");
        java.util.List<Path> files = Files.list(dumpDir).collect(java.util.stream.Collectors.toList());
        assertEquals(1, files.size());
        assertEquals(300, Files.readAllLines(files.get(0)).size());
    }

    /** maxResults 传超大值被钳制，不放大显示量（防爆炸） */
    @Test
    public void grep_maxResults_clamped() throws Exception {
        for (int i = 0; i < 300; i++) {
            Files.write(Paths.get(work, "g" + i + ".txt"), ("needle line " + i).getBytes("UTF-8"));
        }
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\",\"maxResults\":99999}"));
        int shown = r.output.split("needle", -1).length - 1;
        assertTrue(shown <= 250);
        assertTrue(r.output.contains("完整结果已保存到"));
    }
}
