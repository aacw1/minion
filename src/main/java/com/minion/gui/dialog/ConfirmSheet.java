package com.minion.gui.dialog;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.icon.IconFactory;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * 高危/越界操作确认底部卡片（替代旧 Alert 版 ConfirmDialog）：
 * 挂右侧面板 StackPane 顶层，遮罩仅压暗右侧、卡片从底部滑入；弹窗期间
 * 全窗点击拦截（Scene 事件过滤器）、焦点圈定卡片内；并发确认串行排队。
 * 必须在 FX 线程调用（GuiConfirmUi 经 Platform.runLater 保证）。
 */
public class ConfirmSheet {

    private static StackPane host;
    private static boolean showing;
    private static final Queue<Handle> pending = new ArrayDeque<Handle>();
    private static VBox currentCard;
    private static Region currentScrim;
    private static Consumer<ConfirmUi.Decision> currentCallback;

    private ConfirmSheet() { }

    /** MainWindow 注册右侧面板栈（遮罩与卡片的挂载点） */
    public static void setHost(StackPane h) { host = h; }

    private static Consumer<Boolean> visibilityListener;

    /** 注册弹窗可见性回调：true=显示，false=完全关闭（淡出完成）；MainWindow 用于挂起/恢复运行状态指示器 */
    public static void setVisibilityListener(Consumer<Boolean> listener) { visibilityListener = listener; }

    /** 确认卡片是否可见（指示器同步用，与「遮挡/等待用户」单一刷新入口配套） */
    public static boolean isShowing() { return showing; }

    public static Handle show(String message, Consumer<ConfirmUi.Decision> callback) {
        Handle h = new Handle(message, callback);
        if (host == null) {
            System.err.println("[minion] ConfirmSheet host 未注册，确认请求自动拒绝");
            callback.accept(ConfirmUi.Decision.REJECT);
            return h;
        }
        if (showing) {
            pending.add(h);
            return h;
        }
        h.display();
        return h;
    }

    /** 可取消句柄：中断/放弃时撤销自己的确认（FX 线程调用） */
    public static final class Handle {
        private final String message;
        private final Consumer<ConfirmUi.Decision> callback;
        private volatile boolean cancelled;

        Handle(String message, Consumer<ConfirmUi.Decision> callback) {
            this.message = message;
            this.callback = callback;
        }

        void display() {
            if (cancelled) return;
            ConfirmSheet.display(message, callback);
        }

        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (currentCallback == callback) {
                finish(ConfirmUi.Decision.REJECT); // 当前显示中：走淡出收尾
            } else {
                pending.remove(this); // 还在队首：撤销，防事后弹出已放弃的确认
            }
        }
    }

    /** 坐标落卡片外一律拦截：实现「点遮罩无反应 + 弹窗期间全窗点击拦截（含侧栏）」 */
    private static final EventHandler<MouseEvent> mouseFilter = new EventHandler<MouseEvent>() {
        @Override public void handle(MouseEvent e) {
            VBox card = currentCard;
            if (card == null) return;
            if (!card.contains(card.sceneToLocal(e.getSceneX(), e.getSceneY()))) e.consume();
        }
    };

    /** Esc=拒绝；Enter 放行触发焦点按钮（默认焦点=同意）；其余按键一律拦截（Tab 圈定焦点防逃逸到侧栏） */
    private static final EventHandler<KeyEvent> keyFilter = new EventHandler<KeyEvent>() {
        @Override public void handle(KeyEvent e) {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                finish(ConfirmUi.Decision.REJECT);
            } else if (e.getCode() != KeyCode.ENTER) {
                e.consume();
            }
        }
    };

    private static void display(String message, final Consumer<ConfirmUi.Decision> callback) {
        showing = true;
        if (visibilityListener != null) visibilityListener.accept(true);

        final Region scrim = new Region();
        scrim.getStyleClass().add("sheet-scrim");
        FadeTransition scrimIn = new FadeTransition(Duration.millis(150), scrim);
        scrimIn.setFromValue(0);
        scrimIn.setToValue(1);
        scrimIn.play();

        final VBox card = buildCard(message);
        StackPane.setAlignment(card, Pos.BOTTOM_CENTER);
        StackPane.setMargin(card, new Insets(0, 16, 24, 16)); // 距底 24px ≈ 1 行（行高单位同输入框 6*24）
        // 宽度随右侧面板收缩（分栏拖窄时不越过分隔线），上限 640
        card.maxWidthProperty().bind(Bindings.min(640, host.widthProperty().subtract(32)));

        currentCard = card;
        currentScrim = scrim;
        currentCallback = callback;
        Scene scene = host.getScene();
        scene.addEventFilter(MouseEvent.ANY, mouseFilter);
        scene.addEventFilter(KeyEvent.ANY, keyFilter);
        host.getChildren().addAll(scrim, card);

        // 滑入动画需布局完成后的卡片高度，runLater 等一个布局周期
        Platform.runLater(new Runnable() {
            @Override public void run() {
                double h = card.getBoundsInParent().getHeight();
                TranslateTransition slide = new TranslateTransition(Duration.millis(180), card);
                slide.setFromY(h);
                slide.setToY(0);
                slide.setInterpolator(Interpolator.EASE_OUT);
                slide.play();
                card.requestFocus();
            }
        });
    }

    /** 卡片：顶部琥珀危险饰条 | 标题/风险 pill | 消息 | 底部提示 + 按钮行（按钮顺序与默认「同意」不变） */
    private static VBox buildCard(String message) {
        VBox card = new VBox();
        card.getStyleClass().add("sheet-card");
        card.setPrefWidth(600);
        // 宿主 StackPane 默认把可缩放子节点拉伸铺满自身（maxHeight 无界 → 卡片铺满整页），
        // 置 USE_PREF_SIZE 使卡片紧贴内容高度，仅占内容高度
        card.setMaxHeight(Region.USE_PREF_SIZE);

        Region accent = new Region();
        accent.getStyleClass().add("sheet-accent");

        SVGPath alertIcon = IconFactory.alert();
        IconFactory.size(alertIcon, 15);
        StackPane iconBox = new StackPane(alertIcon);
        iconBox.getStyleClass().add("sheet-icon");

        Label title = new Label("需要确认");
        title.getStyleClass().add("sheet-title");
        Label risk = new Label("高危操作");
        risk.getStyleClass().add("sheet-risk");
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox header = new HBox(9, iconBox, title, headSpacer, risk);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("sheet-header");

        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("sheet-message");

        Label hint = new Label("Esc 拒绝 · Enter 同意");
        hint.getStyleClass().add("sheet-hint");

        Button reject = new Button("拒绝");
        reject.getStyleClass().add("btn-ghost");
        reject.setOnAction(e -> finish(ConfirmUi.Decision.REJECT));
        Button session = new Button("本次会话批准");
        session.getStyleClass().add("btn-ghost");
        session.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_SESSION));
        Button whitelist = new Button("批准并记住");
        whitelist.getStyleClass().add("btn-ghost");
        whitelist.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE_WHITELIST));
        Button approve = new Button("同意");
        approve.getStyleClass().add("btn-approve");
        approve.setDefaultButton(true); // Enter 即同意（默认焦点落同意；行为不变）
        approve.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE));

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, hint, btnSpacer, reject, session, whitelist, approve);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getStyleClass().add("sheet-actions");

        VBox content = new VBox(11, header, body, buttons);
        content.setPadding(new Insets(12, 16, 12, 16));
        card.getChildren().addAll(accent, content);
        return card;
    }

    /** 收尾：移除拦截 → 120ms 淡出 → 卸载节点 → 回调结果 → 出队展示下一个 */
    private static void finish(final ConfirmUi.Decision d) {
        final VBox card = currentCard;
        final Region scrim = currentScrim;
        final Consumer<ConfirmUi.Decision> callback = currentCallback;
        if (card == null) return; // 双击/重复触发防御
        Scene scene = host.getScene();
        scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
        scene.removeEventFilter(KeyEvent.ANY, keyFilter);
        currentCard = null;
        currentScrim = null;
        currentCallback = null;
        ParallelTransition out = new ParallelTransition(
                new FadeTransition(Duration.millis(120), scrim),
                new FadeTransition(Duration.millis(120), card));
        out.setOnFinished(new EventHandler<ActionEvent>() {
            @Override public void handle(ActionEvent e) {
                host.getChildren().removeAll(scrim, card);
                showing = false;
                if (visibilityListener != null) visibilityListener.accept(false);
                callback.accept(d);
                // 出队并跳过已取消句柄：Handle.display() 对已取消项直接 return，
                // 若只 poll 一次会让队列停摆（后续 show 永久排在队里不显示）
                Handle next;
                while ((next = pending.poll()) != null) {
                    if (!next.cancelled) {
                        next.display();
                        break;
                    }
                }
            }
        });
        out.play();
    }
}
