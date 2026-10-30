package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 装饰动画降级判定（纯函数）：显式配置优先于管线默认。 */
public class MotionTest {

    @Test
    public void explicitSettingWins() {
        assertFalse(Motion.reduced("full", true));   // 软件管线也强制保留
        assertTrue(Motion.reduced("reduced", false)); // 硬件管线也强制关闭
        assertTrue(Motion.reduced("reduced-motion", false));
        assertTrue(Motion.reduced(" REDUCED ", false));
    }

    @Test
    public void autoFollowsPipeline() {
        assertTrue(Motion.reduced(null, true));
        assertTrue(Motion.reduced("auto", true));
        assertFalse(Motion.reduced(null, false));
        assertFalse(Motion.reduced("auto", false));
        assertFalse(Motion.reduced("unknown", false));
        assertTrue(Motion.reduced("", true));
    }
}
