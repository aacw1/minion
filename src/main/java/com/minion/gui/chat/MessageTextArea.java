package com.minion.gui.chat;

import javafx.geometry.Insets;
import javafx.scene.control.TextArea;
import javafx.scene.text.Text;

/**
 * 消息只读 TextArea：原生支持鼠标拖选/Ctrl+C/双击选词/右键菜单（替代 Label 的右键+双击复制）。
 *
 * 高度随内容自适应（手动管理）：监听文本与宽度变化，用同字体 Text 测量器按实际内容宽度
 * 重算高度并 setPrefHeight，使 TextArea 高度恒等于内容高度——内容全部平铺展开，无内部滚动条，
 * 滚动只由外层 ScrollPane 统一负责。
 *
 * 关键（防回归）：构造时立即测量并设置显式 prefHeight（宽度未定时用兜底宽度 FALLBACK_WIDTH）。
 * 否则 prefHeightProperty 保持 USE_COMPUTED_SIZE（-1），布局早期 Region.prefHeight(-1) 会经
 * Parent.prefHeightCache 缓存 TextAreaSkin 的默认 10 行高度（约 197px）；此后 setPrefHeight 的
 * requestLayout 在父 HBox performingLayout 期间不传播，缓存永不失效 → 长消息被 VBox 按旧缓存
 * 高度压缩，段内出现滚动条（探针实证：重置 HBox.prefHeightCache 后 prefHeight(-1) 立即恢复正确值）。
 */
public class MessageTextArea extends TextArea {

    /** 高度余量：补偿 TextArea skin 内部偏差（目验校准点：低估→内部滚动条回归，高估→文本下方小空隙） */
    private static final double HEIGHT_FUDGE = 4;

    /** 宽度未定（构造/首次布局前）时的测量兜底；布局后宽度监听会用真实宽度更新，此值只影响早期临时高度 */
    private static final double FALLBACK_WIDTH = 600;

    /** 测量器：与自身同字体，wrap 宽度 = 内容区宽度，layoutBounds 高 = 文本行高 */
    private final Text measurer = new Text();

    public MessageTextArea(String text) {
        super(text);
        setEditable(false);
        setWrapText(true);
        getStyleClass().add("msg-textarea");
        // 文本/宽度/全局字号变化 → 重算高度；首次布局前宽度未定则跳过，宽度监听兜底触发
        textProperty().addListener((obs, ov, nv) -> relayout());
        widthProperty().addListener((obs, ov, nv) -> relayout());
        fontProperty().addListener((obs, ov, nv) -> relayout());
        // 构造即设置显式 prefHeight：prefHeightProperty 永不为 USE_COMPUTED_SIZE，
        // 绕过 Parent.prefHeightCache 缓存 TextAreaSkin 默认 10 行高度的旧值（见类注释）
        relayout();
    }

    /** 流式增量更新：就地 setText，不重建节点（不打断用户操作/选中态） */
    public void setStreamText(String text) {
        setText(text);
    }

    /** 按当前内容与宽度重算 prefHeight（内容全部展开，无内部滚动条） */
    private void relayout() {
        double width = getWidth();
        if (width <= 0) width = FALLBACK_WIDTH; // 构造/首次布局前宽度未定：兜底测量，稍后宽度监听用真实宽度更新
        Insets pad = getPadding();
        measurer.setFont(getFont());
        measurer.setText(getText()); // 关键：测量器必须同步当前文本，否则一直按空文本测出 1 行高
        measurer.setWrappingWidth(Math.max(width - pad.getLeft() - pad.getRight(), 1));
        setPrefHeight(measurer.getLayoutBounds().getHeight() + pad.getTop() + pad.getBottom() + HEIGHT_FUDGE);
    }
}
