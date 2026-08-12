package com.minion.gui.dialog;

import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/** 设置窗（右上角 ⚙）：左侧页签 模型 / 基础设置 / 关于；模型操作后触发 applyModelChanged 实时生效 */
public class SettingsDialog {

    public static void show(Window owner, final ModelManager models,
                            final SessionManager manager, final Config config) {
        Dialog<Void> d = new Dialog<Void>();
        d.initOwner(owner);
        d.setTitle("设置");
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Theme.style(d);

        TabPane tp = new TabPane();
        tp.setSide(javafx.geometry.Side.LEFT);
        tp.setTabMinWidth(100);
        tp.getTabs().add(modelTab(models, manager));
        tp.getTabs().add(basicTab(config));
        tp.getTabs().add(aboutTab());
        tp.setPrefSize(560, 480);
        d.getDialogPane().setContent(tp);
        d.showAndWait();
    }

    // ===== 模型页（迁移自 ModelDialog + propagate） =====

    private static Tab modelTab(final ModelManager models, final SessionManager manager) {
        final ListView<String> list = new ListView<String>();
        list.setCellFactory(lv -> new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item + (item.equals(models.currentName()) ? "  ●" : ""));
            }
        });
        refresh(list, models);
        list.setPrefSize(360, 240);

        list.setOnMouseClicked(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0 || e.getClickCount() != 1) return;
            String name = list.getItems().get(idx);
            if (!name.equals(models.currentName())) {
                models.setCurrent(name);
                manager.applyModelChanged(); // 需求 13：切换模型全量生效
            }
            refresh(list, models);
        });

        HBox actions = new HBox(8);
        Button add = new Button("新建");
        Button edit = new Button("修改");
        Button del = new Button("删除");
        add.getStyleClass().add("btn-ghost");
        edit.getStyleClass().add("btn-ghost");
        del.getStyleClass().add("btn-ghost");
        add.setOnAction(e -> {
            ModelConfig mc = form(null);
            if (mc != null) {
                if (!models.add(mc)) error("新建失败", "标识名非法或已存在");
                if (models.currentName().equals(mc.displayName)) manager.applyModelChanged();
            }
            refresh(list, models);
        });
        edit.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            ModelConfig mc = form(models.get(list.getItems().get(idx)));
            if (mc != null) {
                models.update(mc);
                manager.applyModelChanged(); // 参数修改实时生效（含运行中会话，下一轮生效）
            }
            refresh(list, models);
        });
        del.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            String name = list.getItems().get(idx);
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除模型「" + name + "」？", ButtonType.OK, ButtonType.CANCEL);
            Theme.style(a);
            a.setTitle("删除模型");
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                if (!models.remove(name)) error("删除失败", "至少保留一个模型");
                manager.applyModelChanged(); // 删除后 current 可能回落，统一 propagate
            }
            refresh(list, models);
        });
        actions.getChildren().addAll(add, edit, del);

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.getChildren().addAll(list, actions);
        Tab tab = new Tab("模型", box);
        tab.setClosable(false);
        return tab;
    }

    private static void refresh(ListView<String> list, ModelManager models) {
        list.getItems().clear();
        for (ModelConfig m : models.list()) {
            list.getItems().add(m.displayName);
        }
        int idx = list.getItems().indexOf(models.currentName());
        list.getSelectionModel().select(idx < 0 ? 0 : idx);
    }

    /** 新建（mc==null 带默认值）/ 修改（mc!=null 预填）表单；OK 返回配置，取消返回 null（迁移自 ModelDialog） */
    private static ModelConfig form(ModelConfig mc) {
        Dialog<ModelConfig> d = new Dialog<ModelConfig>();
        d.setTitle(mc == null ? "新建模型" : "修改模型");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        TextField displayName = new TextField(mc == null ? "" : mc.displayName);
        displayName.setPromptText("页签显示标识");
        if (mc != null) displayName.setDisable(true); // ModelManager.update 不复制 displayName
        TextField url = new TextField(mc == null ? "https://api.deepseek.com/v1/chat/completions" : mc.url);
        TextField apiKey = new TextField(mc == null ? "" : mc.apiKey);
        apiKey.setPromptText("sk-...");
        TextField modelName = new TextField(mc == null ? "" : mc.modelName);
        TextField provider = new TextField(mc == null ? "deepseek" : mc.provider);
        CheckBox thinking = new CheckBox("深度思考");
        thinking.setSelected(mc != null && mc.thinking);
        ComboBox<String> effort = new ComboBox<String>();
        effort.getItems().addAll("low", "medium", "high", "max");
        effort.setValue(mc == null ? "max" : mc.reasoningEffort);
        TextField maxCtx = new TextField(mc == null ? "900000" : String.valueOf(mc.maxContextTokens));
        TextField thr = new TextField(mc == null ? "0.8" : String.valueOf(mc.compressThreshold));
        TextField keep = new TextField(mc == null ? "10" : String.valueOf(mc.keepRecentMessages));

        grid.addRow(0, new Label("标识名:"), displayName);
        grid.addRow(1, new Label("URL:"), url);
        grid.addRow(2, new Label("API Key:"), apiKey);
        grid.addRow(3, new Label("模型名:"), modelName);
        grid.addRow(4, new Label("provider:"), provider);
        grid.addRow(5, new Label("思考:"), thinking);
        grid.addRow(6, new Label("effort:"), effort);
        grid.addRow(7, new Label("maxContextTokens:"), maxCtx);
        grid.addRow(8, new Label("compressThreshold:"), thr);
        grid.addRow(9, new Label("keepRecentMessages:"), keep);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            ModelConfig out = new ModelConfig();
            out.displayName = displayName.getText().trim();
            out.url = url.getText().trim();
            out.apiKey = apiKey.getText().trim();
            out.modelName = modelName.getText().trim();
            out.provider = provider.getText().trim();
            out.thinking = thinking.isSelected();
            out.reasoningEffort = effort.getValue() == null ? "max" : effort.getValue();
            out.maxContextTokens = parseInt(maxCtx.getText(), 900000);
            out.compressThreshold = parseDouble(thr.getText(), 0.8);
            out.keepRecentMessages = parseInt(keep.getText(), 10);
            return out;
        });
        Optional<ModelConfig> r = d.showAndWait();
        return r.isPresent() ? r.get() : null;
    }

    // ===== 基础设置页 =====

    private static Tab basicTab(final Config config) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        TextField skillsDir = new TextField(config.skillsDir());
        skillsDir.setPrefWidth(320);
        TextArea toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
        toolWhitelist.setPrefRowCount(2);
        TextArea cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
        cmdWhitelist.setPrefRowCount(2);
        CheckBox allowOutside = new CheckBox("允许读取工作区外文件（Read/Grep/Glob）");
        allowOutside.setSelected(config.readAllowOutside());
        CheckBox skipConfirm = new CheckBox("跳过高危操作确认");
        skipConfirm.setSelected(config.confirmSkip());

        grid.addRow(0, new Label("技能目录 skills.dir:"), skillsDir);
        grid.addRow(1, new Label("确认白名单\n(工具, 逗号分隔):"), toolWhitelist);
        grid.addRow(2, new Label("确认白名单\n(命令, 逗号分隔):"), cmdWhitelist);
        grid.addRow(3, new Label("读逃逸:"), allowOutside);
        grid.addRow(4, new Label("确认开关:"), skipConfirm);

        Label browserNote = new Label("浏览器配置（以下项需重启后生效）");
        browserNote.getStyleClass().add("msg-thinking");
        grid.addRow(5, new Label(""), browserNote);
        TextField browserPath = new TextField(config.browserPath());
        TextField browserPort = new TextField(String.valueOf(config.browserPort()));
        TextField browserUserData = new TextField(config.browserUserDataDir());
        CheckBox browserHeadless = new CheckBox("无头模式");
        browserHeadless.setSelected(config.browserHeadless());
        TextField browserTimeout = new TextField(String.valueOf(config.browserTimeoutMs()));
        grid.addRow(6, new Label("browser.path:"), browserPath);
        grid.addRow(7, new Label("browser.port:"), browserPort);
        grid.addRow(8, new Label("browser.userDataDir:"), browserUserData);
        grid.addRow(9, new Label("browser.headless:"), browserHeadless);
        grid.addRow(10, new Label("browser.timeoutMs:"), browserTimeout);

        Button save = new Button("保存");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> {
            config.set("skills.dir", skillsDir.getText().trim());
            config.set("confirm.whitelist.tools", toolWhitelist.getText().trim());
            config.set("confirm.whitelist.commands", cmdWhitelist.getText().trim());
            config.set("paths.read.allowOutside", String.valueOf(allowOutside.isSelected()));
            config.set("confirm.skip", String.valueOf(skipConfirm.isSelected()));
            config.set("browser.path", browserPath.getText().trim());
            config.set("browser.port", browserPort.getText().trim());
            config.set("browser.userDataDir", browserUserData.getText().trim());
            config.set("browser.headless", String.valueOf(browserHeadless.isSelected()));
            config.set("browser.timeoutMs", browserTimeout.getText().trim());
        });
        HBox bottom = new HBox(10);
        bottom.getChildren().addAll(save);
        VBox box = new VBox(10);
        box.getChildren().addAll(grid, bottom);
        box.setPadding(new Insets(4));

        Tab tab = new Tab("基础设置", box);
        tab.setClosable(false);
        return tab;
    }

    // ===== 关于页 =====

    private static Tab aboutTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.getChildren().addAll(
                new Label("minion——类 Claude Code 的代码开发助手"),
                new Separator(),
                new Label("作者：尹承"),
                new Label("联系方式：258915527@qq.com"),
                new Label("开发语言：Java 8 + JavaFX"));
        Tab tab = new Tab("关于", box);
        tab.setClosable(false);
        return tab;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        Theme.style(a);
        a.showAndWait();
    }
}
