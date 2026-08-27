package com.minion.core.llm;

import org.junit.Test;

import static org.junit.Assert.*;

/** LlmException.of 状态码映射与 httpCode/body 字段（瞬时错误长重试的判断依据） */
public class LlmExceptionTest {

    @Test
    public void of_429_rateLimit_carriesCode() {
        LlmException e = LlmException.of(429, null);
        assertEquals(LlmException.Type.RATE_LIMIT, e.type);
        assertTrue(e.retryable);
        assertEquals(429, e.httpCode);
    }

    @Test
    public void of_500_serverError_carriesCodeAndBody() {
        LlmException e = LlmException.of(500, "{\"error\":\"internal\"}");
        assertEquals(LlmException.Type.OTHER, e.type);
        assertTrue(e.retryable);
        assertEquals(500, e.httpCode);
        assertEquals("{\"error\":\"internal\"}", e.body);
    }

    @Test
    public void of_502_gatewayError_carriesCodeAndBody() {
        LlmException e = LlmException.of(502, "{\"message\":\"bad gateway\"}");
        assertEquals(502, e.httpCode);
        assertEquals("{\"message\":\"bad gateway\"}", e.body);
    }

    @Test
    public void of_503_otherServerError_retryable() {
        LlmException e = LlmException.of(503, "unavailable");
        assertTrue(e.retryable);
        assertEquals(503, e.httpCode);
    }

    @Test
    public void of_400_badRequest_notRetryable() {
        LlmException e = LlmException.of(400, "bad");
        assertEquals(LlmException.Type.BAD_REQUEST, e.type);
        assertFalse(e.retryable);
        assertEquals(400, e.httpCode);
    }

    @Test
    public void of_404_other_notRetryable() {
        LlmException e = LlmException.of(404, null);
        assertFalse(e.retryable);
        assertEquals(404, e.httpCode);
    }

    /** 旧构造器兼容：非 HTTP 错误 httpCode=0、body=null */
    @Test
    public void legacyConstructor_defaultsCodeAndBody() {
        LlmException e = new LlmException(LlmException.Type.NETWORK, "网络错误", true);
        assertEquals(0, e.httpCode);
        assertNull(e.body);
    }
}
