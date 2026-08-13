package com.minion.gui.session;

import org.junit.Test;

import static org.junit.Assert.*;

/** 贴底判定：贴底/离开/半屏容差/forceFollow/vmax 增长保持跟随；初始视为贴底（内容未超一屏时 vvalue==vmax==0） */
public class AutoScrollPolicyTest {

    @Test
    public void initiallyPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        assertTrue(p.shouldFollow());
    }

    @Test
    public void atBottom_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(100.0, 100.0);
        assertTrue(p.shouldFollow());
    }

    @Test
    public void scrolledUp_isNotPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(100.0, 100.0);
        p.sync(50.0, 100.0);
        assertFalse(p.shouldFollow());
    }

    @Test
    public void withinHalfScreen_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(99.5, 100.0); // 距底恰好半屏（0.5 视口单位）→ 贴底
        assertTrue(p.shouldFollow());
    }

    @Test
    public void beyondHalfScreen_isNotPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(99.49, 100.0); // 距底超过半屏 → 离开
        assertFalse(p.shouldFollow());
    }

    @Test
    public void backToBottom_pinsAgain() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(50.0, 100.0);
        p.sync(99.9995, 100.0);
        assertTrue(p.shouldFollow());
    }

    @Test
    public void contentFits_noScroll_isPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.0, 0.0);
        assertTrue(p.shouldFollow());
    }

    /** 根因回归：贴底时内容增长（vvalue 停在旧 vmax、vmax 增大）→ 保持跟随 */
    @Test
    public void vmaxGrows_whilePinned_staysPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(100.0, 100.0);
        p.onVmaxChanged(100.0, 100.0, 100.6); // 增长超过半屏容差也必须跟随：增长前就在底部
        assertTrue(p.shouldFollow());
    }

    /** 根因回归：上翻离开底部后内容增长 → 不跟随（阅读历史不被拽回） */
    @Test
    public void vmaxGrows_afterScrolledUp_staysUnpinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(50.0, 100.0);
        p.onVmaxChanged(50.0, 100.0, 101.0);
        assertFalse(p.shouldFollow());
    }

    /** 用户发消息强制贴底：布局抖动短暂离开底部也不失效 */
    @Test
    public void forceFollow_restoresPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(50.0, 100.0);
        assertFalse(p.shouldFollow());
        p.forceFollow();
        assertTrue(p.shouldFollow());
    }
}
