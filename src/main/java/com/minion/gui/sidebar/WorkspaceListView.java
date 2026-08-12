package com.minion.gui.sidebar;

import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;

import java.util.Optional;

/** 左侧工作空间列表：单击切换；右键菜单（重命名/修改/删除）；顶部"新建"按钮由 MainWindow 放置 */
public class WorkspaceListView extends ListView<String> {

    private final SessionManager manager;
    private final WorkspaceManager workspaces;

    public WorkspaceListView(SessionManager manager) {
        this.manager = manager;
        this.workspaces = manager.workspaces();
        setCellFactory(v -> new WsCell());
        setOnMouseClicked(e -> {
            String name = getSelectionModel().getSelectedItem();
            if (name != null && e.getClickCount() == 1) manager.switchWorkspace(name);
        });
        Platform.runLater(() -> refresh()); // 初始列表（loadWorkspaceContexts 无 Listener 通知）
    }

    public void refresh() {
        Platform.runLater(() -> {
            getItems().clear();
            for (WorkspaceConfig w : workspaces.list()) getItems().add(w.workSpaceName);
            getSelectionModel().select(workspaces.currentName());
        });
    }

    private class WsCell extends ListCell<String> {
        @Override protected void updateItem(String name, boolean empty) {
            super.updateItem(name, empty);
            if (empty || name == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            setText(name);
            if (name.equals(workspaces.currentName())) {
                setText(name + "  ●"); // 当前工作空间标记
            }

            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem("重命名");
            rename.setOnAction(e -> doRename(name));
            MenuItem edit = new MenuItem("修改");
            edit.setOnAction(e -> doEdit(name));
            MenuItem del = new MenuItem("删除");
            del.setOnAction(e -> doDelete(name));
            menu.getItems().addAll(rename, edit, del);
            setContextMenu(menu);
        }
    }

    private void doRename(String oldName) {
        TextInputDialog d = new TextInputDialog(oldName);
        d.setTitle("重命名工作空间");
        d.setHeaderText("输入新名称（会同步迁移会话目录）");
        Theme.style(d); // 弹窗深色
        Optional<String> result = d.showAndWait();
        if (!result.isPresent()) return;
        if (!manager.renameWorkspace(oldName, result.get().trim())) {
            error("重命名失败", "名称非法或已存在");
        }
        refresh();
    }

    /** 修改：workDir / project.md 可改；名称不动（重命名是单独操作） */
    private void doEdit(String name) {
        WorkspaceConfig w = workspaces.get(name);
        Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
        d.setTitle("修改工作空间");
        d.setHeaderText("工作空间「" + name + "」（修改对新会话生效）");
        Theme.style(d); // 弹窗深色
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        HBox workDirBox = new HBox(6);
        TextField workDir = new TextField(w.workDir);
        HBox.setHgrow(workDir, Priority.ALWAYS);
        Button browse = new Button("浏览…");
        browse.getStyleClass().add("btn-ghost");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            String cur = workDir.getText().trim();
            if (!cur.isEmpty()) {
                java.io.File f = new java.io.File(cur);
                if (f.isDirectory()) dc.setInitialDirectory(f);
            }
            java.io.File dir = dc.showDialog(d.getOwner());
            if (dir != null) workDir.setText(dir.getAbsolutePath());
        });
        workDirBox.getChildren().addAll(workDir, browse);
        TextField projectMd = new TextField(w.projectMd == null ? "" : w.projectMd);
        grid.addRow(0, new Label("work.dir:"), workDirBox);
        grid.addRow(1, new Label("project.md:"), projectMd);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            WorkspaceConfig out = new WorkspaceConfig();
            out.workSpaceName = name;
            out.workDir = workDir.getText().trim();
            out.projectMd = projectMd.getText().trim();
            return out;
        });
        Optional<WorkspaceConfig> result = d.showAndWait();
        if (!result.isPresent()) return;
        manager.updateWorkspace(name, result.get().workDir, result.get().projectMd);
    }

    private void doDelete(String name) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除工作空间「" + name + "」？其下所有会话与 " + "session/" + name + "/ 目录将一并删除。",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("删除工作空间");
        Theme.style(a); // 弹窗深色
        Optional<ButtonType> r = a.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            if (!manager.deleteWorkspace(name)) {
                error("删除失败", "至少保留一个工作空间");
            }
            refresh();
        }
    }

    private void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        Theme.style(a); // 弹窗深色
        a.showAndWait();
    }
}
