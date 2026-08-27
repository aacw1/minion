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
    private Path tmpDir;

    @org.junit.Before
    public void setup() throws Exception {
        work = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(work);
        tmpDir = tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
        read = new ReadTool(ws);
        glob = new GlobTool(ws);
        grep = new GrepTool(ws, null, tmpDir.toString(), null);
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
        assertFalse("不超限不落盘", Files.exists(tmpDir));
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
        assertTrue(r.output.contains("完整结果已保存到 "));
        assertTrue(r.output.contains(tmpDir.toAbsolutePath().toString()));
        assertTrue(r.output.contains("可用 Read 查看"));
        Path dumpDir = tmpDir;
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

    /** 一行级：maxResults=0 时显示 0 条（旧实现先 append 后检查会多显示 1 条），
     *  全量仍落盘并提示路径 */
    @Test
    public void grep_maxZero_showsNothing_dumpWritten() throws Exception {
        Files.write(Paths.get(work, "m0.txt"), "needle zero-a".getBytes("UTF-8"));
        Files.write(Paths.get(work, "m1.txt"), "needle zero-b".getBytes("UTF-8"));
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\",\"maxResults\":0}"));
        assertTrue(r.ok);
        assertFalse("max=0 不应显示匹配内容: " + r.output, r.output.contains("needle zero"));
        assertTrue("应提示全量落盘: " + r.output, r.output.contains("共 2 条"));
        assertTrue(r.output.contains(tmpDir.toAbsolutePath().toString()));
        Path dumpDir = tmpDir;
        java.util.List<Path> files = Files.list(dumpDir).collect(java.util.stream.Collectors.toList());
        assertEquals(1, files.size());
        assertEquals(2, Files.readAllLines(files.get(0)).size());
    }

    /** 一行级：truncateLine 截断点（1000）切在代理对中间时丢弃孤立高代理。
     *  行 = 999a + emoji + 500b，substring(0,1000) 尾部恰为 emoji 高代理 */
    @Test
    public void grep_longLine_surrogateSafe() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 999; i++) sb.append('a');
        sb.append("\uD83D\uDE00"); // emoji，UTF-16 双 char
        for (int i = 0; i < 500; i++) sb.append('b');
        Files.write(Paths.get(work, "emoji.txt"), sb.toString().getBytes("UTF-8"));
        ToolResult r = grep.execute(args("{\"pattern\":\"aaaa\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("..."));
        assertNoLoneSurrogate("结果含孤立代理: " + r.output, r.output);
    }

    /** P2 降级文案：落盘失败（会话临时目录被占位成文件导致目录创建失败）时，
     *  不得提示"完整结果已保存到 <空路径>，可用 Read 查看"误导模型去读空路径 */
    @Test
    public void grep_dumpFailure_honestNote() throws Exception {
        Path tmpDirAsFile = tmp.getRoot().toPath().resolve("jar2").resolve(".session")
                .resolve("tmp").resolve("s1");
        Files.createDirectories(tmpDirAsFile.getParent());
        Files.write(tmpDirAsFile, "x".getBytes()); // 占位成普通文件 → 目录创建失败 → 落盘失败
        GrepTool g = new GrepTool(ws, null, tmpDirAsFile.toString(), null);
        for (int i = 0; i < 300; i++) {
            Files.write(Paths.get(work, "h" + i + ".txt"), ("needle line " + i).getBytes("UTF-8"));
        }
        ToolResult r = g.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.ok);
        assertTrue("缺降级说明: " + r.output, r.output.contains("落盘失败未保存完整结果"));
        assertFalse("不应提示保存到空路径: " + r.output, r.output.contains("完整结果已保存到 "));
        assertFalse("不应提示可 Read 查看: " + r.output, r.output.contains("可用 Read 查看"));
    }

    /** tmpDir 白名单：模型 Read 会话临时目录内文件免确认（jar 目录在工作路径之外，无 confirm 时直接放行） */
    @Test
    public void read_sessionTmpDir_allowedWithoutConfirm() throws Exception {
        Path workDir = tmp.newFolder("work3").toPath();
        Path jar = tmp.newFolder("jar3").toPath();
        Path tmpDir = jar.resolve(".session").resolve("tmp").resolve("s1");
        Files.createDirectories(tmpDir);
        Path dump = Files.write(tmpDir.resolve("bash-1.txt"), "hi".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(new Workspace(workDir.toString()), null, tmpDir.toString(), null);
        ToolResult res = r.execute(args("{\"path\":\"" + dump.toAbsolutePath().toString().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.ok);
        assertTrue(res.output.contains("hi"));
    }

    /** Grep 路径指向会话临时目录：守卫放行且可搜索（SKIP_SUBTREE 豁免搜索根自身；
     *  落盘文件本身单独跳过防自噬），不被拒绝 */
    @Test
    public void grep_sessionTmpDirPath_allowedWithoutConfirm() throws Exception {
        Path workDir = tmp.newFolder("work-grep").toPath();
        Path jar = tmp.newFolder("jar-grep").toPath();
        Path sessionTmp = jar.resolve(".session").resolve("tmp").resolve("s1");
        Files.createDirectories(sessionTmp);
        Files.write(sessionTmp.resolve("note.txt"), "hello world".getBytes(StandardCharsets.UTF_8));
        GrepTool grep = new GrepTool(new Workspace(workDir.toString()), null, sessionTmp.toString(), null); // confirm=null：被拒则硬拒绝
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "hello");
        args.addProperty("path", sessionTmp.toAbsolutePath().toString());
        ToolResult r = grep.execute(args);
        assertFalse("tmp 目录内路径不应被守卫拒绝: " + r.output, r.output.contains("路径在工作路径之外"));
        assertTrue("root==tmpDir 应可搜索到内容: " + r.output, r.output.contains("note.txt"));
    }

    /** 遍历检查字符串无孤立代理（每个高/低代理必须成对出现） */
    private static void assertNoLoneSurrogate(String msg, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(msg + "（孤立高代理 @" + i + "）",
                        i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
            } else if (Character.isLowSurrogate(c)) {
                assertTrue(msg + "（孤立低代理 @" + i + "）",
                        i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            }
        }
    }
}
