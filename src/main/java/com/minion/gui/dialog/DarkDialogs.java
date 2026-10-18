package com.minion.gui.dialog;

import com.minion.gui.icon.IconFactory;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * 无边框深色弹窗：自绘标题栏（拖动 + 关闭），内容与按钮栏仍交给 DialogPane。
 *
 * <p>动机：Windows 系统标题栏为浅色，与深色内容割裂（暖石主题）。{@link #decorate} 把 Dialog 的
 * Stage 改为 {@link StageStyle#UNDECORATED}，清掉 DialogPane 的 header/graphic（原 headerText 降为
 * 内容区顶部副标题），并在内容上方插入 {@code .dark-titlebar}（标题 + 弹性空白 + 关闭按钮）。
 * 关闭按钮语义同「取消」：{@code setResult(null) + close()}。注意 JavaFX 8 的 close() 行为——
 * 弹窗存在 cancel 按钮（ButtonData.CANCEL_CLOSE，如 ButtonType.CLOSE/CANCEL）时结果会被置为该
 * 按钮，showAndWait 返回 present(取消按钮)；没有 cancel 按钮时返回 empty（8u181 实测）。</p>
 *
 * <p>注意：本类不挂样式表（避免与既有调用点重复挂载），调用方仍需先调用
 * {@code Theme.style(dialog)}，否则 {@code .dark-dialog} 系列规则不生效。</p>
 */
public final class DarkDialogs {

    private DarkDialogs() { }

    /**
     * 新建无边框深色弹窗：owner/模态/按钮/内容一次装配，最后走 {@link #decorate}。
     * 调用方仍需 Theme.style(dialog) 挂样式表。
     */
    public static <T> Dialog<T> create(Window owner, String title, Node content, ButtonType... buttons) {
        Dialog<T> d = new Dialog<T>();
        d.initOwner(owner);
        d.initModality(Modality.APPLICATION_MODAL);
        d.getDialogPane().getButtonTypes().addAll(buttons);
        d.getDialogPane().setContent(content);
        decorate(d, title);
        return d;
    }

    /**
     * 把已装配好 content / buttonTypes 的 Dialog 改为无边框深色样式。
     * 必须在 show/showAndWait 之前调用（{@code initStyle} 只能在未显示时设置）。
     */
    public static void decorate(Dialog<?> d, String title) {
        d.initStyle(StageStyle.UNDECORATED);
        DialogPane pane = d.getDialogPane();
        String header = pane.getHeaderText();
        pane.setHeaderText(null);
        pane.setGraphic(null);
        pane.getStyleClass().add("dark-dialog");

        Node content = pane.getContent();
        VBox body = new VBox(10);
        if (header != null && !header.trim().isEmpty()) {
            Label subtitle = new Label(header);
            subtitle.getStyleClass().add("dark-subtitle");
            body.getChildren().add(subtitle);
        }
        if (content != null) body.getChildren().add(content);

        HBox bar = titleBar(d, title);
        pane.setContent(new VBox(bar, body));

        d.setOnShown(e -> {
            Scene scene = pane.getScene();
            // 圆角（-fx-background-radius: 12）需要透明底板，否则四角露出系统默认填充
            if (scene != null) scene.setFill(Color.TRANSPARENT);
        });
    }

    /** 自绘标题栏：标题 + 弹性空白 + 关闭按钮；按住标题栏空白处可拖动整窗 */
    private static HBox titleBar(final Dialog<?> d, String title) {
        Label label = new Label(title);
        label.getStyleClass().add("dark-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        SVGPath closeIcon = IconFactory.close();
        IconFactory.size(closeIcon, 12);
        Button close = new Button();
        close.setGraphic(closeIcon);
        close.getStyleClass().add("btn-close");
        close.setOnAction(e -> {
            d.setResult(null);
            d.close();
        });
        HBox bar = new HBox(10, label, spacer, close);
        bar.getStyleClass().add("dark-titlebar");
        bar.setAlignment(Pos.CENTER_LEFT);

        // 拖动：记录按下点与窗口左上角的偏移，拖动时按屏幕坐标平移窗口（无边框窗无系统拖动区）
        final double[] offset = new double[2];
        bar.setOnMousePressed(e -> {
            Window w = windowOf(d);
            if (w == null) return;
            offset[0] = e.getScreenX() - w.getX();
            offset[1] = e.getScreenY() - w.getY();
        });
        bar.setOnMouseDragged(e -> {
            Window w = windowOf(d);
            if (w == null) return;
            w.setX(e.getScreenX() - offset[0]);
            w.setY(e.getScreenY() - offset[1]);
        });
        return bar;
    }

    /** 弹窗窗口（未显示/未建场景时为 null，拖动事件里兜底判空） */
    private static Window windowOf(Dialog<?> d) {
        Scene scene = d.getDialogPane().getScene();
        return scene == null ? null : scene.getWindow();
    }
}
