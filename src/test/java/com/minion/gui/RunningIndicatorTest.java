package com.minion.gui;

import com.minion.core.agent.RetryProgress;
import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** RunningIndicator 纯逻辑测试：文案池随机、压缩态优先级（组件本体需 FX toolkit，动画不单测） */
public class RunningIndicatorTest {

    /** 轮换文案随机选择：多次取样全部落在池内 */
    @Test
    public void pickText_staysInPool() {
        Random rnd = new Random(42);
        Set<String> pool = new HashSet<String>();
        for (String s : RunningIndicator.ROTATING_TEXTS) pool.add(s);
        for (int i = 0; i < 200; i++) {
            assertTrue("随机文案必须在池内: " + RunningIndicator.pickText(rnd),
                    pool.contains(RunningIndicator.pickText(rnd)));
        }
    }

    /** 池内恰有两个轮换文案（需求：正在加载中/可随时补充信息） */
    @Test
    public void rotatingPool_hasTwoEntries() {
        assertEquals(2, RunningIndicator.ROTATING_TEXTS.length);
        assertEquals("正在加载中...", RunningIndicator.ROTATING_TEXTS[0]);
        assertEquals("可随时补充信息...", RunningIndicator.ROTATING_TEXTS[1]);
    }

    /** 压缩中固定显示压缩文案，不参与轮换 */
    @Test
    public void displayText_compressingOverrides() {
        assertEquals("上下文压缩中...", RunningIndicator.COMPRESSING_TEXT);
        assertEquals("上下文压缩中...", RunningIndicator.displayText(true, "正在加载中..."));
        assertEquals("正在加载中...", RunningIndicator.displayText(false, "正在加载中..."));
    }

    /** 重试文案：冻结基础文案 + 429 限流后缀（明确限流，不显示错误体） */
    @Test
    public void retryText_429_noBody() {
        assertEquals("正在加载中...(429限流，重试第3次)",
                RunningIndicator.retryText(RetryProgress.of(3, 429, null), "正在加载中..."));
        assertEquals("可随时补充信息...(429限流，重试第1次)",
                RunningIndicator.retryText(RetryProgress.of(1, 429, ""), "可随时补充信息..."));
    }

    /** 500 服务报错：附带服务返回的错误体 */
    @Test
    public void retryText_500_withBody() {
        assertEquals("正在加载中...(500服务报错，重试第2次{\"error\":\"boom\"})",
                RunningIndicator.retryText(RetryProgress.of(2, 500, "{\"error\":\"boom\"}"), "正在加载中..."));
    }

    /** 502 网关报错：附带错误体 */
    @Test
    public void retryText_502_withBody() {
        assertEquals("可随时补充信息...(502网关报错，重试第1次bad gateway)",
                RunningIndicator.retryText(RetryProgress.of(1, 502, "bad gateway"), "可随时补充信息..."));
    }

    /** 错误体截断：只显示前 200 字符（429/500/502 一致） */
    @Test
    public void retryText_bodyTruncatedAt200() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) sb.append('x');
        String longBody = sb.toString();
        assertEquals(200, RunningIndicator.bodyPart(RetryProgress.of(1, 500, longBody)).length());
        assertEquals(200, RunningIndicator.bodyPart(RetryProgress.of(1, 429, longBody)).length());
        assertTrue(RunningIndicator.retryText(RetryProgress.of(1, 500, longBody), "正在加载中...")
                .contains(longBody.substring(0, 200)));
    }

    /** 未知错误码防御性显示（理论不可达：长重试仅 429/500/502） */
    @Test
    public void retryText_unknownCode_defensive() {
        assertEquals("HTTP 503", RunningIndicator.codeLabel(503));
    }
}
