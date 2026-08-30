package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * 路径参数说明：core 的 add/update 要求项目路径与项目级技能路径都是**实际存在的文件夹**，
 * 因此本类一律用 {@link #dir} 造真实目录，不用 d:/a 这类假路径。
 */
public class WorkspaceManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jarDir() throws IOException {
        return tmp.newFolder("jar").toPath();
    }

    /** 造一个真实存在的目录（同名重复调用返回同一路径），返回绝对路径字符串 */
    private String dir(String name) throws IOException {
        return mkdir(new File(tmp.getRoot(), name));
    }

    /** 在已有目录下造真实子目录 */
    private String sub(String parent, String name) throws IOException {
        return mkdir(new File(parent, name));
    }

    private String mkdir(File f) throws IOException {
        if (!f.exists() && !f.mkdirs()) throw new IOException("无法创建测试目录: " + f);
        return f.getAbsolutePath();
    }

    /** 在真实目录下造一个确实存在的文件，返回「./文件名」相对写法（主说明文件校验用） */
    private String mdIn(String parentDir, String name) throws IOException {
        File f = new File(parentDir, name);
        if (!f.getParentFile().exists() && !f.getParentFile().mkdirs()) {
            throw new IOException("无法创建目录: " + f.getParentFile());
        }
        if (!f.exists() && !f.createNewFile()) throw new IOException("无法创建文件: " + f);
        return "./" + name;
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
        String a = dir("a");
        assertTrue(m.add("projA", a, mdIn(a, "project.md"), null));
        assertFalse(m.add("projA", dir("b"), "", null));        // 重名
        assertFalse(m.add("", dir("b"), "", null));             // 空名
        assertFalse(m.add("bad/name", dir("b"), "", null));     // 含 / 非法字符
        assertFalse(m.add("bad:name", dir("b"), "", null));     // 含 : 非法字符
        assertEquals(2, m.list().size());
    }

    /** 持久化：重载后列表与当前工作空间恢复 */
    @Test
    public void load_restoresPersistedState() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        String a = dir("a");
        m.add("projA", a, mdIn(a, "p.md"), null);
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
        m.add("projA", dir("a"), "", null);
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
        m.add("projA", dir("a"), "", null);
        m.add("projB", dir("b"), "", null);
        assertFalse(m.rename("projA", "projB"));
        assertNotNull(m.get("projA"));
    }

    /** 删除：移除列表 + 删除 session 目录 */
    @Test
    public void remove_deletesSessionDir() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", dir("a"), "", null);
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
        m.add("projA", dir("a"), "", null);
        m.setCurrent("projA");
        m.remove("projA");
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals("default", m2.current().workSpaceName);
    }

    /** update 后重载生效 */
    @Test
    public void update_persistsWorkDirAndProjectMd() throws IOException {
        Path dir = jarDir();
        String x = dir("x");
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertTrue(m.update("default", x, mdIn(x, "x.md"), null));
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals(x, m2.current().workDir);
        assertEquals("./x.md", m2.current().projectMd);
    }

    /** 只剩一个工作空间时 remove 拒绝，列表不变 */
    @Test
    public void remove_lastWorkspaceRejected() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertFalse(m.remove("default"));
        assertEquals(1, m.list().size());
        assertNotNull(m.get("default"));
    }

    /** rename 不存在的名字返回 false */
    @Test
    public void rename_missingNameReturnsFalse() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        assertFalse(m.rename("nope", "newname"));
    }

    /** 大小写不敏感重名拒绝（Windows 上 session 目录同路径串扰防护） */
    @Test
    public void add_caseInsensitiveDuplicateRejected() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        assertTrue(m.add("projA", dir("a"), "", null));
        assertFalse(m.add("ProjA", dir("b"), "", null));
        assertFalse(m.add("PROJA", dir("c"), "", null));
        assertEquals(2, m.list().size());
    }

    /** JSON 中空 workspaces 数组是合法状态，不应重建默认覆盖 */
    @Test
    public void load_preservesEmptyWorkspaceList() throws IOException {
        Path dir = jarDir();
        String json = "{\"workspaces\":[],\"currentWorkspaceName\":\"none\"}";
        Files.write(dir.resolve("workspace.json"), json.getBytes(StandardCharsets.UTF_8));
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertEquals(0, m.list().size());
        assertNull(m.current());
        assertEquals(json, new String(Files.readAllBytes(dir.resolve("workspace.json")),
                StandardCharsets.UTF_8));
    }

    /** 移动顺序：列表重排 + 落盘持久化 */
    @Test
    public void move_reordersAndPersists() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", dir("a"), "", null);
        m.add("projB", dir("b"), "", null);
        assertTrue(m.move("default", 2)); // 移到末尾
        assertEquals("projA", m.list().get(0).workSpaceName);
        assertEquals("projB", m.list().get(1).workSpaceName);
        assertEquals("default", m.list().get(2).workSpaceName);
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals("projA", m2.list().get(0).workSpaceName);
        assertEquals("default", m2.list().get(2).workSpaceName);
    }

    /** 越界/不存在返回 false；位置不变返回 true 且列表不变 */
    @Test
    public void move_rejectsInvalidIndexAndMissingName() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        m.add("projA", dir("a"), "", null);
        assertFalse(m.move("nope", 0));     // 不存在
        assertFalse(m.move("projA", -1));   // 越界
        assertFalse(m.move("projA", 3));    // 越界
        assertTrue(m.move("projA", 1));     // 同位置
        assertEquals(2, m.list().size());
    }

    /** 项目级技能路径落盘/回读；四参 update 覆盖三个路径字段 */
    @Test
    public void projectSkillsDir_persistsAndUpdateOverwrites() throws IOException {
        String s1 = dir("s1");
        String s2 = dir("s2");
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        assertTrue(m.add("projS", s1, mdIn(s1, "project.md"), sub(s1, "skills")));
        assertEquals(s1 + File.separator + "skills", m.get("projS").projectSkillsDir);
        m.update("projS", s2, mdIn(s2, "README.md"), sub(s2, ".skills"));
        WorkspaceConfig w = m.get("projS");
        assertEquals(s2, w.workDir);
        assertEquals("./README.md", w.projectMd);
        assertEquals(s2 + File.separator + ".skills", w.projectSkillsDir);
    }

    /** 项目路径必填：add 空白（含纯空格）被拒且不落盘；update 空白被拒且原值不变 */
    @Test
    public void blankWorkDir_rejectedByAddAndUpdate() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertFalse(m.add("noDir", "   ", "./project.md", null));
        assertNull(m.get("noDir"));
        assertEquals(1, m.list().size()); // 默认空间之外没多出条目（add 未落盘）

        String x = dir("x");
        String xSkills = sub(x, "skills");
        assertTrue(m.add("hasDir", x, mdIn(x, "project.md"), xSkills));
        assertFalse(m.update("hasDir", "", "./project.md", xSkills));
        WorkspaceConfig w = m.get("hasDir");
        assertEquals(x, w.workDir);                   // 原值未被清空
        assertEquals(xSkills, w.projectSkillsDir);
        assertFalse(m.update("noSuchSpace", dir("y"), "", null));
    }

    /**
     * 路径必须是文件夹：不存在的路径与指向普通文件的路径，add/update 都拒绝；
     * 技能路径为可选（留空合法），填了才校验。
     */
    @Test
    public void nonDirectoryPath_rejected() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        String real = dir("real");
        String missing = dir("root") + File.separator + "not-created-yet";
        File file = tmp.newFile("plain.txt");

        // add：项目路径不存在 / 是文件 → 拒绝且不落盘
        assertFalse(m.add("missingDir", missing, "", null));
        assertNull(m.get("missingDir"));
        assertFalse(m.add("isFile", file.getAbsolutePath(), "", null));
        assertNull(m.get("isFile"));

        // add：项目路径合法但技能路径指向文件 → 整条拒绝
        assertFalse(m.add("badSkills", real, "", file.getAbsolutePath()));
        assertNull(m.get("badSkills"));
        // 技能路径留空合法（可选）
        assertTrue(m.add("ok", real, "", "  "));
        assertNull(m.get("ok").projectSkillsDir);

        // update：技能路径指向文件 / 不存在 → 拒绝，原字段不变
        assertFalse(m.update("ok", real, "", file.getAbsolutePath()));
        assertFalse(m.update("ok", real, "", missing));
        assertEquals(real, m.get("ok").workDir);
        assertNull(m.get("ok").projectSkillsDir);
        // 技能路径指向真实目录 → 通过
        String skills = dir("skills");
        assertTrue(m.update("ok", real, "", skills));
        assertEquals(skills, m.get("ok").projectSkillsDir);
    }

    /** 技能路径的相对写法按该空间项目路径解析后再判断是否为文件夹；落盘存原样写法 */
    @Test
    public void relativeSkillsDir_resolvedAgainstWorkDir() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        String root = dir("proj");
        assertFalse(m.add("rel", root, "", "./no-such-dir"));   // 解析后不存在 → 拒绝
        assertTrue(new File(root, "skills").mkdirs());
        assertTrue(m.add("rel2", root, "", "./skills"));        // 解析为 <proj>/skills → 通过
        assertEquals("./skills", m.get("rel2").projectSkillsDir);
    }

    /** 首次启动生成的 default 空间：主说明文件留空（不预置 ./project.md 假路径） */
    @Test
    public void load_defaultWorkspace_leavesProjectMdBlank() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        assertNull(m.get("default").projectMd);
        WorkspaceManager m2 = WorkspaceManager.load(jarDir2()); // 重载走落盘回读，同样为空
        assertNull(m2.get("default").projectMd);
    }

    /**
     * 主说明文件填了才校验：必须是**已存在的文件**。
     * 指向不存在的文件、指向文件夹 → add/update 一律拒绝且不落盘；留空合法（表示不使用主说明文件）。
     */
    @Test
    public void projectMd_mustBeExistingFile() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        String root = dir("mdproj");

        // 留空（含纯空格）合法：表示不使用主说明文件
        assertTrue(m.add("blank", root, "   ", null));
        assertNull(m.get("blank").projectMd);

        // 指向不存在的文件 → 拒绝且不落盘
        assertFalse(m.add("missingMd", root, "./no-such.md", null));
        assertNull(m.get("missingMd"));

        // 指向文件夹 → 拒绝
        assertTrue(new File(root, "docs").mkdirs());
        assertFalse(m.add("dirMd", root, "./docs", null));
        assertNull(m.get("dirMd"));

        // 指向真实文件 → 通过；相对写法按该空间项目路径解析，落盘存原样写法
        assertTrue(new File(root, "CLAUDE.md").createNewFile());
        assertTrue(m.add("okMd", root, "./CLAUDE.md", null));
        assertEquals("./CLAUDE.md", m.get("okMd").projectMd);

        // update 同口径：改为文件夹/不存在路径 → 拒绝且原值不变；清空 → 合法
        assertFalse(m.update("okMd", root, "./docs", null));
        assertFalse(m.update("okMd", root, "./gone.md", null));
        assertEquals("./CLAUDE.md", m.get("okMd").projectMd);
        assertTrue(m.update("okMd", root, "", null));
        assertNull(m.get("okMd").projectMd);
    }

    /** 造一个独立的 jar 目录（避免与 jarDir() 用例互相回读） */
    private Path jarDir2() throws IOException {
        return tmp.newFolder("jar2").toPath();
    }
}
