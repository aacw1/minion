package com.minion.gui.input;

import com.minion.core.agent.StatsLine;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/** 上下文环形进度圈（参考 Claude VSCode context 指示器）：
 *  Canvas 自绘背景环+进度弧；悬停显示"上下文大小xk,占比y%,剩余z%自动压缩"，
 *  超过 30% 第二行浅色提示"点击立即压缩"（点击动作在环形圈本体，InputView 注入 onCompress）。
 *  组件本体需 FX toolkit 不单测，纯静态逻辑（arcAngle/compressable/formatInfo）由 ContextRingTest 覆盖。 */
public class ContextRing extends Canvas {

    /** 可点击压缩阈值：超过 30% 才可点击（代码常量，不加配置项） */
    public static final double COMPRESS_HINT_PCT = 0.3;

    private static final double SIZE = 24;      // 画布宽
    private static final double HEIGHT = 38;    // 画布高（保证圆完整不裁剪：CY_RATIO*H + 半径8 + 线宽1.5 ≤ H；改 CY_RATIO 时按此公式核对）
    private static final double CY_RATIO = 0.41; // 圆心垂直位置（相对画布高；改小=上移，改大=下移。0.36 ≈ 上移一行后再下移 3px）
    private static final double STROKE = 3;     // 环线宽
    private static final Color RING_BG = Color.web("#232733");      // 背景环
    private static final Color RING_NORMAL = Color.web("#3a4150");  // ≤30% 中性灰蓝
    private static final Color RING_HOT = Color.web("#f48771");     // >30% 主题橙（与发送按钮同系）

    private int used;
    private int max;
    private boolean running;
    private boolean compressing;
    private double threshold = 0.8;
    private Runnable onCompress;

    private final Label infoLine = new Label();
    private final Label hintLine = new Label();
    /** 压缩中旋转动画：每秒一圈、固定弧长 300°；动画循环内每帧重绘，结束自动停 */
    private final AnimationTimer spinner = new AnimationTimer() {
        @Override public void handle(long now) {
            if (!compressing) { stop(); return; }
            repaint(now / 1_000_000); // 纳秒 → 毫秒
        }
    };

    public ContextRing() {
        super(SIZE, HEIGHT);
        VBox box = new VBox(3);
        hintLine.setStyle("-fx-text-fill: rgba(240,242,246,0.55);"); // 提示行浅色（需求）
        box.getChildren().addAll(infoLine, hintLine);
        Tooltip tip = new Tooltip();
        tip.setGraphic(box);
        tip.setStyle("-fx-show-delay: 300ms;"); // 悬停即时显示（JDK8 此版本无 setShowDelay，走 CSS）
        Tooltip.install(this, tip);
        // hover 微亮：重绘时按 isHover() 取亮色
        hoverProperty().addListener((obs, ov, nv) -> repaint(-1));
        setOnMouseClicked(e -> {
            if (compressable(running, pct()) && onCompress != null) onCompress.run();
        });
        repaint(-1);
    }

    /** 上下文统计更新（InputView 转发；threshold 为自动压缩阈值 0~1） */
    public void update(int used, int max, double threshold, boolean running) {
        this.used = used;
        this.max = max;
        this.threshold = threshold;
        this.running = running;
        refreshTooltip();
        repaint(-1);
    }

    /** 运行态变化（Tooltip 第二行文案/可点击判定依据） */
    public void setRunning(boolean running) {
        this.running = running;
        refreshTooltip();
        repaint(-1);
    }

    /** 压缩中状态：true 启动旋转动画，false 停止并重绘静态进度 */
    public void setCompressing(boolean compressing) {
        this.compressing = compressing;
        if (compressing) spinner.start();
        else {
            spinner.stop();
            repaint(-1);
        }
    }

    /** 点击压缩动作注入（InputView：current!=null 时提交会话线程执行 compactNow） */
    public void setOnCompress(Runnable onCompress) { this.onCompress = onCompress; }

    private double pct() { return max > 0 ? (double) used / max : 0; }

    /** 悬停信息刷新：第一行大小/占比/剩余；第二行超 30% 才显示（运行中提示不可压缩） */
    private void refreshTooltip() {
        infoLine.setText(formatInfo(used, max, threshold));
        boolean over = pct() > COMPRESS_HINT_PCT;
        hintLine.setText(over ? (running ? "运行中不可压缩" : "点击立即压缩") : "");
        hintLine.setVisible(over);
        hintLine.setManaged(over);
    }

    private void repaint(long nowMs) {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        gc.clearRect(0, 0, w, h);
        double cx = w / 2;          // 水平居中
        double cy = h * CY_RATIO;   // 垂直位置由 CY_RATIO 决定（改小=上移，改大=下移）
        double r = Math.min(w, h) / 2 - STROKE / 2 - 0.5;
        gc.setLineWidth(STROKE);
        gc.setStroke(RING_BG);
        gc.strokeOval(cx - r, cy - r, 2 * r, 2 * r); // 背景环
        gc.setLineCap(StrokeLineCap.ROUND);
        if (compressing) {
            double start = nowMs < 0 ? 0 : (nowMs % 1000) / 1000.0 * 360; // 每秒一圈
            gc.setStroke(RING_HOT);
            gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, start, 300, ArcType.OPEN);
            return;
        }
        double p = pct();
        Color c = p > COMPRESS_HINT_PCT ? RING_HOT : RING_NORMAL;
        if (isHover()) c = c.brighter(); // hover 微亮
        gc.setStroke(c);
        gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, -90, arcAngle(p), ArcType.OPEN);
    }

    /** 百分比 → 进度弧角度（0~360，越界钳制；静态可单测） */
    static double arcAngle(double pct) {
        double p = pct < 0 ? 0 : (pct > 1 ? 1 : pct);
        return p * 360;
    }

    /** 可点击压缩判定：非运行中且占比 > 30%（严格超过；静态可单测） */
    static boolean compressable(boolean running, double pct) {
        return !running && pct > COMPRESS_HINT_PCT;
    }

    /** 悬停第一行文案：上下文大小98k,占比70%,剩余10%自动压缩（k 格式复用 StatsLine.formatTokens；
     *  剩余 = 阈值百分比 − 当前占比，负值钳 0；静态可单测） */
    static String formatInfo(int used, int max, double threshold) {
        int pct = max > 0 ? (int) Math.round(used * 100.0 / max) : 0;
        int remain = max > 0 ? (int) Math.round(threshold * 100.0 - pct) : 0;
        if (remain < 0) remain = 0;
        return "上下文大小" + StatsLine.formatTokens(used) + ",占比" + pct + "%,剩余" + remain + "%自动压缩";
    }
}
