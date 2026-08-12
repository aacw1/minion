package com.minion.gui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** 主窗口：顶部栏 / 左侧 1/5（上会话下工作空间）/ 右侧 4/5（消息区 + 输入区） */
public class MainWindow {

    private final Stage stage;

    public MainWindow(Stage stage) { this.stage = stage; }

    public void show() {
        stage.setTitle("minion");
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");

        // 顶部栏：标识 | 当前模型 | 会话页签区 | ⚙
        HBox topbar = new HBox(10);
        topbar.getStyleClass().add("topbar");
        Label title = new Label("minion");
        title.getStyleClass().add("topbar-title");
        Label modelLabel = new Label("模型: " + MinionApp.models().currentName());
        modelLabel.getStyleClass().add("topbar-model");
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        HBox.setHgrow(tabs, Priority.ALWAYS);
        Button gear = new Button("⚙");
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> { }); // Task 13 模型弹窗
        topbar.getChildren().addAll(title, modelLabel, tabs, gear);

        // 左侧 1/5：上会话管理 / 下工作空间管理（Task 9/12 填充）
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        sidebar.setPrefWidth(220);
        Label sessionTitle = new Label("会话管理");
        sessionTitle.getStyleClass().add("section-title");
        Region sessionListPlaceholder = new Region(); // Task 9
        VBox.setVgrow(sessionListPlaceholder, Priority.ALWAYS);
        Label wsTitle = new Label("工作空间");
        wsTitle.getStyleClass().add("section-title");
        Region wsListPlaceholder = new Region();      // Task 12
        sidebar.getChildren().addAll(sessionTitle, sessionListPlaceholder, wsTitle, wsListPlaceholder);

        // 右侧 4/5：消息区 + 输入区占位（Task 10/11 填充）
        VBox right = new VBox(8);
        right.getStyleClass().add("panel-dark");
        Region chatPlaceholder = new Region();        // Task 10
        VBox.setVgrow(chatPlaceholder, Priority.ALWAYS);
        Region inputPlaceholder = new Region();       // Task 11
        right.getChildren().addAll(chatPlaceholder, inputPlaceholder);

        root.setTop(topbar);
        root.setLeft(sidebar);
        root.setCenter(right);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                getClass().getResource("/theme/theme.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }
}
