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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;

import java.util.Optional;

/** 左侧工作空间列表：单击切换；悬停显示操作小按钮（重命名/修改/删除）；拖拽排序；顶部"新建"按钮由 MainWindow 放置 */
public class WorkspaceListView extends ListView<String> {

    private final SessionManager manager;
    private final WorkspaceManager workspaces;

    public WorkspaceListView(SessionManager manager) {
        this.manager = manager;
        this.workspaces = manager.workspaces();
        setCellFactory(v -> new WsCell());
        setOnMouseClicked(e -> {
            // 悬停按钮（✎/⚙/✕）的点击会冒泡到此 handler：跳过切换，避免误清空聊天区（按钮事件已由按钮自身处理）
            if (isHoverButton(e.getTarget())) return;
            String name = getSelectionModel().getSelectedItem();
            if (name != null && e.getClickCount() == 1) manager.switchWorkspace(name);
        });
        Platform.runLater(() -> refresh()); // 初始列表（loadWorkspaceContexts 无 Listener 通知）
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
            Label nameLabel = new Label(name + (name.equals(workspaces.currentName()) ? "  ●" : ""));
            nameLabel.getStyleClass().add("cell-text");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            // 悬停操作按钮（需求：不用右键，鼠标放上去才显示）
            HBox btns = new HBox(4);
            Button renameBtn = new Button("✎");
            renameBtn.getStyleClass().add("btn-cell");
            renameBtn.setTooltip(new Tooltip("重命名"));
            renameBtn.setOnAction(e -> doRename(name));
            Button editBtn = new Button("⚙");
            editBtn.getStyleClass().add("btn-cell");
            editBtn.setTooltip(new Tooltip("修改"));
            editBtn.setOnAction(e -> doEdit(name));
            Button delBtn = new Button("✕");
            delBtn.getStyleClass().add("btn-cell");
            delBtn.setTooltip(new Tooltip("删除"));
            delBtn.setOnAction(e -> doDelete(name));
            btns.getChildren().addAll(renameBtn, editBtn, delBtn);
            btns.setVisible(false);
            btns.setManaged(false);
            setOnMouseEntered(e -> { btns.setVisible(true); btns.setManaged(true); });
            setOnMouseExited(e -> { btns.setVisible(false); btns.setManaged(false); });

            HBox row = new HBox(6);
            row.getChildren().addAll(nameLabel, spacer, btns);
            setGraphic(row);

            // 拖拽排序：拖起携带工作空间名，drop 到目标 cell 位置
            setOnDragDetected(e -> {
                if (isEmpty()) return;
                javafx.scene.input.Dragboard db = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(name);
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getGestureSource() != this) return;
                e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                e.consume();
            });
            setOnDragDropped(e -> {
                String dragged = e.getDragboard().getString();
                if (dragged != null && !dragged.equals(name)) {
                    manager.moveWorkspace(dragged, getIndex()); // 排序持久化；不触发内容切换通知
                    refresh();
                }
                e.setDropCompleted(true); // JavaFX 8 DragEvent API：通知拖起源 drop 已处理（简报中的 setDropHandled 不存在）
                e.consume();
            });
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
        HBox pmBox = new HBox(6);
        TextField projectMd = new TextField(w.projectMd == null ? "" : w.projectMd);
        HBox.setHgrow(projectMd, Priority.ALWAYS);
        Button pmBrowse = new Button("浏览…");
        pmBrowse.getStyleClass().add("btn-ghost");
        pmBrowse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("选择 project.md");
            fc.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"));
            String cur = projectMd.getText().trim();
            if (!cur.isEmpty()) {
                java.io.File f = new java.io.File(cur);
                if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                    fc.setInitialDirectory(f.getParentFile());
                }
            }
            java.io.File file = fc.showOpenDialog(d.getOwner());
            if (file != null) projectMd.setText(file.getAbsolutePath());
        });
        pmBox.getChildren().addAll(projectMd, pmBrowse);
        grid.addRow(0, new Label("work.dir:"), workDirBox);
        grid.addRow(1, new Label("project.md:"), pmBox);
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
