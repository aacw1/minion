package com.minion.gui.dialog;

import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.theme.Theme;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
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
 * 三个路径都可留空；空白如何解释由 core 的 WorkspacePaths 负责，本类不做兜底也不做校验。
 * 名称合法性在 OK 按钮上预校验（禁用 + 行内红字），不采用「先提交后弹错」。
 */
public class WorkspaceFormDialog extends Dialog<WorkspaceConfig> {

    /** 标签列变长（项目主说明文件 / 项目级技能路径）后所需的最小宽度 */
    private static final double MIN_WIDTH = 560;

    /**
     * @param initial       预填值；null = 新建（全空，仅名称必填）
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
        name.setPromptText("名称");

        HBox workBox = new HBox(6);
        final TextField workDir = pathRow(workBox, initial == null ? "" : initial.workDir,
                "项目路径（留空 = 软件所在目录）", true);
        HBox mdBox = new HBox(6);
        final TextField projectMd = pathRow(mdBox, initial == null ? "" : initial.projectMd,
                "项目主说明文件（留空 = 项目路径下 project.md）", false);
        HBox skillsBox = new HBox(6);
        final TextField skillsDir = pathRow(skillsBox,
                initial == null ? "" : initial.projectSkillsDir,
                "项目级技能路径（留空 = 仅使用内置技能）", true);

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

        final Button okBtn = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okBtn.disableProperty().bind(Bindings.createBooleanBinding(
                () -> !WorkspaceManager.isValidName(name.getText().trim(), existingNames),
                name.textProperty()));
        name.textProperty().addListener((o, ov, nv) -> nameErr.setVisible(false));
        // 兜底：正常路径 OK 已禁用，此处防绕过与名称快照过期竞态
        okBtn.addEventFilter(ActionEvent.ACTION, e -> {
            if (!WorkspaceManager.isValidName(name.getText().trim(), existingNames)) {
                e.consume();
                nameErr.setText("名称非法或已存在");
                nameErr.setVisible(true);
            }
        });

        setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return new WorkspaceConfig(name.getText().trim(), workDir.getText().trim(),
                    projectMd.getText().trim(), skillsDir.getText().trim());
        });
    }

    /**
     * 生成一行「输入框 + 浏览…」：dir=true 用 DirectoryChooser 选目录，
     * false 用 FileChooser 选 Markdown 文件；控件加进 box，返回输入框供调用方读值。
     */
    private TextField pathRow(HBox box, String value, String prompt, boolean dir) {
        final TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
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
                fc.setTitle(prompt);
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
