package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
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

    /** 会话临时目录白名单：tmpDir 内路径放行（jar 目录须在工作路径之外，白名单才真正生效） */
    @Test
    public void errorIfOutside_tmpDirAllowed() throws Exception {
        Path workDir = tmp.newFolder("work").toPath();
        Path tmpDir = tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
        Path f = Files.write(tmpDir.resolve("bash-1.txt"), "x".getBytes(StandardCharsets.UTF_8));
        assertNull(PathsGuard.errorIfOutside(new Workspace(workDir.toString()), null, tmpDir.toString(), f));
    }

    /** tmpDir 之外（jar 目录其他位置，且不在工作路径内）仍拦截 */
    @Test
    public void errorIfOutside_outsideTmpDirRejected() throws Exception {
        Path workDir = tmp.newFolder("work2").toPath();
        Path jar = tmp.newFolder("jar2").toPath();
        Path outside = Files.write(jar.resolve("config.txt"), "x".getBytes(StandardCharsets.UTF_8));
        String tmpDir = jar.resolve(".session").resolve("tmp").resolve("s1").toString();
        assertNotNull(PathsGuard.errorIfOutside(new Workspace(workDir.toString()), null, tmpDir, outside));
    }

    /** 额外放行目录（项目级技能目录）内放行；其兄弟目录仍拒绝；清空后回归拒绝 */
    @Test
    public void errorIfOutside_extraAllowedDir() throws Exception {
        Path work = tmp.newFolder("w-extra").toPath();
        Path projSkills = tmp.newFolder("outside-extra").toPath().resolve("skills");
        Path inside = projSkills.resolve("deploy/SKILL.md");
        Files.createDirectories(inside.getParent());
        Files.write(inside, "x".getBytes(StandardCharsets.UTF_8));
        Path sibling = projSkills.getParent().resolve("elsewhere").resolve("x.md");
        Files.createDirectories(sibling.getParent());
        Files.write(sibling, "x".getBytes(StandardCharsets.UTF_8));

        Workspace ws = new Workspace(work.toString());
        ws.setExtraAllowedDirs(java.util.Collections.singletonList(projSkills.toString()));
        assertNull(PathsGuard.errorIfOutside(ws, null, null, inside));
        assertNotNull(PathsGuard.errorIfOutside(ws, null, null, sibling));

        ws.setExtraAllowedDirs(new java.util.ArrayList<String>());   // 替换语义：清空即回归旧行为
        assertNotNull(PathsGuard.errorIfOutside(ws, null, null, inside));
    }

    /** 无额外放行目录时等价原行为：工作路径内放行、外部拒绝 */
    @Test
    public void errorIfOutside_noExtra_behavesAsBefore() throws Exception {
        Path work = tmp.newFolder("w-none").toPath();
        Path insideFile = Files.write(work.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Path outsideFile = Files.write(tmp.newFolder("out-none").toPath().resolve("b.txt"),
                "x".getBytes(StandardCharsets.UTF_8));
        Workspace ws = new Workspace(work.toString());
        assertNull(PathsGuard.errorIfOutside(ws, null, null, insideFile));
        assertNotNull(PathsGuard.errorIfOutside(ws, null, null, outsideFile));
    }

    /** 回归：放行目录自身尚未创建（会话临时目录/会话存储目录惰性创建）时，其下不存在的路径仍判在目录内 */
    @Test
    public void inside_dirItselfMissing_pathUnderIt_returnsTrue() throws Exception {
        Path missingDir = tmp.getRoot().toPath().resolve("jar")
                .resolve(".session").resolve("tmp").resolve("s1");
        assertTrue(PathsGuard.inside(missingDir.toString(), missingDir.resolve("report.md")));
    }

    /** dir 不存在时词法不在其下的路径仍判不在（不得因兜底整目录放宽） */
    @Test
    public void inside_dirMissing_pathElsewhere_returnsFalse() throws Exception {
        Path missingDir = tmp.getRoot().toPath().resolve("jar")
                .resolve(".session").resolve("tmp").resolve("s1");
        assertFalse(PathsGuard.inside(missingDir.toString(),
                tmp.getRoot().toPath().resolve("other.txt")));
    }

    /** 只读放行目录（会话存储目录）：其下路径放行；其外仍拒绝 */
    @Test
    public void errorIfOutsideRead_extraReadDir_allows() throws Exception {
        Path work = tmp.newFolder("w-read").toPath();
        Path sessionDir = tmp.newFolder("session-read", "wsA").toPath();
        Path f = Files.write(sessionDir.resolve("s1.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Workspace ws = new Workspace(work.toString());
        ws.setExtraReadDirs(java.util.Collections.singletonList(sessionDir.toString()));
        assertNull(PathsGuard.errorIfOutsideRead(ws, null, null, f));
        Path outside = Files.write(tmp.newFolder("other-read").toPath().resolve("x.txt"),
                "x".getBytes(StandardCharsets.UTF_8));
        assertNotNull("只读放行目录之外仍应拒绝", PathsGuard.errorIfOutsideRead(ws, null, null, outside));
    }

    /** 只读放行目录不作用于写守卫（errorIfOutside 口径不变），且读放行目录不存在时其下路径也放行 */
    @Test
    public void extraReadDir_notAppliedToWriteGuard_missingDirAllowedForRead() throws Exception {
        Path work = tmp.newFolder("w-read2").toPath();
        Path sessionDir = tmp.newFolder("session-read2").toPath();
        Path f = Files.write(sessionDir.resolve("s1.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Workspace ws = new Workspace(work.toString());
        ws.setExtraReadDirs(java.util.Collections.singletonList(sessionDir.toString()));
        assertNotNull("写守卫（errorIfOutside）不含只读放行目录", PathsGuard.errorIfOutside(ws, null, null, f));

        Path missingDir = tmp.getRoot().toPath().resolve("session-missing");
        Workspace ws2 = new Workspace(work.toString());
        ws2.setExtraReadDirs(java.util.Collections.singletonList(missingDir.toString())); // 放行目录本身未创建
        assertTrue("会话目录未创建时其下路径读放行",
                PathsGuard.insideReadExtra(ws2, missingDir.resolve("s9.json")));
        assertFalse("放行目录之外的路径仍不放行",
                PathsGuard.insideReadExtra(ws2, tmp.getRoot().toPath().resolve("other.json")));
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
