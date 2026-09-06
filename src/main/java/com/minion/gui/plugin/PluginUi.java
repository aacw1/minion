package com.minion.gui.plugin;

import com.minion.gui.theme.Theme;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * gui/plugin 三个界面共用的小件：表单行、错误提示标签、弹窗提示、正整数解析。
 * 抽出来是为了 DRY——原先 SettingsDialog.row 是 private，浏览器段迁出后这三处都要用同一套布局口径。
 * 表单行不用 GridPane+ColumnConstraints：JavaFX 8 下标签列仍会被挤压截断（基础设置页的历史结论）。
 */
final class PluginUi {

    /** 标签列固定宽（与基础设置页 row(...) 同口径） */
    static final int LABEL_WIDTH = 160;

    /** 错误提示占位行高度：空文本时 Label 不占高，固定一行高保证显错前后布局零位移 */
    private static final double ERROR_LINE_HEIGHT = 20;

    private PluginUi() { }

    /** 表单行：标签固定宽不收缩，输入控件铺满剩余宽度 */
    static HBox row(String labelText, Region control) {
        Label l = new Label(labelText);
        l.setMinWidth(LABEL_WIDTH);
        l.setPrefWidth(LABEL_WIDTH);
        l.setWrapText(true);
        control.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(8);
        HBox.setHgrow(control, Priority.ALWAYS);
        box.getChildren().addAll(l, control);
        return box;
    }

    /**
     * 表单内联错误标签（红字，常驻占位一行）：无错时不可见但保留布局空间（managed 恒 true），
     * 出错时 setText + setVisible(true) 即可。不能用 managed 切换动态插入——
     * 会推挤下方输入行、把确认按钮挤出弹窗可视区（三个配置弹窗均踩过此坑）。
     * 预期文案为单行短提示；超长时 wrapText 换行增高仍会顶挤，文案应保持简短。
     */
    static Label errorLabel() {
        Label l = new Label("");
        l.setStyle("-fx-text-fill: #ff6b6b;");
        l.setWrapText(true);
        l.setMinHeight(ERROR_LINE_HEIGHT);   // 空文本时仍占一行高
        l.setVisible(false);                 // 隐藏但不取消占位（managed 保持默认 true）
        return l;
    }

    /** 深色样式的信息/错误弹窗 */
    static void alert(Window owner, Alert.AlertType type, String title, String message) {
        Alert a = new Alert(type, message, ButtonType.OK);
        a.setTitle(title);
        Theme.style(a);
        if (owner != null) a.initOwner(owner);
        a.showAndWait();
    }

    /** 正整数解析：空/非数字/≤0 一律返回 -1（调用方据此拦截保存） */
    static int parsePositiveInt(String text) {
        if (text == null) return -1;
        String s = text.trim();
        if (s.isEmpty()) return -1;
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 弹窗内容统一内边距 */
    static Insets padding() { return new Insets(12); }
}
