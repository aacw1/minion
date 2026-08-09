package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class FileToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private ReadTool read;
    private GlobTool glob;
    private GrepTool grep;

    @org.junit.Before
    public void setup() throws Exception {
        work = tmp.getRoot().getAbsolutePath();
        read = new ReadTool(work);
        glob = new GlobTool(work);
        grep = new GrepTool(work);
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

    private Path p(String rel) {
        return java.nio.file.Paths.get(work, rel);
    }
}
