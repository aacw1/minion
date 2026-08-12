package com.minion.gui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * 运行中状态点：呼吸动画（opacity 0.35↔1.0 往复）。
 * JavaFX 8 不支持 CSS keyframe 动画（-fx-animation 无效），故用 Timeline 实现。
 * 动画 Timeline 以 KeyValue 强引用节点，节点被丢弃前必须 stopPulseIn 回收，
 * 否则每次重建状态点都泄漏一个无限动画（ListView updateItem / tab 重建高频路径）。
 */
public final class StatusDot {

    private static final Object PULSE_KEY = new Object();
    private static final double LOW = 0.35;
    private static final double HIGH = 1.0;
    private static final double PERIOD_MS = 1200;

    private StatusDot() {
    }

    /** 建状态点：running 时附加呼吸动画 */
    public static Circle create(boolean running) {
        Circle dot = new Circle(4);
        dot.getStyleClass().add("status-dot");
        if (running) startPulse(dot);
        return dot;
    }

    /** 启动呼吸动画（幂等：已有动画不重复起） */
    public static void startPulse(Circle dot) {
        if (dot.getProperties().containsKey(PULSE_KEY)) return;
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(dot.opacityProperty(), LOW)),
                new KeyFrame(Duration.millis(PERIOD_MS / 2), new KeyValue(dot.opacityProperty(), HIGH)),
                new KeyFrame(Duration.millis(PERIOD_MS), new KeyValue(dot.opacityProperty(), LOW)));
        t.setCycleCount(Animation.INDEFINITE);
        t.play();
        dot.getProperties().put(PULSE_KEY, t);
    }

    /** 停止并移除动画（动画引用节点，节点离开场景图前必须调用） */
    public static void stopPulse(Node node) {
        Object t = node.getProperties().remove(PULSE_KEY);
        if (t instanceof Timeline) ((Timeline) t).stop();
    }

    /** 递归停止节点子树内全部状态点动画（ListView 旧 graphic / tab 旧头回收用） */
    public static void stopPulseIn(Node root) {
        if (root == null) return;
        stopPulse(root);
        if (root instanceof Parent) {
            for (Node c : ((Parent) root).getChildrenUnmodifiable()) {
                stopPulseIn(c);
            }
        }
    }
}
