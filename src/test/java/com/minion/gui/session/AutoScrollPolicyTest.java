package com.minion.gui.session;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 归一化语义：vvalue ∈ [0,1]（1=底部），eps 为动态半屏容差（0.5×视口/可滚动行程） */
public class AutoScrollPolicyTest {

    @Test public void epsHalf_nearBottom_pinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.6, 0.5);
        assertTrue(p.shouldFollow());
    }

    @Test public void epsHalf_farFromBottom_unpinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.4, 0.5);
        assertFalse(p.shouldFollow());
    }

    @Test public void epsHalf_boundary_exactlyThreshold_pinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.5, 0.5);
        assertTrue(p.shouldFollow());
    }

    @Test public void epsSmall_tightTolerance() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.96, 0.05); // 阈值 1-0.05=0.95
        assertTrue(p.shouldFollow());
        p.sync(0.9, 0.05);
        assertFalse(p.shouldFollow());
    }

    @Test public void epsAtLeastOne_alwaysPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.0, 1.0);
        assertTrue(p.shouldFollow());
        p.sync(0.0, 2.5);
        assertTrue(p.shouldFollow());
    }

    @Test public void forceFollow_restoresPinned() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.sync(0.0, 0.5);
        assertFalse(p.shouldFollow());
        p.forceFollow();
        assertTrue(p.shouldFollow());
    }

    @Test public void sync_afterForceFollow_tracksVvalue() {
        AutoScrollPolicy p = new AutoScrollPolicy();
        p.forceFollow();
        assertTrue(p.shouldFollow());
        p.sync(0.9, 0.5);
        assertTrue(p.shouldFollow());
        p.sync(0.3, 0.5);
        assertFalse(p.shouldFollow());
    }
}
