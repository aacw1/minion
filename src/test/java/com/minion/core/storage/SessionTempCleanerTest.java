package com.minion.core.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.Assert.*;

/** 孤儿会话临时目录清理：文件生命周期 = 会话生命周期（正常由 SessionManager 删除，本类只兜底崩溃/删除失败残留） */
public class SessionTempCleanerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 构造 jarDir 结构：session/<workspace>/<id>.json + .session/tmp/<id>/… */
    private Path sessionRoot() throws Exception {
        return Files.createDirectories(tmp.newFolder("jar").toPath().resolve("session"));
    }

    private Path tmpRoot(Path sessionRoot) throws Exception {
        return Files.createDirectories(sessionRoot.getParent().resolve(".session").resolve("tmp"));
    }

    /** 把目录 mtime 调到过去（模拟陈旧孤儿） */
    private void age(Path dir, long ms) throws Exception {
        Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis() - ms));
    }

    @Test
    public void orphanDir_deleted() throws Exception {
        Path sp = sessionRoot();
        Path tp = tmpRoot(sp);
        Files.createDirectories(sp.resolve("ws"));
        Files.write(sp.resolve("ws").resolve("s1.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Path alive = Files.createDirectories(tp.resolve("s1"));       // 有会话：保留
        Path orphan = Files.createDirectories(tp.resolve("ghost"));   // 无会话：删除
        Files.write(orphan.resolve("bash-1.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(orphan.resolve("nested"));
        age(alive, 10L * 24 * 3600 * 1000);
        age(orphan, 10L * 24 * 3600 * 1000);

        SessionTempCleaner.cleanOrphans(sp, tp, 3600_000L);

        assertTrue("有会话的目录必须保留", Files.isDirectory(alive));
        assertFalse("孤儿目录必须整目录删除", Files.exists(orphan));
    }

    /** 宽限期内（mtime 新）的孤儿目录跳过：防极端竞态误删尚未落盘的活跃会话临时文件 */
    @Test
    public void freshOrphanDir_keptWithinGrace() throws Exception {
        Path sp = sessionRoot();
        Path tp = tmpRoot(sp);
        Path fresh = Files.createDirectories(tp.resolve("brand-new")); // mtime = now

        SessionTempCleaner.cleanOrphans(sp, tp, 3600_000L);

        assertTrue("宽限期内不清", Files.isDirectory(fresh));
    }

    /** 非 .json 文件不算存活会话（.json.bak / 其他文件名），对应 tmp 目录按孤儿处理 */
    @Test
    public void nonJsonFiles_doNotKeepSessionAlive() throws Exception {
        Path sp = sessionRoot();
        Path tp = tmpRoot(sp);
        Files.createDirectories(sp.resolve("ws"));
        Files.write(sp.resolve("ws").resolve("s1.json.bak"), "{}".getBytes(StandardCharsets.UTF_8));
        Path dir = Files.createDirectories(tp.resolve("s1.json"));
        age(dir, 10L * 24 * 3600 * 1000);

        SessionTempCleaner.cleanOrphans(sp, tp, 3600_000L);

        assertFalse(Files.exists(dir));
    }

    @Test
    public void missingRoots_silent() {
        Path nope = tmp.getRoot().toPath().resolve("nope");
        SessionTempCleaner.cleanOrphans(nope.resolve("session"), nope.resolve("tmp"), 0); // 不抛
    }
}
