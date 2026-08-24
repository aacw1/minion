package com.minion.gui;

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

    /** 重试文案格式：显示当前重试次数 */
    @Test
    public void retryText_formatsAttempt() {
        assertEquals("正在重试中…第 1 次", RunningIndicator.retryText(1));
        assertEquals("正在重试中…第 12 次", RunningIndicator.retryText(12));
    }
}
