package com.minion.gui.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.gui.session.EventList;
import com.minion.gui.session.EventList.Ev;
import com.minion.gui.session.SessionHandle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

/**
 * 会话消息区：订阅 EventList（事件来自后台线程，Listener 内 Platform.runLater 包装），
 * 渲染用户消息/助手消息（Markdown 流式重渲染）/思考块/工具卡片/错误横幅。
 */
public class ChatView extends VBox {

    private final EventList events;
    private final SessionHandle handle;
    private final StringBuilder pendingContent = new StringBuilder();
    private final StringBuilder pendingThinking = new StringBuilder();

    /** 用户消息到达时的"滚动到底"回调（MainWindow 注入：强制贴底 + 布局完成后置底） */
    private Runnable scrollBottomRequest;

    /** MainWindow 注入：USER_MESSAGE 事件时请求滚动到底 */
    public void setScrollBottomRequest(Runnable r) { this.scrollBottomRequest = r; }

    public ChatView(EventList events, SessionHandle handle) {
        this.events = events;
        this.handle = handle;
        getStyleClass().add("panel-dark");
        getStyleClass().add("chat-content"); // 显式 LCD 用（ScrollPane 裁剪下 JavaFX 8 默认回退灰阶 AA → 发虚）
        setSpacing(8);
        setStyle("-fx-padding: 16;");
        clear();
    }

    public static ChatView forSession(SessionHandle h) {
        return new ChatView(h.controller.eventList(), h);
    }

    /** 本视图绑定的会话句柄（MainWindow 判断「删除的是当前展示会话」用） */
    public SessionHandle handle() { return handle; }

    /** 绑定/解绑事件流：active=true 先清空，再经 EventList 同步重放存量 + 后续直通 */
    public void bind(boolean active) {
        if (active) clear(); // FX 线程调用：先清再重放，避免存量事件重复渲染
        events.setActive(active, new EventList.Listener() {
            @Override public void onEvent(Ev e) {
                Platform.runLater(() -> onEventFx(e));
            }
        });
    }

    public void clear() {
        getChildren().clear();
        pendingContent.setLength(0);
        pendingThinking.setLength(0);
        getChildren().add(hint("输入消息开始新的会话"));
    }

    private void onEventFx(Ev e) {
        switch (e.kind) {
            case USER_MESSAGE: {
                Label l = new Label(e.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-user");
                makeCopyable(l, e.text);
                getChildren().add(l);
                // 新轮次开始：重置上一轮流式缓冲，防止与下一轮内容拼接（评审 I-1）
                pendingContent.setLength(0);
                pendingThinking.setLength(0);
                if (scrollBottomRequest != null) scrollBottomRequest.run(); // 发送消息后强制滚动到底
                break;
            }
            case USER_SUPPLEMENT: {
                VBox box = new VBox(2);
                Label tag = new Label("⤒ 运行中补充");
                tag.getStyleClass().add("supplement-tag");
                Label l = new Label(e.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-user");
                makeCopyable(l, e.text);
                box.getChildren().addAll(tag, l);
                getChildren().add(box);
                break;
            }
            case THINKING:
                pendingThinking.append(e.text);
                replaceLast(thinkingBlock());
                break;
            case CONTENT:
                pendingContent.append(e.text);
                replaceLast(assistantBlock(pendingContent.toString()));
                break;
            case TOOL_CALL: {
                if ("ask_user".equals(e.text)) {
                    VBox card = new VBox(4);
                    card.getStyleClass().add("card");
                    Label name = new Label("❓ 模型向你提问");
                    name.getStyleClass().add("msg-thinking");
                    Label q = new Label(askQuestionOf(e.data));
                    q.setWrapText(true);
                    q.getStyleClass().add("msg-thinking");
                    card.getChildren().addAll(name, q);
                    makeCopyable(card, "❓ 模型向你提问\n" + askQuestionOf(e.data));
                    getChildren().add(card);
                } else {
                    VBox card = new VBox(4);
                    card.getStyleClass().add("card");
                    Label name = new Label("🔧 " + e.text);
                    name.getStyleClass().add("msg-thinking");
                    Label detail = new Label(shorten(e.data == null ? "{}" : e.data.toString(), 120));
                    detail.getStyleClass().add("msg-thinking");
                    card.getChildren().addAll(name, detail);
                    makeCopyable(card, "🔧 " + e.text + "\n" + (e.data == null ? "{}" : e.data.toString()));
                    getChildren().add(card);
                }
                break;
            }
            case TOOL_RESULT: {
                String data = e.data == null ? "" : e.data.toString();
                Label l = new Label(data.startsWith("ok") ? "✅ " + e.text + " 成功" : "❌ " + e.text + " 失败");
                l.getStyleClass().add("msg-thinking");
                makeCopyable(l, e.text);
                getChildren().add(l);
                break;
            }
            case ERROR:
                getChildren().add(alert(e.text, "msg-error"));
                break;
            case WARNING:
                getChildren().add(alert(e.text, "msg-warning"));
                break;
            case STATS:
                getChildren().add(alert(e.text, "msg-thinking"));
                break;
            case SYSTEM: // 斜杠命令结果等 GUI 本地事件（不入 LLM 历史）
                getChildren().add(alert(e.text, "msg-thinking"));
                break;
            case SUB_AGENT_START:
                getChildren().add(alert("▶ 子任务: " + e.text, "msg-thinking"));
                break;
            case SUB_AGENT_DONE:
                getChildren().add(alert("✓ 子任务完成: " + e.text, "msg-thinking"));
                break;
            default:
                break;
        }
    }

    /** 系统行（错误横幅等，MainWindow.showError 入口） */
    public void appendSystemLine(String text) {
        getChildren().add(alert(text, "msg-error"));
    }

    /** 消息节点复制能力：右键菜单「复制」+ 双击复制全文（消息用 Label/节点渲染，文本不可选中，此为其唯一复制途径）。
     *  不用 Control.setContextMenu（VBox 等非 Control 节点无此 API），统一右键事件手动弹出菜单 */
    private static void makeCopyable(Node node, final String text) {
        if (text == null || text.isEmpty()) return;
        MenuItem copy = new MenuItem("复制");
        copy.setOnAction(e -> copyToClipboard(text));
        final ContextMenu menu = new ContextMenu(copy);
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                menu.show(node, e.getScreenX(), e.getScreenY());
                e.consume();
            } else if (e.getClickCount() == 2) {
                copyToClipboard(text);
            }
        });
    }

    private static void copyToClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /** 流式节点哨兵：仅思考块与助手内容块携带，标识「可被流式增量替换」 */
    private static final Object STREAMING_MARK = new Object();

    private Node hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("msg-thinking");
        return l;
    }

    private Node alert(String text, String style) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add(style);
        makeCopyable(l, text);
        return l;
    }

    private Node thinkingBlock() {
        Label l = new Label("思考: " + pendingThinking.toString());
        l.setWrapText(true);
        l.getStyleClass().add("msg-thinking");
        l.setUserData(STREAMING_MARK); // 流式块标记：多段思考增量合并为同一块
        makeCopyable(l, "思考: " + pendingThinking.toString()); // 快照当前已流式到的内容
        return l;
    }

    private Node assistantBlock(String md) {
        VBox box = new VBox(6);
        box.getStyleClass().add("msg-assistant");
        box.setUserData(STREAMING_MARK); // 流式块标记：CONTENT 增量替换助手块
        for (MarkdownRenderer.Block b : MarkdownRenderer.parse(md)) {
            box.getChildren().add(BlockNodeFactory.create(b));
        }
        makeCopyable(box, md); // 复制 markdown 原文（当前已渲染部分）
        return box;
    }

    /** 流式增量：替换最后一块（思考或助手消息），非流式事件直接追加 */
    private void replaceLast(Node block) {
        if (getChildren().isEmpty()) {
            getChildren().add(block);
            return;
        }
        Node last = getChildren().get(getChildren().size() - 1);
        if (isStreaming(last)) {
            getChildren().set(getChildren().size() - 1, block);
        } else {
            getChildren().add(block);
        }
    }

    /** 仅带哨兵标记的节点才是流式节点；工具结果/统计/子任务等横幅不标记，永不参与流式替换（评审 I-2） */
    private boolean isStreaming(Node n) {
        return n.getUserData() == STREAMING_MARK;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** ask_user 工具调用的 question 参数（解析失败回空串） */
    private static String askQuestionOf(Object data) {
        try {
            JsonObject o = JsonParser.parseString(data == null ? "{}" : data.toString())
                    .getAsJsonObject();
            return o.has("question") ? o.get("question").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
