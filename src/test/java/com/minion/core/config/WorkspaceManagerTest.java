package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class WorkspaceManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jarDir() throws IOException {
        return tmp.newFolder("jar").toPath();
    }

    /** 无文件时生成默认工作空间并落盘 */
    @Test
    public void load_createsDefaultWorkspace() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertEquals(1, m.list().size());
        assertEquals("default", m.list().get(0).workSpaceName);
        assertEquals(".", m.list().get(0).workDir);
        assertTrue(Files.exists(dir.resolve("workspace.json")));
    }

    /** 新增：重名/非法字符/空名拒绝 */
    @Test
    public void add_rejectsDuplicateAndIllegalNames() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        assertTrue(m.add("projA", "d:/a", "./project.md"));
        assertFalse(m.add("projA", "d:/b", ""));        // 重名
        assertFalse(m.add("", "d:/b", ""));             // 空名
        assertFalse(m.add("bad/name", "d:/b", ""));     // 含 / 非法字符
        assertFalse(m.add("bad:name", "d:/b", ""));     // 含 : 非法字符
        assertEquals(2, m.list().size());
    }

    /** 持久化：重载后列表与当前工作空间恢复 */
    @Test
    public void load_restoresPersistedState() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "./p.md");
        m.setCurrent("projA");
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals(2, m2.list().size());
        assertEquals("projA", m2.current().workSpaceName);
    }

    /** 重命名：列表更新 + session 目录迁移 */
    @Test
    public void rename_migratesSessionDir() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        Files.createDirectories(WorkspaceManager.sessionDirFor(dir, "projA"));
        Files.write(WorkspaceManager.sessionDirFor(dir, "projA").resolve("s1.json"),
                "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(m.rename("projA", "projB"));
        assertNull(m.get("projA"));
        assertNotNull(m.get("projB"));
        assertTrue(Files.exists(WorkspaceManager.sessionDirFor(dir, "projB").resolve("s1.json")));
        assertFalse(Files.exists(WorkspaceManager.sessionDirFor(dir, "projA")));
    }

    /** 重命名到已存在名拒绝，且不迁移 */
    @Test
    public void rename_rejectsExistingName() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        m.add("projB", "d:/b", "");
        assertFalse(m.rename("projA", "projB"));
        assertNotNull(m.get("projA"));
    }

    /** 删除：移除列表 + 删除 session 目录 */
    @Test
    public void remove_deletesSessionDir() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        Path sdir = WorkspaceManager.sessionDirFor(dir, "projA");
        Files.createDirectories(sdir);
        assertTrue(m.remove("projA"));
        assertNull(m.get("projA"));
        assertFalse(Files.exists(sdir));
    }

    /** 损坏文件：备份 .bak + 重建默认 */
    @Test
    public void load_corruptFileBacksUpAndRebuilds() throws IOException {
        Path dir = jarDir();
        Files.write(dir.resolve("workspace.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertEquals(1, m.list().size());
        assertTrue(Files.exists(dir.resolve("workspace.json.bak")));
    }

    /** current 指向的工作空间被删后回退到第一个 */
    @Test
    public void load_currentFallsBackWhenDeleted() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        m.setCurrent("projA");
        m.remove("projA");
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals("default", m2.current().workSpaceName);
    }
}
