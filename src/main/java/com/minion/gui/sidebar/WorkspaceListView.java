package com.minion.gui.sidebar;

import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.dialog.WorkspaceFormDialog;
import com.minion.gui.icon.IconFactory;
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
import javafx.scene.control.Tooltip;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.Optional;

/** 左侧工作空间列表：单击切换；悬停显示操作小按钮（修改/删除，重命名并入修改弹窗）；拖拽排序；顶部"新建"按钮由 MainWindow 放置 */
public class WorkspaceListView extends ListView<String> {

    private final SessionManager manager;
    private final WorkspaceManager workspaces;

    public WorkspaceListView(SessionManager manager) {
        this.manager = manager;
        this.workspaces = manager.workspaces();
        setCellFactory(v -> new WsCell());
        setOnMouseClicked(e -> {
            // 悬停按钮（⚙/✕）的点击会冒泡到此 handler：跳过切换，避免误清空聊天区（按钮事件已由按钮自身处理）
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
            Label nameLabel = new Label(name);
            nameLabel.getStyleClass().add("cell-text");
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            nameLabel.setMinWidth(0); // 允许收缩至省略号：长名称不再撑出横向滚动条（悬停 ⚙/✕ 按钮不被挤出）
            // 当前工作空间：名称右侧主色圆点（SVG，替代原 "  ●" 文本）
            boolean current = name.equals(workspaces.currentName());
            if (current) {
                SVGPath dot = IconFactory.dot();
                IconFactory.size(dot, 8);
                nameLabel.setGraphic(dot);
            }
            nameLabel.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            nameLabel.setGraphicTextGap(4);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            // 悬停操作按钮（需求：不用右键，鼠标放上去才显示）
            HBox btns = new HBox(4);
            SVGPath editIcon = IconFactory.settings(); // 修改（齿轮，同原 ⚙ 语义）
            IconFactory.size(editIcon, 13);
            Button editBtn = new Button();
            editBtn.setGraphic(editIcon);
            editBtn.getStyleClass().add("btn-cell");
            editBtn.setTooltip(new Tooltip("修改"));
            editBtn.setOnAction(e -> doEdit(name));
            SVGPath delIcon = IconFactory.delete(); // 删除（垃圾桶）
            IconFactory.size(delIcon, 13);
            Button delBtn = new Button();
            delBtn.setGraphic(delIcon);
            delBtn.getStyleClass().add("btn-cell");
            delBtn.setTooltip(new Tooltip("删除"));
            delBtn.setOnAction(e -> doDelete(name));
            btns.getChildren().addAll(editBtn, delBtn);
            btns.setVisible(false);
            btns.setManaged(false);
            setOnMouseEntered(e -> { btns.setVisible(true); btns.setManaged(true); });
            setOnMouseExited(e -> { btns.setVisible(false); btns.setManaged(false); });

            HBox row = new HBox(6);
            // 行宽绑定 cell 宽：内容超出即截断，根除横向滚动条（须依赖 insetsProperty 重算：
            // CSS 异步应用，updateItem 绑定时刻 getInsets 恒为 0，快照会永久漏抵消 padding，同 SessionCell 修法）
            row.maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> getWidth() - getInsets().getLeft() - getInsets().getRight() - 4,
                    widthProperty(), insetsProperty()));
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
                if (e.getGestureSource() == this) return; // 仅跳过拖起源自身（原条件写反导致所有目标拒绝 drop）
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

    /** 修改工作空间：表单与新建共用，名称可改（重命名会迁移会话目录） */
    private void doEdit(String name) {
        WorkspaceConfig w = workspaces.get(name);
        java.util.List<String> existing = new java.util.ArrayList<String>();
        for (WorkspaceConfig other : workspaces.list()) {
            if (!name.equals(other.workSpaceName)) existing.add(other.workSpaceName);
        }
        WorkspaceFormDialog d = new WorkspaceFormDialog("修改工作空间",
                "工作空间「" + name + "」（重命名会同步迁移会话目录；项目路径 / 项目主说明文件 / "
                        + "项目级技能路径 的修改对新会话生效）",
                w, existing);
        Optional<WorkspaceConfig> r = d.showAndWait();
        if (!r.isPresent()) return;
        WorkspaceConfig v = r.get();
        if (!v.workSpaceName.equals(name) && !manager.renameWorkspace(name, v.workSpaceName)) {
            error("重命名失败", "名称非法或已存在");
            return;
        }
        manager.updateWorkspace(v.workSpaceName, v.workDir, v.projectMd, v.projectSkillsDir);
    }

    private void doDelete(String name) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除工作空间「" + name + "」？其下所有会话与 " + "session/" + name + "/ 目录将一并删除。",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("删除工作空间");
        a.setHeaderText(null);                 // 去掉左边的"确认"文字（header 行移除 → 弹窗高度减一行）
        a.getDialogPane().setGraphic(null);    // 去掉叹号圆圈图标
        a.getDialogPane().getStyleClass().add("dialog-exit"); // 正文居中
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
