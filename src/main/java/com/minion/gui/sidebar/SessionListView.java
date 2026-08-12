package com.minion.gui.sidebar;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/** 左侧会话列表：标题 + 运行状态点 + 右键菜单（重命名/删除）；单击切换 */
public class SessionListView extends ListView<SessionHandle> {

    private final SessionManager manager;

    public SessionListView(final SessionManager manager) {
        this.manager = manager;
        setCellFactory(v -> new SessionCell());
        setOnMouseClicked(e -> {
            SessionHandle h = getSelectionModel().getSelectedItem();
            if (h != null && e.getClickCount() == 1) manager.activateSession(h);
        });
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
            javafx.scene.control.Label name = new javafx.scene.control.Label(label);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(dot, name, spacer);
            setGraphic(box);

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
                    if (bt == ButtonType.OK) manager.deleteSession(h);
                });
            });
            menu.getItems().addAll(rename, del);
            setContextMenu(menu);
        }
    }
}
