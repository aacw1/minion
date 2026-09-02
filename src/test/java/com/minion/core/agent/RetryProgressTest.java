package com.minion.core.agent;

import com.minion.core.llm.LlmException;
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

    /** 网络超时：无 HTTP 码，标签走 label */
    @Test
    public void from_timeout_mapsToNetworkLabel() {
        RetryProgress p = RetryProgress.from(3,
                new LlmException(LlmException.Type.TIMEOUT, "请求超时：60 秒内未收到模型输出", true));
        assertEquals(3, p.attempt);
        assertEquals("网络超时", p.label);
        assertEquals(0, p.httpCode);
        assertEquals("请求超时：60 秒内未收到模型输出", p.body);
    }

    /** 网络错误：同上，标签为"网络错误" */
    @Test
    public void from_networkError_mapsToNetworkLabel() {
        RetryProgress p = RetryProgress.from(7,
                new LlmException(LlmException.Type.NETWORK, "网络错误: Failed to connect to api.xx", true));
        assertEquals("网络错误", p.label);
        assertEquals(0, p.httpCode);
    }

    /** HTTP 错误：label 为 null，httpCode/body 照旧（零回归） */
    @Test
    public void from_httpError_keepsCodeAndNullLabel() {
        RetryProgress p = RetryProgress.from(2, LlmException.of(500, "{\"error\":\"boom\"}"));
        assertNull(p.label);
        assertEquals(500, p.httpCode);
        assertEquals("{\"error\":\"boom\"}", p.body);
    }

    /** ofNetwork 工厂：httpCode 恒 0，detail 落 body */
    @Test
    public void ofNetwork_carriesLabelAndDetail() {
        RetryProgress p = RetryProgress.ofNetwork(1, "网络超时", "timeout");
        assertEquals(1, p.attempt);
        assertEquals("网络超时", p.label);
        assertEquals(0, p.httpCode);
        assertEquals("timeout", p.body);
    }

    /** 总结文案前缀标签：429/500 用码，网络类用中文（避免输出"0 重试了 N 次"） */
    @Test
    public void tag_labelsPerErrorType() {
        assertEquals("429", RetryProgress.tag(LlmException.of(429, null)));
        assertEquals("500", RetryProgress.tag(LlmException.of(500, "x")));
        assertEquals("网络超时", RetryProgress.tag(
                new LlmException(LlmException.Type.TIMEOUT, "t", true)));
        assertEquals("网络错误", RetryProgress.tag(
                new LlmException(LlmException.Type.NETWORK, "n", true)));
    }

    /** 复位态：label 也为 null */
    @Test
    public void none_resetsLabelToo() {
        assertNull(RetryProgress.none().label);
    }
}
