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

    // ---- P0-6：遍历不在锁内、缓存条目不可变、条目上限 ----

    /** 上限截断：最多收集 MAX_ENTRIES 条（提前收敛，不继续深入剩余目录） */
    @Test public void walk_capsAtMaxEntries() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.createDirectories(root.resolve("d"));
        for (int i = 0; i < 20; i++) {
            Files.write(root.resolve("d/f" + i + ".txt"), "x".getBytes(StandardCharsets.UTF_8));
        }
        int old = FileSuggester.MAX_ENTRIES;
        try {
            FileSuggester.MAX_ENTRIES = 5;
            assertEquals("超过上限应截断到上限", 5, FileSuggester.walk(root.toString()).size());
        } finally {
            FileSuggester.MAX_ENTRIES = old;
        }
        assertEquals("上限恢复后应全部收集", 20, FileSuggester.walk(root.toString()).size());
    }

    /** 缓存条目是不可变列表：读接口共享同一实例，调用方无法修改（避免每次敲键全量拷贝） */
    @Test public void load_cacheIsImmutableAndShared() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        List<Suggestion> loaded = fs.load(root.toString());
        List<Suggestion> stale = fs.listStaleOk(root.toString());
        List<Suggestion> cached = fs.listCached(root.toString());
        assertSame("load 返回的即缓存实例（无拷贝）", loaded, stale);
        assertSame("listCached 同样共享缓存实例", loaded, cached);
        try {
            loaded.add(new Suggestion("b.txt", "b.txt", null, Suggestion.Type.FILE));
            fail("缓存列表必须不可变");
        } catch (UnsupportedOperationException expected) {
            // 预期：不可变视图
        }
    }

    /** 结构约束（P0-6）：任何实例方法都不得持有监视器——walk 在锁外执行，
     *  FX 线程读缓存不被后台扫描阻塞（旧实现全类 synchronized 是卡顿根因） */
    @Test public void noMethodIsSynchronized() {
        for (java.lang.reflect.Method m : FileSuggester.class.getDeclaredMethods()) {
            assertFalse("不得在监视器内做 IO：" + m.getName(),
                    java.lang.reflect.Modifier.isSynchronized(m.getModifiers()));
        }
    }

    /** 后台 load 与 FX 线程读缓存并发：读调用不阻塞（读线程在遍历结束前即可返回） */
    @Test public void concurrentReads_notBlockedByLoad() throws Exception {
        Path root = tmp.getRoot().toPath();
        for (int i = 0; i < 400; i++) {
            Files.write(root.resolve("f" + i + ".txt"), "x".getBytes(StandardCharsets.UTF_8));
        }
        Path other = tmp.newFolder("other").toPath();
        Files.write(other.resolve("o.txt"), "x".getBytes(StandardCharsets.UTF_8));
        final FileSuggester fs = new FileSuggester();
        fs.load(other.toString()); // 先有新鲜缓存
        Thread loader = new Thread(() -> fs.load(root.toString()));
        loader.start();
        long t0 = System.nanoTime();
        // 高频读（FX 线程每敲一个字符的路径）：只要不被遍历锁住就能立即返回
        for (int i = 0; i < 1000; i++) {
            assertNotNull(fs.listStaleOk(other.toString()));
            assertTrue(fs.isFresh(other.toString()));
        }
        long elapsedMs = (System.nanoTime() - t0) / 1000000L;
        loader.join(10000);
        assertTrue("1000 次缓存读不应被后台遍历阻塞（耗时 " + elapsedMs + "ms）", elapsedMs < 1000);
    }
}
