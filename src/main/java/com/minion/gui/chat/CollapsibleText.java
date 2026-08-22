package com.minion.gui.chat;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 可折叠文本段：摘要行（可点击切换）+ 内容体（MessageTextArea 高度自适应）。
 *  内容 ≥COLLAPSE_THRESHOLD 字符时默认折叠，短内容默认展开；均可手动点击切换。
 *  折叠态内容体不参与布局（managed=false），滚动只由外层 ScrollPane 统一负责。 */
public class CollapsibleText extends VBox {

    /** 默认折叠阈值：内容长度 ≥ 此值默认折叠（常量可调，不设设置项） */
    public static final int COLLAPSE_THRESHOLD = 500;

    private final MessageTextArea content;
    private final Label toggle;
    private final String summary;
    private final String text;
    private boolean expanded;

    public CollapsibleText(String summary, String text, boolean defaultExpanded) {
        this.summary = summary == null ? "" : summary;
        this.text = text == null ? "" : text;
        this.expanded = defaultExpanded;
        getStyleClass().add("log-collapsible");
        setSpacing(2);

        toggle = new Label();
        toggle.getStyleClass().add("log-collapse-toggle");
        toggle.setOnMouseClicked(e -> setExpanded(!expanded));

        content = new MessageTextArea(this.text);
        content.getStyleClass().addAll("log-body", "log-collapse-content");
        HBox.setHgrow(content, Priority.ALWAYS);

        getChildren().addAll(toggle, content);
        update();
    }

    /** 内容体（焦点治理等需要直接访问内部 TextArea 的场景） */
    public MessageTextArea contentArea() { return content; }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean v) {
        expanded = v;
        update();
    }

    private void update() {
        toggle.setText(expanded
                ? summary + "   ▾ 收起"
                : summary + "   ▸ 展开（" + text.length() + " 字符）");
        content.setVisible(expanded);
        content.setManaged(expanded);
    }

    /** 默认折叠判定（纯逻辑可单测）：null/空不折叠 */
    public static boolean shouldCollapse(String text) {
        return text != null && text.length() >= COLLAPSE_THRESHOLD;
    }
}
