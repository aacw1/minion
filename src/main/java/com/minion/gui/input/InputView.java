package com.minion.gui.input;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** 底部输入区：4/9 宽居中大框（左 TextArea 右按钮、中间竖分割线）+ /命令与 @文件补全弹层。
 *  按钮语义：上箭头=发送/补充/回答、变淡箭头=空输入或等待回答、方块=终止（提问挂起时改 Esc 终止）。
 *  运行中 + 有内容 → 补充；等待回答 + 有内容 → 回答；运行中 + 空 → 终止。 */
public class InputView extends VBox {

    /** 按钮模式：图标/透明度/背景类/动作的判定依据（ANSWER_DIM=提问挂起且空输入，变淡箭头等待输入回答） */
    enum BtnMode { SEND, SEND_DIM, SUPPLEMENT, ANSWER, ANSWER_DIM, STOP }

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button();
    private final SVGPath arrowIcon = new SVGPath();
    private final SVGPath stopIcon = new SVGPath();
    private final SuggestionPopup popup = new SuggestionPopup();
    private final FileSuggester fileSuggester = new FileSuggester();
    private HBox frame; // 大框（弹层锚点）
    private CompletionParser.Token lastToken; // 弹层可见时待替换的词
    private String suppressed; // 刚确认插入的补全文本：当前词与之完全一致时不再弹层（防确认后回弹干扰）
    private volatile SessionHandle current;
    // FX 线程缓存的状态（bindSession/onRunningChanged/onAskChanged 维护）
    private boolean running;
    private boolean askPending;
    private String askQuestion;

    public InputView(final SessionManager manager) {
        this.manager = manager;
        getStyleClass().add("panel-dark");
        setPadding(new Insets(0, 16, 24, 16)); // 顶部 0：输入框顶部贴消息区（上移半行，总高减半行 96→84）；底部 24：距正文部分底部1行

        input.getStyleClass().add("input-textarea");
        input.setWrapText(true);
        input.setPromptText("输入消息…  (@ 引用文件  / 命令  Ctrl+Enter 发送)");
        input.setPrefRowCount(2); // 加一行
        input.setMaxHeight(6 * 24);
        input.textProperty().addListener((obs, ov, nv) -> { updateButton(); onTextChanged(); });
        input.caretPositionProperty().addListener((obs, ov, nv) -> onTextChanged());
        // 弹层显示改为输入内容驱动：输入框失焦（点击聊天区/侧栏）时关闭，点击输入框本身不关
        input.focusedProperty().addListener((obs, ov, nv) -> {
            if (!nv) { popup.hide(); lastToken = null; }
        });

        // 上箭头（Claude Code 同款语义：可发送）；方块 = 终止
        arrowIcon.setContent("M12 4 L20 13 L15 13 L15 21 L9 21 L9 13 L4 13 Z");
        arrowIcon.getStyleClass().add("icon-send");
        stopIcon.setContent("M7 7 L17 7 L17 17 L7 17 Z");
        stopIcon.getStyleClass().add("icon-stop");

        sendButton.setMinSize(36, 36);
        sendButton.setPrefSize(36, 36);
        sendButton.setOnAction(e -> onAction());
        updateButton();

        // 鼠标点击弹层条目：直接插入（弹层侧回调文本，本类执行替换；根因修复：旧接线点击后无插入）
        popup.setOnConfirm(insert -> confirmInsert(insert));

        // 键盘用 capture 阶段过滤器而非 setOnKeyPressed：TextArea 默认按键行为（换行/光标移动）
        // 同为 target 内 handler，bubble 顺序不保证——行为先执行会移动光标触发 onTextChanged 重置弹层
        // 选中、Enter 插入换行关弹层，导致上下键/确认失效；过滤器先于一切 target 内 handler 执行
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onAction();
                return;
            }
            // 补全弹层优先：↑↓ 移动、Enter/Tab 确认、Esc 仅关弹层
            if (popup.isShowing()) {
                switch (e.getCode()) {
                    case UP:    popup.move(-1); e.consume(); break;
                    case DOWN:  popup.move(1);  e.consume(); break;
                    case TAB:
                    case ENTER: confirmPopup(); e.consume(); break;
                    case ESCAPE: popup.hide(); lastToken = null; e.consume(); break;
                }
                return;
            }
            // Esc：终止当前运行（提问挂起时亦可终止）
            if (e.getCode() == KeyCode.ESCAPE && current != null && running) {
                e.consume();
                manager.stop(current);
            }
        });

        // 大框：TextArea | 竖分割线 | 按钮（按钮经 VBox 垂直居中，分割线随框高拉伸）
        frame = new HBox(8);
        frame.getStyleClass().add("input-frame");
        HBox.setHgrow(input, Priority.ALWAYS);
        Region divider = new Region();
        divider.getStyleClass().add("input-divider");
        VBox buttonBox = new VBox();
        buttonBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(buttonBox, Priority.ALWAYS);
        buttonBox.getChildren().add(sendButton);
        frame.getChildren().addAll(input, divider, buttonBox);

        // 黄金比例 0.618 宽居中（占正文部分总宽度）：3 列百分比（19.1% / 61.8% / 19.1%）
        GridPane root = new GridPane();
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(19.1);
        ColumnConstraints center = new ColumnConstraints();
        center.setPercentWidth(61.8);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(19.1);
        root.getColumnConstraints().addAll(left, center, right);
        root.add(frame, 1, 0);
        getChildren().add(root);
    }

    /** 确认插入的最终文本：@ 文件补全补回 @ 前缀（FileSuggester 的 insertText 为纯路径）。
     *  纯静态可脱离 JavaFX 单测 */
    static String insertionText(CompletionParser.Mode mode, String insert) {
        return mode == CompletionParser.Mode.FILE && !insert.startsWith("@") ? "@" + insert : insert;
    }

    /** 纯静态判定（可脱离 JavaFX 单测）：运行/提问挂起/有内容 → 按钮模式。
     *  提问挂起时模型在等待回答而非忙碌，空输入显示变淡箭头而非终止方块 */
    static BtnMode buttonMode(boolean running, boolean askPending, boolean hasContent) {
        if (!running) return hasContent ? BtnMode.SEND : BtnMode.SEND_DIM;
        if (askPending) return hasContent ? BtnMode.ANSWER : BtnMode.ANSWER_DIM;
        return hasContent ? BtnMode.SUPPLEMENT : BtnMode.STOP;
    }

    /** 文本/光标变化 → 重新解析补全模式并刷新弹层（弹层异常不得打断输入，兜底隐藏） */
    private void onTextChanged() {
        try {
            CompletionParser.Token t = CompletionParser.parse(input.getText(), input.getCaretPosition());
            // 补全确认后抑制回弹：当前词与刚插入的补全文本完全一致（含 / @ 前缀）时不再弹层；
            // 词被修改后解除抑制恢复补全
            if (suppressed != null) {
                if (t.mode != CompletionParser.Mode.NONE
                        && suppressed.equals(input.getText().substring(t.start, t.end))) {
                    popup.hide();
                    lastToken = null;
                    return;
                }
                suppressed = null;
            }
            switch (t.mode) {
                case SLASH:
                    popup.show(frame, SlashSuggester.all(manager.skills()), t.query);
                    lastToken = t;
                    break;
                case SLASH_SKILL:
                    popup.show(frame, SlashSuggester.skillEntries(manager.skills()), t.query);
                    lastToken = t;
                    break;
                case FILE: {
                    String dir = manager.currentWorkspaceDir();
                    if (dir == null) { popup.hide(); lastToken = null; break; }
                    popup.show(frame, fileSuggester.list(dir), t.query);
                    lastToken = t;
                    break;
                }
                default:
                    popup.hide();
                    lastToken = null;
            }
        } catch (Exception ex) {
            popup.hide(); // 弹层为增强体验，任何异常不打断输入
            lastToken = null;
        }
    }

    /** 插入确认文本：替换当前词为插入文本并移动光标（键盘与鼠标点击共用路径）。
     *  @ 文件补全的 insertText 为纯相对路径（FileSuggester 不带 @ 前缀），须补回 @，
     *  否则替换词区间（含 @ 字符）后前缀丢失；插入文本记录为 suppressed 抑制回弹；
     *  鼠标路径完成后焦点还回输入框（防后续键盘事件落入弹层列表） */
    private void confirmInsert(String insert) {
        if (insert == null || lastToken == null) return;
        suppressed = insertAt(lastToken, insert);
        lastToken = null;
        input.requestFocus();
    }

    /** 执行替换（独立方法供 runLater 复用）：@ 模式补前缀；token 为捕获快照，
     *  用户已改文本导致坐标越界时静默放弃（不打断输入）；返回最终插入文本（失败为 null） */
    private String insertAt(CompletionParser.Token t, String insert) {
        if (t == null || insert == null || insert.isEmpty()) return null;
        String text = insertionText(t.mode, insert);
        try {
            input.replaceText(t.start, t.end, text);
            input.positionCaret(t.start + text.length());
            return text;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 键盘确认弹层选中：取插入文本 → 关弹层 → runLater 执行替换。
     *  文本修改必须延迟到 KEY_PRESSED 派发结束后：JavaFX 8 在 key 事件派发期间
     *  （capture 阶段）改 TextArea 文本与 behavior 的 Enter keyMapping 竞争，插入不可靠；
     *  鼠标点击走 MOUSE_CLICKED（派发尾声）同步插入无此问题 */
    private void confirmPopup() {
        if (lastToken == null) return;
        final CompletionParser.Token t = lastToken;
        final String insert = popup.confirmSelected();
        popup.hide();
        if (insert == null) return;
        Platform.runLater(new Runnable() {
            @Override public void run() { suppressed = insertAt(t, insert); }
        });
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        Platform.runLater(() -> {
            running = h != null && h.running;
            askPending = h != null && h.askPending;
            askQuestion = h == null ? null : h.askQuestion;
            updateButton();
            updatePrompt();
        });
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.running = running;
            updateButton();
        });
    }

    /** ask_user 挂起状态变化（MainWindow 转发自 SessionManager 监听） */
    public void onAskChanged(SessionHandle h, boolean asking, String question) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.askPending = asking;
            this.askQuestion = question;
            updateButton();
            updatePrompt();
        });
    }

    private boolean hasContent() {
        return input.getText() != null && !input.getText().trim().isEmpty();
    }

    private void updatePrompt() {
        if (askPending) {
            String q = askQuestion == null ? "" : askQuestion;
            input.setPromptText("回答: " + (q.length() > 40 ? q.substring(0, 40) + "…" : q));
        } else {
            input.setPromptText("输入消息…  (@ 引用文件  / 命令  Ctrl+Enter 发送)");
        }
    }

    private void updateButton() {
        switch (buttonMode(running, askPending, hasContent())) {
            case SEND:       applyStyle(arrowIcon, "btn-primary", 1.0, "发送 (Ctrl+Enter)"); break;
            case SEND_DIM:   applyStyle(arrowIcon, "btn-primary", 0.35, "输入消息后发送 (Ctrl+Enter)"); break;
            case SUPPLEMENT: applyStyle(arrowIcon, "btn-primary", 1.0, "补充信息给正在运行的模型 (Ctrl+Enter)"); break;
            case ANSWER:     applyStyle(arrowIcon, "btn-primary", 1.0, "回答模型的提问 (Ctrl+Enter)"); break;
            case ANSWER_DIM: applyStyle(arrowIcon, "btn-primary", 0.35, "输入回答后发送 (Ctrl+Enter)"); break;
            case STOP:       applyStyle(stopIcon, "btn-danger", 1.0, "终止当前运行 (Esc)"); break;
        }
    }

    private void applyStyle(SVGPath graphic, String styleClass, double opacity, String tip) {
        sendButton.setGraphic(graphic);
        sendButton.getStyleClass().removeAll("btn-primary", "btn-danger");
        sendButton.getStyleClass().add(styleClass);
        sendButton.setOpacity(opacity);
        sendButton.setTooltip(new Tooltip(tip));
    }

    /** Ctrl+Enter / 按钮点击统一入口：按当前模式分发 */
    private void onAction() {
        switch (buttonMode(running, askPending, hasContent())) {
            case SEND:
                onSend();
                break;
            case SUPPLEMENT: {
                String text = input.getText();
                if (text == null || text.trim().isEmpty()) return;
                input.clear();
                if (current != null) manager.sendSupplement(current, text);
                break;
            }
            case ANSWER: {
                String text = input.getText();
                if (text == null || text.trim().isEmpty()) return;
                input.clear();
                if (current != null) manager.sendAnswer(current, text);
                break;
            }
            case STOP:
                if (current != null) manager.stop(current);
                break;
            case SEND_DIM:
            case ANSWER_DIM:
                break;
        }
    }

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        input.clear();
        popup.hide();
        SessionHandle target = current;
        if (target == null) {
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        manager.dispatchCommand(target, text); // 斜杠命令本地分发；普通消息走 send
    }
}
