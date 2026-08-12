package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class TitleGeneratorTest {

    @Test
    public void buildPrompt_containsInstructionAndMessage() {
        String p = TitleGenerator.buildPrompt("帮我实现登录功能");
        assertTrue(p.contains("登录功能"));
        assertTrue(p.contains("20"));
    }

    @Test
    public void clean_stripsQuotesAndTrims() {
        assertEquals("修复乱码", TitleGenerator.clean("「修复乱码」"));
        assertEquals("修复乱码", TitleGenerator.clean("\"修复乱码\""));
        assertEquals("修复乱码", TitleGenerator.clean("  修复乱码  "));
        assertEquals("a b", TitleGenerator.clean("a\nb"));
    }

    @Test
    public void clean_truncatesOverLength() {
        String longTitle = "这是一个非常非常非常非常非常非常非常非常非常长的标题超过二十个字的长度限制";
        String c = TitleGenerator.clean(longTitle);
        assertTrue(c.length() <= TitleGenerator.MAX_TITLE_LEN);
    }

    @Test
    public void clean_emptyFallsBack() {
        assertEquals("新会话", TitleGenerator.clean(""));
        assertEquals("新会话", TitleGenerator.clean("   "));
        assertEquals("新会话", TitleGenerator.clean(null));
    }

    @Test
    public void fallbackTitle_truncatesAndDefaults() {
        assertEquals("新会话", TitleGenerator.fallbackTitle(""));
        assertEquals("新会话", TitleGenerator.fallbackTitle(null));
        assertEquals("修复中文乱码问题", TitleGenerator.fallbackTitle("修复中文乱码问题"));
        String longMsg = "这是一个超过三十个字的消息内容用来测试兜底标题的截断行为是否符合预期";
        assertTrue(TitleGenerator.fallbackTitle(longMsg).length() <= 30);
    }
}
