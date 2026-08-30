package com.minion.core.agent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** 系统提示词：临时文件目录注入 + Git Bash 规则说明 + 技能来源标注（[项目]/[内置]）与项目级技能目录行 */
public class SystemPromptBuilderTest {

    @Test
    public void build_withTmpDir_injectsTmpDirAndDevNullRules() {
        String p = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/app/.session/tmp/s1")
                .build(Collections.emptyList());
        assertTrue(p.contains("C:/app/.session/tmp/s1"));
        assertTrue(p.contains("不要在工作空间创建临时文件"));
        assertTrue(p.contains("/dev/null"));
        assertTrue(p.contains("nul"));
    }

    @Test
    public void build_withoutTmpDir_noTmpSection() {
        String p = new SystemPromptBuilder("nonexistent.md", "C:/work").build(Collections.emptyList());
        assertFalse(p.contains("/dev/null"));
    }

    /** 空输出占位开关：开启时追加第 9 条规则（占位含义说明），关闭时不追加 */
    @Test
    public void build_emptyOutputPlaceholder_togglesRule() {
        String on = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/tmp", true)
                .build(Collections.emptyList());
        assertTrue(on.contains("「输出内容为空」"));
        assertTrue(on.contains("不要视为失败或重复调用同一工具"));

        String off = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/tmp", false)
                .build(Collections.emptyList());
        assertFalse(off.contains("「输出内容为空」"));

        // 旧 3 参构造默认关闭（无该配置行为一致）
        String legacy = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/tmp")
                .build(Collections.emptyList());
        assertFalse(legacy.contains("「输出内容为空」"));
    }

    private static com.minion.core.skills.Skill skill(String name, String desc, String source) {
        return new com.minion.core.skills.Skill(name, desc, "正文", "C:/x/" + name + "/SKILL.md", source);
    }

    /** 项目技能在前并标 [项目]，内置标 [内置]，段末给出项目技能目录（模型据此知道去哪儿找） */
    @Test
    public void build_annotatesSourceAndAppendsProjectDir() {
        String p = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/tmp", false, "D:/proj/skills")
                .build(Arrays.asList(
                        skill("deploy", "部署", com.minion.core.skills.Skill.SOURCE_PROJECT),
                        skill("think", "思考", com.minion.core.skills.Skill.SOURCE_GLOBAL)));
        assertTrue(p.contains("- [项目] deploy — 部署（C:/x/deploy/SKILL.md）"));
        assertTrue(p.contains("- [内置] think — 思考（C:/x/think/SKILL.md）"));
        assertTrue(p.contains("项目级技能目录: D:/proj/skills"));
        assertTrue(p.contains("[项目] 来自当前工作空间的项目级技能路径"));
    }

    /** 未配置项目目录：不输出目录末行（来源说明行始终存在，它只是解释标注含义） */
    @Test
    public void build_withoutProjectDir_hasNoProjectLines() {
        String p = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/tmp", false, null)
                .build(Arrays.asList(
                        skill("think", "思考", com.minion.core.skills.Skill.SOURCE_GLOBAL)));
        assertFalse(p.contains("项目级技能目录:"));
        assertTrue(p.contains("- [内置] think — 思考"));
    }

    /** 无技能时整段缺席（含项目目录也不应单独输出） */
    @Test
    public void build_noSkills_noCatalogSection() {
        String p = new SystemPromptBuilder("nonexistent.md", "C:/work", "C:/tmp", false, "D:/proj/skills")
                .build(java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertFalse(p.contains("=== 可用技能 ==="));
        assertFalse(p.contains("项目级技能目录:"));
    }

    /**
     * 主说明文件未配置（null/空白）：不抛 NPE、不注入「项目介绍」段；
     * 只有指向真实文件时才注入（留空即彻底不用主说明文件，不再隐式回落 <项目路径>/project.md）。
     */
    @Test
    public void build_projectMdSection_followsConfig() throws Exception {
        assertFalse("projectMd 为 null 不得抛 NPE",
                new SystemPromptBuilder(null, "C:/work").build(Collections.<com.minion.core.skills.Skill>emptyList())
                        .contains("=== 项目介绍 ==="));
        assertFalse(new SystemPromptBuilder("   ", "C:/work")
                .build(Collections.<com.minion.core.skills.Skill>emptyList()).contains("=== 项目介绍 ==="));

        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mdcase");
        java.nio.file.Path md = dir.resolve("CLAUDE.md");
        java.nio.file.Files.write(md, "中文注释约定".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String p = new SystemPromptBuilder(md.toString(), dir.toString())
                .build(Collections.<com.minion.core.skills.Skill>emptyList());
        assertTrue(p.contains("=== 项目介绍 ==="));
        assertTrue(p.contains("中文注释约定"));
    }
}
