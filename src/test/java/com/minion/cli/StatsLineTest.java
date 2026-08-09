package com.minion.cli;

import com.minion.core.llm.Usage;
import com.minion.core.llm.UsageTracker;
import org.junit.Test;

import static org.junit.Assert.*;

public class StatsLineTest {

    @Test
    public void formatTokens_units() {
        assertEquals("512", StatsLine.formatTokens(512));
        assertEquals("0", StatsLine.formatTokens(0));
        assertEquals("8.2k", StatsLine.formatTokens(8200));
        // 整千直接 "Nk"，不显示小数：900000 → 900k
        assertEquals("900k", StatsLine.formatTokens(900000));
        assertEquals("1k", StatsLine.formatTokens(1000));
        // 10 万以上四舍五入到整 k：131072 → 131k
        assertEquals("131k", StatsLine.formatTokens(131072));
    }

    @Test
    public void format_fullLine() {
        UsageTracker t = new UsageTracker();
        Usage u = new Usage();
        u.inputTokens = 8200;
        u.outputTokens = 3400;
        u.reasoningTokens = 2100;
        t.record(u);
        String line = StatsLine.format(t, 12300, 61400, 131072);
        // ⏱ 等 Unicode 符号在 mintty 默认字体链中渲染为 ?，统计行只用终端可渲染字符
        assertTrue(line.contains("* 12.3s"));
        assertTrue(line.contains("in 8.2k"));
        assertTrue(line.contains("out 3.4k"));
        assertTrue(line.contains("thinking 2.1k"));
        // 上下文分母同样用 k 单位
        assertTrue(line.contains("ctx 61.4k/131k (47%)"));
        SafeGlyphs.assertSafe(line);
    }
}
