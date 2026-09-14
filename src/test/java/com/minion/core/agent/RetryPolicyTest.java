package com.minion.core.agent;

import com.minion.core.llm.LlmException;
import org.junit.Test;

import static org.junit.Assert.*;

public class RetryPolicyTest {

    @Test
    public void transientErrors_defaultParams() {
        RetryPolicy p = RetryPolicy.transientErrors();
        assertEquals(5000, p.shortDelayMs);
        assertEquals(30000, p.longDelayMs);
        assertEquals(720000L, p.maxTotalMs); // 12 分钟
    }

    @Test
    public void delayMs_perKind() {
        RetryPolicy p = RetryPolicy.transientErrors();
        assertEquals(5000, p.delayMs(RetryPolicy.Kind.SHORT));
        assertEquals(30000, p.delayMs(RetryPolicy.Kind.LONG));
    }

    @Test
    public void isExhausted_boundary() {
        RetryPolicy p = new RetryPolicy(10, 20, 1000);
        assertFalse(p.isExhausted(999));
        assertTrue(p.isExhausted(1000));
        assertTrue(p.isExhausted(1001));
    }

    /** 可长重试集合：429/超时/可恢复网络/500 类（含 503/504）/空响应；DNS 与 4xx 不放行 */
    @Test
    public void isTransient_coversAllClasses() {
        assertTrue(RetryPolicy.isTransient(LlmException.of(429, null)));
        assertTrue(RetryPolicy.isTransient(new LlmException(LlmException.Type.TIMEOUT, "t", true)));
        assertTrue(RetryPolicy.isTransient(new LlmException(LlmException.Type.NETWORK, "n", true)));
        assertTrue(RetryPolicy.isTransient(LlmException.of(500, "x")));
        assertTrue(RetryPolicy.isTransient(LlmException.of(502, "x")));
        assertTrue(RetryPolicy.isTransient(LlmException.of(503, "x")));
        assertTrue(RetryPolicy.isTransient(LlmException.of(504, "x")));
        assertTrue(RetryPolicy.isTransient(new LlmException(LlmException.Type.EMPTY_RESPONSE, "空响应", true)));
        assertFalse(RetryPolicy.isTransient(new LlmException(LlmException.Type.NETWORK, "dns", false)));
        assertFalse(RetryPolicy.isTransient(LlmException.of(400, "bad")));
        assertFalse(RetryPolicy.isTransient(LlmException.of(401, "auth")));
        assertFalse(RetryPolicy.isTransient(new LlmException(LlmException.Type.BAD_REQUEST, "x", false)));
    }

    /** 类别：429/超时/网络 → SHORT；500 类与空响应 → LONG */
    @Test
    public void kindOf_shortVsLong() {
        assertEquals(RetryPolicy.Kind.SHORT, RetryPolicy.kindOf(LlmException.of(429, null)));
        assertEquals(RetryPolicy.Kind.SHORT,
                RetryPolicy.kindOf(new LlmException(LlmException.Type.TIMEOUT, "t", true)));
        assertEquals(RetryPolicy.Kind.SHORT,
                RetryPolicy.kindOf(new LlmException(LlmException.Type.NETWORK, "n", true)));
        assertEquals(RetryPolicy.Kind.LONG, RetryPolicy.kindOf(LlmException.of(500, "x")));
        assertEquals(RetryPolicy.Kind.LONG, RetryPolicy.kindOf(LlmException.of(503, "x")));
        assertEquals(RetryPolicy.Kind.LONG,
                RetryPolicy.kindOf(new LlmException(LlmException.Type.EMPTY_RESPONSE, "空响应", true)));
    }
}
