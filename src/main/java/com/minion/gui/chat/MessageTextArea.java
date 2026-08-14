package com.minion.gui.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.text.Text;

/**
 * 消息只读 TextArea：原生支持鼠标拖选/Ctrl+C/双击选词/右键菜单（替代 Label 的右键+双击复制）。
 *
 * 高度随内容自适应（手动管理），内容全部平铺展开、无内部滚动条，滚动只由外层 ScrollPane 统一负责。
 * 三道防线（各自应对一个已实证的布局坑）：
 *
 * 1. 构造即设显式 prefHeight（防 Parent.prefHeightCache 缓存旧值）：prefHeightProperty 永不为
 *    USE_COMPUTED_SIZE，布局早期 Region.prefHeight(-1) 就不会经 Parent.prefHeightCache 缓存
 *    TextAreaSkin 的默认 10 行高度；否则缓存永不失效 → 长消息被 VBox 按旧值压缩（探针实证）。
 * 2. 保守测量（防内部滚动条自反馈死锁）：measurer 的 wrap 宽度预留内部滚动条槽位（边框+滚动条，
 *    实测 12px）——TextArea 内部文本 wrap 在滚动条出现时会变窄、行数变多、内容变高，
 *    若按全宽测量则高度低估 → 滚动条出现 → wrap 更窄 → 死锁。保守窄宽测量使高度恒 ≥ 内容。
 * 3. 布局后精校（消除保守测量的空隙）：双 runLater 在布局 pass 后读内部 Text 节点
 *    （TextAreaSkin 实际渲染）的真实高度，setPrefHeight 精确收敛；临界 wrap 行差/行高差完全消除。
 */
public class MessageTextArea extends TextArea {

    /** 高度余量：补偿 TextArea skin 内部偏差（目验校准点：低估→内部滚动条回归，高估→文本下方小空隙） */
    private static final double HEIGHT_FUDGE = 4;

    /** 宽度未定（构造/首次布局前）时的测量兜底；布局后宽度监听会用真实宽度更新，此值只影响早期临时高度 */
    private static final double FALLBACK_WIDTH = 600;

    /** 内部文本 wrap 比控件内容区窄的固定槽位（内部 ScrollPane 边框 1px×2 + 垂直滚动条 10px，实测 12px@modena/主题） */
    private static final double INNER_SLOT = 12;

    /** 测量器：与自身同字体，wrap 宽度 = 内容区宽度 - 槽位，layoutBounds 高 = 文本行高 */
    private final Text measurer = new Text();

    /** 布局后精校已调度（节流：流式高频 setText 只校正一次） */
    private boolean correctScheduled = false;

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
        // 绕过 Parent.prefHeightCache 缓存 TextAreaSkin 默认 10 行高度的旧值（见类注释防线 1）
        relayout();
    }

    /** 流式增量更新：就地 setText，不重建节点（不打断用户操作/选中态） */
    public void setStreamText(String text) {
        setText(text);
    }

    /** 高度自适应：保守测量（按滚动条槽位窄 wrap）→ 高度恒 ≥ 内容 → 内部滚动条不出现；布局后精校消除空隙 */
    private void relayout() {
        double width = getWidth();
        if (width <= 0) width = FALLBACK_WIDTH; // 构造/首次布局前宽度未定：兜底测量，稍后宽度监听用真实宽度更新
        Insets pad = getPadding();
        measurer.setFont(getFont());
        measurer.setText(getText()); // 关键：测量器必须同步当前文本，否则一直按空文本测出 1 行高
        measurer.setWrappingWidth(Math.max(width - pad.getLeft() - pad.getRight() - INNER_SLOT, 1));
        setPrefHeight(measurer.getLayoutBounds().getHeight() + pad.getTop() + pad.getBottom() + HEIGHT_FUDGE);
        scheduleCorrect(); // 布局后按 skin 实际渲染精校（消除保守测量的空隙/临界 wrap 行差）
    }

    /** 布局后精校：读内部 Text 节点（skin 渲染的真实内容高度）。双 runLater 保证在布局 pass 之后执行 */
    private void scheduleCorrect() {
        if (correctScheduled) return; // 节流：流式高频变化只调度一次
        correctScheduled = true;
        Platform.runLater(() -> Platform.runLater(() -> {
            correctScheduled = false;
            Text inner = innerText();
            if (inner == null) return; // 未挂载/未渲染：宽度/文本监听会再触发
            Insets pad = getPadding();
            double exact = inner.getLayoutBounds().getHeight() + pad.getTop() + pad.getBottom() + HEIGHT_FUDGE;
            if (Math.abs(exact - getPrefHeight()) > 0.5) {
                setPrefHeight(exact);
                scheduleCorrect(); // 高度变化可能改变滚动条状态（wrap 宽度随之变化）→ 再校正一次直至收敛
            }
        }));
    }

    /** 内部文本节点：TextAreaSkin 渲染内容的 TextAreaView（样式类 text），与 measurer 同字体同文本 */
    private Text innerText() {
        for (Node n : lookupAll(".text")) {
            if (n instanceof Text) return (Text) n;
        }
        return null;
    }
}
