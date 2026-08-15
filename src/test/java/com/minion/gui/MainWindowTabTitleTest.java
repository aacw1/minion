package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** 页签标题截取（需求 7：长会话标题撑宽页签栏致标题栏错乱，超 16 字符截断加省略号，完整标题挂 Tooltip） */
public class MainWindowTabTitleTest {

    @Test
    public void shortTitle_unchanged() {
        assertEquals("短标题", MainWindow.tabTitle("短标题"));
    }

    @Test
    public void exactly16_unchanged() {
        assertEquals("1234567890123456", MainWindow.tabTitle("1234567890123456"));
    }

    @Test
    public void over16_truncatedWithEllipsis() {
        assertEquals("1234567890123456…", MainWindow.tabTitle("12345678901234567"));
        assertEquals("一二三四五六七八九十一二三四五六…", MainWindow.tabTitle("一二三四五六七八九十一二三四五六七八"));
    }

    @Test
    public void null_returnsNull() {
        assertNull(MainWindow.tabTitle(null));
    }
}
