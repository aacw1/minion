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
}
