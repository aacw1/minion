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
        assertFalse(InputChip.shouldChipPaste(repeat('a', 1000))); // 等于阈值不变块
        assertTrue(InputChip.shouldChipPaste(repeat('a', 1001)));  // 大于阈值才变块
        assertFalse(InputChip.shouldChipPaste(null));
    }

    @Test public void shouldChipPaste_newlinesCount() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("ab\n"); // 500 行 × 3 字符（含换行）= 1500
        assertEquals(1500, sb.toString().length());
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

    @Test public void imageChip_holdsDataUriAndDisplay() {
        InputChip c = InputChip.imageChip("image/png", "QUJD", "截图.png");
        assertEquals(InputChip.Type.IMAGE, c.type);
        assertEquals("data:image/png;base64,QUJD", c.content);
        assertEquals("图片：截图.png", c.display);
    }

    @Test public void compose_skipsImageChips() {
        InputChip img = InputChip.imageChip("image/png", "QUJD", "截图.png");
        assertEquals("文字", InputChip.compose(listOf(img), "文字"));
        assertEquals("/help 文字", InputChip.compose(listOf(cmd("/help"), img), "文字"));
    }

    private static InputChip paste(String content, String placeholder) {
        return InputChip.pasteChip(content, placeholder);
    }

    @Test public void compose_pastePlaceholderExpandsAtEnd() {
        assertEquals("请看LONG", InputChip.compose(listOf(paste("LONG", "[粘贴块1]")), "请看[粘贴块1]"));
    }

    @Test public void compose_pastePlaceholderExpandsAtStart() {
        assertEquals("LONG谢谢", InputChip.compose(listOf(paste("LONG", "[粘贴块1]")), "[粘贴块1]谢谢"));
    }

    @Test public void compose_pastePlaceholderExpandsInMiddle() {
        assertEquals("aLONGb", InputChip.compose(listOf(paste("LONG", "[粘贴块1]")), "a[粘贴块1]b"));
    }

    @Test public void compose_multiplePastePlaceholdersKeepPositions() {
        assertEquals("一LONG1二LONG2三", InputChip.compose(
                listOf(paste("LONG1", "[粘贴块1]"), paste("LONG2", "[粘贴块2]")),
                "一[粘贴块1]二[粘贴块2]三"));
    }

    @Test public void compose_pastePlaceholderWithCommandChip() {
        // 命令块仍前置，粘贴块在占位处原位展开
        assertEquals("/help 请看LONG", InputChip.compose(
                listOf(cmd("/help"), paste("LONG", "[粘贴块1]")), "请看[粘贴块1]"));
    }

    @Test public void compose_pastePlaceholderMissingFromTextSkipped() {
        // 占位符已不在文本中（reconcile 兜底场景）：不前置、不重复
        assertEquals("abc", InputChip.compose(listOf(paste("LONG", "[粘贴块1]")), "abc"));
    }

    @Test public void compose_pasteWithoutPlaceholderKeepsPrefixBehavior() {
        assertEquals("LONG abc", InputChip.compose(listOf(InputChip.pasteChip("LONG")), "abc"));
    }

    @Test public void pasteChip_holdsPlaceholder() {
        InputChip c = InputChip.pasteChip(repeat('x', 1001), "[粘贴块3]");
        assertEquals(InputChip.Type.PASTE, c.type);
        assertEquals("[粘贴块3]", c.placeholder);
        assertEquals("粘贴内容，1001 字符", c.display);
        assertNull(InputChip.pasteChip("y").placeholder);
    }

    private static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }
}
