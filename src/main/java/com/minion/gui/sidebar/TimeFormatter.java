package com.minion.gui.sidebar;

/** 会话列表时间显示：消息创建时间 → 相对距离（5m/3h/2d）；ts<=0（旧数据）→ null 不显示 */
public class TimeFormatter {

    public static String format(long ts, long now) {
        if (ts <= 0) return null;
        long diff = Math.max(0L, now - ts);
        if (diff < 60_000L) return "1m";
        long minutes = diff / 60_000L;
        if (minutes < 60) return minutes + "m";
        long hours = diff / 3_600_000L;
        if (hours < 24) return hours + "h";
        return (diff / 86_400_000L) + "d";
    }
}
