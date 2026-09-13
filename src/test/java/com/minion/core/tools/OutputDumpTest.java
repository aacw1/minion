package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** 工具输出落盘：会话临时目录（jarDir/.session/tmp/<sessionId>）结构与 tail 尾部窗口切割 */
public class OutputDumpTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 会话临时目录（jarDir/.session/tmp/<sessionId>），tail/write 测试统一落点 */
    private Path sessionTmpDir() throws Exception {
        return tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
    }

    // ---------- write ----------

    @Test
    public void write_createsFileInSessionTmpDir() throws Exception {
        Path dir = sessionTmpDir();
        Path f = OutputDump.write(dir, "bash", "hello");
        assertNotNull(f);
        assertTrue("落盘须在会话临时目录内: " + f, f.toAbsolutePath().startsWith(dir.toAbsolutePath()));
        assertTrue(f.getFileName().toString().startsWith("bash-"));
        assertEquals("hello", new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
    }

    @Test
    public void write_nullTmpDir_returnsNull() {
        assertNull(OutputDump.write(null, "bash", "x"));
    }

    @Test
    public void write_createsNestedDirs() throws Exception {
        Path dir = tmp.getRoot().toPath().resolve("jar").resolve(".session").resolve("tmp").resolve("s2");
        Path f = OutputDump.write(dir, "grep", "x");
        assertNotNull(f);
        assertTrue(Files.exists(f));
    }

    @Test
    public void write_uniqueNames() throws Exception {
        Path dir = sessionTmpDir();
        Path a = OutputDump.write(dir, "bash", "a");
        Path b = OutputDump.write(dir, "bash", "b");
        assertNotEquals(a, b);
    }

    // ---------- tail（尾部窗口切割：UTF-8 多字节/代理对边界安全，原 tail 专项测试） ----------

    @Test
    public void tail_readsLastChars() throws Exception {
        Path f = OutputDump.write(sessionTmpDir(), "grep", "line1\nline2\nline3\n");
        assertEquals("line3\n", OutputDump.tail(f, 6));
        assertEquals("line1\nline2\nline3\n", OutputDump.tail(f, 100));
    }

    @Test
    public void tail_utf8Boundary_noBrokenChars() throws Exception {
        Path f = OutputDump.write(sessionTmpDir(), "bash", "中文内容行\nsecond line\n");
        String t = OutputDump.tail(f, 8); // 8 字符落在中文多字节中间
        assertFalse(t.contains("\uFFFD")); // 无替换符
        assertTrue(t.endsWith("line\n"));
    }

    /** 多字节密集内容（全中文 3 字节/字符）：尾部应恰好 maxChars 字符且无替换符
     *  （回归：旧实现窗口字节数不足，只返回约 1/3 字符） */
    @Test
    public void tail_denseMultibyte_exactChars_noBroken() throws Exception {
        String content = "中文内容行中文内容行中文内容行中文内容行中文内容行中文内容行"; // 30 字符 90 字节
        Path f = OutputDump.write(sessionTmpDir(), "bash", content);
        String t = OutputDump.tail(f, 8);
        assertEquals(8, t.length());
        assertFalse(t.contains("\uFFFD"));
        assertTrue(content.endsWith(t));
    }

    /** 文件字符数 ≤ maxChars：应返回全量（回归：旧实现字节窗口不足丢内容，且可能泄漏 U+FFFD） */
    @Test
    public void tail_multibyteWithinMaxChars_returnsAll() throws Exception {
        String content = "中文字符串内容测试行"; // 10 字符 30 字节
        Path f = OutputDump.write(sessionTmpDir(), "bash", content);
        assertEquals(content, OutputDump.tail(f, 12)); // 12 ≥ 10 → 全量
    }

    /** 4 字节字符（emoji，1 emoji = 2 个 UTF-16 char）：边界切割不产生孤立代理/替换符 */
    @Test
    public void tail_4byteChars() throws Exception {
        String content = "😀😀😀😀😀😀😀😀😀😀"; // 10 个 emoji = 40 字节 = 20 UTF-16 char
        Path f = OutputDump.write(sessionTmpDir(), "bash", content);
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
        Path f = OutputDump.write(sessionTmpDir(), "bash", "abc");
        assertEquals("", OutputDump.tail(f, 0));
        assertEquals("", OutputDump.tail(f, -3));
    }
}
