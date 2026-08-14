package com.minion.gui.dialog;

import com.minion.core.tools.confirm.ConfirmUi;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
    private static final Queue<Runnable> pending = new ArrayDeque<Runnable>();
    private static VBox currentCard;
    private static Region currentScrim;
    private static Consumer<ConfirmUi.Decision> currentCallback;

    private ConfirmSheet() { }

    /** MainWindow 注册右侧面板栈（遮罩与卡片的挂载点） */
    public static void setHost(StackPane h) { host = h; }

    public static void show(String message, Consumer<ConfirmUi.Decision> callback) {
        if (host == null) {
            System.err.println("[minion] ConfirmSheet host 未注册，确认请求自动拒绝");
            callback.accept(ConfirmUi.Decision.REJECT);
            return;
        }
        Runnable job = new Runnable() {
            @Override public void run() { display(message, callback); }
        };
        if (showing) { pending.add(job); return; }
        job.run();
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

        final Region scrim = new Region();
        scrim.getStyleClass().add("sheet-scrim");
        FadeTransition scrimIn = new FadeTransition(Duration.millis(150), scrim);
        scrimIn.setFromValue(0);
        scrimIn.setToValue(1);
        scrimIn.play();

        final VBox card = buildCard(message);
        StackPane.setAlignment(card, Pos.BOTTOM_CENTER);
        StackPane.setMargin(card, new Insets(0, 16, 12, 16));
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

    /** 卡片：顶部琥珀危险饰条 | 消息 | 按钮行 | 快捷键提示（压缩为 3 行，去掉标题行） */
    private static VBox buildCard(String message) {
        VBox card = new VBox();
        card.getStyleClass().add("sheet-card");
        card.setPrefWidth(560);

        Region accent = new Region();
        accent.getStyleClass().add("sheet-accent");

        Label body = new Label(message);
        body.setWrapText(true);
        body.getStyleClass().add("sheet-message");

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
        approve.setDefaultButton(true); // Enter 即同意（默认焦点落同意）
        approve.setOnAction(e -> finish(ConfirmUi.Decision.APPROVE));
        HBox buttons = new HBox(8);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(reject, session, whitelist, approve);

        Label hint = new Label("Enter 同意 · Esc 拒绝");
        hint.getStyleClass().add("sheet-hint");

        VBox content = new VBox(6);
        content.setPadding(new Insets(10, 14, 10, 14));
        content.getChildren().addAll(body, buttons, hint);
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
                callback.accept(d);
                Runnable next = pending.poll();
                if (next != null) next.run();
            }
        });
        out.play();
    }
}
