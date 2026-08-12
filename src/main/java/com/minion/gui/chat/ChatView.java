package com.minion.gui.chat;

import com.minion.gui.session.EventList;
import com.minion.gui.session.EventList.Ev;
import com.minion.gui.session.SessionHandle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
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

    public ChatView(EventList events, SessionHandle handle) {
        this.events = events;
        this.handle = handle;
        getStyleClass().add("panel-dark");
        setSpacing(8);
        setStyle("-fx-padding: 16;");
        clear();
    }

    public static ChatView forSession(SessionHandle h) {
        return new ChatView(h.controller.eventList(), h);
    }

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
                getChildren().add(l);
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
                VBox card = new VBox(4);
                card.getStyleClass().add("card");
                Label name = new Label("🔧 " + e.text);
                name.getStyleClass().add("msg-thinking");
                Label detail = new Label(shorten(e.data == null ? "{}" : e.data.toString(), 120));
                detail.getStyleClass().add("msg-thinking");
                card.getChildren().addAll(name, detail);
                getChildren().add(card);
                break;
            }
            case TOOL_RESULT: {
                String data = e.data == null ? "" : e.data.toString();
                Label l = new Label(data.startsWith("ok") ? "✅ " + e.text + " 成功" : "❌ " + e.text + " 失败");
                l.getStyleClass().add("msg-thinking");
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

    private Node hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("msg-thinking");
        return l;
    }

    private Node alert(String text, String style) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add(style);
        return l;
    }

    private Node thinkingBlock() {
        Label l = new Label("思考: " + pendingThinking.toString());
        l.setWrapText(true);
        l.getStyleClass().add("msg-thinking");
        return l;
    }

    private Node assistantBlock(String md) {
        VBox box = new VBox(6);
        box.getStyleClass().add("msg-assistant");
        for (MarkdownRenderer.Block b : MarkdownRenderer.parse(md)) {
            box.getChildren().add(BlockNodeFactory.create(b));
        }
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

    private boolean isStreaming(Node n) {
        return (n instanceof VBox && ((VBox) n).getStyleClass().contains("msg-assistant"))
                || (n instanceof Label && ((Label) n).getStyleClass().contains("msg-thinking"));
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
