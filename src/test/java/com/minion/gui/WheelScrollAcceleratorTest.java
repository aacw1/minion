package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 滚轮增量 → vvalue 换算（固定像素每格 100px，Windows 一格 WHEEL_DELTA=40） */
public class WheelScrollAcceleratorTest {

    @Test public void wheelDown_oneNotch_advances100px() {
        assertEquals(0.6, WheelScrollAccelerator.newVvalue(0.5, -40, 1000), 1e-9);
    }

    @Test public void wheelUp_oneNotch_retreats100px() {
        assertEquals(0.4, WheelScrollAccelerator.newVvalue(0.5, 40, 1000), 1e-9);
    }

    @Test public void smoothWheel_fractionalDelta_continuous() {
        // 平滑滚轮每事件 1/10 格 → 10px → 0.01
        assertEquals(0.51, WheelScrollAccelerator.newVvalue(0.5, -4, 1000), 1e-9);
    }

    @Test public void zeroDelta_noChange() {
        assertEquals(0.5, WheelScrollAccelerator.newVvalue(0.5, 0, 1000), 1e-9);
    }

    @Test public void noScrollableHeight_doesNotThrow() {
        // 行程 0 除零得 ±Infinity（double 不抛异常）；拦截在 attach 的 scrollable<=0 放行分支
        assertTrue(Double.isInfinite(WheelScrollAccelerator.newVvalue(0.5, -40, 0)));
        // 负行程（防御）仅反转方向，结果有限
        assertFalse(Double.isNaN(WheelScrollAccelerator.newVvalue(0.5, -40, -1000)));
    }
}
