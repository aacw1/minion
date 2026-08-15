package com.minion.gui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;

/** 正文消息区滚轮加速：每格固定像素滚动（替换 JavaFX 8 皮肤默认比例滚动），
 *  平滑滚轮小数增量连续换算不跳格。静态挂接，随 ScrollPane 生命周期共存。 */
public class WheelScrollAccelerator {

    /** 每格滚轮滚动像素（Windows 一格 = 40，平滑滚轮按比例换算） */
    static final double SCROLL_PIXELS_PER_NOTCH = 100.0;
    /** Windows 一格滚轮的 deltaY 基准值（WHEEL_DELTA） */
    private static final double DELTA_PER_NOTCH = 40.0;

    /** 挂接滚轮加速到 ScrollPane（正文消息区构造后调用一次；换 content 无需重挂） */
    public static void attach(ScrollPane pane) {
        pane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() || e.isShiftDown()) return; // 放行皮肤默认（Ctrl+滚轮缩放等）
            double scrollable = scrollableHeight(pane);
            if (scrollable <= 0) return; // 内容未超一屏：无滚动行程，放行皮肤（无操作）
            double v = newVvalue(pane.getVvalue(), e.getDeltaY(), scrollable);
            pane.setVvalue(clamp(v)); // setVvalue 越界值 JavaFX 自动钳制，此处显式 clamp 保证语义清晰
            e.consume(); // 阻止皮肤二次滚动（防双倍滚动）
        });
    }

    /** 纯函数：滚轮增量 → 新 vvalue（[0,1] 外值由调用方 clamp）；
     *  行程 ≤0 时调用方必须先放行（除零得 ±Infinity 由 double 语义兜底不抛异常） */
    static double newVvalue(double vvalue, double deltaY, double scrollableHeight) {
        return vvalue - deltaY * SCROLL_PIXELS_PER_NOTCH / DELTA_PER_NOTCH / scrollableHeight;
    }

    /** 实时可滚动行程：内容高 − 视口高（未布局完成时为 0 → attach 放行） */
    private static double scrollableHeight(ScrollPane pane) {
        Node content = pane.getContent();
        if (content == null) return 0;
        return content.getLayoutBounds().getHeight() - pane.getViewportBounds().getHeight();
    }

    private static double clamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
