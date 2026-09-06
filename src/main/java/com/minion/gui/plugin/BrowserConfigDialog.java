package com.minion.gui.plugin;

import com.minion.core.tools.plugin.BrowserConfig;
import com.minion.core.tools.plugin.BrowserPlugin;
import com.minion.core.tools.plugin.ToolPluginManager;
import com.minion.gui.theme.Theme;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;

/**
 * 浏览器工具配置弹窗：path / port / userDataDir / headless / timeoutMs，全部读写 tools.json。
 * 保存即生效——BrowserPlugin.onConfigChanged() 会关掉 minion 自启的 Chrome 并按新配置重建
 * （已打开的页面会丢失，弹窗顶部有明确标注）。
 */
public class BrowserConfigDialog {

    public static void show(Window owner, final ToolPluginManager plugins) {
        final BrowserPlugin plugin = plugins.browserPlugin();
        final BrowserConfig cfg = plugin.config();

        Dialog<Void> d = new Dialog<Void>();
        if (owner != null) d.initOwner(owner);
        d.setTitle("浏览器操作 配置");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        Label note = new Label("保存立即生效；已打开的浏览器页面会被关闭");
        note.getStyleClass().add("msg-thinking");
        final Label error = PluginUi.errorLabel();

        final TextField path = new TextField(cfg.path);
        Button browse = new Button("浏览…");
        browse.getStyleClass().add("btn-ghost");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择浏览器程序");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("可执行文件", "*.exe"),
                    new FileChooser.ExtensionFilter("所有文件", "*.*"));
            // 当前值若是存在的文件，初始定位到其父目录
            File f = new File(path.getText().trim());
            if (f.isFile() && f.getParentFile() != null && f.getParentFile().isDirectory()) {
                fc.setInitialDirectory(f.getParentFile());
            }
            File picked = fc.showOpenDialog(owner);
            if (picked != null) path.setText(picked.getAbsolutePath());
        });
        HBox pathBox = new HBox(6, path, browse);
        HBox.setHgrow(path, Priority.ALWAYS);

        final TextField port = new TextField(String.valueOf(cfg.port));
        final TextField userDataDir = new TextField(cfg.userDataDir);
        final CheckBox headless = new CheckBox("无头模式");
        headless.setSelected(cfg.headless);
        final TextField timeoutMs = new TextField(String.valueOf(cfg.timeoutMs));

        VBox rows = new VBox(10);
        rows.setPadding(PluginUi.padding());
        rows.getChildren().addAll(note, error,
                PluginUi.row("浏览器路径:", pathBox),
                PluginUi.row("调试端口:", port),
                PluginUi.row("用户数据目录:", userDataDir),
                PluginUi.row("无头模式:", headless),
                PluginUi.row("超时(ms):", timeoutMs));
        d.getDialogPane().setContent(rows);
        d.getDialogPane().setPrefWidth(600);

        // DialogPane 对任意按钮点击都会关窗，校验失败必须用捕获阶段 filter 先 consume（同「应用」按钮技巧）
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            int p = PluginUi.parsePositiveInt(port.getText());
            int t = PluginUi.parsePositiveInt(timeoutMs.getText());
            if (p < 0) {
                showError(error, "调试端口必须是正整数，未保存");
                ev.consume();
                return;
            }
            if (t < 0) {
                showError(error, "超时(ms) 必须是正整数，未保存");
                ev.consume();
                return;
            }
            cfg.path = path.getText().trim();
            cfg.port = p;
            cfg.userDataDir = userDataDir.getText().trim();
            cfg.headless = headless.isSelected();
            cfg.timeoutMs = t;
            plugins.save();
            plugin.onConfigChanged();   // 关旧 Chrome + 按新配置懒建
        });
        // 输入变化即清掉上一次的错误提示
        port.textProperty().addListener((obs, o, n) -> hideError(error));
        timeoutMs.textProperty().addListener((obs, o, n) -> hideError(error));

        d.showAndWait();
    }

    private static void showError(Label error, String message) {
        error.setText(message);
        error.setVisible(true);   // 占位常驻，不切 managed——避免推挤下方行与确认按钮
    }

    private static void hideError(Label error) {
        error.setText("");
        error.setVisible(false);
    }
}
