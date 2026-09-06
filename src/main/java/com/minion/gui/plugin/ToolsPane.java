package com.minion.gui.plugin;

import com.minion.core.tools.plugin.DbPlugin;
import com.minion.core.tools.plugin.ToolPlugin;
import com.minion.core.tools.plugin.ToolPluginManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置窗「工具」页：每个可插拔工具一行 = 显示名 + 状态文案 + 启用开关 + 该工具专属配置入口。
 * 行数固定（浏览器 + 三个数据库）、每行控件不同，故用 VBox 直接拼装而非 ListView。
 * 启用开关与数据源下拉改动即落 tools.json —— 生效由 ToolRegistry 的 gate 在下一轮 schemas() 判定
 * （拉模式），本页不需要通知任何会话。
 */
public class ToolsPane {

    private final ToolPluginManager plugins;
    private final Window owner;
    private final VBox root = new VBox(10);
    private final List<Row> rows = new ArrayList<Row>();
    /** refresh 期间抑制 ComboBox/CheckBox 的 onAction 回环 */
    private boolean updating;

    /** 一行的可变控件（刷新时更新状态文案、开关与下拉框） */
    private static class Row {
        final ToolPlugin plugin;
        final Label status = new Label();
        final CheckBox enabled = new CheckBox("启用");
        DbPlugin db;                  // 数据库插件行才有
        ComboBox<String> sources;     // 数据库插件行才有

        Row(ToolPlugin plugin) { this.plugin = plugin; }
    }

    /** 设置窗调用入口；plugins 为 null（未装配的异常路径）返回提示页 */
    public static Node build(ToolPluginManager plugins, Window owner) {
        if (plugins == null) {
            VBox empty = new VBox(10);
            empty.setPadding(new Insets(10));
            Label tip = new Label("工具管理器未装配");
            tip.getStyleClass().add("msg-thinking");
            empty.getChildren().add(tip);
            return empty;
        }
        final ToolsPane pane = new ToolsPane(plugins, owner);
        pane.layout();
        // 变更监听：刷新面板的唯一途径（含后台线程落盘如测试连接、数据源管理弹窗改动）。
        // 设置窗关闭后 root 脱离场景 → 自注销，防每次打开设置窗累积一个面板引用；
        // 监听仅用于 GUI 刷新，不参与工具生效链路
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            @Override public void run() {
                Platform.runLater(new Runnable() {
                    @Override public void run() {
                        if (pane.root.getScene() == null) {
                            plugins.removeListener(self[0]);
                            return;
                        }
                        pane.refresh();
                    }
                });
            }
        };
        plugins.addListener(self[0]);
        return pane.root;
    }

    private ToolsPane(ToolPluginManager plugins, Window owner) {
        this.plugins = plugins;
        this.owner = owner;
    }

    private void layout() {
        root.setPadding(PluginUi.padding());
        for (ToolPlugin p : plugins.plugins()) {
            final Row row = new Row(p);

            Label name = new Label(p.displayName());
            name.setMinWidth(100);
            name.setPrefWidth(100);
            row.status.getStyleClass().add("msg-thinking");

            row.enabled.setSelected(p.enabled());
            row.enabled.setOnAction(e -> {
                if (updating) return;
                // 只改状态：落盘触发的 listener 会刷新本行（去掉重复的同步 refresh）
                plugins.setEnabled(row.plugin.id(), row.enabled.isSelected());
            });

            HBox configArea = new HBox(8);
            DbPlugin db = plugins.dbPlugin(p.id());
            if (db != null) {
                row.db = db;
                row.sources = new ComboBox<String>();
                row.sources.setPrefWidth(180);
                row.sources.setPromptText("（无数据源）");
                row.sources.setOnAction(e -> {
                    if (updating) return;
                    String v = row.sources.getValue();
                    if (v != null) row.db.setCurrent(v);   // 落盘 → 全局会话下一轮生效（listener 负责刷行）
                });
                Button manage = ghost("数据源管理");
                manage.setOnAction(e -> DataSourceDialog.show(owner, plugins, row.db));
                configArea.getChildren().addAll(row.sources, manage);
            } else {
                Button cfg = ghost("配置");
                cfg.setOnAction(e -> BrowserConfigDialog.show(owner, plugins));
                configArea.getChildren().add(cfg);
            }

            // 启用开关第一列（最左）；状态列仅提示缺失态（如「未配置数据源」），
            // 已配置时的当前选择由下拉框可见，不重复占位
            HBox line = new HBox(8, row.enabled, name, row.status, configArea);
            HBox.setHgrow(row.status, Priority.ALWAYS);
            line.setPadding(new Insets(4, 0, 4, 0));
            rows.add(row);
            root.getChildren().add(line);
        }
        refresh();
    }

    /** 按插件当前状态刷新四行（状态文案 / 开关 / 下拉框内容与选中值） */
    private void refresh() {
        updating = true;
        try {
            for (Row row : rows) {
                row.status.setText(row.plugin.statusText());
                row.enabled.setSelected(row.plugin.enabled());
                if (row.db != null) {
                    List<String> names = row.db.dataSourceNames();
                    row.sources.getItems().setAll(names);
                    row.sources.setDisable(names.isEmpty());
                    String current = row.db.config().current;
                    row.sources.setValue(names.contains(current) ? current : null);
                }
            }
        } finally {
            updating = false;
        }
    }

    private static Button ghost(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-ghost");
        return b;
    }
}
