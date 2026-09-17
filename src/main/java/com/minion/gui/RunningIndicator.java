package com.minion.gui;

import com.minion.core.agent.RetryProgress;
import com.minion.gui.icon.IconFactory;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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
    /** 点击复制后的反馈文案与显示时长（ms）：短暂提示后按当前状态重绘 */
    static final String COPIED_TEXT = "已复制 ✓";
    static final long COPY_FEEDBACK_MS = 1500;
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
    private PauseTransition copyFeedback; // 「已复制 ✓」反馈计时（隐藏/状态更新时停，防过期回调）

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
                renderText();
                startAnimations();
            }
        });
        // 点击复制完整错误详情（仅重试态且有 body 时可复制；复制后短暂「已复制 ✓」）
        setOnMouseClicked(e -> copyErrorDetail());
    }

    /** 从轮换池随机取一个文案（纯静态可单测；允许连续相同，符合"随机"语义） */
    static String pickText(Random rnd) {
        return ROTATING_TEXTS[rnd.nextInt(ROTATING_TEXTS.length)];
    }

    /** 文案优先级：压缩中固定压缩文案，否则当前轮换文案（纯静态可单测） */
    static String displayText(boolean compressing, String current) {
        return compressing ? COMPRESSING_TEXT : current;
    }

    /** 重试文案：冻结基础文案 + 错误标签/次数/详情 + 等待时长后缀 */
    static String retryText(RetryProgress p, String base) {
        return base + "(" + labelOf(p) + "，重试第" + p.attempt + "次" + bodyPart(p) + delayPart(p) + ")";
    }

    /** 等待时长后缀：如「，约 30 秒后重试」；nextDelayMs<=0 时为空（毫秒向上取整到秒） */
    static String delayPart(RetryProgress p) {
        if (p.nextDelayMs <= 0) return "";
        return "，约 " + ((p.nextDelayMs + 999) / 1000) + " 秒后重试";
    }

    /** 错误标签：网络类直接用 RetryProgress.label（无 HTTP 码）；HTTP 类按状态码查表 */
    static String labelOf(RetryProgress p) {
        return p.label != null ? p.label : codeLabel(p.httpCode);
    }

    /** 错误码标签：429 限流 / 500 服务报错 / 502 网关报错 / 503、504 等 5xx（长重试覆盖）；未知码防御性显示 HTTP xxx */
    static String codeLabel(int httpCode) {
        if (httpCode == 429) return "429限流";
        if (httpCode == 500) return "500服务报错";
        if (httpCode == 502) return "502网关报错";
        if (httpCode == 503) return "503服务不可用";
        if (httpCode == 504) return "504网关超时";
        return "HTTP " + httpCode;
    }

    /** 错误详情后缀：HTTP 类直拼服务响应体（json，可诊断）；网络类剥掉与标签重复的
     *  自带前缀后用"："分隔。均截断 BODY_MAX_CHARS */
    static String bodyPart(RetryProgress p) {
        if (p.body == null || p.body.isEmpty()) return "";
        if (p.label == null) {
            return p.body.length() > BODY_MAX_CHARS ? p.body.substring(0, BODY_MAX_CHARS) : p.body;
        }
        String detail = stripNetworkPrefix(p.body);
        if (detail.equals(p.label)) return ""; // 详情与标签重复（如空响应）：不重复展示
        detail = detail.length() > BODY_MAX_CHARS ? detail.substring(0, BODY_MAX_CHARS) : detail;
        return "：" + detail;
    }

    /** 网络类异常消息自带"网络错误: "/"请求超时: "前缀，与标签重复，展示时剥除 */
    static String stripNetworkPrefix(String s) {
        if (s.startsWith("网络错误: ")) return s.substring("网络错误: ".length());
        if (s.startsWith("请求超时: ")) return s.substring("请求超时: ".length());
        if (s.startsWith("请求超时：")) return s.substring("请求超时：".length());
        return s;
    }

    /** 可复制错误原文：body 为 null/空白 → null（不可点）；否则返回完整原文（不截断，
     *  与 bodyPart 的 200 字符展示截断口径区分） */
    static String copyText(RetryProgress p) {
        if (p == null || p.body == null || p.body.trim().isEmpty()) return null;
        return p.body;
    }

    /** 点击复制完整错误详情（package-private 供探针直接调用）：无详情不动作；
     *  复制后文字短暂显示「已复制 ✓」，到期按当前状态重绘 */
    void copyErrorDetail() {
        String full = copyText(retryProgress);
        if (full == null) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(full);
        Clipboard.getSystemClipboard().setContent(cc);
        if (copyFeedback != null) copyFeedback.stop();
        text.setText(COPIED_TEXT);
        copyFeedback = new PauseTransition(Duration.millis(COPY_FEEDBACK_MS));
        copyFeedback.setOnFinished(e -> { copyFeedback = null; renderText(); });
        copyFeedback.play();
    }

    /** 按当前状态重绘文案（统一口径：状态变化/反馈到期共用）：重试态 → 重试文案；
     *  否则压缩/轮换文案。反馈计时未到期即被新状态覆盖时在此停表（提示让位于实时状态） */
    private void renderText() {
        if (copyFeedback != null) { copyFeedback.stop(); copyFeedback = null; }
        text.setText(retryBase != null ? retryText(retryProgress, retryBase)
                : displayText(compressing, pickText(rnd)));
    }

    /** 运行状态：false → 整体隐藏 + 停止全部动画（防泄漏）+ 复位压缩态；true → 显示 + 启动动画（收敛到可见性监听） */
    public void setRunning(boolean running) {
        this.running = running;
        if (!running) {
            compressing = false;
            retryBase = null;
            retryProgress = null;
            setCursor(null);
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
        this.compressing = compressing;   // 重试态也更新字段：重试结束后能回到正确文案
        if (retryBase != null) return;    // 重试态：不重绘（重试文案优先）
        if (!running) return;
        renderText();
        if (compressing) {
            if (rotateText != null) rotateText.stop();
        } else {
            startRotateText();
        }
    }

    /** 瞬时错误重试进度：attempt ≥ 1 → 首次进入取基础文案并冻结（停轮换），
     *  之后每次更新后缀（错误标签/详情/等待时长随最近一次失败更新）；attempt == 0 → 恢复压缩/轮换文案（仅运行态生效） */
    public void setRetryProgress(RetryProgress p) {
        if (!running) return;
        retryProgress = p;
        setCursor(copyText(p) != null ? Cursor.HAND : null); // 有详情可复制：手型提示，否则默认光标
        if (p.attempt >= 1) {
            if (retryBase == null) {
                // 压缩中的重试：基础文案取压缩固定文案（否则显示成普通加载文案，用户不知在压缩）
                retryBase = compressing ? COMPRESSING_TEXT : pickText(rnd);
                if (rotateText != null) rotateText.stop();
            }
            renderText();
        } else {
            retryBase = null;
            renderText();
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
        if (copyFeedback != null) { copyFeedback.stop(); copyFeedback = null; } // 隐藏时停反馈（防过期回调与引用泄漏）
    }
}
