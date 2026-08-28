package com.minion.core.agent;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/** 系统提示词：临时文件目录注入 + Git Bash 规则说明 */
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
}
