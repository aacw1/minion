package com.minion.gui.session;

/** 消息区自动滚动策略（纯逻辑，归一化语义）：贴底判定与内容增长跟随；离开底部即暂停，拖回底部恢复。
 *  JavaFX 8 ScrollPane 的 vvalue 是 [0,1] 归一化比例（top = vvalue×(内容高−视口高)），vmax 恒为 1.0；
 *  贴底容差用动态半屏 eps = 0.5×视口高/可滚动行程（随内容变长而收窄，等效"距底半屏"） */
public class AutoScrollPolicy {

    private boolean pinned = true; // 初始视为贴底：内容未超一屏时无滚动可言

    /** 滚动位置变化时重算贴底状态（vvalue 监听器调用）。
     *  eps：半屏容差（归一化）= 0.5×视口高/可滚动行程；eps >= 1（内容未超一屏）恒贴底 */
    public void sync(double vvalue, double eps) {
        pinned = eps >= 1.0 || vvalue >= 1.0 - eps;
    }

    /** 用户发消息时强制贴底：新一轮回复必然跟随 */
    public void forceFollow() {
        pinned = true;
    }

    /** 内容增长后是否应滚动到底（贴底时 true） */
    public boolean shouldFollow() {
        return pinned;
    }
}
