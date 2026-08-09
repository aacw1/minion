package com.minion.core.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
