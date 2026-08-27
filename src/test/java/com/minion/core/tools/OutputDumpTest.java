package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.*;

/** 工具输出落盘：会话临时目录（jarDir/.session/tmp/<sessionId>）结构与清理 */
public class OutputDumpTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void write_createsFileInSessionTmpDir() throws Exception {
        Path dir = tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
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

    /** cleanup 扫所有会话子目录，只删修改超期文件 */
    @Test
    public void cleanup_deletesOnlyExpiredFiles_acrossSessions() throws Exception {
        Path root = tmp.newFolder("jar").toPath().resolve(".session").resolve("tmp");
        Path s1 = Files.createDirectories(root.resolve("s1"));
        Path s2 = Files.createDirectories(root.resolve("s2"));
        Path old1 = s1.resolve("bash-old.txt");
        Files.write(old1, "old".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(old1, FileTime.fromMillis(System.currentTimeMillis() - 4L * 24 * 3600 * 1000));
        Path fresh = s2.resolve("grep-fresh.txt");
        Files.write(fresh, "new".getBytes(StandardCharsets.UTF_8));

        OutputDump.cleanup(root, OutputDump.RETENTION_MS);

        assertFalse("超期文件应被清理: " + old1, Files.exists(old1));
        assertTrue("近期文件应保留: " + fresh, Files.exists(fresh));
    }

    @Test
    public void cleanup_missingRoot_silent() {
        OutputDump.cleanup(tmp.getRoot().toPath().resolve("nope"), OutputDump.RETENTION_MS); // 不抛
    }
}
