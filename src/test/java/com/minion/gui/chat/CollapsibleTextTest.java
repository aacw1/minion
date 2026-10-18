package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/** CollapsibleText 默认折叠阈值纯逻辑测试（shouldCollapse） */
public class CollapsibleTextTest {

    @Test
    public void underThreshold_notCollapsed() {
        assertFalse(CollapsibleText.shouldCollapse("短内容"));
        assertFalse(CollapsibleText.shouldCollapse(""));
    }

    @Test
    public void atThreshold_collapsed() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CollapsibleText.COLLAPSE_THRESHOLD; i++) sb.append('x');
        assertTrue(CollapsibleText.shouldCollapse(sb.toString()));
    }

    @Test
    public void null_notCollapsed() {
        assertFalse(CollapsibleText.shouldCollapse(null));
    }

    /** 折叠态不得把文本交给 TextArea：宽度 0 的 TextArea 仍会被 JavaFX 皮肤布局，
     *  特定文本会让 PrismTextLayout 越界并中断整个 Scene 布局 pass（线上整页错乱根因） */
    @Test
    public void collapsed_contentTextIsEmpty() {
        assertEquals("", CollapsibleText.contentText(false, true, "很长的工具输出"));
    }

    @Test
    public void expanded_contentTextIsFullText() {
        assertEquals("很长的工具输出", CollapsibleText.contentText(true, true, "很长的工具输出"));
    }

    @Test
    public void emptyContent_contentTextIsEmpty() {
        assertEquals("", CollapsibleText.contentText(true, false, ""));
        assertEquals("", CollapsibleText.contentText(false, false, null));
    }
}
