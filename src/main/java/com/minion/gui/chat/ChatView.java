package com.minion.gui.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.gui.session.EventList;
import com.minion.gui.session.EventList.Ev;
import com.minion.gui.session.SessionHandle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话消息区（纯控制台输出流）：订阅 EventList（事件来自后台线程，Listener 内 Platform.runLater 包装）。
 * 每条消息 = HBox（彩色加粗标签 Label + 白色正文 MessageTextArea），段间无缝紧贴（spacing 0），
 * 整区背景 #121314（.panel-dark）铺满正文窗口（配合 ScrollPane fitToHeight）；
 * 正文 TextArea 高度自适应（MessageTextArea）内容全部平铺、无内部滚动条；
 * 段内原生拖选/Ctrl+C/右键复制。
 * 流式身份三值 StreamKind：THINK/REPLY 末段就地更新正文，NONE 静态行（输入/工具/系统等）永不参与就地更新。
 */
public class ChatView extends VBox {

    /** 空会话占位文本（只读 TextArea 不显示 promptText，用文本代替） */
    private static final String EMPTY_HINT = "输入消息开始新的会话";

    private final EventList events;
    private final SessionHandle handle;
    private final StringBuilder pendingContent = new StringBuilder();
    private final StringBuilder pendingThinking = new StringBuilder();

    /** 用户消息到达时的"滚动到底"回调（MainWindow 注入：强制贴底 + 布局完成后置底） */
    private Runnable scrollBottomRequest;

    /** MainWindow 注入：USER_MESSAGE 事件时请求滚动到底 */
    public void setScrollBottomRequest(Runnable r) { this.scrollBottomRequest = r; }

    /** 流身份：THINK=思考流、REPLY=回复流（流式就地更新末段正文），NONE=静态行（永不参与就地更新） */
    private enum StreamKind { THINK, REPLY, NONE }

    /** 消息段：彩色加粗标签 + 白色正文 + 流身份 kind（THINK/REPLY 就地更新，NONE 静态行永不覆盖） */
    private static class Seg {
        final Label tag;
        final MessageTextArea body;
        final StreamKind kind;
        Seg(String tagText, String tagColorClass, String text, StreamKind kind) {
            tag = new Label(tagText);
            tag.getStyleClass().addAll("log-tag", tagColorClass);
            body = new MessageTextArea(text);
            body.getStyleClass().add("log-body");
            HBox.setHgrow(body, Priority.ALWAYS); // 正文吃满剩余宽度，wrap 换行正常
            this.kind = kind;
        }
    }

    private final List<Seg> segs = new ArrayList<Seg>();
    private boolean empty = true; // 无任何消息段（仍显示占位提示）

    public ChatView(EventList events, SessionHandle handle) {
        this.events = events;
        this.handle = handle;
        getStyleClass().add("panel-dark"); // 背景 rgb(18,19,20)，随 ScrollPane fitToHeight 铺满正文窗口
        setSpacing(0); // 段间无缝紧贴（控制台连续输出）
        setStyle("-fx-padding: 16 16 12 16;"); // 底部 12px + 右栏 VBox spacing 8 = 距输入框约 1 行
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
        segs.clear();
        pendingContent.setLength(0);
        pendingThinking.setLength(0);
        empty = true;
        getChildren().add(hint());
    }

    private void onEventFx(Ev e) {
        switch (e.kind) {
            case USER_MESSAGE:
                append("【输入】", "log-input", e.text, StreamKind.NONE);
                // 新轮次开始：重置上一轮流式缓冲，防止与下一轮内容拼接（评审 I-1）
                pendingContent.setLength(0);
                pendingThinking.setLength(0);
                if (scrollBottomRequest != null) scrollBottomRequest.run(); // 发送消息后强制滚动到底
                break;
            case USER_SUPPLEMENT:
                append("【输入】", "log-input", e.text, StreamKind.NONE);
                break;
            case THINKING:
                pendingThinking.append(e.text);
                stream("【思考】", "log-think", pendingThinking.toString(), StreamKind.THINK);
                break;
            case CONTENT:
                pendingContent.append(e.text);
                // 纯文本展示（Label 不可选问题之解）：markdown 展平去语法记号，段内原生拖选复制
                stream("【回复】", "log-reply", MarkdownRenderer.toPlainText(pendingContent.toString()), StreamKind.REPLY);
                break;
            case TOOL_CALL: {
                String body = "ask_user".equals(e.text)
                        ? "❓ 模型向你提问\n" + askQuestionOf(e.data)
                        : "🔧 " + e.text + "\n" + shorten(e.data == null ? "{}" : e.data.toString(), 120);
                append("【工具】", "log-tool", body, StreamKind.NONE);
                break;
            }
            case TOOL_RESULT: {
                String data = e.data == null ? "" : e.data.toString();
                boolean ok = data.startsWith("ok");
                if (ok) append("【工具】", "log-tool", "✅ " + e.text + " 成功", StreamKind.NONE);
                else append("【系统】", "log-error", "❌ " + e.text + " 失败", StreamKind.NONE);
                break;
            }
            case ERROR:
                append("【系统】", "log-error", e.text, StreamKind.NONE);
                break;
            case WARNING:
                append("【系统】", "log-warn", e.text, StreamKind.NONE);
                break;
            case STATS:
                append("【系统】", "log-sys", e.text, StreamKind.NONE);
                break;
            case SYSTEM: // 斜杠命令结果等 GUI 本地事件（不入 LLM 历史）
                append("【系统】", "log-sys", e.text, StreamKind.NONE);
                break;
            case SUB_AGENT_START:
                append("【工具】", "log-tool", "▶ 子任务: " + e.text, StreamKind.NONE);
                break;
            case SUB_AGENT_DONE:
                append("【工具】", "log-tool", "✓ 子任务完成: " + e.text, StreamKind.NONE);
                break;
            default:
                break;
        }
    }

    /** 系统行（错误横幅等，MainWindow.showError 入口） */
    public void appendSystemLine(String text) {
        append("【系统】", "log-error", text, StreamKind.NONE);
    }

    private Node hint() {
        MessageTextArea ta = new MessageTextArea(EMPTY_HINT);
        ta.getStyleClass().add("log-sys");
        return ta;
    }

    /** 追加一段控制台输出（首段先清掉占位提示；kind 仅记录流身份，NONE 静态行恒新起一段） */
    private void append(String tagText, String tagColorClass, String text, StreamKind kind) {
        if (empty) {
            getChildren().clear();
            empty = false;
        }
        Seg seg = new Seg(tagText, tagColorClass, text, kind);
        segs.add(seg);
        getChildren().add(new HBox(seg.tag, seg.body));
    }

    /** 流式增量：末段是同一流（THINK/REPLY）→ 就地更新正文不重建节点；NONE 静态行永不参与就地更新，恒新起一段 */
    private void stream(String tagText, String tagColorClass, String text, StreamKind kind) {
        Seg last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
        if (last != null && last.kind == kind && kind != StreamKind.NONE) {
            last.body.setStreamText(text);
            return;
        }
        append(tagText, tagColorClass, text, kind);
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
