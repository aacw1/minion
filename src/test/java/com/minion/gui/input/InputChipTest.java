package com.minion.gui.input;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 输入块纯逻辑：compose 组装 / 粘贴阈值 / 类型映射 / 粘贴块显示。无 JavaFX 依赖。 */
public class InputChipTest {

    private static List<InputChip> listOf(InputChip... chips) {
        List<InputChip> list = new ArrayList<InputChip>();
        if (chips != null) {
            for (InputChip c : chips) list.add(c);
        }
        return list;
    }

    private static InputChip cmd(String s) { return InputChip.textChip(InputChip.Type.COMMAND, s); }

    @Test public void compose_emptyAll() {
        assertEquals("", InputChip.compose(new ArrayList<InputChip>(), ""));
        assertEquals("", InputChip.compose(new ArrayList<InputChip>(), null));
    }

    @Test public void compose_textOnly_keepsAsIs() {
        assertEquals("你好", InputChip.compose(new ArrayList<InputChip>(), "你好"));
    }

    @Test public void compose_chipOnly() {
        assertEquals("/help", InputChip.compose(listOf(cmd("/help")), ""));
    }

    @Test public void compose_chipsJoinedBySpace() {
        assertEquals("/help @src/a.java",
                InputChip.compose(listOf(cmd("/help"), cmd("@src/a.java")), ""));
    }

    @Test public void compose_chipThenText_singleSpace() {
        assertEquals("/help 修复bug", InputChip.compose(listOf(cmd("/help")), "修复bug"));
    }

    @Test public void compose_twoChipsAndText() {
        assertEquals("/help @src/a.java 继续",
                InputChip.compose(listOf(cmd("/help"), cmd("@src/a.java")), "继续"));
    }

    @Test public void shouldChipPaste_threshold() {
        assertFalse(InputChip.shouldChipPaste(repeat('a', 99)));
        assertTrue(InputChip.shouldChipPaste(repeat('a', 100)));
        assertFalse(InputChip.shouldChipPaste(null));
    }

    @Test public void shouldChipPaste_newlinesCount() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("ab\n"); // 50 行 × 3 字符（含换行）= 150
        assertEquals(150, sb.toString().length());
        assertTrue(InputChip.shouldChipPaste(sb.toString()));
    }

    @Test public void modeToType_mapping() {
        assertEquals(InputChip.Type.COMMAND, InputChip.modeToType(CompletionParser.Mode.SLASH));
        assertEquals(InputChip.Type.SKILL, InputChip.modeToType(CompletionParser.Mode.SLASH_SKILL));
        assertEquals(InputChip.Type.FILE, InputChip.modeToType(CompletionParser.Mode.FILE));
        assertEquals(InputChip.Type.COMMAND, InputChip.modeToType(CompletionParser.Mode.NONE));
    }

    @Test public void pasteChip_displayShowsCount() {
        InputChip c = InputChip.pasteChip(repeat('x', 123));
        assertEquals(InputChip.Type.PASTE, c.type);
        assertEquals(123, c.content.length());
        assertEquals("粘贴内容，123 字符", c.display);
    }

    private static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }
}
