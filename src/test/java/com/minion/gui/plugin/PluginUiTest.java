package com.minion.gui.plugin;

import org.junit.Test;

import static org.junit.Assert.*;

/** PluginUi 纯函数（不触发 JavaFX toolkit，故只测解析类逻辑；控件构造留给手工验证） */
public class PluginUiTest {

    @Test
    public void parsePositiveIntAcceptsValid() {
        assertEquals(9222, PluginUi.parsePositiveInt("9222"));
        assertEquals(1, PluginUi.parsePositiveInt(" 1 "));
        assertEquals(30000, PluginUi.parsePositiveInt("30000"));
    }

    @Test
    public void parsePositiveIntRejectsGarbage() {
        assertEquals(-1, PluginUi.parsePositiveInt(null));
        assertEquals(-1, PluginUi.parsePositiveInt(""));
        assertEquals(-1, PluginUi.parsePositiveInt("   "));
        assertEquals(-1, PluginUi.parsePositiveInt("abc"));
        assertEquals(-1, PluginUi.parsePositiveInt("9222x"));
        assertEquals(-1, PluginUi.parsePositiveInt("0"));
        assertEquals(-1, PluginUi.parsePositiveInt("-5"));
        assertEquals(-1, PluginUi.parsePositiveInt("9222.5"));
    }

    @Test
    public void labelWidthIsFixed() {
        assertEquals(160, PluginUi.LABEL_WIDTH);
    }
}
