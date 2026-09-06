package com.minion.gui.plugin;

import com.minion.core.tools.db.DataSourceConfig;
import com.minion.core.tools.db.DataSourceValidator;
import com.minion.core.tools.db.DbType;
import com.minion.core.tools.plugin.DbPlugin;
import com.minion.core.tools.plugin.ToolPluginManager;
import com.minion.gui.theme.Theme;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * 数据源管理弹窗（按数据库类型各开一个）：列表 + 新建/修改/删除/测试连接。
 * 所有改动经 DbPlugin 直接落 tools.json —— 删掉当前选中项时 DbConfig 内部自动回退到第一个，
 * 「工具」页的下拉框由 ToolPluginManager 的监听器自动刷新，本弹窗不需要回调外层。
 */
public class DataSourceDialog {

    public static void show(Window owner, final ToolPluginManager plugins, final DbPlugin db) {
        Dialog<Void> d = new Dialog<Void>();
        if (owner != null) d.initOwner(owner);
        d.setTitle(db.displayName() + " 数据源管理");
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Theme.style(d);

        final ListView<String> list = new ListView<String>();
        list.setPrefHeight(240); // 宽度随弹窗铺满（VBox fillWidth），不再固定窄列
        final Label detail = new Label("");
        detail.getStyleClass().add("msg-thinking");
        detail.setWrapText(true);

        Button add = ghost("新建");
        Button edit = ghost("修改");
        Button del = ghost("删除");
        final Button test = ghost("测试连接");

        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> refreshDetail(db, list, detail));

        add.setOnAction(e -> {
            DataSourceConfig out = form(owner, db.type(), null, db.config().dataSources);
            if (out == null) return;
            if (!db.addDataSource(out)) {
                PluginUi.alert(owner, Alert.AlertType.ERROR, "新建失败", "标识名已存在：" + out.name);
                return;
            }
            refreshList(db, list, detail);
            list.getSelectionModel().select(out.name.trim());
        });

        edit.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            DataSourceConfig old = db.find(sel);
            if (old == null) return;
            DataSourceConfig out = form(owner, db.type(), old, db.config().dataSources);
            if (out == null) return;
            if (!db.updateDataSource(sel, out)) {
                PluginUi.alert(owner, Alert.AlertType.ERROR, "修改失败", "标识名已存在：" + out.name);
                return;
            }
            refreshList(db, list, detail);
            list.getSelectionModel().select(out.name.trim());
        });

        del.setOnAction(e -> {
            final String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除数据源「" + sel + "」？", ButtonType.OK, ButtonType.CANCEL);
            a.setTitle("删除数据源");
            Theme.style(a);
            if (owner != null) a.initOwner(owner);
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                db.removeDataSource(sel);   // 删的是当前项 → DbConfig 内部回退到第一个
                refreshList(db, list, detail);
            }
        });

        test.setOnAction(e -> {
            final String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            final String idleText = test.getText();
            test.setDisable(true);
            test.setText("测试中…");
            // 建连最长阻塞 loginTimeout（10s），必须离开 FX 线程，否则整个界面卡住
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    final DbPlugin.TestResult res = db.testConnection(sel);
                    Platform.runLater(new Runnable() {
                        @Override public void run() {
                            test.setDisable(false);
                            test.setText(idleText);
                            PluginUi.alert(owner,
                                    res.ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                                    res.ok ? "连接成功" : "连接失败", res.message);
                        }
                    });
                }
            }, "minion-db-test");
            t.setDaemon(true);
            t.start();
        });

        // 按钮横排一行放在列表下方（原右侧竖排占宽，列表过窄），详情行置于最底
        HBox buttonRow = new HBox(8, add, edit, del, test);
        VBox root = new VBox(10, list, buttonRow, detail);
        root.setPadding(PluginUi.padding());
        d.getDialogPane().setContent(root);
        d.getDialogPane().setPrefWidth(560);

        refreshList(db, list, detail);
        d.showAndWait();
    }

    /** 新建（original=null，URL 预填该类型模板）/ 修改（预填原值，标识名可改）；取消或校验失败返回 null */
    private static DataSourceConfig form(Window owner, DbType type, final DataSourceConfig original,
                                         List<DataSourceConfig> all) {
        Dialog<DataSourceConfig> d = new Dialog<DataSourceConfig>();
        if (owner != null) d.initOwner(owner);
        d.setTitle(original == null ? "新建数据源" : "修改数据源");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        final TextField name = new TextField(original == null ? "" : original.name);
        name.setPromptText("同类型内不可重复，可修改");
        final TextField url = new TextField(original == null ? type.urlTemplate() : original.url);
        final TextField user = new TextField(original == null ? "" : original.user);
        // 密码明文显示（与 model.json 的 apiKey 一致，便于核对），不做遮罩
        final TextField password = new TextField(original == null ? "" : original.password);
        final Label error = PluginUi.errorLabel();

        VBox rows = new VBox(10);
        rows.setPadding(PluginUi.padding());
        rows.getChildren().addAll(error,
                PluginUi.row("标识名:", name),
                PluginUi.row("URL:", url),
                PluginUi.row("用户名:", user),
                PluginUi.row("密码:", password));
        d.getDialogPane().setContent(rows);
        d.getDialogPane().setPrefWidth(600);

        final DataSourceConfig[] out = new DataSourceConfig[1];
        // 校验失败必须 consume 掉 OK 事件，否则 DialogPane 会直接关窗（resultConverter 返回 null 也关窗）
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            String why = DataSourceValidator.validate(name.getText(), url.getText(), all,
                    original == null ? null : original.name);
            if (why != null) {
                error.setText(why);
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
                return;
            }
            DataSourceConfig ds = new DataSourceConfig();
            ds.name = name.getText().trim();
            ds.url = url.getText().trim();
            ds.user = user.getText().trim();
            ds.password = password.getText();   // 密码不 trim：首尾空格可能是密码的一部分
            out[0] = ds;
        });
        name.textProperty().addListener((obs, o, n) -> hide(error));
        url.textProperty().addListener((obs, o, n) -> hide(error));

        d.showAndWait();
        return out[0];
    }

    private static void hide(Label error) {
        error.setVisible(false);
        error.setManaged(false);
    }

    private static Button ghost(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-ghost");
        return b;
    }

    /** 重读数据源列表并尽量保持原选中项（被删则落到第一项） */
    private static void refreshList(DbPlugin db, ListView<String> list, Label detail) {
        String selected = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(db.dataSourceNames());
        if (selected != null && list.getItems().contains(selected)) {
            list.getSelectionModel().select(selected);
        } else if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        } else {
            list.getSelectionModel().clearSelection();
        }
        refreshDetail(db, list, detail);
    }

    /** 下方详情行：只显示 URL 与用户名，密码不回显 */
    private static void refreshDetail(DbPlugin db, ListView<String> list, Label detail) {
        String sel = list.getSelectionModel().getSelectedItem();
        DataSourceConfig ds = sel == null ? null : db.find(sel);
        detail.setText(ds == null ? "（无数据源，点「新建」添加）"
                : "URL: " + ds.url + "     用户名: " + (ds.user == null || ds.user.isEmpty() ? "（空）" : ds.user));
    }
}
