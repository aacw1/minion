package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 指示器显示条件：运行中且不在等待用户（提问挂起/确认卡片） */
public class MainWindowIndicatorTest {

    @Test
    public void running_alone_allowed() {
        assertTrue(MainWindow.indicatorAllowed(true, false, false));
    }

    @Test
    public void idle_neverAllowed() {
        assertFalse(MainWindow.indicatorAllowed(false, false, false));
    }

    @Test
    public void askPending_hidesIndicator() {
        assertFalse(MainWindow.indicatorAllowed(true, true, false));
    }

    @Test
    public void confirmSheetShowing_hidesIndicator() {
        assertFalse(MainWindow.indicatorAllowed(true, false, true));
    }
}
