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
}
