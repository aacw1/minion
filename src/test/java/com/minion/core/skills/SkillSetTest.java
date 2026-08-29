package com.minion.core.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 内置 + 项目技能合并：项目覆盖同名、顺序稳定、快照不可变 */
public class SkillSetTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Skill global(String name, String desc) {
        return new Skill(name, desc, "内置正文", "C:/app/skills/" + name + "/SKILL.md", Skill.SOURCE_GLOBAL);
    }

    private Path projectWith(String dirName, String skillName, String desc) throws Exception {
        Path dir = tmp.newFolder("proj").toPath().resolve(dirName).resolve(skillName);
        Files.createDirectories(dir);
        Files.write(dir.resolve("SKILL.md"),
                ("---\nname: " + skillName + "\ndescription: " + desc + "\n---\n项目正文")
                        .getBytes(StandardCharsets.UTF_8));
        return dir.getParent().getParent();
    }

    private static List<String> namesOf(List<Skill> skills) {
        List<String> out = new ArrayList<String>();
        for (Skill s : skills) out.add(s.name);
        return out;
    }

    /** 未配置项目目录 → 只有内置，且全部标注内置，无告警 */
    @Test
    public void withoutProjectDir_returnsGlobalsOnly() {
        SkillSet set = new SkillSet(Arrays.asList(global("think", "思考")));
        SkillSet.Result r = set.resolve(null);
        assertEquals(Arrays.asList("think"), namesOf(r.skills));
        assertEquals(Skill.SOURCE_GLOBAL, r.skills.get(0).source);
        assertNull(r.warning);
    }

    /** 同名（大小写不同）→ 保留项目那条，内置那条不进快照 */
    @Test
    public void projectOverridesSameName() throws Exception {
        Path dir = projectWith("skills", "Deploy", "项目部署");
        SkillSet set = new SkillSet(Arrays.asList(global("deploy", "内置部署")));
        List<Skill> merged = set.resolve(dir.toString()).skills;
        assertEquals(1, merged.size());
        assertEquals("项目部署", merged.get(0).description);
        assertEquals(Skill.SOURCE_PROJECT, merged.get(0).source);
    }

    /** 项目技能排在内置之前，各自按名排序 */
    @Test
    public void projectSkillsListedBeforeGlobals() throws Exception {
        Path dir = projectWith("skills", "zeta", "项目Z");
        SkillSet set = new SkillSet(Arrays.asList(global("beta", "B"), global("alpha", "A")));
        assertEquals(Arrays.asList("zeta", "alpha", "beta"), namesOf(set.resolve(dir.toString()).skills));
    }

    /** 快照不可变：改动会抛 UnsupportedOperationException（会话间隔离的前提） */
    @Test(expected = UnsupportedOperationException.class)
    public void snapshotIsUnmodifiable() {
        SkillSet set = new SkillSet(Arrays.asList(global("think", "思考")));
        set.resolve(null).skills.add(global("extra", "额外"));
    }

    /** 目录不存在 → 只回内置列表，但带告警文案（由上层 notifyError） */
    @Test
    public void missingDir_warnsAndKeepsGlobals() {
        SkillSet set = new SkillSet(Arrays.asList(global("think", "思考")));
        SkillSet.Result r = set.resolve(tmp.getRoot().toPath().resolve("nope").toString());
        assertEquals(1, r.skills.size());
        assertNotNull(r.warning);
    }
}
