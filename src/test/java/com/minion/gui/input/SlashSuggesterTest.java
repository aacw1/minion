package com.minion.gui.input;

import com.minion.core.skills.Skill;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SlashSuggesterTest {

    @Test public void builtins_coverAllFiveCommands() {
        List<Suggestion> all = SlashSuggester.all(Arrays.<Skill>asList());
        assertEquals(5, all.size());
        assertEquals("/help", all.get(0).insertText);
        assertEquals("/skills", all.get(1).insertText);
        assertEquals("/skill", all.get(2).insertText);
        assertEquals("/compact", all.get(3).insertText);
        assertEquals("/tokens", all.get(4).insertText);
    }

    @Test public void skillEntries_insertFullSkillCommand() {
        List<Suggestion> all = SlashSuggester.all(Arrays.asList(
                new Skill("brainstorming", "需求头脑风暴", "正文", "f.md", Skill.SOURCE_GLOBAL)));
        Suggestion skill = null;
        for (Suggestion s : all) if (s.type == Suggestion.Type.SKILL) skill = s;
        assertNotNull(skill);
        assertEquals("/skill brainstorming", skill.insertText);
        assertTrue(skill.desc.contains("需求头脑风暴"));
    }

    @Test public void skillsOnly_excludesBuiltins() {
        List<Suggestion> only = SlashSuggester.skillEntries(Arrays.asList(
                new Skill("brainstorming", "需求头脑风暴", "正文", "f.md", Skill.SOURCE_GLOBAL)));
        assertEquals(1, only.size());
        assertEquals(Suggestion.Type.SKILL, only.get(0).type);
    }
}
