package com.minion.gui.sidebar;

import com.minion.core.llm.Message;
import com.minion.gui.StatusDot;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 左侧会话列表：标题 + 最后消息摘要 + 运行状态点 + 悬停操作按钮（重命名/删除）/ 最近消息时间；单击切换 */
public class SessionListView extends ListView<SessionHandle> {

    private final SessionManager manager;
    /** 删除联动回调（MainWindow 用于移除页签）；创建/删除无 Listener 通知，UI 层自行刷新 */
    private final Consumer<SessionHandle> onDeleted;

    public SessionListView(final SessionManager manager, final Consumer<SessionHandle> onDeleted) {
        this.manager = manager;
        this.onDeleted = onDeleted;
        setCellFactory(v -> new SessionCell());
        // 相对时间周期刷新（60 秒）：5m/3h/2d 不停留初始值；Timeline 运行于 FX 线程，随应用退出自然停止
        javafx.animation.Timeline clock = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.minutes(1), e -> refresh()));
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clock.play();
        setOnMouseClicked(e -> {
            // 悬停按钮（✎/✕）的点击会冒泡到此 handler：跳过切换，避免误激活会话（按钮事件已由按钮自身处理）
            if (isHoverButton(e.getTarget())) return;
            SessionHandle h = getSelectionModel().getSelectedItem();
            if (h != null && e.getClickCount() == 1) manager.activateSession(h);
        });
        Platform.runLater(() -> refresh()); // 初始恢复列表（restoreSessions 无 Listener 通知）
    }

    /** 事件目标（或其父链）是否为悬停操作按钮（btn-cell）：按钮内部命中 LabeledText 等子节点，故沿父链逐层判断 */
    private boolean isHoverButton(Object target) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null) {
            if (n.getStyleClass().contains("btn-cell")) return true;
            n = n.getParent();
        }
        return false;
    }

    public void refresh() {
        Platform.runLater(() -> getItems().setAll(manager.sessions()));
    }

    private class SessionCell extends ListCell<SessionHandle> {
        @Override protected void updateItem(SessionHandle h, boolean empty) {
            super.updateItem(h, empty);
            // 旧 graphic 若带呼吸动画先回收（Timeline 强引用节点，不回收会泄漏）
            StatusDot.stopPulseIn(getGraphic());
            if (empty || h == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String label = h.title == null ? "(新会话)" : h.title;
            Circle dot = StatusDot.create(h.running);
            Label name = new Label(label);
            name.getStyleClass().add("cell-text"); // 显式上色：graphic 内 Label 不响应 .list-cell 的 -fx-text-fill
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            name.setMinWidth(0); // 允许收缩至省略号：长标题不再撑出横向滚动条
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            // 右区双态：非悬停显示最近消息时间（5m/3h/2d）；悬停切换为操作按钮
            Label timeLabel = new Label();
            timeLabel.getStyleClass().add("cell-time");
            String t = TimeFormatter.format(lastMessageTs(h), System.currentTimeMillis());
            if (t != null) timeLabel.setText(t);
            Button renameBtn = new Button("✎");
            renameBtn.getStyleClass().add("btn-cell");
            renameBtn.setTooltip(new Tooltip("重命名"));
            renameBtn.setOnAction(e -> doRename(h));
            Button delBtn = new Button("✕");
            delBtn.getStyleClass().add("btn-cell");
            delBtn.setTooltip(new Tooltip("删除"));
            delBtn.setOnAction(e -> doDelete(h));
            renameBtn.setVisible(false);
            renameBtn.setManaged(false);
            delBtn.setVisible(false);
            delBtn.setManaged(false);
            setOnMouseEntered(e -> {
                timeLabel.setVisible(false);
                timeLabel.setManaged(false);
                renameBtn.setVisible(true);
                renameBtn.setManaged(true);
                delBtn.setVisible(true);
                delBtn.setManaged(true);
            });
            setOnMouseExited(e -> {
                timeLabel.setVisible(true);
                timeLabel.setManaged(true);
                renameBtn.setVisible(false);
                renameBtn.setManaged(false);
                delBtn.setVisible(false);
                delBtn.setManaged(false);
            });

            HBox box = new HBox(6);
            box.getChildren().addAll(dot, name, spacer, timeLabel, renameBtn, delBtn);

            VBox cellBox = new VBox(2);
            cellBox.maxWidthProperty().bind(widthProperty().subtract(getInsets().getLeft() + getInsets().getRight() + 4)); // 绑定 cell 宽并抵消 cell 自身 padding（theme.css 左右 24px）：padding 未抵消时 cellPref 超视口，横向滚动条仍会出现
            cellBox.getChildren().add(box);
            String summary = lastSummary(h);
            if (summary != null) {
                Label sum = new Label(summary);
                sum.getStyleClass().add("section-title");
                sum.setTextOverrun(OverrunStyle.ELLIPSIS);
                sum.setMinWidth(0);
                cellBox.getChildren().add(sum);
            }
            setGraphic(cellBox);
        }
    }

    /** 重命名弹窗（复用原右键菜单逻辑） */
    private void doRename(SessionHandle h) {
        TextInputDialog d = new TextInputDialog(h.title);
        d.setTitle("重命名会话");
        d.setHeaderText("输入新标题");
        Theme.style(d); // 弹窗深色
        d.showAndWait().ifPresent(t -> manager.renameSession(h, t));
    }

    /** 删除确认弹窗（复用原右键菜单逻辑） */
    private void doDelete(SessionHandle h) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除会话「" + (h.title == null ? h.id : h.title) + "」？",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("删除会话");
        Theme.style(a); // 弹窗深色
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                manager.deleteSession(h);
                onDeleted.accept(h); // 页签联动清理（MainWindow.removeTabById）
                refresh();
            }
        });
    }

    /** 最后一条非 TOOL 消息的创建时间戳（毫秒；无消息/全 TOOL/旧数据 → 0） */
    private long lastMessageTs(SessionHandle h) {
        if (h.session == null || h.session.messages == null) return 0L;
        List<Message> msgs = new ArrayList<Message>(h.session.messages); // 防御性拷贝（同 lastSummary）
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.role == Message.Role.TOOL) continue;
            return m.ts;
        }
        return 0L;
    }

    /** 摘要：最后一条非 TOOL 角色且非空 content 的消息，前 40 字；无消息/全 TOOL/空 content → null（不显示行） */
    private String lastSummary(SessionHandle h) {
        if (h.session == null || h.session.messages == null) return null;
        // 防御性拷贝：FX 线程遍历时 agent 工作线程可能正在写 messages（普通 ArrayList 非线程安全）
        List<Message> msgs = new ArrayList<Message>(h.session.messages);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.role == Message.Role.TOOL) continue;
            if (m.content == null || m.content.trim().isEmpty()) continue;
            String text = m.content.replace('\n', ' ').replace('\r', ' ').trim();
            return text.length() > 40 ? text.substring(0, 40) + "..." : text;
        }
        return null;
    }
}
