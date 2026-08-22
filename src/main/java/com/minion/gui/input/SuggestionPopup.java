package com.minion.gui.input;

import javafx.application.Platform;
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

    /** 行高估算（首帧校准前的初始定位用；校准后按实际行高取整） */
    private static final double ROW_HEIGHT = 30;
    /** 可见条目上限（初始窗口/首帧估算用） */
    private static final int MAX_VISIBLE = 8;
    /** 内容行数：光标可到达的条目数（固定 6，不足按实际项数）。弹窗总高 = 内容行 + 1 行
     *  余量（第 7 行显示窗口下一项作预览，同时容纳水平滚动条）；到第 6 位（内容行末行）
     *  按下即触发窗口滑动。行高变化不改变内容行数——弹窗高度随行高伸缩 */
    private static final int VISIBLE_ITEMS = 6;

    /** 弹层：重建时换新实例（强制新 scene+skin；hide+show 复用旧 scene，
     *  行高变化时残留旧 cell 高度会错乱——见 rebuild()） */
    private Popup popup = new Popup();
    private ListView<Suggestion> list = createList();
    private Consumer<String> onConfirm;

    /** 新建 ListView：每次重建弹层时换新实例。JDK8 中 Control 的 skin/VirtualFlow
     *  随 Control 实例存在、不随 scene 销毁——行高变化时复用 cell 会残留旧高度
     *  （实测混合行高、行距错位、内容裁剪），新实例保证 VirtualFlow 从零布局 */
    private ListView<Suggestion> createList() {
        ListView<Suggestion> l = new ListView<Suggestion>();
        l.getStyleClass().add("suggest-list");
        l.setMaxHeight(2000); // 高度保险（防校准异常撑爆弹窗）；正常校准高度 ≤ 内容行上限 6 + 余量 1 = 7 行，行高再高也够
        l.setPrefHeight(MAX_VISIBLE * ROW_HEIGHT);
        // 不可聚焦：鼠标点击条目后焦点不得落入 ListView——否则后续键盘事件全被列表吞掉
        // （↑↓ 由列表内置导航响应、Enter/Tab 无默认动作），输入框的补全键处理全部失效
        l.setFocusTraversable(false);
        l.setCellFactory(lv -> new ListCell<Suggestion>() {
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
        l.setOnMouseClicked(e -> {
            Suggestion sel = l.getSelectionModel().getSelectedItem();
            hide();
            if (sel != null && onConfirm != null) onConfirm.accept(sel.insertText);
        });
        return l;
    }

    public SuggestionPopup() {
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

    /** 弹层重建锚点（move 滑动窗口时复用，重建 VirtualFlow 用） */
    private javafx.stage.Window lastOwner;
    private double lastX, lastY;

    /** 本次会话完整过滤后列表（show 时快照；move 只在其上滑动窗口，不增删、不关弹层） */
    private List<Suggestion> all = new ArrayList<Suggestion>();
    /** 窗口首行在 all 中的下标（向上滑动可恢复被移除的顶项） */
    private int viewTop;
    /** 最近一次校准后的弹层高度（-1 未校准）：show 时作为初始高度估计，避免反复重建 */
    private double calibratedH = -1;
    /** 弹层锚点（校准后重新定位用） */
    private Node lastAnchor;

    /** 显示弹层：过滤为空自动隐藏；锚定 anchor 正上方同宽，空间不足时放下方 */
    public void show(Node anchor, List<Suggestion> all, String query) {
        List<Suggestion> items = filter(all, query);
        if (items.isEmpty()) { hide(); return; }
        this.all = items;
        viewTop = 0; // 新会话：窗口从列表头开始
        // 初始窗口：前 MAX_VISIBLE 项（内容行数校准后才收缩为 VISIBLE_ITEMS+1）
        list.getItems().setAll(new ArrayList<Suggestion>(items.subList(0, Math.min(items.size(), MAX_VISIBLE))));
        list.getSelectionModel().select(0);
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        double w = anchor.getBoundsInLocal().getWidth();
        // 高度：优先用校准值（按实际行高取整），首次未校准时用估算
        double h = calibratedH > 0 ? calibratedH : Math.min(items.size(), MAX_VISIBLE) * ROW_HEIGHT;
        double y = b.getMinY() - h; // 上方优先
        if (y < 0) y = b.getMaxY(); // 屏幕顶部空间不足时放下方
        lastOwner = anchor.getScene().getWindow();
        lastAnchor = anchor;
        lastX = b.getMinX();
        lastY = y;
        rebuild(); // 重建为新 ListView 实例
        list.setPrefWidth(w);
        list.setMinWidth(w);
        list.setPrefHeight(h); // 直接以校准/估算高度起步（与校准结果一致时 calibrate 跳过，不重建）
        calibrate(); // 首帧后按实际行高取整，避免末行只显示一半
    }

    /** 重建弹层窗口：换新 Popup + 新 ListView 实例，强制重建 scene 与 skin/VirtualFlow。
     *  popup.show 的 hide+show 复用旧 scene 与旧 Control——实测行高变化时残留
     *  旧 cell 高度，出现行高混合、行距错位、内容裁剪；新实例保证与首开完全一致。
     *  新 ListView 沿用当前 items 与选中项（调用方在 rebuild 前/后设置的选中均生效）。 */
    private void rebuild() {
        List<Suggestion> items = new ArrayList<Suggestion>(list.getItems());
        int sel = Math.max(0, list.getSelectionModel().getSelectedIndex());
        // 继承旧 list 的宽度/高度：校准后的 prefHeight 若被 createList 默认值覆盖，
        // 重建后弹层高度回落（行高不变但窗口缩小，末行被裁剪）
        double pw = list.getPrefWidth(), mw = list.getMinWidth(), ph = list.getPrefHeight();
        if (popup.isShowing()) popup.hide();
        popup = new Popup();
        list = createList();
        list.setPrefWidth(pw);
        list.setMinWidth(mw);
        list.setPrefHeight(ph);
        list.getItems().setAll(items);
        list.getSelectionModel().select(sel);
        popup.getContent().add(list);
        popup.setAutoHide(false);
        popup.show(lastOwner, lastX, lastY);
    }

    /** 首帧后校准弹层高度：按实际渲染行高取整到整行（末行不再只显示一半）。
     *  行高随内容变化（带 desc 的行比纯标签高、DPI 缩放亦不同），/ 与 @ 各自校准；
     *  结果与当前高度一致则跳过，需要调整时重定位并重建弹层（换新 ListView 实例，
     *  避免 VirtualFlow 复用旧 cell 行高）。弹层已关闭或会话已切换（继续打字重新
     *  过滤）则放弃，避免过期校准。 */
    private void calibrate() {
        final List<Suggestion> session = all;
        final Node anchor = lastAnchor;
        final Runnable[] task = new Runnable[1];
        task[0] = new Runnable() {
            int tries = 0;
            @Override public void run() {
                if (!popup.isShowing() || all != session) return; // 已关闭或会话已切换
                double rowH = -1;
                for (javafx.scene.Node c : list.lookupAll(".list-cell")) {
                    if (((ListCell<?>) c).getIndex() >= 0) { rowH = c.getLayoutBounds().getHeight(); break; }
                }
                if (rowH <= 0) {
                    if (++tries < 5) { Platform.runLater(this); return; } // 尚未布局，下一帧重试
                    return;
                }
                // 收缩窗口到内容行 + 1 项：初始窗口按 MAX_VISIBLE 估算（最多 8 项）超出
                // 视口行数，底部多出半截项并触发垂直滚动条；首帧实测行高后收缩
                list.getItems().setAll(window());
                list.getSelectionModel().select(0);
                // 弹窗高度 = 内容行 + 1 行余量：第 7 行显示窗口下一项作预览（光标不可达），
                // 同时容纳超长名称撑出的水平滚动条（占约 19px）——不再挡末行内容；
                // 窗口滑动到第 6 位（内容行末行）触发。
                // +3：ListView 上下边框各 1px（theme.css border），VirtualFlow 视口 =
                // 弹窗高 - 2；再 +1 保险——items 总高恰好等于视口高时 VirtualFlow 的
                // 浮点比较仍判溢出，残留垂直滚动条（实测 280=280 时 vis=true）
                double h = (visibleRows() + 1) * rowH + 3;
                // 非整高才减半像素：防窗口向上取整后多出残行（整高如 280.0 保持精确，不裁剪末行）
                if (Math.abs(h - Math.rint(h)) > 0.05) h -= 0.5;
                if (Math.abs(h - list.getPrefHeight()) >= 0.6) {
                    list.setPrefHeight(h);
                    calibratedH = h;
                    Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
                    double y = b.getMinY() - h; // 上方优先
                    if (y < 0) y = b.getMaxY(); // 屏幕顶部空间不足时放下方
                    lastY = y;
                }
                // 收缩后强制重建：换新 ListView 清除 items 收缩（8→6）残留的旧 cell——
                // VirtualFlow 复用 cell 池不移除多余 cell，行高变化时出现混合高度/行距错位
                rebuild();
            }
        };
        Platform.runLater(task[0]);
    }

    /** 内容行数：固定 VISIBLE_ITEMS（不足按实际项数）。行高变化不改变内容行数——
     *  弹窗高度随之伸缩（高行如 slash 得到更高的弹窗）；不按视口高度换算——
     *  水平滚动条占掉视口约 19px 会导致换算少算一行 */
    private int visibleRows() { return Math.min(VISIBLE_ITEMS, all.size()); }

    /** 当前窗口切片：首行 viewTop 起，内容行 + 1 项（第 7 行显示窗口最后一项作预览，
     *  光标不可达——到第 6 位按下即触发滑动；列表不足 7 项时为全部）。
     *  拷贝防迭代中修改（直接传 subList 给 setAll 会 ConcurrentModificationException） */
    private List<Suggestion> window() {
        int end = Math.min(viewTop + visibleRows() + 1, all.size());
        return new ArrayList<Suggestion>(all.subList(viewTop, end));
    }

    /** 键盘上下移动选中：光标先在窗口内走，到窗口底/顶后再按向下/上则滑动窗口
     *  （向下：最顶项滑出、底部补下一项；向上：恢复最顶上一项，被移除项可找回）。
     *  窗口滑到列表两端后光标停住——不再移除项、不关弹层（只剩 1 项也保留显示）。
     *  窗口滑动时重建弹层：直接 setAll 的复用 VirtualFlow 布局会错乱（cell 高度
     *  突变、行距错位、行重叠）；popup.show 内部先 hide 再 show，重建 window+
     *  VirtualFlow，与打字时 show() 路径一致，行高重新正确计算。
     *  不用 scrollTo：JavaFX 8 的 scrollTo 会把目标项钉到视口顶部。 */
    public void move(int delta) {
        if (all.isEmpty()) return;
        int w = Math.min(visibleRows(), all.size()); // 滑动窗口大小（=可见行数）
        int cur = list.getSelectionModel().getSelectedIndex() + viewTop; // 选中项在 all 中的下标
        if (cur < 0) cur = 0;
        boolean slide = false;
        if (delta < 0) {
            if (cur == 0) return; // 已到列表头：不动
            cur--;
            if (cur < viewTop) { viewTop--; slide = true; } // 窗口上滑：恢复被移除项
        } else {
            if (cur >= all.size() - 1) return; // 已到列表尾：窗口保留，光标停最下
            cur++;
            if (cur - viewTop >= w) { viewTop++; slide = true; } // 窗口下滑：顶项滑出
        }
        if (slide) {
            list.getItems().setAll(window());
            // 重建弹层（新 Popup + 新 ListView，VirtualFlow 从零布局）；内容行数固定，
            // 不随按键连发重测，窗口始终 = 内容行 + 1 项
            rebuild();
        }
        list.getSelectionModel().select(Math.min(cur - viewTop, list.getItems().size() - 1));
    }

    /** 确认选中：仅返回选中项 insertText（无选中返回 null），不关弹层——hide 由调用方
     *  （InputView）在拿到插入文本后统一执行，避免 KEY_PRESSED 派发期间 hide 与插入竞争 */
    public String confirmSelected() {
        Suggestion sel = list.getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.insertText;
    }

    public void hide() { popup.hide(); }

    public boolean isShowing() { return popup.isShowing(); }
}
