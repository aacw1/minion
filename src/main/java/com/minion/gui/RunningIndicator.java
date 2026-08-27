package com.minion.gui;

import com.minion.core.agent.RetryProgress;
import com.minion.gui.icon.IconFactory;
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
    /** 错误体在指示器内的最大显示长度（500/502 展示服务返回的报错，防单行爆宽） */
    static final int BODY_MAX_CHARS = 200;
    /** 齿轮旋转周期（2s/圈） */
    static final double SPIN_MS = 2000;
    /** 文案轮换间隔（10s） */
    static final double ROTATE_INTERVAL_MS = 10000;

    private final SVGPath gear = IconFactory.gear(); // Material 齿轮（IconFactory 集中管理，缩放 0.7 显示）
    private final Label text = new Label();
    private final Random rnd = new Random();
    private Timeline spin;       // 齿轮旋转动画
    private Timeline rotateText; // 10s 文案轮换动画
    private boolean running;
    private boolean compressing;
    private String retryBase;          // 进入重试态时冻结的基础文案；null = 非重试态
    private RetryProgress retryProgress; // 最近一次进度（挂起/恢复时重绘重试文案用）

    public RunningIndicator() {
        getStyleClass().add("running-indicator");
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
                text.setText(retryBase != null ? retryText(retryProgress, retryBase)
                        : displayText(compressing, pickText(rnd)));
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

    /** 重试文案：冻结基础文案 + 错误码/次数/错误体后缀（如"正在加载中...(429限流，重试第3次)"） */
    static String retryText(RetryProgress p, String base) {
        return base + "(" + codeLabel(p.httpCode) + "，重试第" + p.attempt + "次" + bodyPart(p) + ")";
    }

    /** 错误码标签：429 限流 / 500 服务报错 / 502 网关报错；未知码防御性显示 HTTP xxx */
    static String codeLabel(int httpCode) {
        if (httpCode == 429) return "429限流";
        if (httpCode == 500) return "500服务报错";
        if (httpCode == 502) return "502网关报错";
        return "HTTP " + httpCode;
    }

    /** 错误体后缀：仅非 429 且 body 非空时显示，截断 BODY_MAX_CHARS（429 明确是限流，无需错误体） */
    static String bodyPart(RetryProgress p) {
        if (p.httpCode == 429 || p.body == null || p.body.isEmpty()) return "";
        return p.body.length() > BODY_MAX_CHARS ? p.body.substring(0, BODY_MAX_CHARS) : p.body;
    }

    /** 运行状态：false → 整体隐藏 + 停止全部动画（防泄漏）+ 复位压缩态；true → 显示 + 启动动画（收敛到可见性监听） */
    public void setRunning(boolean running) {
        this.running = running;
        if (!running) {
            compressing = false;
            retryBase = null;
            retryProgress = null;
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
        if (retryBase != null) return; // 重试态：忽略压缩切换（压缩发生在请求前，理论不可达）
        this.compressing = compressing;
        if (!running) return;
        text.setText(displayText(compressing, pickText(rnd)));
        if (compressing) {
            if (rotateText != null) rotateText.stop();
        } else {
            startRotateText();
        }
    }

    /** 瞬时错误重试进度：attempt ≥ 1 → 首次进入随机取一条基础文案并冻结（停轮换），
     *  之后每次更新后缀（错误码/错误体随最近一次失败更新）；attempt == 0 → 恢复压缩/轮换文案（仅运行态生效） */
    public void setRetryProgress(RetryProgress p) {
        if (!running) return;
        retryProgress = p;
        if (p.attempt >= 1) {
            if (retryBase == null) {
                retryBase = pickText(rnd); // 首次进入：随机取一条基础文案并冻结
                if (rotateText != null) rotateText.stop();
            }
            text.setText(retryText(p, retryBase));
        } else {
            retryBase = null;
            text.setText(displayText(compressing, pickText(rnd)));
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
