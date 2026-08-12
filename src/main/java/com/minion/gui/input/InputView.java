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
import javafx.scene.layout.VBox;

/** 底部输入区：多行 TextArea（自适应 1→6 行）+ 发送/终止按钮 */
public class InputView extends VBox {

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button("⤒ 发送");
    private volatile SessionHandle current;
    /** 发送后保留草稿直至本轮结束（供终止后修改再发）；结束时若未被用户修改则清空 */
    private String lastSent;

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

        HBox row = new HBox(10);
        row.getChildren().add(input);
        HBox.setHgrow(input, Priority.ALWAYS);
        row.getChildren().add(sendButton);
        getChildren().add(row);
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        this.lastSent = null;
        Platform.runLater(() -> updateButton(h.running));
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> {
            updateButton(running);
            if (!running && lastSent != null && input.getText().equals(lastSent)) {
                input.clear(); // 本轮结束且用户未修改 → 清空草稿，准备新输入
                lastSent = null;
            }
        });
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
        if (current == null) return;
        lastSent = text; // 不清空：草稿保留至本轮结束，供终止后修改再发
        manager.send(current, text); // 摘要标题 + 正式任务由 SessionManager 统一处理
    }
}
