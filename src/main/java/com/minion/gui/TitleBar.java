package com.minion.gui;

import com.minion.gui.icon.IconFactory;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 自绘标题栏（无边框窗口）：应用名 | 模型标签 | 留白（弹性）| 设置 | 最小化 | 最大化 | 关闭（图标为 SVG，IconFactory 集中管理）。
 * 拖动标题栏移动窗口；双击空白区切换最大化。关闭走 confirmClose（与系统关闭共用退出确认）。
 * 最大化不用 stage.setMaximized（无边框窗口在 Windows 上会覆盖任务栏），改手动定位到
 * Screen.getPrimary().getVisualBounds()（系统已排除任务栏区域），还原时恢复记录的原 bounds。
 */
public class TitleBar extends HBox {

    private final Stage stage;
    private double dragX;
    private double dragY;
    private boolean maxed;          // 手动最大化状态（自管理，不依赖系统窗口状态）
    private Rectangle2D restore;    // 最大化前的窗口 bounds（null=未最大化）
    private final Button max;

    public TitleBar(Stage stage, Label modelLabel,
                    Runnable openSettings, Runnable confirmClose) {
        this.stage = stage;
        this.modelLabel = modelLabel; // 设置窗关闭后 MainWindow 刷新顶部模型名用
        getStyleClass().add("topbar");
        setSpacing(10);

        Label app = new Label("minion");
        app.getStyleClass().add("topbar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS); // 原页签区弹性占位：模型名与右侧按钮之间留白

        Button gear = new Button();
        gear.setGraphic(sized(IconFactory.settings()));
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> openSettings.run());

        Button min = new Button();
        min.setGraphic(sized(IconFactory.remove()));
        min.getStyleClass().add("btn-ghost");
        min.setOnAction(e -> stage.setIconified(true));

        max = new Button();
        max.setGraphic(maximizeIcon(maxed));
        max.getStyleClass().add("btn-ghost");
        max.setOnAction(e -> toggleMaximize()); // 手动最大化（不挡任务栏）

        Button close = new Button();
        close.setGraphic(sized(IconFactory.close()));
        close.getStyleClass().add("btn-close");
        close.setOnAction(e -> confirmClose.run());

        getChildren().addAll(app, modelLabel, spacer, gear, min, max, close);

        // 拖动移动窗口（记录按下偏移，拖拽按屏幕坐标差值移动）
        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
        // 双击标题栏空白处切换最大化：只认标题栏自身与其两个 Label（应用名/模型名）
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2
                    && (e.getTarget() == this || e.getTarget() == app || e.getTarget() == modelLabel)) {
                toggleMaximize();
            }
        });
    }

    /** 手动最大化目标 bounds：直接使用可视区（Windows 已排除任务栏；本方法独立成静态以便单测） */
    static Rectangle2D maxBounds(Rectangle2D visual) {
        return visual;
    }

    /** 标题栏图标：24 viewport 缩放到 14px 显示 */
    private static Node sized(SVGPath icon) {
        IconFactory.size(icon, 14);
        return icon;
    }

    /** 最大化/还原图标（maxed=true 显示还原双框，否则最大化方框） */
    private static Node maximizeIcon(boolean maxed) {
        return sized(maxed ? IconFactory.filterNone() : IconFactory.cropSquare());
    }

    /** 切换最大化：未最大化 → 记录当前 bounds 并移动到可视区；已最大化 → 还原记录 */
    private void toggleMaximize() {
        if (maxed) {
            stage.setX(restore.getMinX());
            stage.setY(restore.getMinY());
            stage.setWidth(restore.getWidth());
            stage.setHeight(restore.getHeight());
        } else {
            restore = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            Rectangle2D vb = maxBounds(Screen.getPrimary().getVisualBounds());
            stage.setX(vb.getMinX());
            stage.setY(vb.getMinY());
            stage.setWidth(vb.getWidth());
            stage.setHeight(vb.getHeight());
        }
        maxed = !maxed;
        max.setGraphic(maximizeIcon(maxed));
    }

    /** 模型标签（MainWindow 设置窗关闭后刷新顶部模型名用） */
    public Label modelLabel() { return modelLabel; }

    private final Label modelLabel;
}
