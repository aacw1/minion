package com.minion.core.agent;

import com.minion.core.skills.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class SystemPromptBuilderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void build_includesProjectMdAndSkillsInOrder() throws Exception {
        Path work = tmp.getRoot().toPath();
        File md = new File(work.toFile(), "project.md");
        Files.write(md.toPath(), "这是一个测试项目".getBytes(StandardCharsets.UTF_8));
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), ("model.name=x\nwork.dir=.\nproject.md.path="
                + md.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));

        com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
        SystemPromptBuilder b = new SystemPromptBuilder(config);

        Skill available = new Skill("review", "代码审查技能", "审查指令全文", "SKILL.md");
        Skill loaded = new Skill("debug", "调试技能", "调试指令全文", "SKILL.md");
        String prompt = b.build(java.util.Collections.singletonList(available),
                java.util.Collections.singletonList(loaded));

        int iProject = prompt.indexOf("=== 项目介绍 ===");
        int iSkills = prompt.indexOf("=== 可用技能 ===");
        int iLoaded = prompt.indexOf("=== 已加载技能 ===");
        assertTrue(iProject > 0);
        assertTrue(iSkills > iProject);
        assertTrue(iLoaded > iSkills);
        assertTrue(prompt.contains("这是一个测试项目"));
        assertTrue(prompt.contains("review — 代码审查技能"));
        assertTrue(prompt.contains("调试指令全文"));
    }

    @Test
    public void build_missingProjectMd_skipsSection() throws Exception {
        Path work = tmp.getRoot().toPath();
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), "model.name=x\nproject.md.path=./nope.md\n".getBytes(StandardCharsets.UTF_8));
        com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
        String prompt = new SystemPromptBuilder(config).build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList(),
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertFalse(prompt.contains("=== 项目介绍 ==="));
        assertFalse(prompt.contains("=== 可用技能 ==="));
    }

    @Test
    public void build_clarificationRuleIsFirst() throws Exception {
        Path work = tmp.getRoot().toPath();
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), "model.name=x\n".getBytes(StandardCharsets.UTF_8));
        com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
        String prompt = new SystemPromptBuilder(config).build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList(),
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        int iClarify = prompt.indexOf("不要猜测用户意图");
        int iOldRule1 = prompt.indexOf("使用工具前先想清楚目标");
        assertTrue(iClarify > 0);
        assertTrue(iOldRule1 > iClarify);
    }
}
