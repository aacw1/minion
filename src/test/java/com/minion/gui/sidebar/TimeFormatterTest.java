package com.minion.gui.sidebar;

import org.junit.Test;

import static org.junit.Assert.*;

/** 消息时间显示规则：1m/5m/3h/2d；旧数据 ts<=0 不显示 */
public class TimeFormatterTest {

    private static final long NOW = 1_000_000_000_000L;

    @Test
    public void format_underOneMinuteShows1m() {
        assertEquals("1m", TimeFormatter.format(NOW - 30_000L, NOW));
        assertEquals("1m", TimeFormatter.format(NOW, NOW));
    }

    @Test
    public void format_minutesFloor() {
        assertEquals("5m", TimeFormatter.format(NOW - 5 * 60_000L - 30_000L, NOW));
        assertEquals("59m", TimeFormatter.format(NOW - 59 * 60_000L, NOW));
    }

    @Test
    public void format_hoursFloor() {
        assertEquals("1h", TimeFormatter.format(NOW - 3_600_000L, NOW));
        assertEquals("3h", TimeFormatter.format(NOW - 3 * 3_600_000L - 59 * 60_000L, NOW));
        assertEquals("23h", TimeFormatter.format(NOW - 23 * 3_600_000L, NOW));
    }

    @Test
    public void format_daysFloor() {
        assertEquals("1d", TimeFormatter.format(NOW - 24 * 3_600_000L, NOW));
        assertEquals("2d", TimeFormatter.format(NOW - 2 * 86_400_000L - 1L, NOW));
    }

    @Test
    public void format_oldDataReturnsNull() {
        assertNull(TimeFormatter.format(0L, NOW));
        assertNull(TimeFormatter.format(-1L, NOW));
    }
}
