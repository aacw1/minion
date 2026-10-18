package com.minion.gui.chat;

import com.minion.gui.icon.IconFactory;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** 可折叠文本段：摘要行（可点击切换）+ 内容体（MessageTextArea 高度自适应）。
 *  内容 ≥COLLAPSE_THRESHOLD 字符时默认折叠，短内容默认展开；均可手动点击切换。
 *  折叠态内容体不参与布局（managed=false），滚动只由外层 ScrollPane 统一负责。
 *  摘要行 = [chevron 图标][摘要节点][收起/展开（N 字符）]，chevron 随折叠态切换图形（SVG，不依赖字体）。 */
public class CollapsibleText extends VBox {

    /** 默认折叠阈值：内容长度 ≥ 此值默认折叠（常量可调，不设设置项） */
    public static final int COLLAPSE_THRESHOLD = 500;

    private final MessageTextArea content;
    private final HBox toggle;          // 摘要行（可点击）
    private final SVGPath chevron;      // 折叠态指示（expand_more/chevron_right）
    private final Label state;          // 「收起」/「展开（N 字符）」
    private final Node summaryNode;     // 摘要（String 构造时 = Label）
    private String text; // 流式思考段需更新，不可 final
    private boolean expanded;

    public CollapsibleText(String summary, String text, boolean defaultExpanded) {
        this(new Label(summary == null ? "" : summary), text, defaultExpanded);
    }

    public CollapsibleText(Node summary, String text, boolean defaultExpanded) {
        this.summaryNode = summary;
        this.text = text == null ? "" : text;
        this.expanded = defaultExpanded;
        getStyleClass().add("log-collapsible");
        setSpacing(2);

        chevron = new SVGPath();
        chevron.getStyleClass().add("icon-chevron");
        IconFactory.size(chevron, 12);
        state = new Label();
        state.getStyleClass().add("log-collapse-toggle");
        toggle = new HBox(4);
        toggle.getStyleClass().add("log-collapse-toggle");
        toggle.getChildren().addAll(chevron, summaryNode, state);
        toggle.setOnMouseClicked(e -> {
            if (text != null && !text.trim().isEmpty()) setExpanded(!expanded);
        });

        content = new MessageTextArea("");
        content.getStyleClass().add("log-body");

        // 折叠态内容体不挂载（懒挂载）：折叠段占长会话多数，每段 TextArea skin 约 3ms，
        // 挂载即创建 skin → 首次布局 200 段可达 800ms。展开时才 add（见 update）。
        getChildren().add(toggle);
        update();
    }

    /** 内容体（焦点治理等需要直接访问内部 TextArea 的场景） */
    public MessageTextArea contentArea() { return content; }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean v) {
        expanded = v;
        update();
    }

    /** 流式内容更新（思考段）：更新正文并强制展开——流式过程内容持续增长，
     *  折叠会遮挡实时输出；定稿折叠由 finalizeLength 按最终长度决定 */
    public void setStreamText(String t) {
        this.text = t == null ? "" : t;
        setExpanded(true); // 先展开：update() 把最新全文写入内容体（折叠态不得持有文本）
    }

    /** 内容定稿：按最终长度决定折叠态（≥阈值折叠、短内容展开），流式结束后调用一次 */
    public void finalizeLength() {
        setExpanded(!shouldCollapse(text));
    }

    private void update() {
        boolean hasText = text != null && !text.trim().isEmpty();
        // 折叠态内容体不参与布局（managed=false）但会被 JavaFX 皮肤布局（宽度 0）；
        // 折叠期间绝不能持有文本，否则宽 0 换行会让 JavaFX 8 文本引擎越界（详见类说明与设计文档）
        String want = contentText(expanded, hasText, text);
        if (!want.equals(content.getText())) content.setDisplayText(want);
        // 折叠懒挂载：不展开就不进场景图（不创建 skin，不参与布局/渲染）；展开时按需挂到摘要行之后
        boolean attach = shouldAttachContent(expanded, hasText);
        if (attach) {
            content.setVisible(true);
            content.setManaged(true);
            if (!getChildren().contains(content)) getChildren().add(content);
        } else {
            content.setVisible(false);
            content.setManaged(false);
            getChildren().remove(content);
        }
        chevron.setVisible(hasText);
        chevron.setManaged(hasText);
        state.setVisible(hasText);
        state.setManaged(hasText);
        if (hasText) {
            chevron.setContent(expanded ? IconFactory.CHEVRON_DOWN_PATH : IconFactory.CHEVRON_RIGHT_PATH);
            state.setText(expanded ? "收起" : "展开（" + text.length() + " 字符）");
        }
    }

    /** 内容体应持有的文本（纯逻辑可单测）：只有展开且确有内容时才交给 TextArea，否则一律空串。
     *  折叠态 TextArea 宽度为 0，JavaFX 8 仍对其执行皮肤布局；含特定字符组合的文本会在
     *  PrismTextLayout/TextRun.getWrapIndex 越界，异常发生在 Scene 布局 pass 中会中断整场景布局
     *  （线上实证：点击历史会话后消息区/侧栏不显示、输入框按钮重叠）。空文本不触发换行，天然免疫。 */
    static String contentText(boolean expanded, boolean hasText, String text) {
        if (!expanded || !hasText || text == null) return "";
        return text;
    }

    /** 默认折叠判定（纯逻辑可单测）：null/空不折叠 */
    public static boolean shouldCollapse(String text) {
        return text != null && text.length() >= COLLAPSE_THRESHOLD;
    }

    /** 内容体是否进入场景图（纯逻辑可单测）：仅展开且有内容时挂载——折叠态不创建 TextArea skin，
     *  长会话大量折叠段的首次布局成本从每段约 3ms 降到接近 0。折叠期间内容体文本仍保持空串
     *  （contentText 保证），不会触发 JavaFX 8 文本引擎越界。 */
    public static boolean shouldAttachContent(boolean expanded, boolean hasText) {
        return expanded && hasText;
    }
}
