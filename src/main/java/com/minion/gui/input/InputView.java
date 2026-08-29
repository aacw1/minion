package com.minion.gui.input;

import com.minion.core.config.Config;
import com.minion.core.llm.ImagePart;
import com.minion.gui.icon.IconFactory;
import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 底部输入区：4/9 宽居中大框（上=块行+输入框，下=底部操作行：上传按钮左 + 发送按钮右）+ /命令与 @文件补全弹层。
 *  按钮语义：上箭头=发送/补充/回答、变淡箭头=空输入或等待回答、方块=终止（提问挂起时改 Esc 终止）；
 *  背景按状态取色（btn-send-empty #f48771 / btn-send-full #ff947c）。上传按钮（回形针）→ FileChooser 选图建 IMAGE 块。
 *  运行中 + 有内容 → 补充；等待回答 + 有内容 → 回答；运行中 + 空 → 终止。 */
public class InputView extends VBox {

    /** 按钮模式：图标/透明度/背景类/动作的判定依据（ANSWER_DIM=提问挂起且空输入，变淡箭头等待输入回答） */
    enum BtnMode { SEND, SEND_DIM, SUPPLEMENT, ANSWER, ANSWER_DIM, STOP }

    private final SessionManager manager;
    private final Config config;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button();
    private final Button uploadButton = new Button();
    private final ContextRing contextRing = new ContextRing();
    private final SVGPath arrowIcon = IconFactory.send();
    private final SVGPath stopIcon = IconFactory.stop();
    private final SVGPath uploadIcon = IconFactory.attachFile();
    private final SuggestionPopup popup = new SuggestionPopup();
    private final FileSuggester fileSuggester = new FileSuggester();
    /** 块行与块列表：模型 List<InputChip> 与视图 FlowPane 同步维护（增删块后须 refreshChipRow + updateButton） */
    private final List<InputChip> chips = new ArrayList<InputChip>();
    private final FlowPane chipRow = new FlowPane();
    /** 粘贴块占位符序号：保证同框内占位符唯一（[粘贴块N]） */
    private int pasteSeq;
    /** 占位符 → 最近被移除的粘贴块：文本撤销（Ctrl+Z）使占位符重现时恢复块，避免占位文本原样发出 */
    private final Map<String, InputChip> droppedPastes = new LinkedHashMap<String, InputChip>();
    /** reconcile 重入锁：清理占位残片改文本会再触发文本监听 */
    private boolean reconciling;
    private VBox frame; // 大框（弹层锚点）
    private CompletionParser.Token lastToken; // 弹层可见时待替换的词
    private volatile SessionHandle current;
    // FX 线程缓存的状态（bindSession/onRunningChanged/onAskChanged 维护）
    private boolean running;
    private boolean askPending;
    private String askQuestion;

    public InputView(final SessionManager manager, final Config config) {
        this.manager = manager;
        this.config = config;
        getStyleClass().add("panel-dark");
        setPadding(new Insets(0, 16, 24, 16)); // 顶部 0：输入框顶部贴消息区（上移半行，总高减半行 96→84）；底部 24：距正文部分底部1行

        input.getStyleClass().add("input-textarea");
        input.setWrapText(true);
        input.setPrefRowCount(2); // 加一行
        input.setMaxHeight(6 * 24);
        // reconcile 先于补全解析：占位符增删同步粘贴块后再解析，弹层与发送按钮基于最终文本
        input.textProperty().addListener((obs, ov, nv) -> { updateButton(); reconcilePasteChips(); onTextChanged(); });
        input.caretPositionProperty().addListener((obs, ov, nv) -> onTextChanged());
        // 弹层显示改为输入内容驱动：输入框失焦（点击聊天区/侧栏）时关闭，点击输入框本身不关；
        // 焦点进入时刷新文案——设置窗勾选发送键后点回输入框，提示与 tooltip 立即正确
        // 聚焦边框：聚焦时大框边框 #f48771，失焦还原 #232733（frame 构造晚于本监听注册，判空）
        input.focusedProperty().addListener((obs, ov, nv) -> {
            if (frame != null) {
                if (nv) frame.getStyleClass().add("input-frame-focused");
                else frame.getStyleClass().remove("input-frame-focused");
            }
            if (!nv) { popup.hide(); lastToken = null; }
            else { updateButton(); updatePrompt(); }
        });

        // 上箭头（Claude Code 同款语义：可发送）；方块 = 终止（图形与样式类由 IconFactory 提供）
        uploadButton.setGraphic(uploadIcon);
        uploadButton.setMinSize(30, 30);
        uploadButton.setPrefSize(30, 30);
        uploadButton.getStyleClass().add("btn-upload");
        uploadButton.setTooltip(new Tooltip("上传图片 (最多" + ImagePart.MAX_IMAGES + "张, 每张≤" + (ImagePart.MAX_FILE_BYTES / 1024 / 1024) + "MB)"));
        uploadButton.setOnAction(e -> chooseImages());

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
            boolean ctrl = e.isControlDown(), shift = e.isShiftDown(), alt = e.isAltDown(), meta = e.isMetaDown();
            boolean enterSends = config.enterSends();
            // 发送键：默认模式优先于弹层（同现状：弹层开着 Ctrl+Enter 也发送）；
            // Enter 发送模式弹层打开时不发送，Enter/Ctrl+Enter 均走弹层确认
            if (isSendKey(e.getCode(), ctrl, shift, alt, meta, enterSends) && !(enterSends && popup.isShowing())) {
                e.consume();
                onAction();
                return;
            }
            // Enter 发送模式：弹层关闭时 Ctrl+Enter 显式插入换行（JavaFX TextArea 对 Ctrl+Enter 无默认换行绑定，须 replaceSelection）
            if (enterSends && !popup.isShowing() && e.getCode() == KeyCode.ENTER && ctrl && !shift && !alt && !meta) {
                e.consume();
                input.replaceSelection("\n");
                return;
            }
            // 长文本粘贴（Ctrl+V / Shift+Insert）→ 变块：块进块行，占位符插在光标处（有选区则替换选区），
            // 发送时占位符原位展开为全文，保证落位 = 光标位置；短文本不拦截走默认粘贴
            if ((e.isControlDown() && e.getCode() == KeyCode.V)
                    || (e.isShiftDown() && e.getCode() == KeyCode.INSERT)) {
                String clip = Clipboard.getSystemClipboard().getString();
                if (InputChip.shouldChipPaste(clip)) {
                    InputChip chip = InputChip.pasteChip(clip, "[粘贴块" + (++pasteSeq) + "]");
                    addChip(chip);
                    input.replaceSelection(chip.placeholder);
                    e.consume();
                    return;
                }
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
            // 空文本时 Backspace/Delete 删除最后一个块
            if ((e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE)
                    && !chips.isEmpty() && input.getText().isEmpty()) {
                removeLastChip();
                e.consume();
                return;
            }
            // Esc：终止当前运行（提问挂起时亦可终止）
            if (e.getCode() == KeyCode.ESCAPE && current != null && running) {
                e.consume();
                manager.stop(current);
            }
        });

        // 大框：上=块行+输入框，下=底部操作行（上传按钮左 + 弹性空白 + 发送按钮右）
        frame = new VBox(6);
        frame.getStyleClass().add("input-frame");
        // 块行（上方）+ 文本区（下方）：chipRow 无块时 unmanaged 不占位
        chipRow.getStyleClass().add("chip-row");
        VBox composer = new VBox(6);
        composer.getChildren().addAll(chipRow, input);
        VBox.setVgrow(composer, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottomRow = new HBox(8);
        bottomRow.getChildren().addAll(uploadButton, contextRing, spacer, sendButton);
        frame.getChildren().addAll(composer, bottomRow);

        // 启动/未选会话：环形圈初始隐藏（bindSession(null) 与 onContextStats 无数据时均不显示，避免空白 Tooltip 颜色块）
        contextRing.setVisible(false);
        contextRing.setManaged(false);

        // 点击环形圈 → 会话工作线程立即压缩（非运行中且超 30% 才可点；压缩事件回调驱动旋转动画与刷新）
        contextRing.setOnCompress(new Runnable() {
            @Override public void run() {
                SessionHandle h = current;
                if (h == null || h.running) return;
                contextRing.setCompressing(true); // 提交后立即防重；onCompressingChanged(false) 自然恢复
                h.pool.execute(new Runnable() {
                    @Override public void run() { h.loop.compactNow(); }
                });
            }
        });

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
        updatePrompt(); // askPending 默认 false，显示正常发送键提示
    }

    /** 确认插入的最终文本：@ 文件补全补回 @ 前缀（FileSuggester 的 insertText 为纯路径）。
     *  纯静态可脱离 JavaFX 单测 */
    static String insertionText(CompletionParser.Mode mode, String insert) {
        return mode == CompletionParser.Mode.FILE && !insert.startsWith("@") ? "@" + insert : insert;
    }

    /** 块行占位开关：无块时 unmanaged（VBox 布局忽略，不产生多余间距） */
    private void refreshChipRow() {
        boolean has = !chips.isEmpty();
        chipRow.setManaged(has);
        chipRow.setVisible(has);
    }

    /** 追加块到尾部（模型 + 视图同步） */
    private void addChip(InputChip chip) {
        if (chip == null) return;
        chips.add(chip);
        chipRow.getChildren().add(chipView(chip));
        refreshChipRow();
        updateButton();
    }

    /** 删除第 i 个块（越界静默忽略） */
    private void removeChipAt(int i) {
        if (i < 0 || i >= chips.size()) return;
        chips.remove(i);
        chipRow.getChildren().remove(i);
        refreshChipRow();
        updateButton();
    }

    /** 删除最后一个块（Backspace 用）；无块返回 false */
    private boolean removeLastChip() {
        if (chips.isEmpty()) return false;
        removeChipAt(chips.size() - 1);
        return true;
    }

    /** 文本变化同步粘贴块（占位符是块在输入文本中的锚点）：
     *  占位符消失 → 删块并清理残留碎片（Backspace 逐字删占位符时整块移除）；
     *  占位符重现（Ctrl+Z 撤销删除）→ 从 droppedPastes 恢复块，避免占位文本原样发出。
     *  reconciling 防重入：清理碎片改文本会再次触发文本监听 */
    private void reconcilePasteChips() {
        if (reconciling) return;
        reconciling = true;
        try {
            String text = input.getText() == null ? "" : input.getText();
            for (int i = chips.size() - 1; i >= 0; i--) {
                InputChip c = chips.get(i);
                if (c.type != InputChip.Type.PASTE || c.placeholder == null || text.contains(c.placeholder)) continue;
                removeChipAt(i);
                droppedPastes.put(c.placeholder, c);
                removePlaceholderRemnants(c.placeholder);
            }
            String now = input.getText() == null ? "" : input.getText();
            Iterator<Map.Entry<String, InputChip>> it = droppedPastes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, InputChip> en = it.next();
                if (now.contains(en.getKey())) {
                    addChip(en.getValue());
                    it.remove();
                }
            }
        } finally {
            reconciling = false;
        }
    }

    /** 占位符整体消失后清理残留碎片（用户逐字删除占位符的场景）：
     *  逐次查找文本中仍存在的最长前缀/后缀碎片（≥3 字符）并 deleteText 移除（光标留在删除点）。
     *  碎片若同时是其他在用占位符的子串（如「[粘贴块1」之于「[粘贴块12]」）则跳过，防止误伤整块 */
    private void removePlaceholderRemnants(String placeholder) {
        while (true) {
            String hit = null;
            for (int k = placeholder.length() - 1; k >= 3 && hit == null; k--) {
                String frag = placeholder.substring(0, k);
                if (input.getText().contains(frag) && !fragmentInOtherPlaceholder(frag)) hit = frag;
            }
            for (int k = 1; k <= placeholder.length() - 3 && hit == null; k++) {
                String frag = placeholder.substring(k);
                if (input.getText().contains(frag) && !fragmentInOtherPlaceholder(frag)) hit = frag;
            }
            if (hit == null) return;
            int idx = input.getText().indexOf(hit);
            input.deleteText(idx, idx + hit.length());
        }
    }

    /** 碎片是否仍属于其他在用块的占位符（防清理碎片误删其他块的占位符） */
    private boolean fragmentInOtherPlaceholder(String frag) {
        for (InputChip c : chips) {
            if (c != null && c.placeholder != null && c.placeholder.contains(frag)) return true;
        }
        return false;
    }

    /** 渲染单个块：Label + 关闭按钮（SVG 叉，点击删除并还焦输入框）；COMMAND/SKILL/FILE 超 40 字符挂完整内容 tooltip */
    private Node chipView(InputChip chip) {
        Label label = new Label(chip.display);
        label.getStyleClass().add("chip-label");
        SVGPath closeIcon = IconFactory.close();
        IconFactory.size(closeIcon, 11);
        Button close = new Button();
        close.setGraphic(closeIcon);
        close.getStyleClass().add("chip-close");
        close.setOnAction(e -> {
            removeChipAt(chips.indexOf(chip));
            // 粘贴块联动删除光标处占位符；记入 droppedPastes 使 Ctrl+Z 恢复占位文本时块一同恢复
            if (chip.type == InputChip.Type.PASTE && chip.placeholder != null) {
                droppedPastes.put(chip.placeholder, chip);
                int idx = input.getText().indexOf(chip.placeholder);
                if (idx >= 0) input.deleteText(idx, idx + chip.placeholder.length());
            }
            input.requestFocus();
        });
        HBox box = new HBox(6);
        box.getStyleClass().addAll("input-chip", chipTypeClass(chip.type));
        box.getChildren().addAll(label, close);
        if (chip.type != InputChip.Type.PASTE && chip.content.length() > 40) {
            Tooltip.install(box, new Tooltip(chip.content));
        }
        return box;
    }

    /** 块类型 → 语义样式类 */
    private static String chipTypeClass(InputChip.Type type) {
        switch (type) {
            case COMMAND: return "input-chip-command";
            case SKILL:   return "input-chip-skill";
            case FILE:    return "input-chip-file";
            case IMAGE:   return "input-chip-image";
            default:      return "input-chip-paste";
        }
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
            switch (t.mode) {
                case SLASH:
                    popup.show(frame, SlashSuggester.all(manager.currentSkills()), t.query);
                    lastToken = t;
                    break;
                case SLASH_SKILL:
                    popup.show(frame, SlashSuggester.skillEntries(manager.currentSkills()), t.query);
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

    /** 鼠标点击弹层条目：按 token 建块并删除已输入的部分词，焦点还回输入框（防后续键盘事件落入弹层列表） */
    private void confirmInsert(String insert) {
        if (insert == null || lastToken == null) return;
        addChipFor(lastToken, insert);
        lastToken = null;
        input.requestFocus();
    }

    /** 按 token 建块并追加：@ 模式补回 @ 前缀（FileSuggester insertText 为纯路径）；
     *  先删除 token 区间已输入的部分词（坐标过期=用户已改文本，整体放弃不建块）；空插入忽略 */
    private void addChipFor(CompletionParser.Token t, String insert) {
        if (insert == null || insert.isEmpty()) return;
        String text = insertionText(t.mode, insert);
        try {
            input.deleteText(t.start, t.end);
        } catch (RuntimeException ex) {
            return; // 坐标越界：用户已改文本，静默放弃
        }
        addChip(InputChip.textChip(InputChip.modeToType(t.mode), text));
    }

    /** 键盘确认弹层选中：取插入文本 → 关弹层 → runLater 建块追加。
     *  runLater 保持原时序（KEY_PRESSED 派发期间改输入区与 behavior 竞争的老规避惯例，统一路径） */
    private void confirmPopup() {
        if (lastToken == null) return;
        final CompletionParser.Token t = lastToken;
        final String insert = popup.confirmSelected();
        popup.hide();
        if (insert == null) return;
        Platform.runLater(new Runnable() {
            @Override public void run() { addChipFor(t, insert); }
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
            // 环形圈：切会话先隐藏，初始估算放会话线程（全量估算较重，防卡 UI），完成后 runLater 填充
            contextRing.setCompressing(false);
            contextRing.setVisible(false);
            contextRing.setManaged(false);
            if (h != null && h.loop.contextManager() != null) {
                final SessionHandle target = h;
                h.pool.execute(new Runnable() {
                    @Override public void run() {
                        final int used = target.loop.contextManager().estimate(target.session.messages);
                        final int max = target.loop.contextManager().maxTokens();
                        Platform.runLater(new Runnable() {
                            @Override public void run() { onContextStats(target, used, max); }
                        });
                    }
                });
            }
        });
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.running = running;
            updateButton();
            contextRing.setRunning(running);
        });
    }

    /** AskUserQuestion 挂起状态变化（MainWindow 转发自 SessionManager 监听） */
    public void onAskChanged(SessionHandle h, boolean asking, String question) {
        if (current != h) return;
        Platform.runLater(() -> {
            this.askPending = asking;
            this.askQuestion = question;
            updateButton();
            updatePrompt();
        });
    }

    /** 上下文统计更新（MainWindow 转发 SessionManager.Listener）：仅当前会话生效；
     *  无数据（max<=0）时隐藏不占位 */
    public void onContextStats(SessionHandle h, int used, int max) {
        if (current != h) return;
        Platform.runLater(new Runnable() {
            @Override public void run() {
                double threshold = 0.8; // 兜底；正常从 ContextManager 实时读取
                if (h.loop.contextManager() != null) threshold = h.loop.contextManager().threshold();
                boolean active = used > 0 && max > 0;
                contextRing.update(used, max, threshold, running);
                contextRing.setVisible(active);
                contextRing.setManaged(active);
            }
        });
    }

    /** 压缩状态变化（MainWindow 转发）：仅当前会话生效，环形旋转动画 */
    public void setCompressing(SessionHandle h, boolean compressing) {
        if (current != h) return;
        Platform.runLater(new Runnable() {
            @Override public void run() { contextRing.setCompressing(compressing); }
        });
    }

    /** 块 + 文本组装发送文本 */
    private String composedText() {
        return InputChip.compose(chips, input.getText());
    }

    /** 发送后清空：块 + 文本区 + 弹层（块行 unmanaged 自动归位）；占位符状态一并重置 */
    private void clearComposer() {
        chips.clear();
        chipRow.getChildren().clear();
        droppedPastes.clear();
        pasteSeq = 0;
        refreshChipRow();
        popup.hide();
        input.clear();
        updateButton();
    }

    private boolean hasContent() {
        return !chips.isEmpty() || (input.getText() != null && !input.getText().trim().isEmpty());
    }

    private void updatePrompt() {
        if (askPending) {
            String q = askQuestion == null ? "" : askQuestion;
            input.setPromptText("回答: " + (q.length() > 40 ? q.substring(0, 40) + "…" : q));
        } else {
            input.setPromptText("输入消息…  (@ 引用文件  / 命令  " + sendKeyLabel(config.enterSends()) + " 发送)");
        }
    }

    /** 按钮模式 → 背景样式类：内容空与运行中 #f48771（btn-send-empty），有内容非运行 #ff947c（btn-send-full）；纯静态可单测 */
    static String buttonStyleClass(BtnMode mode) {
        switch (mode) {
            case SEND:
            case ANSWER:
                return "btn-send-full";
            default:
                return "btn-send-empty";
        }
    }

    /** 当前发送键模式下该按键事件是否为「发送」：默认=Ctrl+Enter（精确修饰键语义，同 KeyCodeCombination）；
     *  Enter 发送模式=纯 Enter。弹层打开时由事件过滤器分流，不算发送 */
    static boolean isSendKey(KeyCode code, boolean ctrl, boolean shift, boolean alt, boolean meta, boolean enterSends) {
        if (code != KeyCode.ENTER) return false;
        if (enterSends) return !ctrl && !shift && !alt && !meta;
        return ctrl && !shift && !alt && !meta;
    }

    /** 发送键显示名（按钮 tooltip / 输入框占位提示用） */
    static String sendKeyLabel(boolean enterSends) {
        return enterSends ? "Enter" : "Ctrl+Enter";
    }

    private void updateButton() {
        BtnMode mode = buttonMode(running, askPending, hasContent());
        String sendKey = sendKeyLabel(config.enterSends());
        switch (mode) {
            case SEND:       applyStyle(arrowIcon, buttonStyleClass(mode), 1.0, "发送 (" + sendKey + ")"); break;
            case SEND_DIM:   applyStyle(arrowIcon, buttonStyleClass(mode), 0.35, "输入消息后发送 (" + sendKey + ")"); break;
            case SUPPLEMENT: applyStyle(arrowIcon, buttonStyleClass(mode), 1.0, "补充信息给正在运行的模型 (" + sendKey + ")"); break;
            case ANSWER:     applyStyle(arrowIcon, buttonStyleClass(mode), 1.0, "回答模型的提问 (" + sendKey + ")"); break;
            case ANSWER_DIM: applyStyle(arrowIcon, buttonStyleClass(mode), 0.35, "输入回答后发送 (" + sendKey + ")"); break;
            case STOP:       applyStyle(stopIcon, buttonStyleClass(mode), 1.0, "终止当前运行 (Esc)"); break;
        }
    }

    private void applyStyle(SVGPath graphic, String styleClass, double opacity, String tip) {
        sendButton.setGraphic(graphic);
        sendButton.getStyleClass().removeAll("btn-primary", "btn-danger", "btn-send-full", "btn-send-empty");
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
                String text = composedText();
                if (text == null || text.trim().isEmpty()) return;
                clearComposer();
                if (current != null) manager.sendSupplement(current, text);
                break;
            }
            case ANSWER: {
                if (!composedImages().isEmpty()) {
                    notifyChat("回答模式暂不支持图片，请删除图片后再回答");
                    return;
                }
                String text = composedText();
                if (text == null || text.trim().isEmpty()) return;
                clearComposer();
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
        String text = composedText();
        List<ImagePart> images = composedImages();
        if ((text == null || text.trim().isEmpty()) && images.isEmpty()) return;
        clearComposer();
        SessionHandle target = current;
        if (target == null) {
            target = manager.createSession(null);
            if (target == null) return;
            manager.activateSession(target);
        }
        // 带图消息不走斜杠命令分发（图片无法本地处理，照发普通消息）
        if (images.isEmpty()) {
            manager.dispatchCommand(target, text); // 斜杠命令本地分发；普通消息走 send
        } else {
            manager.send(target, text, images);
        }
    }

    /** 图片块 → ImagePart 列表：解析 data URI 头拆 mime/base64；name 取 display 的「图片：」前缀之后 */
    private List<ImagePart> composedImages() {
        List<ImagePart> out = new ArrayList<ImagePart>();
        for (InputChip c : chips) {
            if (c == null || c.type != InputChip.Type.IMAGE) continue;
            int comma = c.content.indexOf(',');
            if (comma < 0 || !c.content.startsWith("data:")) continue;
            String header = c.content.substring(0, comma); // data:<mime>;base64
            int semi = header.indexOf(';');
            String mime = semi > 5 ? header.substring(5, semi) : header.substring(5);
            ImagePart p = new ImagePart();
            p.mime = mime;
            p.base64 = c.content.substring(comma + 1);
            p.name = c.display.startsWith("图片：") ? c.display.substring(3) : c.display;
            out.add(p);
        }
        return out;
    }

    /** 上传图片：FileChooser 选图 → 大小/数量校验 → 转 base64 建 IMAGE 块 */
    private void chooseImages() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("上传图片");
        chooser.getExtensionFilters().add(new ExtensionFilter("图片文件",
                "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"));
        File f = chooser.showOpenDialog(getScene().getWindow());
        if (f == null) return;
        if (f.length() > ImagePart.MAX_FILE_BYTES) {
            notifyChat("图片超过 " + (ImagePart.MAX_FILE_BYTES / 1024 / 1024) + "MB，拒绝上传: " + f.getName());
            return;
        }
        int have = 0;
        for (InputChip c : chips) if (c.type == InputChip.Type.IMAGE) have++;
        if (have >= ImagePart.MAX_IMAGES) {
            notifyChat("最多上传 " + ImagePart.MAX_IMAGES + " 张图片");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            addChip(InputChip.imageChip(mimeFor(f.getName()), b64, f.getName()));
        } catch (Exception ex) {
            notifyChat("读取图片失败: " + ex.getMessage());
        }
    }

    /** 文件名后缀 → mime；未知后缀兜底 png */
    private static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    /** 聊天区提示（无会话时静默忽略；发送前必先有会话或上传仅装饰） */
    private void notifyChat(String msg) {
        if (current != null) current.controller.onWarning(msg);
    }
}
