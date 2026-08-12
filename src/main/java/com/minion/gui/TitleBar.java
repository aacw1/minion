package com.minion.gui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

/**
 * 自绘标题栏（无边框窗口）：应用名 | 模型标签 | 页签区（弹性）| ⚙ 设置 | — 最小化 | □ 最大化 | ✕ 关闭。
 * 拖动标题栏移动窗口；双击空白区切换最大化。关闭走 confirmClose（与系统关闭共用退出确认）。
 */
public class TitleBar extends HBox {

    private final Stage stage;
    private double dragX;
    private double dragY;

    public TitleBar(Stage stage, Label modelLabel, Node center,
                    Runnable openSettings, Runnable confirmClose) {
        this.stage = stage;
        this.modelLabel = modelLabel; // 设置窗关闭后 MainWindow 刷新顶部模型名用
        getStyleClass().add("topbar");
        setSpacing(10);

        Label app = new Label("minion");
        app.getStyleClass().add("topbar-title");
        if (center != null) HBox.setHgrow(center, Priority.ALWAYS);

        Button gear = new Button("⚙");
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> openSettings.run());

        Button min = new Button("—");
        min.getStyleClass().add("btn-ghost");
        min.setOnAction(e -> stage.setIconified(true));

        final Button max = new Button("□");
        max.getStyleClass().add("btn-ghost");
        max.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        // 图标与状态单向同步：双击标题栏/OS 快捷键等路径最大化也能刷新（按钮点击经 setMaximized 触发同一监听）
        stage.maximizedProperty().addListener((o, ov, nv) -> max.setText(nv ? "❐" : "□"));

        Button close = new Button("✕");
        close.getStyleClass().add("btn-close");
        close.setOnAction(e -> confirmClose.run());

        getChildren().addAll(app, modelLabel, center, gear, min, max, close);

        // 拖动移动窗口（记录按下偏移，拖拽按屏幕坐标差值移动）
        setOnMousePressed(e -> {
            dragX = e.getScreenX() - stage.getX();
            dragY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
        });
        // 双击标题栏空白处切换最大化：只认标题栏自身与其两个 Label（应用名/模型名）；
        // 页签文本也是 Label（TabHeaderSkin 内部），用 == 收窄排除，防双击页签误触发最大化
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2
                    && (e.getTarget() == this || e.getTarget() == app || e.getTarget() == modelLabel)) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    /** 模型标签（MainWindow 设置窗关闭后刷新顶部模型名用） */
    public Label modelLabel() { return modelLabel; }

    private final Label modelLabel;
}
