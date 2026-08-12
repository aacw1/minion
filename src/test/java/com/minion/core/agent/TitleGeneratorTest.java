package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class TitleGeneratorTest {

    @Test
    public void localTitle_truncatesTo20Chars() {
        String longText = "帮我修复登录问题需要修改三个文件的位置和配置信息";
        String t = TitleGenerator.localTitle(longText);
        assertEquals(TitleGenerator.MAX_TITLE_LEN, t.length());
        assertEquals(longText.substring(0, 20), t);
    }

    @Test
    public void localTitle_normalizesNewlinesAndTrim() {
        assertEquals("修复乱码", TitleGenerator.localTitle("  修复乱码  "));
        assertEquals("a b", TitleGenerator.localTitle("a\nb"));
        assertEquals("a b c", TitleGenerator.localTitle("a\r\nb\nc"));
    }

    @Test
    public void localTitle_shortTextUnchanged() {
        assertEquals("修复登录", TitleGenerator.localTitle("修复登录"));
    }

    @Test
    public void localTitle_emptyFallsBackToNewSession() {
        assertEquals("新会话", TitleGenerator.localTitle(""));
        assertEquals("新会话", TitleGenerator.localTitle("   "));
        assertEquals("新会话", TitleGenerator.localTitle(null));
    }
}
