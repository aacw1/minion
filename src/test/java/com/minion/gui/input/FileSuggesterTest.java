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

    @Test public void walk_missingDirReturnsEmpty() {
        assertTrue(FileSuggester.walk(tmp.getRoot().toPath().resolve("nope").toString()).isEmpty());
    }
}
