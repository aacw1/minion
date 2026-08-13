package com.minion.gui.input;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** 底部输入区：多行 TextArea + 单图标按钮（上箭头=发送/补充/回答、方块=终止、变淡箭头=空输入）。
 *  运行中 + 有内容 → 补充；等待回答 + 有内容 → 回答；运行中 + 空 → 终止。 */
public class InputView extends VBox {

    /** 按钮模式：图标/透明度/背景类/动作的判定依据 */
    enum BtnMode { SEND, SEND_DIM, SUPPLEMENT, ANSWER, STOP }

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button();
    private final SVGPath arrowIcon = new SVGPath();
    private final SVGPath stopIcon = new SVGPath();
    private volatile SessionHandle current;
    // FX 线程缓存的状态（bindSession/onRunningChanged/onAskChanged 维护）
    private boolean running;
    private boolean askPending;
    private String askQuestion;

    public InputView(final SessionManager manager) {
        this.manager = manager;
        getStyleClass().add("panel-dark");
        setSpacing(8);
        setStyle("-fx-padding: 12 16 12 16;");

        input.getStyleClass().add("input-area");
        input.setWrapText(true);
        input.setPromptText("输入消息…  (Ctrl+Enter 发送)");
        input.setPrefRowCount(2);
        input.setMaxHeight(6 * 24);
        input.textProperty().addListener((obs, ov, nv) -> updateButton());

        // 上箭头（Claude Code 同款语义：可发送）；方块 = 终止
        arrowIcon.setContent("M12 4 L20 13 L15 13 L15 21 L9 21 L9 13 L4 13 Z");
        arrowIcon.getStyleClass().add("icon-send");
        stopIcon.setContent("M7 7 L17 7 L17 17 L7 17 Z");
        stopIcon.getStyleClass().add("icon-stop");

        sendButton.setMinSize(36, 36);
        sendButton.setPrefSize(36, 36);
        sendButton.setOnAction(e -> onAction());
        updateButton();

        input.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onAction();
            }
        });

        HBox buttonRow = new HBox(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonRow.getChildren().addAll(spacer, sendButton);
        VBox.setVgrow(input, Priority.ALWAYS);
        getChildren().addAll(input, buttonRow);
    }

    /** 纯静态判定（可脱离 JavaFX 单测）：运行/提问挂起/有内容 → 按钮模式 */
    static BtnMode buttonMode(boolean running, boolean askPending, boolean hasContent) {
        if (!running) return hasContent ? BtnMode.SEND : BtnMode.SEND_DIM;
        if (askPending) return hasContent ? BtnMode.ANSWER : BtnMode.STOP;
        return hasContent ? BtnMode.SUPPLEMENT : BtnMode.STOP;
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        Platform.runLater(() -> {
            running = h != null && h.running;
            askPending = h != null && h.askPending;
            askQuestion = h == null ? null : h.askQuestion;
            updateButton();
            updatePrompt();
        });
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.running = running;
            updateButton();
        });
    }

    /** ask_user 挂起状态变化（MainWindow 转发自 SessionManager 监听） */
    public void onAskChanged(SessionHandle h, boolean asking, String question) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.askPending = asking;
            this.askQuestion = question;
            updateButton();
            updatePrompt();
        });
    }

    private boolean hasContent() {
        return input.getText() != null && !input.getText().trim().isEmpty();
    }

    private void updatePrompt() {
        if (askPending) {
            String q = askQuestion == null ? "" : askQuestion;
            input.setPromptText("回答: " + (q.length() > 40 ? q.substring(0, 40) + "…" : q));
        } else {
            input.setPromptText("输入消息…  (Ctrl+Enter 发送)");
        }
    }

    private void updateButton() {
        switch (buttonMode(running, askPending, hasContent())) {
            case SEND:       applyStyle(arrowIcon, "btn-primary", 1.0, "发送 (Ctrl+Enter)"); break;
            case SEND_DIM:   applyStyle(arrowIcon, "btn-primary", 0.35, "输入消息后发送 (Ctrl+Enter)"); break;
            case SUPPLEMENT: applyStyle(arrowIcon, "btn-primary", 1.0, "补充信息给正在运行的模型 (Ctrl+Enter)"); break;
            case ANSWER:     applyStyle(arrowIcon, "btn-primary", 1.0, "回答模型的提问 (Ctrl+Enter)"); break;
            case STOP:       applyStyle(stopIcon, "btn-danger", 1.0, "终止当前运行"); break;
        }
    }

    private void applyStyle(SVGPath graphic, String styleClass, double opacity, String tip) {
        sendButton.setGraphic(graphic);
        sendButton.getStyleClass().removeAll("btn-primary", "btn-danger");
        sendButton.getStyleClass().add(styleClass);
        sendButton.setOpacity(opacity);
        sendButton.setTooltip(new Tooltip(tip));
    }

    /** Ctrl+Enter / 按钮点击统一入口：按当前模式分发 */
    private void onAction() {
        switch (buttonMode(running, askPending, hasContent())) {
            case SEND:
                onSend();
                break;
            case SUPPLEMENT: {
                String text = input.getText();
                if (text == null || text.trim().isEmpty()) return;
                input.clear();
                if (current != null) manager.sendSupplement(current, text);
                break;
            }
            case ANSWER: {
                String text = input.getText();
                if (text == null || text.trim().isEmpty()) return;
                input.clear();
                if (current != null) manager.sendAnswer(current, text);
                break;
            }
            case STOP:
                if (current != null) manager.stop(current);
                break;
            case SEND_DIM:
                break;
        }
    }

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.clear();
        SessionHandle target = current;
        if (target == null) {
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        manager.send(target, text);
    }
}
