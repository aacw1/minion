package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

/** RetryProgress 值对象：重试进度链路传参（attempt=0 表示退出重试态） */
public class RetryProgressTest {

    @Test
    public void of_carriesFields() {
        RetryProgress p = RetryProgress.of(3, 500, "{\"error\":\"boom\"}");
        assertEquals(3, p.attempt);
        assertEquals(500, p.httpCode);
        assertEquals("{\"error\":\"boom\"}", p.body);
    }

    @Test
    public void none_resetsState() {
        RetryProgress p = RetryProgress.none();
        assertEquals(0, p.attempt);
        assertEquals(0, p.httpCode);
        assertNull(p.body);
    }
}
