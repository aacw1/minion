package com.minion.gui.session;

import org.junit.Test;

import static org.junit.Assert.*;

/** 贴底判定：贴底/离开/回到底部；初始视为贴底（内容未超一屏时 vvalue==vmax==0） */
public class AutoScrollPolicyTest {

    @Test
    public void initiallyPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        assertTrue(p.shouldFollow());
    }

    @Test
    public void atBottom_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(100.0, 100.0);
        assertTrue(p.shouldFollow());
    }

    @Test
    public void scrolledUp_isNotPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(100.0, 100.0);
        p.onScroll(50.0, 100.0);
        assertFalse(p.shouldFollow());
    }

    @Test
    public void backToBottom_pinsAgain() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(50.0, 100.0);
        p.onScroll(99.9995, 100.0); // 距底 0.0005（视口单位）→ 视为贴底
        assertTrue(p.shouldFollow());
    }

    @Test
    public void contentFits_noScroll_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.onScroll(0.0, 0.0);
        assertTrue(p.shouldFollow());
    }
}
