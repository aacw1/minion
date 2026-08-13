package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;
import static com.minion.gui.input.CompletionParser.*;

/** 补全触发解析：纯静态、无 JavaFX 依赖 */
public class CompletionParserTest {

    private Token p(String text, int caret) { return CompletionParser.parse(text, caret); }

    @Test public void emptyText_none() {
        Token t = p("", 0);
        assertEquals(Mode.NONE, t.mode);
    }

    @Test public void slashAlone_triggersWithEmptyQuery() {
        Token t = p("/", 1);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("", t.query);
        assertEquals(0, t.start);
        assertEquals(1, t.end);
    }

    @Test public void slashPartial_queryAfterSlash() {
        Token t = p("/ski", 4);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("ski", t.query);
    }

    @Test public void slashMidSentence_usesCurrentWord() {
        // 词边界在空白处：光标在第 2 个词内 → 取该词
        Token t = p("修复 /ski", 6);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("ski", t.query);
        assertEquals(3, t.start);
        assertEquals(7, t.end);
    }

    @Test public void skillArg_filtersSkillNames() {
        // "/skill bran" 光标在末尾：当前词 bran，前一词 /skill → 技能名补全
        Token t = p("/skill bran", 11);
        assertEquals(Mode.SLASH_SKILL, t.mode);
        assertEquals("bran", t.query);
    }

    @Test public void skillArgEmpty_afterSpaceShowsAllSkills() {
        // "/skill " 光标在空格后：当前词为空，前一词 /skill → 技能名补全、query 空
        Token t = p("/skill ", 7);
        assertEquals(Mode.SLASH_SKILL, t.mode);
        assertEquals("", t.query);
    }

    @Test public void atTriggers_fileMode() {
        Token t = p("@Ma", 3);
        assertEquals(Mode.FILE, t.mode);
        assertEquals("Ma", t.query);
    }

    @Test public void atAlone_fileModeEmptyQuery() {
        Token t = p("你好 @", 4);
        assertEquals(Mode.FILE, t.mode);
        assertEquals("", t.query);
    }

    @Test public void emailLike_doesNotTrigger() {
        Token t = p("发到 a@b.com", 8);
        assertEquals(Mode.NONE, t.mode);
    }

    @Test public void plainWord_none() {
        Token t = p("你好", 2);
        assertEquals(Mode.NONE, t.mode);
    }

    @Test public void multiline_wordAcrossLines() {
        Token t = p("第一行\n/ski", 7);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("ski", t.query);
    }
}
