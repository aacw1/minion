package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class RetryPolicyTest {

    @Test
    public void delay_linearIncrement_cappedAtMax() {
        RetryPolicy p = new RetryPolicy(2000, 2000, 10000, 1800000);
        assertEquals(2000, p.delayMs(1));
        assertEquals(4000, p.delayMs(2));
        assertEquals(6000, p.delayMs(3));
        assertEquals(8000, p.delayMs(4));
        assertEquals(10000, p.delayMs(5));  // 命中上限
        assertEquals(10000, p.delayMs(100)); // 封顶不再涨
    }

    @Test
    public void isExhausted_boundary() {
        RetryPolicy p = new RetryPolicy(2000, 2000, 10000, 1000);
        assertFalse(p.isExhausted(999));
        assertTrue(p.isExhausted(1000));
        assertTrue(p.isExhausted(1001));
    }

    @Test
    public void transientErrors_defaultParams() {
        RetryPolicy p = RetryPolicy.transientErrors();
        assertEquals(5000, p.initialDelayMs);
        assertEquals(0, p.incrementMs);
        assertEquals(5000, p.maxDelayMs);
        assertEquals(1200000L, p.maxTotalMs); // 20 分钟
    }

    @Test
    public void transientErrors_fixedDelayEveryAttempt() {
        RetryPolicy p = RetryPolicy.transientErrors();
        assertEquals(5000, p.delayMs(1));
        assertEquals(5000, p.delayMs(2));
        assertEquals(5000, p.delayMs(100)); // 任意次数恒 5s
    }
}
