package com.minion.core.agent;

import com.minion.core.llm.UsageTracker;

import java.util.Locale;

/** 每轮结束的统计行格式化（GUI 展示；移植自已删除的 cli/StatsLine，去掉 CLI 的 * 前缀） */
public class StatsLine {

    public static String format(UsageTracker usage, long elapsedMillis,
                                int currentCtx, int maxCtx) {
        double secs = elapsedMillis / 1000.0;
        int pct = maxCtx > 0 ? (int) Math.round(currentCtx * 100.0 / maxCtx) : 0;
        return String.format(Locale.ROOT,
                "⏱ %.1fs · in %s · out %s · thinking %s · ctx %s/%s (%d%%)",
                secs,
                formatTokens(usage.sessionInput()),
                formatTokens(usage.sessionOutput()),
                formatTokens(usage.sessionThinking()),
                formatTokens(currentCtx), formatTokens(maxCtx), pct);
    }

    /** 1000 以下原样；整千 "900k"；10 万以上四舍五入到整 k（如 131072 → 131k）；其余 "7.8k" */
    public static String formatTokens(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n % 1000 == 0) return (n / 1000) + "k";
        if (n >= 100000) return Math.round(n / 1000.0) + "k";
        return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
    }
}
