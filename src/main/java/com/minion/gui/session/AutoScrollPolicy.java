package com.minion.gui.session;

/** 消息区自动滚动策略（纯逻辑）：贴底判定与内容增长跟随；离开底部即暂停，拖回底部恢复。
 *  贴底容差半屏（固定不随内容变长）；vvalue/vmax 任一变化都必须重算，
 *  否则流式增长中 vvalue 停在旧 vmax、vmax 继续增大时会误判"离开底部"→ 跟随永久失效 */
public class AutoScrollPolicy {

    /** 贴底阈值（vvalue 视口单位）：距底小于该值视为贴底（半屏容差） */
    private static final double EPSILON = 0.5;

    private boolean pinned = true; // 初始视为贴底：内容未超一屏时 vvalue==vmax==0

    /** 滚动位置变化时重算贴底状态（vvalue 监听器调用） */
    public void sync(double vvalue, double vmax) {
        pinned = vvalue >= vmax - EPSILON;
    }

    /** 内容高度变化（vmax: prev → cur）时重算（vmax 监听器调用）：
     *  增长前贴底（vvalue≈prev）视为仍贴底——内容增长跟随，且不受增长幅度影响 */
    public void onVmaxChanged(double vvalue, double prevVmax, double curVmax) {
        pinned = (vvalue >= curVmax - EPSILON) || (vvalue >= prevVmax - EPSILON);
    }

    /** 用户发消息时强制贴底：新一轮回复必然跟随 */
    public void forceFollow() {
        pinned = true;
    }

    /** 内容增长后是否应跟随滚动到底（贴底时 true） */
    public boolean shouldFollow() {
        return pinned;
    }
}
