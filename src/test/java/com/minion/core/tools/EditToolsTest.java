package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class EditToolsTest {

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

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private Path p(String rel) { return Paths.get(work, rel); }

    @Test
    public void skillsDir_allowsWriteEdit() throws Exception {
        Path skillsDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-skills-write-" + System.nanoTime());
        Path skillFile = skillsDir.resolve("t").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.write(skillFile, "旧内容".getBytes(StandardCharsets.UTF_8));
        try {
            WriteTool w = new WriteTool(ws, skillsDir.toString());
            EditTool e = new EditTool(ws, skillsDir.toString());
            String esc = skillFile.toString().replace("\\", "\\\\");

            ToolResult wr = w.execute(args("{\"path\":\"" + esc + "\",\"content\":\"新内容\"}"));
            assertTrue(wr.output, wr.ok);

            ToolResult er = e.execute(args("{\"path\":\"" + esc
                    + "\",\"oldString\":\"新内容\",\"newString\":\"更新后\"}"));
            assertTrue(er.output, er.ok);
            assertEquals("更新后",
                    new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8));
        } finally {
            deleteRecursively(skillsDir);
        }
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

    @Test
    public void write_newFile_andRiskFalse() throws Exception {
        assertFalse(write.isHighRisk(args("{\"path\":\"new.txt\"}"))); // 尚不存在
        ToolResult r = write.execute(args("{\"path\":\"new.txt\",\"content\":\"hello\"}"));
        assertTrue(r.ok);
        assertEquals("hello", new String(Files.readAllBytes(p("new.txt")), StandardCharsets.UTF_8));
        assertTrue(write.isHighRisk(args("{\"path\":\"new.txt\"}"))); // 写入后覆盖需确认
    }

    @Test
    public void write_overwrite_highRisk() throws Exception {
        Files.write(p("a.txt"), "old".getBytes(StandardCharsets.UTF_8));
        assertTrue(write.isHighRisk(args("{\"path\":\"a.txt\"}")));
        ToolResult r = write.execute(args("{\"path\":\"a.txt\",\"content\":\"new\"}"));
        assertTrue(r.ok);
        assertEquals("new", new String(Files.readAllBytes(p("a.txt")), StandardCharsets.UTF_8));
    }

    @Test
    public void edit_replace_matches() throws Exception {
        Files.write(p("b.txt"), "int x = 1;\nint y = 2;".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"int x = 1;\",\"newString\":\"int x = 100;\"}"));
        assertTrue(r.ok);
        String content = new String(Files.readAllBytes(p("b.txt")), StandardCharsets.UTF_8);
        assertTrue(content.contains("int x = 100;"));
        assertFalse(content.contains("int x = 1;"));
    }

    @Test
    public void edit_crlfFile_matchesLfOldString() throws Exception {
        // Windows 下 Git 检出文件多为 CRLF；Read 工具剥离行尾后 agent 提供的
        // oldString 是 LF——精确匹配必须忽略行尾差异，否则"未找到待替换内容"
        Files.write(p("b.txt"), "int x = 1;\r\nint y = 2;".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"int x = 1;\",\"newString\":\"int x = 100;\"}"));
        assertTrue(r.ok);
        String content = new String(Files.readAllBytes(p("b.txt")), StandardCharsets.UTF_8);
        assertTrue("写回应保持 CRLF", content.contains("int x = 100;\r\nint y = 2;"));
    }

    @Test
    public void edit_crlfFile_matchesMultiLineLfOldString() throws Exception {
        Files.write(p("b.txt"), "a\r\nb\r\nc".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"a\\nb\",\"newString\":\"A\\nB\"}"));
        assertTrue(r.output, r.ok);
        String content = new String(Files.readAllBytes(p("b.txt")), StandardCharsets.UTF_8);
        assertTrue("多行替换后仍保持 CRLF", content.contains("A\r\nB\r\nc"));
    }

    /** GBK 编码文件（记事本 ANSI 保存）：编辑成功且写回仍为 GBK，不得重编码破坏其余内容 */
    @Test
    public void edit_gbkFile_editsAndPreservesEncoding() throws Exception {
        Files.write(p("b.txt"), "阿诗丹顿".getBytes(Charset.forName("GBK")));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"阿诗丹顿\",\"newString\":\"阿诗丹顿2\"}"));
        assertTrue(r.output, r.ok);
        String gbkDecoded = new String(Files.readAllBytes(p("b.txt")), Charset.forName("GBK"));
        assertEquals("写回应保持 GBK 编码（若被重编码为 UTF-8，GBK 解码是乱码）", "阿诗丹顿2", gbkDecoded);
    }

    @Test
    public void edit_noMatch_returnsError() throws Exception {
        Files.write(p("b.txt"), "abc".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"zzz\",\"newString\":\"x\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("未找到"));
    }

    @Test
    public void edit_multiMatch_requiresReplaceAll() throws Exception {
        Files.write(p("b.txt"), "x=1\nx=2".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"x=\",\"newString\":\"y=\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("多处匹配"));
        ToolResult r2 = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"x=\",\"newString\":\"y=\",\"replaceAll\":true}"));
        assertTrue(r2.ok);
        assertTrue(new String(Files.readAllBytes(p("b.txt")), StandardCharsets.UTF_8).contains("y="));
    }

    @Test
    public void edit_alwaysHighRisk() {
        assertTrue(edit.isHighRisk(new JsonObject()));
    }

    @Test
    public void write_outsideRejected() throws Exception {
        Path outside = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "minion-out-w.txt");
        ToolResult r = write.execute(args("{\"path\":\"" + outside.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}"));
        assertFalse(r.ok);
        assertFalse(Files.exists(outside));
    }

    /** 会话临时目录尚未创建（会话首次写入/Bash/Grep 不超限执行后目录被清空）时，
     *  tmpDir 内新文件写入应放行（词法兜底），并自动创建目录 */
    @Test
    public void write_tmpDirNotExists_newFileAllowed() throws Exception {
        Path workDir = tmp.newFolder("work-write").toPath();
        Path jar = tmp.newFolder("jar-write").toPath();
        String tmpDirStr = jar.resolve(".session").resolve("tmp").resolve("s1").toString();
        assertFalse("前置：tmpDir 不存在（词法兜底场景）", Files.exists(Paths.get(tmpDirStr)));
        WriteTool w = new WriteTool(new Workspace(workDir.toString()), null, tmpDirStr);
        Path target = Paths.get(tmpDirStr, "new.txt");
        ToolResult r = w.execute(args("{\"path\":\"" + target.toString().replace("\\", "\\\\")
                + "\",\"content\":\"hello\"}"));
        assertTrue(r.output, r.ok);
        assertTrue("应提示已写入: " + r.output, r.output.contains("已写入"));
        assertFalse("不应被拒绝: " + r.output, r.output.contains("已拒绝"));
        assertTrue("tmpDir 应被自动创建", Files.isDirectory(Paths.get(tmpDirStr)));
        assertEquals("hello", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    // ---- Round 1 review regression tests ----

    @Test
    public void edit_emptyOldString_error() throws Exception {
        Files.write(p("b.txt"), "abc".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"\",\"newString\":\"x\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("不能为空"));
    }

    @Test
    public void write_symlinkDirNewFile_rejected() throws Exception {
        // I-2 回归：workDir 内符号链接目录指向外部时，新建文件不得经符号链接落盘到外部。
        // Windows 创建符号链接需管理员权限/开发者模式；无权限时退化为等价构造
        //（已存在的外部父目录 + 不存在的子文件，同样走"最深已存在祖先"真实路径校验）。
        String name = "minion-out-new-" + System.nanoTime() + ".txt";
        boolean realLink = true;
        try {
            Files.createSymbolicLink(p("link"), java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
        } catch (Exception e) {
            realLink = false;
        }
        if (realLink) {
            Path target = p("link/" + name);
            ToolResult r = write.execute(args("{\"path\":\"link/" + name + "\",\"content\":\"x\"}"));
            assertFalse(r.ok);
            assertTrue(r.output.contains("工作路径之外"));
            assertFalse(Files.exists(target));
        } else {
            Path outsideDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                    "minion-out-parent-" + System.nanoTime());
            Files.createDirectories(outsideDir);
            outsideDir.toFile().deleteOnExit();
            Path target = outsideDir.resolve(name);
            ToolResult r = write.execute(args("{\"path\":\"" + target.toString().replace("\\", "\\\\") + "\",\"content\":\"x\"}"));
            assertFalse(r.ok);
            assertTrue(r.output.contains("工作路径之外"));
            assertFalse(Files.exists(target));
        }
    }

    // ---- Round 2 review regression tests ----

    /** Round 2 回归：tmpDir 内符号链接目录指向外部时，新建文件不得经符号链接落盘到外部。
     *  b1dd803 词法兜底过宽：probe 为 tmpDir 内符号链接时真实路径在外部，但 p 词法在 tmpDir
     *  内使兜底条件不成立即放行（逃逸）；修复后 probe 词法在 tmpDir 内时以真实路径校验为准。
     *  链接构造三级降级：真实符号链接（Linux/macOS/管理员 Windows）→ mklink /J 联结
     * （无需管理员权限，toRealPath 同样跟随）→ 已存在的外部父目录 + 不存在的子文件
     * （等价构造，同样走"最深已存在祖先"真实路径校验）。 */
    @Test
    public void write_symlinkInsideTmpDir_rejected() throws Exception {
        Path workDir = tmp.newFolder("work-symtmp-").toPath();
        Path sessionTmp = tmp.newFolder("session-tmp-").toPath();
        WriteTool w = new WriteTool(new Workspace(workDir.toString()), null, sessionTmp.toString());
        Path outsideDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-out-junc-" + System.nanoTime());
        Files.createDirectories(outsideDir);
        outsideDir.toFile().deleteOnExit();
        String name = "minion-out-new-" + System.nanoTime() + ".txt";
        Path link = sessionTmp.resolve("link");
        boolean linked = false;
        try {
            Files.createSymbolicLink(link, outsideDir);
            linked = true;
        } catch (Exception e) {
            linked = createJunction(link, outsideDir);
        }
        if (linked) {
            Path target = sessionTmp.resolve("link/" + name);
            target.toFile().deleteOnExit();
            ToolResult r = w.execute(args("{\"path\":\"" + target.toString().replace("\\", "\\\\")
                    + "\",\"content\":\"x\"}"));
            assertFalse(r.output, r.ok);
            assertTrue(r.output.contains("工作路径之外"));
            assertFalse(Files.exists(target));
        } else {
            Path target = outsideDir.resolve(name);
            ToolResult r = w.execute(args("{\"path\":\"" + target.toString().replace("\\", "\\\\")
                    + "\",\"content\":\"x\"}"));
            assertFalse(r.output, r.ok);
            assertTrue(r.output.contains("工作路径之外"));
            assertFalse(Files.exists(target));
        }
    }

    /** Windows 目录联结（cmd mklink /J）：无需管理员权限/开发者模式即可创建，
     *  toRealPath 与符号链接一样跟随目标，可用于复现符号链接逃逸 */
    private static boolean createJunction(Path link, Path target) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    "\"" + link.toString() + "\"", "\"" + target.toString() + "\"")
                    .redirectErrorStream(true).start();
            p.waitFor();
            return Files.exists(link);
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void edit_outsideRejected() throws Exception {
        Path outside = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "minion-edit-out.txt");
        Files.write(outside, "abc".getBytes(StandardCharsets.UTF_8));
        outside.toFile().deleteOnExit();
        ToolResult r = edit.execute(args("{\"path\":\"" + outside.toString().replace("\\", "\\\\") + "\",\"oldString\":\"a\",\"newString\":\"b\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
    }

    @Test
    public void write_toDirectory_error() throws Exception {
        Files.createDirectories(p("adir"));
        ToolResult r = write.execute(args("{\"path\":\"adir\",\"content\":\"x\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("是目录"));
    }
}
