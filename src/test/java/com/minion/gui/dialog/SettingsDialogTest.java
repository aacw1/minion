package com.minion.gui.dialog;

import org.junit.Test;

import static org.junit.Assert.*;

/** 设置窗纯逻辑辅助：MCP 表单解析、模型激活判定（无 JavaFX 环境可用） */
public class SettingsDialogTest {

    // ===== MCP 页表单辅助（纯逻辑，无 JavaFX） =====

    /** 参数文本：每行一个，trim 后去空行 */
    @Test
    public void splitLines_trimsAndDropsEmpty() {
        assertEquals(java.util.Arrays.asList("a", "b"),
                SettingsDialog.splitLines(" a \n\n b "));
        assertEquals(0, SettingsDialog.splitLines("  \n\n").size());
    }

    /** KEY=VALUE / K:V 混排逐行解析；非法行忽略 */
    @Test
    public void parsePairs_supportsEqAndColon() {
        java.util.Map<String, String> m = SettingsDialog.parsePairs("A=1\nB: 2\nbadline\nC=3");
        assertEquals(3, m.size());
        assertEquals("1", m.get("A"));
        assertEquals("2", m.get("B"));
        assertEquals("3", m.get("C"));
    }

    /** 失败原因列表显示：null→空、取首行并去首尾空白、超 40 字符截断加省略号 */
    @Test
    public void shorten_takesFirstLineAndTruncates() {
        assertEquals("", SettingsDialog.shorten(null));
        assertEquals("short", SettingsDialog.shorten("short"));
        assertEquals("first", SettingsDialog.shorten("first\nsecond line"));
        assertEquals("padded", SettingsDialog.shorten("  padded  \nsecond line"));   // 首行首尾空白剥掉
        assertEquals("", SettingsDialog.shorten("   \nsecond"));                     // 首行纯空白 → 空
        String longLine = "aVeryLongFailureReasonThatExceedsFortyCharactersByFar!!!";
        String s = SettingsDialog.shorten(longLine + "\nsecond");
        assertEquals(41, s.length()); // 40 字符 + …
        assertTrue(s.endsWith("…"));
    }

    // ===== 模型页激活判定（纯逻辑，无 JavaFX） =====

    /** 无选中（null）或选中的就是当前模型 → 不可激活 */
    @Test
    public void canActivate_rejectsNullOrCurrent() {
        assertFalse(SettingsDialog.canActivate(null, "deepseek-v4-flash"));
        assertFalse(SettingsDialog.canActivate("deepseek-v4-flash", "deepseek-v4-flash"));
    }

    /** 选中了其它模型 → 可激活 */
    @Test
    public void canActivate_allowsDifferentModel() {
        assertTrue(SettingsDialog.canActivate("qwen3-max", "deepseek-v4-flash"));
    }

    /** maxContextTokens 解析（终审 P3）：非数字/非正数回退 200000——0 会让压缩百分比表达式除零 */
    @Test
    public void parseMaxContextTokens_rejectsNonPositive() {
        assertEquals(200000, SettingsDialog.parseMaxContextTokens("0"));
        assertEquals(200000, SettingsDialog.parseMaxContextTokens("-5"));
        assertEquals(200000, SettingsDialog.parseMaxContextTokens("abc"));
        assertEquals(200000, SettingsDialog.parseMaxContextTokens(""));
        assertEquals(200000, SettingsDialog.parseMaxContextTokens(null));
        assertEquals(131072, SettingsDialog.parseMaxContextTokens(" 131072 "));
    }
}
