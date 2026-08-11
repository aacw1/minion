package com.minion.core.tools;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Workspace:workDir 固定,cwd 可变,cd 仅限工作区内 */
public class WorkspaceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String workDir;
    private Workspace ws;

    @Before
    public void setUp() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(workDir);
    }

    @Test
    public void initialCwdIsWorkDir() {
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdIntoSubdir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        Path target = ws.cd("sub");
        assertNotNull(target);
        assertTrue(target.endsWith("sub"));
        assertEquals(target, ws.cwd());
    }

    @Test
    public void cdOutsideRejected() throws Exception {
        assertNull(ws.cd(".."));
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdUnknownDirRejected() {
        assertNull(ws.cd("不存在"));
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdEmptyReturnsToWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        ws.cd("sub");
        ws.cd("");
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdAbsoluteInsideWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        Path target = ws.cd(Paths.get(workDir, "sub").toAbsolutePath().toString());
        assertNotNull(target);
    }

    @Test
    public void cdAbsoluteOutsideRejected() throws Exception {
        // 在 java.io.tmpdir(workDir 的父目录)下新建目录,确保位于工作区之外
        Path outside = Files.createTempDirectory("ws-outside");
        assertNull(ws.cd(outside.toAbsolutePath().toString()));
    }

    @Test
    public void cdRelativeFromCurrentCwd() throws Exception {
        Files.createDirectories(Paths.get(workDir, "a", "b"));
        ws.cd("a");
        Path target = ws.cd("b");
        assertNotNull(target);
        assertTrue(target.endsWith("b"));
    }

    @Test
    public void restoreValidCwd() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        ws.restore(Paths.get(workDir, "sub").toAbsolutePath().toString());
        assertTrue(ws.cwd().endsWith("sub"));
    }

    @Test
    public void restoreInvalidCwdIgnored() throws Exception {
        ws.restore(Paths.get(workDir, "不存在").toAbsolutePath().toString());
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
        ws.restore(Paths.get(tmp.getRoot().getParentFile().getAbsolutePath()).toString());
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
        ws.restore(null);
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void resetCwdReturnsToWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        ws.cd("sub");
        ws.resetCwd();
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }
}
