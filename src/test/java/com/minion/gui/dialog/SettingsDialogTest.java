package com.minion.gui.dialog;

import com.minion.core.config.Config;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** 需求 2：设置窗基础设置页保存校验——browser.port/timeoutMs 非法输入不写回（防写坏配置致下次启动崩溃） */
public class SettingsDialogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void setInt_rejectsGarbageWithoutPersisting() throws Exception {
        Config c = Config.load(tmp.getRoot().toPath());
        assertFalse(SettingsDialog.setInt("browser.port", "abc", c));
        String content = new String(Files.readAllBytes(c.externalFile()), StandardCharsets.UTF_8);
        assertFalse("非法值不应写入外部文件", content.contains("browser.port=abc"));
    }

    @Test
    public void setInt_savesValidIntegerAndPersists() throws Exception {
        Config c = Config.load(tmp.getRoot().toPath());
        assertTrue(SettingsDialog.setInt("browser.port", "9223", c));
        Config c2 = Config.load(tmp.getRoot().toPath());
        assertEquals(9223, c2.browserPort());
    }

    @Test
    public void setInt_rejectsNegative() throws Exception {
        Config c = Config.load(tmp.getRoot().toPath());
        assertFalse(SettingsDialog.setInt("browser.timeoutMs", "-5", c));
    }

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

    /** 失败原因列表显示：null→空、取首行、超 40 字符截断加省略号 */
    @Test
    public void shorten_takesFirstLineAndTruncates() {
        assertEquals("", SettingsDialog.shorten(null));
        assertEquals("short", SettingsDialog.shorten("short"));
        assertEquals("first", SettingsDialog.shorten("first\nsecond line"));
        String longLine = "aVeryLongFailureReasonThatExceedsFortyCharactersByFar!!!";
        String s = SettingsDialog.shorten(longLine + "\nsecond");
        assertEquals(41, s.length()); // 40 字符 + …
        assertTrue(s.endsWith("…"));
    }
}
