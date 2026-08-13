package com.minion.gui.dialog;

import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.util.Optional;

/** 设置窗（右上角 ⚙）：左列导航 基础设置 / 模型 / 关于，右侧内容切换；模型操作后触发 applyModelChanged 实时生效 */
public class SettingsDialog {

    public static void show(Window owner, final ModelManager models,
                            final SessionManager manager, final Config config) {
        Dialog<Void> d = new Dialog<Void>();
        d.initOwner(owner);
        d.setTitle("设置");
        final BasicPane basic = new BasicPane(config, owner);
        // 按钮栏从左到右「应用」「关闭」：ButtonBar 按平台 ButtonData 顺序重排视觉位置，
        // OTHER(U) 在 Win/Linux/Mac 三套顺序串里均先于 CANCEL_CLOSE(C)，APPLY(A) 在 Win/Mac 反而排 C 之后（实测 8u181）
        ButtonType applyType = new ButtonType("应用", ButtonBar.ButtonData.OTHER);
        d.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CLOSE);
        // DialogPane 对任意按钮点击都触发关窗（impl_setResultAndClose）；应用=保存不关窗，须用捕获阶段 filter 先 consume
        ((Button) d.getDialogPane().lookupButton(applyType)).addEventFilter(ActionEvent.ACTION, e -> {
            basic.apply();
            e.consume();
        });
        Theme.style(d);

        // 左列导航：TabPane 侧放文字旋转 90°（历史"字倒了"根因）不可用；ListView 复用现有深色样式
        final ListView<String> nav = new ListView<String>();
        nav.getItems().addAll("基础设置", "模型", "关于");
        nav.setPrefWidth(120);
        nav.setMinWidth(120); // HBox 空间不足时按 HGrow 优先级分配，无 HGrow 的子项会被压到最小宽度；minWidth 保证导航列不被压塌
        final Node model = modelPane(models, manager);
        final Node about = aboutPane();
        final StackPane content = new StackPane();
        nav.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
            if (item == null) return;
            content.getChildren().setAll("基础设置".equals(item) ? basic.root
                    : "模型".equals(item) ? model : about);
        });
        nav.getSelectionModel().select(0); // 默认选中基础设置（选中监听触发内容显示）

        HBox box = new HBox(0);
        box.getChildren().addAll(nav, content);
        HBox.setHgrow(content, Priority.ALWAYS);
        box.setPrefSize(620, 500);
        d.getDialogPane().setContent(box);
        d.showAndWait();
    }

    // ===== 模型页（迁移自 ModelDialog + propagate） =====

    private static VBox modelPane(final ModelManager models, final SessionManager manager) {
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
        return box;
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

    /** 基础设置页：控件 + 保存逻辑（按钮栏「应用」接线用） */
    private static class BasicPane {
        final Node root;
        private final Config config;
        private final TextField skillsDir;
        private final TextArea toolWhitelist;
        private final TextArea cmdWhitelist;
        private final CheckBox allowOutside;
        private final CheckBox skipConfirm;
        private final TextField browserPath;
        private final TextField browserPort;
        private final TextField browserUserData;
        private final CheckBox browserHeadless;
        private final TextField browserTimeout;

        BasicPane(final Config config, final Window owner) {
            this.config = config;
            HBox skillsBox = new HBox(6);
            skillsDir = new TextField(config.skillsDir());
            HBox.setHgrow(skillsDir, Priority.ALWAYS);
            Button browse = new Button("浏览…");
            browse.getStyleClass().add("btn-ghost");
            browse.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                String cur = skillsDir.getText().trim();
                if (!cur.isEmpty()) {
                    java.io.File f = new java.io.File(cur);
                    if (f.isDirectory()) dc.setInitialDirectory(f);
                }
                java.io.File dir = dc.showDialog(owner);
                if (dir != null) skillsDir.setText(dir.getAbsolutePath());
            });
            skillsBox.getChildren().addAll(skillsDir, browse);
            toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
            toolWhitelist.setPrefRowCount(2);
            toolWhitelist.setPrefColumnCount(20); // 默认 40 列偏好宽 ≈624px 把基础页撑到 794，触发 HBox 压缩导航列；20 列后偏好宽 ~500 与内容区匹配
            cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
            cmdWhitelist.setPrefRowCount(2);
            cmdWhitelist.setPrefColumnCount(20);
            allowOutside = new CheckBox("允许读取工作区外文件（Read/Grep/Glob）");
            allowOutside.setSelected(config.readAllowOutside());
            skipConfirm = new CheckBox("跳过高危操作确认");
            skipConfirm.setSelected(config.confirmSkip());
            Label browserNote = new Label("浏览器配置（以下项需重启后生效）");
            browserNote.getStyleClass().add("msg-thinking");
            browserPath = new TextField(config.browserPath());
            browserPort = new TextField(String.valueOf(config.browserPort()));
            browserUserData = new TextField(config.browserUserDataDir());
            browserHeadless = new CheckBox("无头模式");
            browserHeadless.setSelected(config.browserHeadless());
            browserTimeout = new TextField(String.valueOf(config.browserTimeoutMs()));

            VBox rows = new VBox(10);
            rows.getChildren().addAll(
                    row("技能目录 skills.dir:", skillsBox),
                    row("确认白名单\n(工具, 逗号分隔):", toolWhitelist),
                    row("确认白名单\n(命令, 逗号分隔):", cmdWhitelist),
                    row("读逃逸:", allowOutside),
                    row("确认开关:", skipConfirm),
                    browserNote,
                    row("browser.path:", browserPath),
                    row("browser.port:", browserPort),
                    row("browser.userDataDir:", browserUserData),
                    row("browser.headless:", browserHeadless),
                    row("browser.timeoutMs:", browserTimeout));

            VBox contentBox = new VBox(10);
            contentBox.getChildren().addAll(rows);
            contentBox.setPadding(new Insets(12));
            ScrollPane sp = new ScrollPane(contentBox); // 窗口小时可滚动，选项不再被裁剪
            sp.setFitToWidth(true);
            this.root = sp;
        }

        /** 「应用」按钮：全部配置项写入，窗口不关闭；port/timeoutMs 非法弹错且该项不写 */
        void apply() {
            config.set("skills.dir", skillsDir.getText().trim());
            // 白名单是逗号分隔的单行配置：多行粘贴的换行替换为空格，否则落盘后重载会静默丢内容
            config.set("confirm.whitelist.tools",
                    toolWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
            config.set("confirm.whitelist.commands",
                    cmdWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
            config.set("paths.read.allowOutside", String.valueOf(allowOutside.isSelected()));
            config.set("confirm.skip", String.valueOf(skipConfirm.isSelected()));
            config.set("browser.path", browserPath.getText().trim());
            if (!setInt("browser.port", browserPort.getText(), config)) {
                error("保存失败", "browser.port 必须是整数，未保存");
            }
            config.set("browser.userDataDir", browserUserData.getText().trim());
            config.set("browser.headless", String.valueOf(browserHeadless.isSelected()));
            if (!setInt("browser.timeoutMs", browserTimeout.getText(), config)) {
                error("保存失败", "browser.timeoutMs 必须是整数，未保存");
            }
        }
    }

    /** 表单行：标签固定宽 160 不收缩（GridPane+ColumnConstraints 在 JavaFX 8 下仍挤压截断，弃用），输入控件铺满剩余宽度 */
    private static HBox row(String labelText, Region control) {
        Label l = new Label(labelText);
        l.setMinWidth(160);
        l.setPrefWidth(160);
        l.setWrapText(true);
        control.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(8);
        HBox.setHgrow(control, Priority.ALWAYS);
        box.getChildren().addAll(l, control);
        return box;
    }

    // ===== 关于页 =====

    private static VBox aboutPane() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.getChildren().addAll(
                new Label("兼容win7的代码开发助手"),
                new Separator(),
                new Label("作者：尹承"),
                new Label("联系方式：258915527@qq.com"),
                new Label("开发语言：Java 8 + JavaFX"));
        return box;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** 保存前校验整数型配置项：非法（非整数/负数/空）→ 不写回并返回 false（调用方弹错）；合法 → 写回 */
    static boolean setInt(String key, String text, Config config) {
        if (parseInt(text, -1) < 0) return false;
        config.set(key, text.trim());
        return true;
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
