package com.minion;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

import com.minion.JdkResolver.Plan;

/** JdkResolver：JDK8 探测 + 启动决策纯逻辑（目录伪造 jfxrt.jar/java.exe，不依赖真实 JDK） */
public class JdkResolverTest {

    /** 建假 JDK 目录：<dir>/jre/lib/ext/jfxrt.jar + <dir>/bin/java.exe */
    private static Path fakeJdk(Path root, String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve("jre").resolve("lib").resolve("ext"));
        Files.write(dir.resolve("jre").resolve("lib").resolve("ext").resolve("jfxrt.jar"), new byte[0]);
        Files.createDirectories(dir.resolve("bin"));
        Files.write(dir.resolve("bin").resolve("java.exe"), new byte[0]);
        return dir;
    }

    private static Path fakeJavaExe(Path root, String name) throws IOException {
        Path exe = root.resolve(name).resolve("bin").resolve("java.exe");
        Files.createDirectories(exe.getParent());
        Files.write(exe, new byte[0]);
        return exe;
    }

    private static Map<String, String> env() { return new HashMap<String, String>(); }

    // ---------- isJdk8Version ----------

    @Test
    public void isJdk8Version_jdk8ReturnsTrue() {
        assertTrue(JdkResolver.isJdk8Version("1.8"));
        assertTrue(JdkResolver.isJdk8Version("1.8.0_401"));
    }

    @Test
    public void isJdk8Version_nonJdk8ReturnsFalse() {
        assertFalse(JdkResolver.isJdk8Version("17.0.1"));
        assertFalse(JdkResolver.isJdk8Version("9"));
        assertFalse(JdkResolver.isJdk8Version("1.7.0"));
        assertFalse(JdkResolver.isJdk8Version(""));
    }

    // ---------- hasJfx ----------

    @Test
    public void hasJfx_dirWithJfxrtReturnsTrue() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            Path jdk = fakeJdk(dir, "jdk8");
            assertTrue(JdkResolver.hasJfx(jdk));
            assertFalse(JdkResolver.hasJfx(dir));   // 无 jre/lib/ext/jfxrt.jar 的普通目录
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------- findJdk8 探测顺序 ----------

    @Test
    public void findJdk8_minionJavaExplicitTrustedWithoutJfx() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            Path exe = fakeJavaExe(dir, "custom");   // 无 jfxrt.jar
            Map<String, String> env = env();
            env.put("MINION_JAVA", exe.toString());
            assertEquals(exe, JdkResolver.findJdk8(env, new ArrayList<Path>()));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void findJdk8_minionJavaMissingSkipped() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            Map<String, String> env = env();
            env.put("MINION_JAVA", dir.resolve("no-such-java.exe").toString());
            assertNull(JdkResolver.findJdk8(env, new ArrayList<Path>()));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void findJdk8_javaHomeWithJfxHit() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            Path jdk = fakeJdk(dir, "java-home-jdk");
            Map<String, String> env = env();
            env.put("JAVA_HOME", jdk.toString());
            assertEquals(jdk.resolve("bin").resolve("java.exe"), JdkResolver.findJdk8(env, new ArrayList<Path>()));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void findJdk8_javaHomeWithoutJfxSkipped() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            Path plain = Files.createDirectories(dir.resolve("plain-home"));
            Map<String, String> env = env();
            env.put("JAVA_HOME", plain.toString());
            assertNull(JdkResolver.findJdk8(env, new ArrayList<Path>()));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void findJdk8_candidatesFirstHitWins() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            Path miss = Files.createDirectories(dir.resolve("no-fx"));
            Path hit1 = fakeJdk(dir, "jdk8-a");
            Path hit2 = fakeJdk(dir, "jdk8-b");
            List<Path> candidates = new ArrayList<Path>();
            candidates.add(miss);
            candidates.add(hit1);
            candidates.add(hit2);
            assertEquals(hit1.resolve("bin").resolve("java.exe"), JdkResolver.findJdk8(env(), candidates));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void findJdk8_noneFoundReturnsNull() throws IOException {
        Path dir = Files.createTempDirectory("jdk-test");
        try {
            List<Path> candidates = new ArrayList<Path>();
            candidates.add(Files.createDirectories(dir.resolve("a")));
            candidates.add(Files.createDirectories(dir.resolve("b")));
            assertNull(JdkResolver.findJdk8(env(), candidates));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void defaultCandidates_containsBatList() {
        List<Path> c = JdkResolver.defaultCandidates();
        assertTrue(c.size() >= 5);
        assertTrue(c.contains(Paths.get("D:\\javame\\jdk1.8")));
        assertTrue(c.contains(Paths.get("C:\\Program Files\\Zulu\\zulu-8")));
    }

    // ---------- decide 决策表 ----------

    @Test
    public void decide_notFromJar_runsDirect() {
        assertEquals(Plan.RUN_DIRECT, JdkResolver.decide(false, false, false, null, null, false).plan);
    }

    @Test
    public void decide_jdk8WithFxAndConsole_runsDirect() {
        assertEquals(Plan.RUN_DIRECT,
                JdkResolver.decide(true, true, true, "C:\\cur\\java.exe", null, true).plan);
    }

    @Test
    public void decide_jdk8WithFxNoConsole_relaunchCurrentWithNewConsole() {
        JdkResolver.Decision d = JdkResolver.decide(true, true, true, "C:\\cur\\java.exe", null, false);
        assertEquals(Plan.RELAUNCH, d.plan);
        assertEquals("C:\\cur\\java.exe", d.javaExe);
        assertTrue(d.newConsole);
    }

    @Test
    public void decide_nonJdk8FoundJava_relaunchInheritConsole() {
        JdkResolver.Decision d = JdkResolver.decide(true, false, true, "C:\\cur\\java.exe",
                "C:\\jdk8\\bin\\java.exe", true);
        assertEquals(Plan.RELAUNCH, d.plan);
        assertEquals("C:\\jdk8\\bin\\java.exe", d.javaExe);
        assertFalse(d.newConsole);
    }

    @Test
    public void decide_nonJdk8FoundJavaNoConsole_newConsole() {
        JdkResolver.Decision d = JdkResolver.decide(true, false, false, "C:\\cur\\java.exe",
                "C:\\jdk8\\bin\\java.exe", false);
        assertEquals(Plan.RELAUNCH, d.plan);
        assertTrue(d.newConsole);
    }

    @Test
    public void decide_nonJdk8NoFoundWithFx_runWithWarn() {
        assertEquals(Plan.RUN_WITH_WARN,
                JdkResolver.decide(true, false, true, "C:\\cur\\java.exe", null, true).plan);
    }

    @Test
    public void decide_nonJdk8NoFoundNoJfx_error() {
        assertEquals(Plan.ERROR_NO_JVM,
                JdkResolver.decide(true, false, false, "C:\\cur\\java.exe", null, true).plan);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
            try { Files.delete(f); } catch (IOException e) { throw new RuntimeException(e); }
        });
    }
}
