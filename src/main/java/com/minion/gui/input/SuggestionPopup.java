package com.minion.gui.input;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** 通用补全弹层：Popup+ListView，锚定输入大框正上方、同宽（Claude Code 风格）。
 *  Popup 不抢焦点：键盘事件由 TextArea 拦截转发（move/confirm/hide）；
 *  鼠标点击条目经 onConfirm 回调把插入文本交给输入框（插入逻辑在 InputView 侧）。 */
public class SuggestionPopup {

    /** 行高估算（CSS 未加载时定位用） */
    private static final double ROW_HEIGHT = 30;
    /** 可见条目上限（约 200px） */
    private static final int MAX_VISIBLE = 8;

    private final Popup popup = new Popup();
    private final ListView<Suggestion> list = new ListView<Suggestion>();
    private Consumer<String> onConfirm;

    public SuggestionPopup() {
        list.getStyleClass().add("suggest-list");
        list.setMaxHeight(MAX_VISIBLE * ROW_HEIGHT);
        list.setPrefHeight(MAX_VISIBLE * ROW_HEIGHT);
        list.setCellFactory(lv -> new ListCell<Suggestion>() {
            @Override protected void updateItem(Suggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                HBox row = new HBox(8);
                Label l = new Label(item.label);
                l.getStyleClass().add("suggest-label");
                HBox.setHgrow(l, Priority.ALWAYS);
                row.getChildren().add(l);
                if (item.desc != null && !item.desc.isEmpty()) {
                    Label d = new Label(item.desc);
                    d.getStyleClass().add("suggest-desc");
                    row.getChildren().add(d);
                }
                setGraphic(row);
            }
        });
        // 鼠标点击条目：hide + 回调插入文本（根因修复：旧实现仅返回文本不插入，点击后输入框无变化）
        list.setOnMouseClicked(e -> {
            Suggestion sel = list.getSelectionModel().getSelectedItem();
            hide();
            if (sel != null && onConfirm != null) onConfirm.accept(sel.insertText);
        });
        popup.getContent().add(list);
        // 不用 autoHide：点击输入框（常见操作）会在 bubble 阶段关掉弹层，与 onTextChanged
        // 的 show() 竞争——点击后弹层闪一下消失，键盘 Enter 落入 TextArea 变成换行，确认失效。
        // 弹层显示改为输入内容驱动 + 输入框失焦关闭（InputView 监听 focusedProperty）
        popup.setAutoHide(false);
    }

    /** 鼠标点击确认回调注册：收到选中项 insertText（弹层负责 hide，插入由输入框执行） */
    public void setOnConfirm(Consumer<String> c) { this.onConfirm = c; }

    /** 过滤+排序（纯静态，可单测）：大小写不敏感 contains；前缀匹配优先 → 标签短优先 → 字典序 */
    public static List<Suggestion> filter(List<Suggestion> all, String query) {
        final String q = query == null ? "" : query.trim().toLowerCase();
        List<Suggestion> out = new ArrayList<Suggestion>();
        for (Suggestion s : all) {
            if (q.isEmpty() || s.label.toLowerCase().contains(q)) out.add(s);
        }
        Collections.sort(out, new Comparator<Suggestion>() {
            @Override public int compare(Suggestion a, Suggestion b) {
                boolean ap = q.isEmpty() || a.label.toLowerCase().startsWith(q);
                boolean bp = q.isEmpty() || b.label.toLowerCase().startsWith(q);
                if (ap != bp) return ap ? -1 : 1;
                int len = Integer.compare(a.label.length(), b.label.length());
                if (len != 0) return len;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /** 显示弹层：过滤为空自动隐藏；锚定 anchor 正上方同宽，空间不足时放下方 */
    public void show(Node anchor, List<Suggestion> all, String query) {
        List<Suggestion> items = filter(all, query);
        if (items.isEmpty()) { hide(); return; }
        list.getItems().setAll(items);
        list.getSelectionModel().select(0);
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        double w = anchor.getBoundsInLocal().getWidth();
        double h = Math.min(items.size(), MAX_VISIBLE) * ROW_HEIGHT;
        list.setPrefWidth(w);
        list.setMinWidth(w);
        double y = b.getMinY() - h; // 上方优先
        if (y < 0) y = b.getMaxY(); // 屏幕顶部空间不足时放下方
        popup.show(anchor.getScene().getWindow(), b.getMinX(), y);
    }

    /** 键盘上下移动选中（循环钳制） */
    public void move(int delta) {
        int n = list.getItems().size();
        if (n == 0) return;
        int cur = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(n - 1, cur + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    /** 确认选中：仅返回选中项 insertText（无选中返回 null），不关弹层——hide 由调用方
     *  （InputView）在拿到插入文本后统一执行，避免 KEY_PRESSED 派发期间 hide 与插入竞争 */
    public String confirmSelected() {
        Suggestion sel = list.getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.insertText;
    }

    /** 临时调试：当前选中状态（定位键盘确认问题，验证后删除） */
    public String debugSelected() {
        int idx = list.getSelectionModel().getSelectedIndex();
        Suggestion item = idx >= 0 && idx < list.getItems().size() ? list.getItems().get(idx) : null;
        return "idx=" + idx + " item=" + (item == null ? "null" : item.label);
    }

    public void hide() { popup.hide(); }

    public boolean isShowing() { return popup.isShowing(); }
}
