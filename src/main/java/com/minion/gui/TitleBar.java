package com.minion.gui;

import com.minion.gui.icon.IconFactory;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 自绘标题栏（无边框窗口）：品牌标 + 应用名 | 留白 | 模型 pill | 设置 | 最小化 | 最大化 | 关闭。
 * 拖动标题栏移动窗口；双击空白区切换最大化。关闭走 confirmClose（与系统关闭共用退出确认）。
 * 模型 pill 只做静态展示（绿点 + 模型名），不新增下拉/点击入口；模型切换仍走设置窗。
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
        setSpacing(8);

        StackPane logo = new StackPane();
        logo.getStyleClass().add("brand-mark");
        SVGPath spark = IconFactory.spark();
        IconFactory.size(spark, 15);
        logo.getChildren().add(spark);

        Label app = new Label("minion");
        app.getStyleClass().add("brand-name");

        HBox brand = new HBox(8, logo, app);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("tb-brand");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        modelLabel.getStyleClass().add("model-name");
        Region modelDot = new Region();
        modelDot.getStyleClass().add("model-dot");
        HBox modelPill = new HBox(6, modelDot, modelLabel);
        modelPill.setAlignment(Pos.CENTER_LEFT);
        modelPill.getStyleClass().add("model-pill");
        Tooltip modelTip = new Tooltip();
        modelTip.textProperty().bind(Bindings.concat("当前模型：", modelLabel.textProperty()));
        Tooltip.install(modelPill, modelTip);

        Button gear = new Button();
        gear.setGraphic(sized(IconFactory.settings()));
        gear.getStyleClass().add("icon-btn");
        gear.setTooltip(new Tooltip("设置"));
        gear.setOnAction(e -> openSettings.run());

        Button min = new Button();
        min.setGraphic(sized(IconFactory.remove()));
        min.getStyleClass().add("icon-btn");
        min.setTooltip(new Tooltip("最小化"));
        min.setOnAction(e -> stage.setIconified(true));

        max = new Button();
        max.setGraphic(maximizeIcon(maxed));
        max.getStyleClass().add("icon-btn");
        max.setTooltip(new Tooltip("最大化"));
        max.setOnAction(e -> toggleMaximize()); // 手动最大化（不挡任务栏）

        Button close = new Button();
        close.setGraphic(sized(IconFactory.close()));
        close.getStyleClass().addAll("icon-btn", "close");
        close.setTooltip(new Tooltip("关闭"));
        close.setOnAction(e -> confirmClose.run());

        getChildren().addAll(brand, spacer, modelPill, gear, min, max, close);

        // 拖动移动窗口（记录按下偏移，拖拽按屏幕坐标差值移动）
        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
        // 双击标题栏空白处切换最大化：只认标题栏自身与其两个文本节点（品牌名/模型名）
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
        max.setTooltip(new Tooltip(maxed ? "还原" : "最大化"));
    }

    /** 模型标签（MainWindow 设置窗关闭后刷新顶部模型名用） */
    public Label modelLabel() { return modelLabel; }

    private final Label modelLabel;
}
