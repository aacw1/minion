package com.minion.gui.plugin;

import com.minion.core.tools.ssh.SshAuth;
import com.minion.core.tools.ssh.SshConnection;
import com.minion.core.tools.ssh.SshExecutor;
import com.minion.core.tools.ssh.SshPlugin;
import com.minion.core.tools.ssh.SshValidator;
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
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * ssh 连接管理弹窗（设置 → 工具 → ssh → 连接管理）：列表 + 新建/修改/删除/测试连接。
 * 表单「认证方式」单选：密码 | 私钥，先选后显对应输入区（密码框 / 私钥路径+浏览+口令）；
 * 保存时按所选方式清空另一组（互斥持久化，避免手改 tools.json 双填时认证优先级二义）。
 * 所有改动经 SshPlugin 直接落 tools.json —— 删掉当前选中项时 SshConfig 内部自动回退到第一个，
 * 「工具」页的下拉框由 ToolPluginManager 的监听器自动刷新，本弹窗不需要回调外层。
 */
public class SshConnectionsDialog {

    public static void show(Window owner, final ToolPluginManager plugins, final SshPlugin ssh) {
        Dialog<Void> d = new Dialog<Void>();
        if (owner != null) d.initOwner(owner);
        d.setTitle("ssh 连接管理");
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Theme.style(d);

        final ListView<String> list = new ListView<String>();
        list.setPrefHeight(240); // 宽度随弹窗铺满（VBox fillWidth）
        final Label detail = new Label("");
        detail.getStyleClass().add("msg-thinking");
        detail.setWrapText(true);

        Button add = ghost("新建");
        Button edit = ghost("修改");
        Button del = ghost("删除");
        final Button test = ghost("测试连接");

        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> refreshDetail(ssh, list, detail));

        add.setOnAction(e -> {
            SshConnection out = form(owner, null, ssh.config().connections);
            if (out == null) return;
            if (!ssh.addConnection(out)) {
                PluginUi.alert(owner, Alert.AlertType.ERROR, "新建失败", "标识名已存在：" + out.name);
                return;
            }
            refreshList(ssh, list, detail);
            list.getSelectionModel().select(out.name.trim());
        });

        edit.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            SshConnection old = ssh.find(sel);
            if (old == null) return;
            SshConnection out = form(owner, old, ssh.config().connections);
            if (out == null) return;
            if (!ssh.updateConnection(sel, out)) {
                PluginUi.alert(owner, Alert.AlertType.ERROR, "修改失败", "标识名已存在：" + out.name);
                return;
            }
            refreshList(ssh, list, detail);
            list.getSelectionModel().select(out.name.trim());
        });

        del.setOnAction(e -> {
            final String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除连接「" + sel + "」？", ButtonType.OK, ButtonType.CANCEL);
            a.setTitle("删除连接");
            Theme.style(a);
            if (owner != null) a.initOwner(owner);
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                ssh.removeConnection(sel);   // 删的是当前项 → SshConfig 内部回退到第一个
                refreshList(ssh, list, detail);
            }
        });

        test.setOnAction(e -> {
            final String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            final String idleText = test.getText();
            test.setDisable(true);
            test.setText("测试中…");
            // 连接认证最长阻塞 10s，必须离开 FX 线程，否则整个界面卡住
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    final SshExecutor.TestResult res = ssh.testConnection(sel);
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
            }, "minion-ssh-test");
            t.setDaemon(true);
            t.start();
        });

        // 按钮横排一行放在列表下方，详情行置于最底（同数据源管理弹窗布局）
        HBox buttonRow = new HBox(8, add, edit, del, test);
        VBox root = new VBox(10, list, buttonRow, detail);
        root.setPadding(PluginUi.padding());
        d.getDialogPane().setContent(root);
        d.getDialogPane().setPrefWidth(560);

        refreshList(ssh, list, detail);
        d.showAndWait();
    }

    /** 新建（original=null）/ 修改（预填原值，标识名可改）；取消或校验失败返回 null。
     *  认证方式单选先选后显；保存按所选方式清空另一组字段（互斥持久化）。 */
    private static SshConnection form(Window owner, final SshConnection original,
                                      List<SshConnection> all) {
        Dialog<SshConnection> d = new Dialog<SshConnection>();
        if (owner != null) d.initOwner(owner);
        d.setTitle(original == null ? "新建 ssh 连接" : "修改 ssh 连接");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        final TextField name = new TextField(original == null ? "" : original.name);
        name.setPromptText("同插件内不可重复，可修改");
        final TextField host = new TextField(original == null ? "" : original.host);
        host.setPromptText("IP 或域名");
        final TextField port = new TextField(original == null ? "22" : String.valueOf(original.port));
        final TextField user = new TextField(original == null ? "" : original.user);
        final Label error = PluginUi.errorLabel();

        // 认证方式单选：密码 | 私钥（先选后显对应输入区）
        final ToggleGroup group = new ToggleGroup();
        final RadioButton rbPwd = new RadioButton("密码");
        final RadioButton rbKey = new RadioButton("私钥");
        rbPwd.setToggleGroup(group);
        rbKey.setToggleGroup(group);
        final boolean isKey = original != null && SshAuth.isKey(original);
        (isKey ? rbKey : rbPwd).setSelected(true);

        final TextField password = new TextField(original != null && !isKey ? original.password : "");
        password.setPromptText("登录密码"); // 明文显示（与数据源密码口径一致）
        final TextField keyPath = new TextField(original != null && isKey ? original.privateKeyPath : "");
        keyPath.setPromptText("私钥文件绝对路径，如 C:\\Users\\me\\.ssh\\id_rsa");
        final TextField passphrase = new TextField(original != null && isKey ? original.passphrase : "");
        passphrase.setPromptText("私钥口令（无则留空）");
        Button browse = ghost("浏览…");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择私钥文件");
            File f = fc.showOpenDialog(d.getDialogPane().getScene().getWindow());
            if (f != null) keyPath.setText(f.getAbsolutePath());
        });
        HBox keyRow = new HBox(8, keyPath, browse);
        HBox.setHgrow(keyPath, Priority.ALWAYS);
        // 认证区动态显隐：选「密码」显示密码框，选「私钥」显示路径+浏览+口令
        final Label pwdCap = new Label("密码：");
        final Label keyCap = new Label("私钥：");
        final VBox pwdArea = new VBox(4, pwdCap, password);
        final VBox keyArea = new VBox(4, keyCap, keyRow, passphrase);
        Runnable syncAuth = new Runnable() {
            @Override public void run() {
                boolean key = rbKey.isSelected();
                pwdArea.setVisible(!key);   // 显示「密码」输入区
                pwdArea.setManaged(!key);
                keyArea.setVisible(key);    // 显示「私钥」输入区
                keyArea.setManaged(key);
            }
        };
        rbPwd.setOnAction(e -> syncAuth.run());
        rbKey.setOnAction(e -> syncAuth.run());
        syncAuth.run();

        VBox rows = new VBox(10);
        rows.setPadding(PluginUi.padding());
        rows.getChildren().addAll(error,
                PluginUi.row("标识名:", name),
                PluginUi.row("主机:", host),
                PluginUi.row("端口:", port),
                PluginUi.row("用户名:", user),
                PluginUi.row("认证方式:", new HBox(10, rbPwd, rbKey)),
                pwdArea,
                keyArea);
        d.getDialogPane().setContent(rows);
        d.getDialogPane().setPrefWidth(560);

        final SshConnection[] out = new SshConnection[1];
        // 校验失败必须 consume 掉 OK 事件，否则 DialogPane 会直接关窗（resultConverter 返回 null 也关窗）
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            int p;
            try {
                p = Integer.parseInt(port.getText().trim());
            } catch (NumberFormatException nfe) {
                error.setText("端口必须是数字");
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
                return;
            }
            String kind = rbKey.isSelected() ? SshAuth.KEY : SshAuth.PASSWORD;
            String why = SshValidator.validate(name.getText(), host.getText(), p, user.getText(),
                    kind, password.getText(), keyPath.getText(), all,
                    original == null ? null : original.name);
            if (why != null) {
                error.setText(why);
                error.setVisible(true);
                error.setManaged(true);
                ev.consume();
                return;
            }
            SshConnection c = new SshConnection();
            c.name = name.getText().trim();
            c.host = host.getText().trim();
            c.port = p;
            c.user = user.getText().trim();
            if (rbKey.isSelected()) {
                c.privateKeyPath = keyPath.getText().trim();
                c.passphrase = passphrase.getText();   // 口令不 trim
                c.password = "";                        // 互斥：清空另一组
            } else {
                c.password = password.getText();        // 密码不 trim
                c.privateKeyPath = "";
                c.passphrase = "";
            }
            out[0] = c;
        });
        name.textProperty().addListener((obs, o, n) -> hide(error));
        host.textProperty().addListener((obs, o, n) -> hide(error));

        d.showAndWait();
        return out[0];
    }

    /** 重读连接列表并尽量保持原选中项（被删则落到第一项） */
    private static void refreshList(SshPlugin ssh, ListView<String> list, Label detail) {
        String selected = list.getSelectionModel().getSelectedItem();
        list.getItems().setAll(ssh.connectionNames());
        if (selected != null && list.getItems().contains(selected)) {
            list.getSelectionModel().select(selected);
        } else if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        } else {
            list.getSelectionModel().clearSelection();
        }
        refreshDetail(ssh, list, detail);
    }

    /** 下方详情行：地址 + 认证方式摘要；密码/口令不回显 */
    private static void refreshDetail(SshPlugin ssh, ListView<String> list, Label detail) {
        String sel = list.getSelectionModel().getSelectedItem();
        SshConnection c = sel == null ? null : ssh.find(sel);
        if (c == null) {
            detail.setText("（无连接，点「新建」添加）");
            return;
        }
        String auth = SshAuth.isKey(c) ? "私钥认证" : "密码认证";
        detail.setText(c.host + ":" + c.port + "（" + c.user + "，" + auth + "）");
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
}
