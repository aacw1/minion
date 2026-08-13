package com.minion.gui.session;

/** 消息区自动滚动策略（纯逻辑）：贴底判定与内容增长跟随；离开底部即暂停，拖回底部恢复 */
public class AutoScrollPolicy {

    /** 贴底阈值（vvalue 视口单位）：距底小于该值视为贴底 */
    private static final double EPSILON = 0.001;

    private boolean pinned = true; // 初始视为贴底：内容未超一屏时 vvalue==vmax==0

    /** 滚动位置变化时更新贴底状态 */
    public void onScroll(double vvalue, double vmax) {
        pinned = vvalue >= vmax - EPSILON;
    }

    /** 内容增长后是否应跟随滚动到底（贴底时 true） */
    public boolean shouldFollow() {
        return pinned;
    }
}
