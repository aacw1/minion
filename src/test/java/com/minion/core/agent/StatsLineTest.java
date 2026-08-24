package com.minion.core.agent;

import com.minion.core.llm.Usage;
import com.minion.core.llm.UsageTracker;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 统计行格式化：token 缩写边界与整行格式（GUI 版无 CLI 的 * 前缀） */
public class StatsLineTest {

    @Test public void formatTokens_below1000_plain() {
        assertEquals("999", StatsLine.formatTokens(999));
        assertEquals("0", StatsLine.formatTokens(0));
    }

    @Test public void formatTokens_exactThousands_k() {
        assertEquals("1k", StatsLine.formatTokens(1000));
        assertEquals("900k", StatsLine.formatTokens(900000));
    }

    @Test public void formatTokens_large_roundedK() {
        assertEquals("100k", StatsLine.formatTokens(100000));
        assertEquals("131k", StatsLine.formatTokens(131072));
    }

    @Test public void formatTokens_middle_oneDecimal() {
        assertEquals("7.8k", StatsLine.formatTokens(7800));
    }

    @Test public void format_completeLine() {
        UsageTracker t = new UsageTracker();
        Usage u = new Usage();
        u.inputTokens = 1200;
        u.outputTokens = 345;
        u.reasoningTokens = 123;
        t.record(u);
        String line = StatsLine.format(t, 12300, 45000, 900000);
        assertEquals("⏱ 12.3s  in 1.2k  out 345  thinking 123  ctx 45k/900k (5%)", line);
    }

    @Test public void format_zeroMaxCtx_printsZeroPct() {
        String line = StatsLine.format(new UsageTracker(), 500, 0, 0);
        assertEquals("⏱ 0.5s  in 0  out 0  thinking 0  ctx 0/0 (0%)", line);
    }
}
