package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** 路径守卫测试。重点回归：目标路径不存在（写新文件/新建目录）时不得误报越界。 */
public class PathsGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work() { return tmp.getRoot().toString(); }

    @Test
    public void inside_existingFileInside_returnsTrue() throws Exception {
        Path f = tmp.newFile("a.txt").toPath();
        assertTrue(PathsGuard.inside(work(), f));
    }

    /** 回归：截图/写文件目标还不存在，toRealPath 会失败，不得判为越界 */
    @Test
    public void inside_nonexistentFileInside_returnsTrue() throws Exception {
        Path p = tmp.getRoot().toPath().resolve("new-shot.png");
        assertTrue(PathsGuard.inside(work(), p));
    }

    /** 回归：目标在尚未创建的目录里，同样不得误报越界 */
    @Test
    public void inside_nonexistentPathInNewSubdirInside_returnsTrue() throws Exception {
        Path p = tmp.getRoot().toPath().resolve("newdir").resolve("new-shot.png");
        assertTrue(PathsGuard.inside(work(), p));
    }

    @Test
    public void inside_existingDirOutside_returnsFalse() throws Exception {
        Path outside = Files.createTempDirectory("minion-guard-outside");
        try {
            assertFalse(PathsGuard.inside(work(), outside));
        } finally {
            deleteRecursively(outside);
        }
    }

    /** 回归：越界目录里的新文件（文件本身不存在）必须仍判越界 */
    @Test
    public void inside_nonexistentPathOutside_returnsFalse() throws Exception {
        Path outside = Files.createTempDirectory("minion-guard-outside2");
        try {
            assertFalse(PathsGuard.inside(work(), outside.resolve("new.txt")));
        } finally {
            deleteRecursively(outside);
        }
    }

    /** 符号链接逃逸（已存在链接指向外部）必须仍判越界 */
    @Test
    public void inside_symlinkToOutside_returnsFalse() throws Exception {
        Path outside = Files.createTempDirectory("minion-guard-outside3");
        try {
            Path link = tmp.newFolder("link").toPath().resolve("escape");
            Files.createSymbolicLink(link, outside);
            assertFalse(PathsGuard.inside(work(), link));
        } catch (UnsupportedOperationException e) {
            // 无符号链接支持的环境跳过
        } catch (java.io.IOException e) {
            // Windows 无管理员权限创建符号链接失败时跳过
        } finally {
            deleteRecursively(outside);
        }
    }

    private static void deleteRecursively(Path p) throws Exception {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) {
            try (java.util.stream.Stream<Path> s = Files.walk(p)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                    try { Files.deleteIfExists(x); } catch (java.io.IOException ignored) { }
                });
            }
        } else {
            Files.deleteIfExists(p);
        }
    }
}
