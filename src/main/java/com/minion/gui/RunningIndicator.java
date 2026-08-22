package com.minion.gui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.Random;

/**
 * 运行状态指示器：正文区左下角悬浮（齿轮旋转 + 文案轮换）。
 * 齿轮 2s/圈旋转；文案每 10s 随机轮换（正在加载中.../可随时补充信息...）；
 * 上下文压缩中固定显示「上下文压缩中...」（不参与轮换，压缩结束恢复）。
 * 动画规约同 StatusDot：隐藏前必须停全部 Timeline，防动画引用节点泄漏。
 * 挂载方式：放入 StackPane 并 setAlignment(BOTTOM_LEFT)（本组件不管理布局）。
 */
public class RunningIndicator extends HBox {

    /** 轮换文案池（随机选择，允许连续相同） */
    static final String[] ROTATING_TEXTS = {"正在加载中...", "可随时补充信息..."};
    /** 压缩固定文案（只在压缩时显示，不参与轮换） */
    static final String COMPRESSING_TEXT = "上下文压缩中...";
    /** 齿轮旋转周期（2s/圈） */
    static final double SPIN_MS = 2000;
    /** 文案轮换间隔（10s） */
    static final double ROTATE_INTERVAL_MS = 10000;

    /** Material settings 齿轮路径（24×24 viewport，缩放 0.7 显示） */
    private static final String GEAR_PATH = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94"
            + "l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22"
            + "l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41"
            + "h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33"
            + "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58"
            + "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61"
            + "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54"
            + "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54"
            + "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32"
            + "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6"
            + "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z";

    private final SVGPath gear = new SVGPath();
    private final Label text = new Label();
    private final Random rnd = new Random();
    private Timeline spin;       // 齿轮旋转动画
    private Timeline rotateText; // 10s 文案轮换动画
    private boolean running;
    private boolean compressing;

    public RunningIndicator() {
        getStyleClass().add("running-indicator");
        gear.setContent(GEAR_PATH);
        gear.getStyleClass().add("running-indicator-gear");
        gear.setScaleX(0.7);
        gear.setScaleY(0.7);
        text.getStyleClass().add("running-indicator-text");
        getChildren().addAll(gear, text);
        setVisible(false); // 初始隐藏（空闲）；位置由挂载方 StackPane 对齐决定
        // 可见性自洽：手动隐藏（弹窗遮挡等）时停动画防泄漏；恢复可见且运行中时重启动画
        visibleProperty().addListener((obs, ov, nv) -> {
            if (!nv) {
                stopAnimations();
            } else if (running) {
                text.setText(displayText(compressing, pickText(rnd)));
                startAnimations();
            }
        });
    }

    /** 从轮换池随机取一个文案（纯静态可单测；允许连续相同，符合"随机"语义） */
    static String pickText(Random rnd) {
        return ROTATING_TEXTS[rnd.nextInt(ROTATING_TEXTS.length)];
    }

    /** 文案优先级：压缩中固定压缩文案，否则当前轮换文案（纯静态可单测） */
    static String displayText(boolean compressing, String current) {
        return compressing ? COMPRESSING_TEXT : current;
    }

    /** 运行状态：false → 整体隐藏 + 停止全部动画（防泄漏）+ 复位压缩态；true → 显示 + 启动动画（收敛到可见性监听） */
    public void setRunning(boolean running) {
        this.running = running;
        if (!running) {
            compressing = false;
            stopAnimations();
            setVisible(false);
            return;
        }
        setVisible(true); // 触发 visibleProperty 监听：显示文案 + 启动动画
    }

    /** 弹窗遮挡期间挂起：仅隐藏（visible 监听自动停动画），保留 running/compressing 状态 */
    public void suspend() {
        setVisible(false);
    }

    /** 弹窗关闭后恢复：会话仍运行则重新显示（visible 监听重启动画）；否则保持隐藏（防弹窗期间会话已结束） */
    public void resume() {
        setVisible(running);
    }

    /** 压缩状态：true → 固定压缩文案并暂停轮换；false → 恢复轮换（仅运行态生效） */
    public void setCompressing(boolean compressing) {
        this.compressing = compressing;
        if (!running) return;
        text.setText(displayText(compressing, pickText(rnd)));
        if (compressing) {
            if (rotateText != null) rotateText.stop();
        } else {
            startRotateText();
        }
    }

    private void startAnimations() {
        if (spin == null || spin.getStatus() != Animation.Status.RUNNING) {
            spin = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(gear.rotateProperty(), 0)),
                    new KeyFrame(Duration.millis(SPIN_MS), new KeyValue(gear.rotateProperty(), 360)));
            spin.setCycleCount(Animation.INDEFINITE);
            spin.play();
        }
        startRotateText();
    }

    private void startRotateText() {
        if (compressing || rotateText != null && rotateText.getStatus() == Animation.Status.RUNNING) return;
        rotateText = new Timeline(new KeyFrame(Duration.millis(ROTATE_INTERVAL_MS),
                e -> text.setText(displayText(compressing, pickText(rnd)))));
        rotateText.setCycleCount(Animation.INDEFINITE);
        rotateText.play();
    }

    /** 停止全部动画并置空引用（组件隐藏前必须调用；动画强引用节点防泄漏） */
    private void stopAnimations() {
        if (spin != null) { spin.stop(); spin = null; }
        if (rotateText != null) { rotateText.stop(); rotateText = null; }
    }
}
