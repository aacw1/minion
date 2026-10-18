package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 消息区文本清洗纯逻辑测试（U+FFFD 替换字符会触发 JavaFX 8 文本布局越界） */
public class ChatTextTest {

    @Test
    public void replacementCharBecomesQuestionMark() {
        assertEquals("?", ChatText.sanitize("\uFFFD"));
    }

    @Test
    public void mixedTextKeepsOtherChars() {
        assertEquals("a?b中?", ChatText.sanitize("a\uFFFDb中\uFFFD"));
    }

    @Test
    public void normalTextUnchanged() {
        assertEquals("正常文本 abc", ChatText.sanitize("正常文本 abc"));
    }

    @Test
    public void nullBecomesEmpty() {
        assertEquals("", ChatText.sanitize(null));
    }
}
