package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;

/** ContextRing 纯静态逻辑测试：弧角度/可压缩判定/悬停文案（组件本体需 FX toolkit，绘制与动画不单测） */
public class ContextRingTest {

    @Test public void arcAngle_zero() {
        assertEquals(0.0, ContextRing.arcAngle(0), 1e-9);
    }
    @Test public void arcAngle_thirtyPct() {
        assertEquals(108.0, ContextRing.arcAngle(0.3), 1e-9);
    }
    @Test public void arcAngle_full() {
        assertEquals(360.0, ContextRing.arcAngle(1), 1e-9);
    }
    @Test public void arcAngle_clampsNegative() {
        assertEquals(0.0, ContextRing.arcAngle(-0.5), 1e-9);
    }
    @Test public void arcAngle_clampsAboveOne() {
        assertEquals(360.0, ContextRing.arcAngle(1.5), 1e-9);
    }

    /** 需求示例：上下文大小98k,占比70%,剩余10%自动压缩（98000/140000=70%，80%-70%=10%） */
    @Test public void formatInfo_example() {
        assertEquals("上下文大小98k,占比70%,剩余10%自动压缩",
                ContextRing.formatInfo(98000, 140000, 0.8));
    }
    @Test public void formatInfo_remainClampedZero() {
        assertEquals("上下文大小900k,占比100%,剩余0%自动压缩",
                ContextRing.formatInfo(900000, 900000, 0.8));
    }
    @Test public void formatInfo_zeroMax() {
        assertEquals("上下文大小0,占比0%,剩余0%自动压缩",
                ContextRing.formatInfo(0, 0, 0.8));
    }

    @Test public void compressable_over30_idle() {
        assertTrue(ContextRing.compressable(false, false, 0.31));
    }
    @Test public void compressable_compressing_no() {
        assertFalse(ContextRing.compressable(true, false, 0.31));
    }
    @Test public void compressable_compressing_running_no() {
        assertFalse(ContextRing.compressable(true, true, 0.31));
    }
    @Test public void compressable_running_no() {
        assertFalse(ContextRing.compressable(false, true, 0.31));
    }
    @Test public void compressable_exactly30_no() {
        assertFalse(ContextRing.compressable(false, false, 0.3));
    }
    @Test public void compressable_below30_no() {
        assertFalse(ContextRing.compressable(false, false, 0.1));
    }
}
