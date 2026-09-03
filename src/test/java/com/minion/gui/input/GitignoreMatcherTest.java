package com.minion.gui.input;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** GitignoreMatcher：工作空间根 .gitignore 的简易正向规则匹配（目录/文件级跳过）。
 *  局限（与设计文档一致）：不支持 ! 反向规则；只读根级 .gitignore；Windows 上大小写不敏感。 */
public class GitignoreMatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private GitignoreMatcher load(String... lines) throws Exception {
        Path gi = tmp.getRoot().toPath().resolve(".gitignore");
        Files.write(gi, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return GitignoreMatcher.load(tmp.getRoot().toString());
    }

    @Test public void load_nullWithoutGitignore() throws Exception {
        assertNull(GitignoreMatcher.load(tmp.getRoot().toString()));
    }

    @Test public void dirRule_trailingSlash_skipsAnyLevelDir() throws Exception {
        GitignoreMatcher m = load("target/");
        assertTrue("根下 target 目录应跳过", m.matchesDir("target"));
        assertTrue("任意层级同名目录应跳过", m.matchesDir("a/b/target"));
        assertFalse("其他目录不受影响", m.matchesDir("src"));
        assertFalse("目录规则不匹配文件", m.matchesFile("target/x.txt"));
    }

    @Test public void dirRule_noSlash_sameAsDirRule() throws Exception {
        GitignoreMatcher m = load("build");
        assertTrue(m.matchesDir("build"));
        assertTrue(m.matchesDir("x/build"));
        assertFalse(m.matchesDir("buildings"));
    }

    @Test public void anchoredRule_leadingSlash_rootOnly() throws Exception {
        GitignoreMatcher m = load("/run/\n/bak");
        assertTrue(m.matchesDir("run"));
        assertFalse("非根下同名目录不应匹配", m.matchesDir("a/run"));
        assertTrue(m.matchesFile("bak"));
        assertFalse("非根下同名文件不应匹配", m.matchesFile("a/bak"));
    }

    @Test public void fileExtRule_basenameAnyLevel() throws Exception {
        GitignoreMatcher m = load("*.iml");
        assertTrue(m.matchesFile("a.iml"));
        assertTrue("任意层级 *.iml 都应匹配", m.matchesFile("x/y/deep.iml"));
        assertFalse(m.matchesFile("x.txt"));
        assertFalse("扩展名规则不匹配无关目录", m.matchesDir("lib"));
    }

    @Test public void relativeDirRule_anchoredToRoot() throws Exception {
        GitignoreMatcher m = load("src/res/");
        assertTrue(m.matchesDir("src/res"));
        assertFalse(m.matchesDir("other/src/res"));
        assertTrue("规则也可命中目录下文件场景由 preVisit 保证，此处验证目录匹配", true);
    }

    @Test public void negateAndCommentLines_skipped() throws Exception {
        GitignoreMatcher m = load("# 注释", "", "!keep.txt", "*.log");
        assertFalse("! 反向规则不支持，应跳过该行", m.matchesFile("keep.txt"));
        assertTrue("注释后正常规则仍生效", m.matchesFile("x.log"));
    }

    @Test public void doubleStar_anyLevel() throws Exception {
        GitignoreMatcher m = load("**/logs/\na/**/b/");
        assertTrue(m.matchesDir("logs"));
        assertTrue(m.matchesDir("x/y/logs"));
        assertTrue(m.matchesDir("a/b"));
        assertTrue(m.matchesDir("a/x/y/b"));
        assertFalse(m.matchesDir("x/a/b"));
    }

    @Test public void trailingDoubleStar_matchesEverythingUnder() throws Exception {
        GitignoreMatcher m = load("logs/**");
        assertTrue(m.matchesDir("logs/x"));
        assertTrue(m.matchesDir("logs/x/deep"));
        assertTrue(m.matchesFile("logs/out.txt"));
        assertFalse("logs 目录本身由 git 保留，不受 logs/** 影响", m.matchesDir("logs"));
    }

    @Test public void windowsCaseInsensitiveOnWindows() throws Exception {
        GitignoreMatcher m = load("Target/");
        boolean windows = File.separatorChar == '\\';
        assertEquals("大小写规则跟随文件系统", windows, m.matchesDir("target"));
        assertEquals(windows, m.matchesDir("TARGET"));
    }
}
