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
