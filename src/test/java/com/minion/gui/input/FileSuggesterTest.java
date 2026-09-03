package com.minion.gui.input;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class FileSuggesterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void walk_listsRelativePathsForwardSlashes() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.createDirectories(root.resolve("src/main/java/com/minion"));
        Files.write(root.resolve("src/main/java/com/minion/Main.java"),
                "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("README.md"), "x".getBytes(StandardCharsets.UTF_8));

        List<Suggestion> out = FileSuggester.walk(root.toString());
        boolean hasMain = false;
        boolean hasReadme = false;
        for (Suggestion s : out) {
            if ("src/main/java/com/minion/Main.java".equals(s.label)) hasMain = true;
            if ("README.md".equals(s.label)) hasReadme = true;
        }
        assertTrue("应包含相对路径文件", hasMain);
        assertTrue("应包含根文件", hasReadme);
    }

    @Test public void walk_skipsDotDirs() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve(".git/config"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve(".idea"));
        Files.write(root.resolve(".idea/misc.xml"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));

        List<Suggestion> out = FileSuggester.walk(root.toString());
        for (Suggestion s : out) {
            assertFalse("不应包含 .git/.idea 内容: " + s.label, s.label.contains(".git"));
            assertFalse("不应包含 .idea 内容: " + s.label, s.label.contains(".idea"));
        }
        assertEquals(1, out.size());
    }

    @Test public void list_cachesWithin10Seconds() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        List<Suggestion> first = fs.list(root.toString());
        assertEquals(1, first.size());
        // 缓存期间新增文件不重扫
        Files.write(root.resolve("b.txt"), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, fs.list(root.toString()).size());
    }

    @Test public void listCached_missBeforeAnyLoad() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        assertNull("未加载过不应触发 IO", fs.listCached(root.toString()));
    }

    @Test public void load_fillsCacheThenListCachedHits() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        List<Suggestion> loaded = fs.load(root.toString());
        assertEquals(1, loaded.size());
        List<Suggestion> hit = fs.listCached(root.toString());
        assertNotNull("load 后缓存应命中", hit);
        assertEquals(1, hit.size());
        assertEquals("a.txt", hit.get(0).label);
    }

    @Test public void load_wrongDirNotCached() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        fs.load(root.toString());
        assertNull(fs.listCached(tmp.getRoot().toPath().resolve("other").toString()));
    }

    @Test public void caches_perWorkspaceIndependent() throws Exception {
        Path p1 = tmp.newFolder("p1").toPath();
        Files.write(p1.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Path p2 = tmp.newFolder("p2").toPath();
        Files.write(p2.resolve("b.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(p2.resolve("c.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        fs.load(p1.toString());
        fs.load(p2.toString());
        List<Suggestion> h1 = fs.listCached(p1.toString());
        List<Suggestion> h2 = fs.listCached(p2.toString());
        assertNotNull("p1 缓存应保留", h1);
        assertEquals(1, h1.size());
        assertEquals("a.txt", h1.get(0).label);
        assertNotNull("p2 缓存应保留", h2);
        assertEquals(2, h2.size());
        // p2 缓存不混入 p1 内容
        assertEquals("b.txt", h2.get(0).label);
        assertEquals("c.txt", h2.get(1).label);
    }

    @Test public void reload_sameDirReturnsCache() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        List<Suggestion> first = fs.load(root.toString());
        // 缓存期内新增文件不重扫
        Files.write(root.resolve("b.txt"), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, fs.load(root.toString()).size());
        assertEquals(first.size(), fs.load(root.toString()).size());
    }

    @Test public void stale_cacheStillReadableForInstantPopup() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        fs.load(root.toString());
        long old = FileSuggester.CACHE_TTL_MS;
        try {
            FileSuggester.CACHE_TTL_MS = -1; // 立即过期
            assertFalse("过期应判定为不新鲜", fs.isFresh(root.toString()));
            assertNull("新鲜接口返回 null", fs.listCached(root.toString()));
            List<Suggestion> stale = fs.listStaleOk(root.toString());
            assertNotNull("过期缓存仍可即时读取", stale);
            assertEquals("a.txt", stale.get(0).label);
        } finally {
            FileSuggester.CACHE_TTL_MS = old;
        }
    }

    @Test public void stale_reloadRefreshesCache() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        fs.load(root.toString());
        long old = FileSuggester.CACHE_TTL_MS;
        try {
            FileSuggester.CACHE_TTL_MS = -1;
            Files.write(root.resolve("b.txt"), "x".getBytes(StandardCharsets.UTF_8));
            List<Suggestion> refreshed = fs.load(root.toString()); // 过期：重扫
            assertEquals("过期后 load 应重扫含新文件", 2, refreshed.size());
        } finally {
            FileSuggester.CACHE_TTL_MS = old;
        }
        assertTrue("重扫写入的新缓存应新鲜", fs.isFresh(root.toString()));
    }

    @Test public void noCache_anyApiReturnsNull() throws Exception {
        Path root = tmp.newFolder("empty2").toPath();
        FileSuggester fs = new FileSuggester();
        assertFalse(fs.isFresh(root.toString()));
        assertNull(fs.listStaleOk(root.toString()));
    }

    @Test public void walk_missingDirReturnsEmpty() {
        assertTrue(FileSuggester.walk(tmp.getRoot().toPath().resolve("nope").toString()).isEmpty());
    }

    @Test public void walk_noLimit_collectsAllFiles() throws Exception {
        // 超过旧 200 上限：全部收录（不截断）
        Path root = tmp.getRoot().toPath();
        for (int d = 0; d < 5; d++) {
            Path dir = root.resolve("dir" + d);
            Files.createDirectories(dir);
            for (int i = 0; i < 60; i++) {
                Files.write(dir.resolve("f" + i + ".txt"), "x".getBytes(StandardCharsets.UTF_8));
            }
        }
        List<Suggestion> out = FileSuggester.walk(root.toString());
        assertEquals("超过 200 个文件也应全部收录", 300, out.size());
    }

    @Test public void walk_skipsGitIgnoredDirs() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve(".gitignore"), "target/\n/run/\n*.log\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("target"));
        Files.write(root.resolve("target/a.class"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("run"));
        Files.write(root.resolve("run/session.bin"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("src/main"));
        Files.write(root.resolve("src/main/x.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("src/main/debug.log"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("deep/target"));
        Files.write(root.resolve("deep/target/out.bin"), "x".getBytes(StandardCharsets.UTF_8));

        List<Suggestion> out = FileSuggester.walk(root.toString());
        for (Suggestion s : out) {
            assertFalse("不应包含 target: " + s.label, s.label.startsWith("target/"));
            assertFalse("不应包含 run: " + s.label, s.label.startsWith("run/"));
            assertFalse("不应包含 *.log: " + s.label, s.label.endsWith(".log"));
            assertFalse("深层 target 目录也不应包含: " + s.label, s.label.startsWith("deep/target/"));
        }
        assertEquals("仅剩 src/main/x.txt", 1, out.size());
    }

    @Test public void walk_sortedByLabelLexicographically() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("c.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("b"));
        Files.write(root.resolve("b/x.txt"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("b/a.txt"), "x".getBytes(StandardCharsets.UTF_8));

        List<Suggestion> out = FileSuggester.walk(root.toString());
        assertEquals(4, out.size());
        assertEquals("a.txt", out.get(0).label);
        assertEquals("b/a.txt", out.get(1).label);
        assertEquals("b/x.txt", out.get(2).label);
        assertEquals("c.txt", out.get(3).label);
    }
}
