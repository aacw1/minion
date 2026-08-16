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
    public void build_includesProjectMdAndSkillCatalog() throws Exception {
        Path work = tmp.getRoot().toPath();
        File md = new File(work.toFile(), "project.md");
        Files.write(md.toPath(), "这是一个测试项目".getBytes(StandardCharsets.UTF_8));

        SystemPromptBuilder b = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md");
        Skill available = new Skill("review", "代码审查技能", "审查指令全文", "SKILL.md");
        String prompt = b.build(java.util.Collections.singletonList(available));

        int iProject = prompt.indexOf("=== 项目介绍 ===");
        int iSkills = prompt.indexOf("=== 可用技能 ===");
        assertTrue(iProject > 0);
        assertTrue(iSkills > iProject);
        assertTrue(prompt.contains("这是一个测试项目"));
        assertTrue(prompt.contains("review — 代码审查技能"));
        // 路由引导语：匹配才加载；正文以用户消息注入
        assertTrue(prompt.contains("调用 Skill 工具加载"));
        assertTrue(prompt.contains("匹配才加载"));
        // 已加载技能段删除：正文不再进系统提示词
        assertFalse(prompt.contains("=== 已加载技能 ==="));
        assertFalse(prompt.contains("审查指令全文"));
    }

    @Test
    public void build_missingProjectMd_skipsSection() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/nope.md").build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertFalse(prompt.contains("=== 项目介绍 ==="));
        assertFalse(prompt.contains("=== 可用技能 ==="));
    }

    @Test
    public void build_includesStuckStopRule() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md").build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertTrue(prompt.contains("停止调用工具"));
        assertTrue(prompt.contains("不要反复重试同一方法"));
    }

    @Test
    public void build_clarificationRuleIsFirst() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md").build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        int iClarify = prompt.indexOf("不要猜测用户意图");
        int iOldRule1 = prompt.indexOf("使用工具前先想清楚目标");
        assertTrue(iClarify > 0);
        assertTrue(iOldRule1 > iClarify);
    }

    /** 工作目录注入：模型必须知道当前工作目录，否则会猜测/编造路径（曾实测编造出旧项目目录路径） */
    @Test
    public void build_injectsWorkDirBeforeProjectMd() throws Exception {
        Path work = tmp.getRoot().toPath();
        Files.write(work.resolve("project.md"), "项目".getBytes(StandardCharsets.UTF_8));
        String prompt = new SystemPromptBuilder(work.resolve("project.md").toString(), "D:/work/minion")
                .build(java.util.Collections.<Skill>emptyList());
        int iWork = prompt.indexOf("D:/work/minion");
        int iProject = prompt.indexOf("=== 项目介绍 ===");
        assertTrue(iWork > 0);
        assertTrue(iProject > 0);
        assertTrue("工作目录段应在项目介绍之前", iWork < iProject);
        assertTrue(prompt.contains("pwd"));
    }

    /** 未传工作目录时不注入该段（兼容构造行为） */
    @Test
    public void build_withoutWorkDir_skipsSection() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/nope.md").build(
                java.util.Collections.<Skill>emptyList());
        assertFalse(prompt.contains("D:/work/minion"));
    }

    /** 规则指引模型用 AskUserQuestion 工具提问（替代纯文本提问等待） */
    @Test
    public void build_mentionsAskUserQuestionTool() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md").build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertTrue(prompt.contains("AskUserQuestion"));
    }

    /** 审查检查点规则：完成需用户审查的产出（设计文档/实施计划/关键方案）后必须 AskUserQuestion 确认 */
    @Test
    public void build_includesReviewGateRule() throws Exception {
        String prompt = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md").build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertTrue(prompt.contains("需要用户审查的产出"));
        assertTrue(prompt.contains("未获批准不得继续"));
    }
}
