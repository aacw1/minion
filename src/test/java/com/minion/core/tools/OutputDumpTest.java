package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.*;

public class OutputDumpTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path workDir() { return tmp.getRoot().toPath(); }

    @Test
    public void write_createsFileInDumpDir() throws Exception {
        Path f = OutputDump.write(workDir(), "bash", "hello\nworld\n");
        assertNotNull(f);
        assertTrue(Files.isRegularFile(f));
        assertTrue(f.toString().contains(".minion" + File.separator + "tmp"));
        assertEquals("hello\nworld\n", new String(Files.readAllBytes(f), "UTF-8"));
        assertTrue(f.getFileName().toString().startsWith("bash-"));
    }

    @Test
    public void write_uniqueNames() throws Exception {
        Path a = OutputDump.write(workDir(), "bash", "a");
        Path b = OutputDump.write(workDir(), "bash", "b");
        assertNotEquals(a, b);
    }

    @Test
    public void tail_readsLastChars() throws Exception {
        Path f = OutputDump.write(workDir(), "grep", "line1\nline2\nline3\n");
        assertEquals("line3\n", OutputDump.tail(f, 6));
        assertEquals("line1\nline2\nline3\n", OutputDump.tail(f, 100));
    }

    @Test
    public void tail_utf8Boundary_noBrokenChars() throws Exception {
        Path f = OutputDump.write(workDir(), "bash", "中文内容行\nsecond line\n");
        String t = OutputDump.tail(f, 8); // 8 字符落在中文多字节中间
        assertFalse(t.contains("\uFFFD")); // 无替换符
        assertTrue(t.endsWith("line\n"));
    }

    /** 多字节密集内容（全中文 3 字节/字符）：尾部应恰好 maxChars 字符且无替换符
     *  （回归：旧实现窗口字节数不足，只返回约 1/3 字符） */
    @Test
    public void tail_denseMultibyte_exactChars_noBroken() throws Exception {
        String content = "中文内容行中文内容行中文内容行中文内容行中文内容行中文内容行"; // 30 字符 90 字节
        Path f = OutputDump.write(workDir(), "bash", content);
        String t = OutputDump.tail(f, 8);
        assertEquals(8, t.length());
        assertFalse(t.contains("\uFFFD"));
        assertTrue(content.endsWith(t));
    }

    /** 文件字符数 ≤ maxChars：应返回全量（回归：旧实现字节窗口不足丢内容，且可能泄漏 U+FFFD） */
    @Test
    public void tail_multibyteWithinMaxChars_returnsAll() throws Exception {
        String content = "中文字符串内容测试行"; // 10 字符 30 字节
        Path f = OutputDump.write(workDir(), "bash", content);
        assertEquals(content, OutputDump.tail(f, 12)); // 12 ≥ 10 → 全量
    }

    /** 4 字节字符（emoji，1 emoji = 2 个 UTF-16 char）：边界切割不产生孤立代理/替换符 */
    @Test
    public void tail_4byteChars() throws Exception {
        String content = "😀😀😀😀😀😀😀😀😀😀"; // 10 个 emoji = 40 字节 = 20 UTF-16 char
        Path f = OutputDump.write(workDir(), "bash", content);
        String t = OutputDump.tail(f, 6); // 最多 6 个 UTF-16 char
        assertFalse(t.contains("\uFFFD"));
        assertFalse(hasLoneSurrogate(t)); // 无孤立代理（完整 emoji 或空）
        assertTrue(content.endsWith(t));  // 是文件内容后缀
        assertEquals(content, OutputDump.tail(f, 20)); // 恰好全量
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** maxChars ≤ 0：返回空串而非异常 */
    @Test
    public void tail_zeroOrNegativeMaxChars() throws Exception {
        Path f = OutputDump.write(workDir(), "bash", "abc");
        assertEquals("", OutputDump.tail(f, 0));
        assertEquals("", OutputDump.tail(f, -3));
    }

    /** 清理后 dump 目录可被删除（回归：Files.list 流未关闭导致 Windows 句柄泄漏） */
    @Test
    public void cleanup_releasesDirectoryHandle() throws Exception {
        Path f = OutputDump.write(workDir(), "bash", "x");
        // 将文件 mtime 调到过去，确保 deadline(now) 判定为超期
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        OutputDump.cleanup(workDir(), 0); // 全部视为超期
        assertTrue(Files.deleteIfExists(workDir().resolve(".minion").resolve("tmp")));
    }

    @Test
    public void cleanup_removesOnlyOld() throws Exception {
        Path old = OutputDump.write(workDir(), "bash", "old");
        Path fresh = OutputDump.write(workDir(), "bash", "fresh");
        Files.setLastModifiedTime(old, FileTime.fromMillis(
                System.currentTimeMillis() - 4L * 24 * 3600 * 1000));
        OutputDump.cleanup(workDir(), OutputDump.RETENTION_MS);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    @Test
    public void cleanup_missingDir_ok() throws Exception {
        OutputDump.cleanup(workDir().resolve(".minion").resolve("tmp"),
                1000); // 不抛异常即通过
    }

    @Test
    public void workDirRelative_forwardSlashes() throws Exception {
        Path f = OutputDump.write(workDir(), "grep", "x");
        String rel = OutputDump.workDirRelative(workDir(), f);
        assertTrue(rel.startsWith(".minion/tmp/grep-"));
        assertFalse(rel.contains("\\"));
    }
}
