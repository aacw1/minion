package com.minion.core.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SkillManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void scan_directoryFormat_withFrontmatter() throws Exception {
        Path skillsDir = tmp.getRoot().toPath().resolve("skills");
        Path debug = skillsDir.resolve("debugging");
        Files.createDirectories(debug);
        Files.write(debug.resolve("SKILL.md"),
                ("---\nname: debugging\ndescription: 调试技能\nmetadata:\n  type: process\n---\n"
                        + "调试指令正文").getBytes(StandardCharsets.UTF_8));
        SkillManager mgr = new SkillManager(skillsDir.toString());
        List<Skill> skills = mgr.scan();
        assertEquals(1, skills.size());
        Skill s = skills.get(0);
        assertEquals("debugging", s.name);
        assertEquals("调试技能", s.description);
        assertTrue(s.instructions.contains("调试指令正文"));
        assertFalse(s.instructions.contains("---"));
        // file 为源文件完整路径（供模型用 Read 读取），而非仅文件名
        assertTrue(s.file.endsWith("SKILL.md"));
        assertTrue(java.nio.file.Paths.get(s.file).isAbsolute());
    }

    @Test
    public void scan_singleFileFormat() throws Exception {
        Path skillsDir = tmp.getRoot().toPath().resolve("skills");
        Files.createDirectories(skillsDir);
        Files.write(skillsDir.resolve("review.skill.md"),
                ("---\ndescription: 代码审查\n---\n审查要点：读、写、测").getBytes(StandardCharsets.UTF_8));
        SkillManager mgr = new SkillManager(skillsDir.toString());
        List<Skill> skills = mgr.scan();
        assertEquals(1, skills.size());
        assertEquals("review", skills.get(0).name); // name 缺省取文件名
        assertEquals("代码审查", skills.get(0).description);
    }

    @Test
    public void scan_noFrontmatter_usesWholeFile() throws Exception {
        Path skillsDir = tmp.getRoot().toPath().resolve("skills");
        Path t = skillsDir.resolve("mytool");
        Files.createDirectories(t);
        Files.write(t.resolve("SKILL.md"), "纯指令，没有 frontmatter".getBytes(StandardCharsets.UTF_8));
        SkillManager mgr = new SkillManager(skillsDir.toString());
        List<Skill> skills = mgr.scan();
        assertEquals(1, skills.size());
        assertEquals("mytool", skills.get(0).name);
        assertTrue(skills.get(0).instructions.contains("纯指令"));
    }

    @Test
    public void scan_missingDir_returnsEmpty() {
        SkillManager mgr = new SkillManager(tmp.getRoot().toPath().resolve("nope").toString());
        assertTrue(mgr.scan().isEmpty());
    }

    private static void writeSkill(Path dir, String name, String desc) throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve("SKILL.md"),
                ("---\nname: " + name + "\ndescription: " + desc + "\n---\n正文:" + name)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static java.util.List<String> namesOf(java.util.List<Skill> skills) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Skill s : skills) out.add(s.name);
        return out;
    }

    /** 递归扫描：嵌套多层 + 单文件形式都能发现，来源标 project，结果按名排序 */
    @Test
    public void scanTree_findsNestedSkills() throws Exception {
        Path root = tmp.newFolder("proj").toPath();
        writeSkill(root.resolve("skills/deploy"), "deploy", "部署");
        writeSkill(root.resolve("a/b/review"), "review", "审查");
        Files.write(root.resolve("tips.skill.md"),
                "---\nname: tips\ndescription: 小技巧\n---\n正文".getBytes(StandardCharsets.UTF_8));
        SkillManager.ScanResult r = SkillManager.scanTree(root, 6, 200);
        assertNull(r.warning);
        assertEquals(Arrays.asList("deploy", "review", "tips"), namesOf(r.skills));
        for (Skill s : r.skills) assertEquals(Skill.SOURCE_PROJECT, s.source);
    }

    /** 递归扫描：跳过依赖/构建/VCS 目录 */
    @Test
    public void scanTree_skipsNoisyDirs() throws Exception {
        Path root = tmp.newFolder("proj2").toPath();
        writeSkill(root.resolve("skills/real"), "real", "真实技能");
        writeSkill(root.resolve("node_modules/pkg/skills/fake"), "fake", "噪声");
        writeSkill(root.resolve("skills/other/target/fake2"), "fake2", "噪声");
        SkillManager.ScanResult r = SkillManager.scanTree(root, 6, 200);
        assertEquals(1, r.skills.size());
        assertEquals("real", r.skills.get(0).name);
    }

    /** 递归扫描：深度超限不收集 */
    @Test
    public void scanTree_respectsMaxDepth() throws Exception {
        Path root = tmp.newFolder("proj3").toPath();
        writeSkill(root.resolve("l1/l2/l3"), "deep3", "三层");
        writeSkill(root.resolve("l1/l2/l3/l4/l5/l6/l7"), "deep7", "七层");
        SkillManager.ScanResult r = SkillManager.scanTree(root, 3, 200);
        assertEquals(1, r.skills.size());
        assertEquals("deep3", r.skills.get(0).name);
    }

    /** 递归扫描：数量触顶 → 截断并在 warning 说明（不抛异常） */
    @Test
    public void scanTree_truncatesAtMaxCount() throws Exception {
        Path root = tmp.newFolder("proj4").toPath();
        for (int i = 0; i < 5; i++) writeSkill(root.resolve("s/skill" + i), "skill" + i, "d");
        SkillManager.ScanResult r = SkillManager.scanTree(root, 6, 3);
        assertEquals(3, r.skills.size());
        assertNotNull(r.warning);
        assertTrue(r.warning.contains("截断"));
    }

    /** 递归扫描：目录不存在 → 空列表 + 告警，不抛异常 */
    @Test
    public void scanTree_missingDir_warns() {
        SkillManager.ScanResult r =
                SkillManager.scanTree(tmp.getRoot().toPath().resolve("nope"), 6, 200);
        assertTrue(r.skills.isEmpty());
        assertNotNull(r.warning);
    }

    /** 来源标注进 hint()：UI 与提示词共用同一串 */
    @Test
    public void skillHint_carriesSource() {
        Skill p = new Skill("deploy", "部署", "正文", "/x/SKILL.md", Skill.SOURCE_PROJECT);
        Skill g = new Skill("think", "思考", "正文", "/y/SKILL.md", Skill.SOURCE_GLOBAL);
        assertEquals("[项目] deploy — 部署", p.hint());
        assertEquals("[内置] think — 思考", g.hint());
    }
}
