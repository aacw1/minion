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

/** 底部输入区：多行 TextArea（自适应 1→6 行）+ 下方靠右发送/终止按钮；无会话时发送自动建会话 */
public class InputView extends VBox {

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button("⤒ 发送");
    private volatile SessionHandle current;

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

        input.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onSend();
            }
        });

        sendButton.getStyleClass().add("btn-primary");
        updateButton(false);

        // 需求 4：TextArea 在上（弹性占高），按钮行在下、按钮靠右下（Region 弹性填充）
        HBox buttonRow = new HBox(10);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonRow.getChildren().addAll(spacer, sendButton);
        VBox.setVgrow(input, Priority.ALWAYS);
        getChildren().addAll(input, buttonRow);
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        Platform.runLater(() -> updateButton(h == null ? false : h.running));
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> updateButton(running));
    }

    private void updateButton(boolean running) {
        if (running) {
            sendButton.setText("■ 终止");
            sendButton.getStyleClass().remove("btn-primary");
            sendButton.getStyleClass().add("btn-danger");
            sendButton.setTooltip(new Tooltip("终止当前运行"));
            sendButton.setOnAction(e -> manager.stop(current));
        } else {
            sendButton.setText("⤒ 发送");
            sendButton.getStyleClass().remove("btn-danger");
            sendButton.getStyleClass().add("btn-primary");
            sendButton.setTooltip(new Tooltip("发送 (Ctrl+Enter)"));
            sendButton.setOnAction(e -> onSend());
        }
    }

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.clear(); // 需求 14：发送后清空输入框
        SessionHandle target = current;
        if (target == null) {
            // 需求 12：无激活会话时发送自动建会话（激活回调会绑定右侧面板；send 直接传新句柄）
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        manager.send(target, text);
    }
}
