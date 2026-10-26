package com.minion.gui.dialog;

import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.config.WorkspacePaths;
import com.minion.gui.theme.Theme;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

/**
 * 工作空间表单（新建与修改共用）：名称 + 项目路径 + 项目主说明文件 + 项目级技能路径。
 * 名称与项目路径必填；主说明文件与技能路径可选，填了才校验（文件必须是已存在的**文件**，
 * 技能路径必须是已存在的文件夹），校验口径与 core 的 add/update 同一实现（WorkspacePaths）。
 * 名称合法性在 OK 按钮上预校验（禁用 + 行内红字）；路径不合法点击确定时弹框说明原因。
 */
public class WorkspaceFormDialog extends Dialog<WorkspaceConfig> {

    /** 弹窗最小宽度（随输入框宽度按比例收缩） */
    private static final double MIN_WIDTH = 620;
    /** 路径输入框优先宽度（字符数） */
    private static final int FIELD_PREF_COLUMNS = 25;

    /**
     * @param initial       预填值；null = 新建（全空，仅名称与项目路径必填）
     * @param existingNames 冲突名称集合：修改场景须排除自身，新建场景给全部现有名称
     */
    public WorkspaceFormDialog(String title, String header, WorkspaceConfig initial,
                               final List<String> existingNames) {
        setTitle(title);
        setHeaderText(header);
        Theme.style(this);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setMinWidth(MIN_WIDTH);

        final TextField name = new TextField(initial == null ? "" : initial.workSpaceName);
        name.setPromptText("必填");
        name.setPrefColumnCount(FIELD_PREF_COLUMNS);

        HBox workBox = new HBox(6);
        final TextField workDir = pathRow(workBox, initial == null ? "" : initial.workDir,
                "必填", true);
        HBox mdBox = new HBox(6);
        final TextField projectMd = pathRow(mdBox, initial == null ? "" : initial.projectMd,
                "可选", false);
        HBox skillsBox = new HBox(6);
        final TextField skillsDir = pathRow(skillsBox,
                initial == null ? "" : initial.projectSkillsDir,
                "可选", true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("名称:"), name);
        grid.addRow(1, new Label("项目路径:"), workBox);
        grid.addRow(2, new Label("项目主说明文件:"), mdBox);
        grid.addRow(3, new Label("项目级技能路径:"), skillsBox);
        getDialogPane().setContent(grid);

        final Label nameErr = new Label();
        nameErr.getStyleClass().add("log-error");
        nameErr.setVisible(false);
        grid.add(nameErr, 1, 4);
        final Label workDirErr = new Label();
        workDirErr.getStyleClass().add("log-error");
        workDirErr.setVisible(false);
        grid.add(workDirErr, 1, 5);

        // 必填项：名称合法（非空/无非法字符/不重名）+ 项目路径非空。
        // 「填了但不是文件夹」不在此禁用——留到点击时弹框说明原因，避免按钮变灰却不知为何
        final Button okBtn = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !WorkspaceManager.isValidName(name.getText().trim(), existingNames)
                        || workDir.getText().trim().isEmpty(),
                name.textProperty(), workDir.textProperty()));
        name.textProperty().addListener((o, ov, nv) -> nameErr.setVisible(false));
        workDir.textProperty().addListener((o, ov, nv) -> workDirErr.setVisible(false));
        // 兜底：名称非法/项目路径空走行内红字；两个路径「不是已存在的文件夹」各自弹框提示
        okBtn.addEventFilter(ActionEvent.ACTION, e -> {
            if (!WorkspaceManager.isValidName(name.getText().trim(), existingNames)) {
                e.consume();
                nameErr.setText("名称非法或已存在");
                nameErr.setVisible(true);
                return;
            }
            if (workDir.getText().trim().isEmpty()) {
                e.consume();
                workDirErr.setText("项目路径不能为空");
                workDirErr.setVisible(true);
                return;
            }
            String bad = notADirectoryMessage("项目路径", workDir.getText(), null);
            if (bad == null) {
                bad = notADirectoryMessage("项目级技能路径", skillsDir.getText(), workDir.getText());
            }
            if (bad == null) {
                bad = notAFileMessage(projectMd.getText(), workDir.getText());
            }
            if (bad != null) {
                e.consume();     // 表单保持打开，用户改完可直接再提交
                alertPathInvalid(bad);
            }
        });

        setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return new WorkspaceConfig(name.getText().trim(), workDir.getText().trim(),
                    projectMd.getText().trim(), skillsDir.getText().trim());
        });
    }

    /**
     * 路径不是「已存在的文件夹」时的提示语，合法返回 null。
     * baseDir 供技能路径的相对写法解析；raw 空白表示未填写——技能路径可选，由调用方决定语义
     * （项目路径的空值走行内「不能为空」，不进这里）。
     */
    private static String notADirectoryMessage(String label, String raw, String baseDir) {
        if (raw == null || raw.trim().isEmpty()) return null;
        return WorkspacePaths.isExistingDir(raw, baseDir) ? null
                : label + "必须是已存在的文件夹（该路径不存在，或指向的是文件）：\n" + raw.trim();
    }

    /**
     * 主说明文件校验（可选字段，留空 = 不使用，合法）：填了必须是**已存在的文件**，
     * 指向文件夹与不存在分别给出对应提示，避免用户猜是哪一种。合法返回 null。
     * baseDir 供相对写法按项目路径解析，口径与 core 的 add/update 一致。
     */
    private static String notAFileMessage(String raw, String baseDir) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String abs = WorkspacePaths.resolve(baseDir, raw);
        if (WorkspacePaths.isExistingDir(raw, baseDir)) {
            return "项目主说明文件必须是一个文件，但该路径是文件夹：\n" + abs;
        }
        if (!WorkspacePaths.isExistingFile(raw, baseDir)) {
            return "项目主说明文件不存在：\n" + abs;
        }
        return null;
    }

    /** 路径无效提示框：点「确定」后回到表单继续修改（本表单不关闭） */
    private void alertPathInvalid(String message) {
        Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        a.setTitle("路径无效");
        a.setHeaderText(null);
        Theme.style(a);
        if (getOwner() != null) a.initOwner(getOwner());
        a.showAndWait();
    }

    /**
     * 生成一行「输入框 + 浏览…」：dir=true 用 DirectoryChooser 选目录，
     * false 用 FileChooser 选 Markdown 文件；控件加进 box，返回输入框供调用方读值。
     */
    private TextField pathRow(HBox box, String value, String prompt, boolean dir) {
        final TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.setPrefColumnCount(FIELD_PREF_COLUMNS);
        HBox.setHgrow(field, Priority.ALWAYS);
        Button btn = new Button("浏览…");
        btn.getStyleClass().add("btn-ghost");
        btn.setOnAction(e -> {
            String cur = field.getText().trim();
            if (dir) {
                DirectoryChooser dc = new DirectoryChooser();
                File f = new File(cur);
                if (!cur.isEmpty() && f.isDirectory()) dc.setInitialDirectory(f);
                File picked = dc.showDialog(getOwner());
                if (picked != null) field.setText(picked.getAbsolutePath());
            } else {
                FileChooser fc = new FileChooser();
                fc.setTitle("选择项目主说明文件");
                fc.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Markdown", "*.md", "*.markdown"));
                File f = new File(cur);
                if (!cur.isEmpty() && f.getParentFile() != null && f.getParentFile().isDirectory()) {
                    fc.setInitialDirectory(f.getParentFile());
                }
                File picked = fc.showOpenDialog(getOwner());
                if (picked != null) field.setText(picked.getAbsolutePath());
            }
        });
        box.getChildren().addAll(field, btn);
        return field;
    }
}
