package com.minion.gui.sidebar;

import com.minion.core.llm.Message;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.function.Consumer;

/** 左侧会话列表：标题 + 最后消息摘要 + 运行状态点 + 右键菜单（重命名/删除）；单击切换 */
public class SessionListView extends ListView<SessionHandle> {

    private final SessionManager manager;
    /** 删除联动回调（MainWindow 用于移除页签）；创建/删除无 Listener 通知，UI 层自行刷新 */
    private final Consumer<SessionHandle> onDeleted;

    public SessionListView(final SessionManager manager, final Consumer<SessionHandle> onDeleted) {
        this.manager = manager;
        this.onDeleted = onDeleted;
        setCellFactory(v -> new SessionCell());
        setOnMouseClicked(e -> {
            SessionHandle h = getSelectionModel().getSelectedItem();
            if (h != null && e.getClickCount() == 1) manager.activateSession(h);
        });
        Platform.runLater(() -> refresh()); // 初始恢复列表（restoreSessions 无 Listener 通知）
    }

    public void refresh() {
        Platform.runLater(() -> getItems().setAll(manager.sessions()));
    }

    private class SessionCell extends ListCell<SessionHandle> {
        @Override protected void updateItem(SessionHandle h, boolean empty) {
            super.updateItem(h, empty);
            if (empty || h == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String label = h.title == null ? "(新会话)" : h.title;
            Circle dot = new Circle(4);
            dot.getStyleClass().add("status-dot");
            if (h.running) dot.getStyleClass().add("status-dot-running");
            HBox box = new HBox(6);
            Label name = new Label(label);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(dot, name, spacer);

            VBox cellBox = new VBox(2);
            cellBox.getChildren().add(box);
            String summary = lastSummary(h);
            if (summary != null) {
                Label sum = new Label(summary);
                sum.getStyleClass().add("section-title");
                cellBox.getChildren().add(sum);
            }
            setGraphic(cellBox);

            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem("重命名");
            rename.setOnAction(e -> {
                TextInputDialog d = new TextInputDialog(h.title);
                d.setTitle("重命名会话");
                d.setHeaderText("输入新标题");
                d.showAndWait().ifPresent(t -> manager.renameSession(h, t));
            });
            MenuItem del = new MenuItem("删除");
            del.setOnAction(e -> {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "删除会话「" + (h.title == null ? h.id : h.title) + "」？",
                        ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("删除会话");
                a.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.OK) {
                        manager.deleteSession(h);
                        onDeleted.accept(h); // 页签联动清理（MainWindow.removeTabById）
                        refresh();
                    }
                });
            });
            menu.getItems().addAll(rename, del);
            setContextMenu(menu);
        }
    }

    /** 摘要：最后一条非 TOOL 角色且非空 content 的消息，前 40 字；无消息/全 TOOL/空 content → null（不显示行） */
    private String lastSummary(SessionHandle h) {
        if (h.session == null || h.session.messages == null) return null;
        for (int i = h.session.messages.size() - 1; i >= 0; i--) {
            Message m = h.session.messages.get(i);
            if (m.role == Message.Role.TOOL) continue;
            if (m.content == null || m.content.trim().isEmpty()) continue;
            String text = m.content.replace('\n', ' ').replace('\r', ' ').trim();
            return text.length() > 40 ? text.substring(0, 40) + "..." : text;
        }
        return null;
    }
}
