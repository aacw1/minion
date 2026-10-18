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

    /** 折叠懒挂载判定：折叠或空内容时内容体不进入场景图（不创建 TextArea skin，
     *  长会话大量折叠段的首次布局成本从每段 ~3ms 降到接近 0） */
    @Test
    public void attachContent_onlyWhenExpandedAndHasText() {
        assertTrue(CollapsibleText.shouldAttachContent(true, true));
        assertFalse("折叠态不挂载（仍保持空文本，防 JavaFX 8 文本引擎越界）",
                CollapsibleText.shouldAttachContent(false, true));
        assertFalse(CollapsibleText.shouldAttachContent(true, false));
        assertFalse(CollapsibleText.shouldAttachContent(false, false));
    }
}
