package com.minion.gui.input;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 弹层过滤/排序为纯静态逻辑（弹层本体为 UI，由 run 启动目验） */
public class SuggestionPopupTest {

    private Suggestion s(String label) {
        return new Suggestion(label, label, null, Suggestion.Type.COMMAND);
    }

    @Test public void emptyQuery_keepsAll() {
        List<Suggestion> out = SuggestionPopup.filter(Arrays.asList(s("/skills"), s("/help")), "");
        assertEquals(2, out.size());
    }

    @Test public void filter_caseInsensitiveContains() {
        List<Suggestion> out = SuggestionPopup.filter(Arrays.asList(s("/skills"), s("/help")), "SKI");
        assertEquals(1, out.size());
        assertEquals("/skills", out.get(0).label);
    }

    @Test public void prefixMatch_ranksBeforeContains() {
        // "/skill" 前缀命中排第一；"/skills" 为包含命中排后（技能名条目同属包含命中）
        List<Suggestion> out = SuggestionPopup.filter(
                Arrays.asList(s("/skills"), s("/skill")), "skill");
        assertEquals("/skill", out.get(0).label);
    }

    @Test public void shorterPath_ranksFirstOnTie() {
        List<Suggestion> out = SuggestionPopup.filter(
                Arrays.asList(s("src/main/java/com/minion/Main.java"), s("src/main/Main.java")),
                "Main.java");
        assertEquals("src/main/Main.java", out.get(0).label);
    }

    @Test public void noMatch_returnsEmpty() {
        List<Suggestion> out = SuggestionPopup.filter(Arrays.asList(s("/skills")), "zzz");
        assertTrue(out.isEmpty());
    }
}
