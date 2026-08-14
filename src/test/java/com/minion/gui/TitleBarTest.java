package com.minion.gui;

import javafx.geometry.Rectangle2D;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 手动最大化的目标 bounds 计算（无边框窗口 setMaximized 在 Windows 上覆盖任务栏，改手动定位） */
public class TitleBarTest {

    @Test
    public void maxBounds_returnsVisualBounds() {
        Rectangle2D vb = new Rectangle2D(0, 0, 1920, 1040); // visualBounds 已排除任务栏
        assertEquals(vb, TitleBar.maxBounds(vb));
    }

    @Test
    public void maxBounds_preservesOriginAndSize() {
        Rectangle2D vb = new Rectangle2D(100, 50, 1600, 900);
        Rectangle2D r = TitleBar.maxBounds(vb);
        assertEquals(100, r.getMinX(), 0.001);
        assertEquals(50, r.getMinY(), 0.001);
        assertEquals(1600, r.getWidth(), 0.001);
        assertEquals(900, r.getHeight(), 0.001);
    }
}
