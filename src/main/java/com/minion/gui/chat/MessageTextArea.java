package com.minion.gui.chat;

import javafx.geometry.Insets;
import javafx.scene.control.TextArea;
import javafx.scene.text.Text;

/**
 * 消息只读 TextArea：原生支持鼠标拖选/Ctrl+C/双击选词/右键菜单（替代 Label 的右键+双击复制）。
 *
 * 高度随内容自适应（手动管理，不依赖 computePrefHeight——JavaFX 8 中 TextArea 的
 * prefHeight 计算会被 skin 内部机制覆盖，实测出现段内滚动条）：
 * 监听文本与宽度变化，用同字体 Text 测量器按实际内容宽度重算高度并 setPrefHeight，
 * 使 TextArea 高度恒等于内容高度——内容全部平铺展开，无内部滚动条，
 * 滚动只由外层 ScrollPane 统一负责。
 */
public class MessageTextArea extends TextArea {

    /** 测量器：与自身同字体，wrap 宽度 = 内容区宽度，layoutBounds 高 = 文本行高 */
    private final Text measurer = new Text();

    public MessageTextArea(String text) {
        super(text);
        setEditable(false);
        setWrapText(true);
        getStyleClass().add("msg-textarea");
        // 文本或宽度变化 → 重算高度；首次布局前宽度未定则跳过，宽度监听兜底触发
        textProperty().addListener((obs, ov, nv) -> relayout());
        widthProperty().addListener((obs, ov, nv) -> relayout());
    }

    /** 流式增量更新：就地 setText，不重建节点（不打断用户操作/选中态） */
    public void setStreamText(String text) {
        setText(text);
    }

    /** 按当前内容与宽度重算 prefHeight（内容全部展开，无内部滚动条） */
    private void relayout() {
        double width = getWidth();
        if (width <= 0) return; // 首次布局前宽度未定，宽度变化监听会再触发
        Insets pad = getPadding();
        measurer.setFont(getFont());
        measurer.setWrappingWidth(Math.max(width - pad.getLeft() - pad.getRight(), 1));
        setPrefHeight(measurer.getLayoutBounds().getHeight() + pad.getTop() + pad.getBottom() + 4);
    }
}
