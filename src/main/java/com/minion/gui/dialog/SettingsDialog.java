package com.minion.gui.dialog;

import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.core.mcp.McpManager;
import com.minion.core.mcp.McpServer;
import com.minion.gui.icon.IconFactory;
import com.minion.gui.session.SessionManager;
import com.minion.gui.theme.Theme;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 设置窗（右上角 ⚙）：左列导航 基础设置 / 模型 / MCP / 关于，右侧内容切换；模型操作后触发 applyModelChanged 实时生效 */
public class SettingsDialog {

    public static void show(Window owner, final ModelManager models,
                            final SessionManager manager, final Config config,
                            final McpManager mcp) {
        Dialog<Void> d = new Dialog<Void>();
        d.initOwner(owner);
        d.setTitle("设置");
        final BasicPane basic = new BasicPane(config, owner);
        // 按钮栏「关闭」「应用」相邻，调换为关闭在左、应用在右：
        // ButtonData 按 type 分区排列（LEFT 区 < OTHER 区 < RIGHT 区）。APPLY 属 BUTTON_LEFT
        // （应用落最左区，与关闭不相邻/相邻但在前）；改为 OK_DONE 进 OTHER 区，其字符 O 在
        // CLOSE 的字符 C 之后（Win 顺序串 L_E+U+FBIX_NCYOA_R_G_）→ [关闭][应用]。
        // 注：DialogPane.getButtonBar()/setButtonOrder（8u60+）在本 jfxrt 不可用，仅靠 ButtonData 归区
        ButtonType applyType = new ButtonType("应用", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CLOSE);
        // DialogPane 对任意按钮点击都触发关窗（impl_setResultAndClose）；应用=保存不关窗，须用捕获阶段 filter 先 consume
        ((Button) d.getDialogPane().lookupButton(applyType)).addEventFilter(ActionEvent.ACTION, e -> {
            basic.apply();
            e.consume();
        });
        Theme.style(d);

        // 左列导航：TabPane 侧放文字旋转 90°（历史"字倒了"根因）不可用；ListView 复用现有深色样式
        final ListView<String> nav = new ListView<String>();
        nav.getItems().addAll("基础设置", "模型", "MCP", "关于");
        nav.setPrefWidth(120);
        nav.setMinWidth(120); // HBox 空间不足时按 HGrow 优先级分配，无 HGrow 的子项会被压到最小宽度；minWidth 保证导航列不被压塌
        final Node model = modelPane(models, manager);
        final Node mcpNode = mcpPane(mcp, owner);
        final Node about = aboutPane();
        final StackPane content = new StackPane();
        nav.getSelectionModel().selectedItemProperty().addListener((obs, ov, item) -> {
            if (item == null) return;
            content.getChildren().setAll("基础设置".equals(item) ? basic.root
                    : "模型".equals(item) ? model
                    : "MCP".equals(item) ? mcpNode : about);
        });
        nav.getSelectionModel().select(0); // 默认选中基础设置（选中监听触发内容显示）

        HBox box = new HBox(0);
        box.getChildren().addAll(nav, content);
        HBox.setHgrow(content, Priority.ALWAYS);
        box.setPrefSize(620, 500);
        d.getDialogPane().setContent(box);
        d.showAndWait();
    }

    // ===== 模型页（迁移自 ModelDialog + propagate） =====

    private static VBox modelPane(final ModelManager models, final SessionManager manager) {
        final ListView<String> list = new ListView<String>();
        list.setCellFactory(lv -> new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                // 当前激活模型：名称右侧主色圆点（SVG，替代原 "  ●" 文本）
                if (item.equals(models.currentName())) {
                    SVGPath dot = IconFactory.dot();
                    IconFactory.size(dot, 8);
                    setGraphic(dot);
                } else {
                    setGraphic(null);
                }
                setContentDisplay(ContentDisplay.RIGHT);
                setGraphicTextGap(4);
            }
        });
        refresh(list, models);
        list.setPrefSize(360, 240);

        HBox actions = new HBox(8);
        Button activate = new Button("激活");
        Button add = new Button("新建");
        Button edit = new Button("修改");
        Button del = new Button("删除");
        activate.getStyleClass().add("btn-ghost");
        add.getStyleClass().add("btn-ghost");
        edit.getStyleClass().add("btn-ghost");
        del.getStyleClass().add("btn-ghost");
        activate.setDisable(true); // 初始置灰：首次 refresh 已选中当前模型（监听注册在后，首次选中不触发）
        activate.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            String name = list.getItems().get(idx);
            if (!canActivate(name, models.currentName())) return; // 双保险：已激活不可重复激活
            models.setCurrent(name);
            manager.applyModelChanged(); // 需求 13：切换模型全量生效
            refresh(list, models);
        });
        // 选中项变化（鼠标点击/键盘导航/refresh 重选）联动按钮状态
        list.getSelectionModel().selectedItemProperty().addListener(
                (obs, o, n) -> activate.setDisable(!canActivate(n, models.currentName())));
        add.setOnAction(e -> {
            ModelConfig mc = form(null);
            if (mc != null) {
                if (!models.add(mc)) error("新建失败", "标识名非法或已存在");
                if (models.currentName().equals(mc.displayName)) manager.applyModelChanged();
            }
            refresh(list, models);
        });
        edit.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            ModelConfig mc = form(models.get(list.getItems().get(idx)));
            if (mc != null) {
                models.update(mc);
                manager.applyModelChanged(); // 参数修改实时生效（含运行中会话，下一轮生效）
            }
            refresh(list, models);
        });
        del.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            String name = list.getItems().get(idx);
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除模型「" + name + "」？", ButtonType.OK, ButtonType.CANCEL);
            Theme.style(a);
            a.setTitle("删除模型");
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                if (!models.remove(name)) error("删除失败", "至少保留一个模型");
                manager.applyModelChanged(); // 删除后 current 可能回落，统一 propagate
            }
            refresh(list, models);
        });
        actions.getChildren().addAll(activate, add, edit, del);

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.getChildren().addAll(list, actions);
        return box;
    }

    private static void refresh(ListView<String> list, ModelManager models) {
        list.getItems().clear();
        for (ModelConfig m : models.list()) {
            list.getItems().add(m.displayName);
        }
        int idx = list.getItems().indexOf(models.currentName());
        // 重选当前模型：「激活」按钮的置灰联动依赖此次选中变化触发监听，勿改为不重选/增量刷新
        list.getSelectionModel().select(idx < 0 ? 0 : idx);
    }

    /** 选中项可激活：非空且不同于当前模型（模型页「激活」按钮启用/置灰判定） */
    static boolean canActivate(String selectedName, String currentName) {
        return selectedName != null && !selectedName.equals(currentName);
    }

    /** 新建（mc==null 带默认值）/ 修改（mc!=null 预填）表单；OK 返回配置，取消返回 null（迁移自 ModelDialog） */
    private static ModelConfig form(ModelConfig mc) {
        Dialog<ModelConfig> d = new Dialog<ModelConfig>();
        d.setTitle(mc == null ? "新建模型" : "修改模型");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        TextField displayName = new TextField(mc == null ? "" : mc.displayName);
        displayName.setPromptText("页签显示标识");
        if (mc != null) displayName.setDisable(true); // ModelManager.update 不复制 displayName
        TextField url = new TextField(mc == null ? "https://api.deepseek.com/v1/chat/completions" : mc.url);
        TextField apiKey = new TextField(mc == null ? "" : mc.apiKey);
        apiKey.setPromptText("sk-...");
        TextField modelName = new TextField(mc == null ? "" : mc.modelName);
        ComboBox<String> provider = new ComboBox<String>();
        provider.getItems().addAll("qwen", "deepseek");
        if (mc == null) {
            provider.setValue("deepseek");
        } else {
            // 编辑旧配置：按原值忽略大小写回填，匹配不到取 deepseek
            String p = mc.provider == null ? "deepseek" : mc.provider;
            boolean hit = false;
            for (String opt : provider.getItems()) {
                if (opt.equalsIgnoreCase(p)) { provider.setValue(opt); hit = true; break; }
            }
            if (!hit) provider.setValue("deepseek");
        }
        CheckBox thinking = new CheckBox("深度思考");
        thinking.setSelected(mc != null && mc.thinking);
        ComboBox<String> effort = new ComboBox<String>();
        effort.getItems().addAll("low", "medium", "high", "xhigh", "max");
        effort.setValue(mc == null ? "max" : mc.reasoningEffort);
        TextField maxCtx = new TextField(mc == null ? "900000" : String.valueOf(mc.maxContextTokens));
        TextField thr = new TextField(mc == null ? "0.8" : String.valueOf(mc.compressThreshold));
        TextField keep = new TextField(mc == null ? "10" : String.valueOf(mc.keepRecentMessages));

        grid.addRow(0, new Label("标识名:"), displayName);
        grid.addRow(1, new Label("URL:"), url);
        grid.addRow(2, new Label("API Key:"), apiKey);
        grid.addRow(3, new Label("模型名:"), modelName);
        grid.addRow(4, new Label("provider:"), provider);
        grid.addRow(5, new Label("思考:"), thinking);
        grid.addRow(6, new Label("effort:"), effort);
        grid.addRow(7, new Label("maxContextTokens:"), maxCtx);
        grid.addRow(8, new Label("compressThreshold:"), thr);
        grid.addRow(9, new Label("keepRecentMessages:"), keep);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            ModelConfig out = new ModelConfig();
            out.displayName = displayName.getText().trim();
            out.url = url.getText().trim();
            out.apiKey = apiKey.getText().trim();
            out.modelName = modelName.getText().trim();
            out.provider = provider.getValue() == null ? "deepseek" : provider.getValue();
            out.thinking = thinking.isSelected();
            out.reasoningEffort = effort.getValue() == null ? "max" : effort.getValue();
            out.maxContextTokens = parseInt(maxCtx.getText(), 900000);
            out.compressThreshold = parseDouble(thr.getText(), 0.8);
            out.keepRecentMessages = parseInt(keep.getText(), 10);
            return out;
        });
        Optional<ModelConfig> r = d.showAndWait();
        return r.isPresent() ? r.get() : null;
    }

    // ===== MCP 页（列表 + 状态点 + 启用开关 + 新建/编辑/删除/重连） =====

    /** MCP 页：服务器列表（状态点/传输/工具数/失败原因/启用开关）+ 操作按钮；连接线程回调经 FX 刷新 */
    private static VBox mcpPane(final McpManager mcp, final Window owner) {
        if (mcp == null) { // 未装配（异常路径）：空列表提示
            VBox empty = new VBox(10);
            empty.setPadding(new Insets(10));
            Label tip = new Label("MCP 管理器未装配");
            tip.getStyleClass().add("msg-thinking");
            empty.getChildren().add(tip);
            return empty;
        }
        final ListView<McpServer> list = new ListView<McpServer>();
        list.setCellFactory(lv -> new ListCell<McpServer>() {
            @Override protected void updateItem(McpServer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                // 状态点：灰=未启用 绿=已连接 橙=连接中 红=失败（SVG 圆点，4 态色内联）
                String color = !item.enabled ? "gray"
                        : item.state == McpServer.State.CONNECTED ? "green"
                        : item.state == McpServer.State.CONNECTING ? "orange" : "red";
                SVGPath dot = IconFactory.dot();
                IconFactory.size(dot, 8);
                dot.setStyle("-fx-fill: " + color + ";");
                Label name = new Label(item.name);
                String metaText = item.transport
                        + (item.state == McpServer.State.CONNECTED
                            ? "  " + (item.tools.size() - item.skippedTools) + " 工具"
                            : item.state == McpServer.State.FAILED ? "  失败: " + shorten(item.failReason)
                            : item.state == McpServer.State.CONNECTING ? "  连接中…" : "");
                Label meta = new Label(metaText);
                meta.getStyleClass().add("msg-thinking");
                CheckBox on = new CheckBox("启用");
                on.setSelected(item.enabled);
                on.selectedProperty().addListener((obs, ov, nv) -> {
                    item.enabled = nv;
                    if (nv) mcp.ensureConnectedAsync(item.name);
                    else mcp.disconnect(item.name);
                });
                HBox box = new HBox(8, dot, name, meta, on);
                HBox.setHgrow(meta, Priority.ALWAYS);
                setGraphic(box);
            }
        });
        refresh(list, mcp);
        // 连接线程回调（onStateChanged 在后台连接线程）：切回 FX 线程刷新列表
        mcp.addListener(s -> javafx.application.Platform.runLater(() -> refresh(list, mcp)));

        HBox actions = new HBox(8);
        Button add = new Button("新建");
        Button edit = new Button("编辑");
        Button del = new Button("删除");
        Button reconnect = new Button("重连");
        add.getStyleClass().add("btn-ghost");
        edit.getStyleClass().add("btn-ghost");
        del.getStyleClass().add("btn-ghost");
        reconnect.getStyleClass().add("btn-ghost");
        add.setOnAction(e -> {
            McpServer s = form(null, owner);
            if (s != null) {
                mcp.servers().add(s);
                mcp.save();
            }
            refresh(list, mcp);
        });
        edit.setOnAction(e -> {
            McpServer s = list.getSelectionModel().getSelectedItem();
            if (s == null) return;
            McpServer out = form(s, owner);
            if (out != null) {
                mcp.save();
                // 命令/传输可能已改：原连接态 → 断开重连（新配置生效）
                if (out.state != McpServer.State.DISCONNECTED) {
                    mcp.disconnect(out.name);
                    mcp.ensureConnectedAsync(out.name);
                }
            }
            refresh(list, mcp);
        });
        del.setOnAction(e -> {
            McpServer s = list.getSelectionModel().getSelectedItem();
            if (s == null) return;
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除 MCP 服务器「" + s.name + "」？", ButtonType.OK, ButtonType.CANCEL);
            Theme.style(a);
            a.setTitle("删除 MCP 服务器");
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                mcp.servers().remove(s);
                mcp.disconnect(s.name);
                mcp.save();
            }
            refresh(list, mcp);
        });
        reconnect.setOnAction(e -> {
            McpServer s = list.getSelectionModel().getSelectedItem();
            if (s == null) return;
            mcp.reconnect(s.name); // 同步等待 ≤10s；成功/失败后状态刷新
            refresh(list, mcp);
        });
        actions.getChildren().addAll(add, edit, del, reconnect);

        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.getChildren().addAll(list, actions);
        return box;
    }

    private static void refresh(ListView<McpServer> list, McpManager mcp) {
        list.getItems().clear();
        list.getItems().addAll(mcp.servers());
    }

    /** 新建（null 带默认值）/ 编辑（预填）MCP 服务器表单；OK 返回服务器对象（编辑回写原对象），取消 null */
    private static McpServer form(McpServer s, final Window owner) {
        Dialog<McpServer> d = new Dialog<McpServer>();
        d.initOwner(owner);
        d.setTitle(s == null ? "新建 MCP 服务器" : "编辑 MCP 服务器");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Theme.style(d);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        TextField name = new TextField(s == null ? "" : s.name);
        ComboBox<String> transport = new ComboBox<String>();
        transport.getItems().addAll("stdio", "sse");
        transport.setValue(s == null ? "stdio" : s.transport);
        TextField command = new TextField(s == null ? "npx" : s.command);
        TextArea argsArea = new TextArea(s == null ? "@playwright/mcp" : joinLines(s.args));
        TextArea envArea = new TextArea(s == null ? "" : pairLines(s.env));
        TextField url = new TextField(s == null ? "" : s.url);
        TextArea headerArea = new TextArea(s == null ? "" : pairLines(s.headers));
        // TextArea 默认 pref 高 231px/宽 683px，3 个会把表单撑到 ~900px 超屏；
        // 与基础设置页白名单一致压到 2 行 20 列（表单高 ~400px 放得下）
        argsArea.setPrefRowCount(2);
        argsArea.setPrefColumnCount(20);
        envArea.setPrefRowCount(2);
        envArea.setPrefColumnCount(20);
        headerArea.setPrefRowCount(2);
        headerArea.setPrefColumnCount(20);
        // 传输为 sse 时命令/参数区禁用（命令只对 stdio 有意义）
        transport.valueProperty().addListener((obs, ov, nv) -> {
            boolean stdio = "stdio".equals(nv);
            command.setDisable(!stdio);
            argsArea.setDisable(!stdio);
        });
        boolean stdio0 = "stdio".equals(transport.getValue());
        command.setDisable(!stdio0);
        argsArea.setDisable(!stdio0);

        grid.addRow(0, new Label("名称:"), name);
        grid.addRow(1, new Label("传输:"), transport);
        grid.addRow(2, new Label("命令:"), command);
        grid.addRow(3, new Label("参数(每行一个):"), argsArea);
        grid.addRow(4, new Label("环境变量(KEY=VALUE):"), envArea);
        grid.addRow(5, new Label("URL(SSE):"), url);
        grid.addRow(6, new Label("请求头(K:V):"), headerArea);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String nm = name.getText().trim();
            if (nm.isEmpty()) return null; // 名称空视为取消
            McpServer out = s == null ? new McpServer() : s;
            out.name = nm;
            out.transport = transport.getValue() == null ? "stdio" : transport.getValue();
            out.command = command.getText().trim();
            out.args = splitLines(argsArea.getText());
            out.env = parsePairs(envArea.getText());
            out.url = url.getText().trim();
            out.headers = parsePairs(headerArea.getText());
            out.enabled = s != null && s.enabled; // 新建默认禁用（用户勾选启用时再连接）
            return out;
        });
        Optional<McpServer> r = d.showAndWait();
        return r.isPresent() ? r.get() : null;
    }

    // ===== MCP 表单文本辅助（纯逻辑，测试覆盖） =====

    /** 参数列表 → 每行一个 */
    static String joinLines(List<String> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String a : args) sb.append(a).append('\n');
        return sb.toString();
    }

    /** 键值表 → KEY=VALUE 每行一个 */
    static String pairLines(Map<String, String> pairs) {
        if (pairs == null || pairs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 每行一个：trim 后去空行 */
    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    /** KEY=VALUE（或 K:V）逐行解析；非法行忽略 */
    static Map<String, String> parsePairs(String text) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            int i = line.indexOf('=');
            if (i < 0) i = line.indexOf(':');
            if (i <= 0) continue;
            out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        return out;
    }

    /** 失败原因截断（列表显示）：null→空、取首行、超 40 字符截断加省略号 */
    static String shorten(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String first = i < 0 ? s : s.substring(0, i);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    // ===== 基础设置页 =====

    /** 基础设置页：控件 + 保存逻辑（按钮栏「应用」接线用） */
    private static class BasicPane {
        final Node root;
        private final Config config;
        private final TextField skillsDir;
        private final TextArea toolWhitelist;
        private final TextArea cmdWhitelist;
        private final CheckBox allowOutside;
        private final CheckBox skipConfirm;
        private final CheckBox enterSends;
        private final TextField browserPath;
        private final TextField browserPort;
        private final TextField browserUserData;
        private final CheckBox browserHeadless;
        private final TextField browserTimeout;

        BasicPane(final Config config, final Window owner) {
            this.config = config;
            HBox skillsBox = new HBox(6);
            skillsDir = new TextField(config.skillsDir());
            HBox.setHgrow(skillsDir, Priority.ALWAYS);
            Button browse = new Button("浏览…");
            browse.getStyleClass().add("btn-ghost");
            browse.setOnAction(e -> {
                DirectoryChooser dc = new DirectoryChooser();
                String cur = skillsDir.getText().trim();
                if (!cur.isEmpty()) {
                    java.io.File f = new java.io.File(cur);
                    if (f.isDirectory()) dc.setInitialDirectory(f);
                }
                java.io.File dir = dc.showDialog(owner);
                if (dir != null) skillsDir.setText(dir.getAbsolutePath());
            });
            skillsBox.getChildren().addAll(skillsDir, browse);
            toolWhitelist = new TextArea(config.get("confirm.whitelist.tools", ""));
            toolWhitelist.setPrefRowCount(2);
            toolWhitelist.setPrefColumnCount(20); // 默认 40 列偏好宽 ≈624px 把基础页撑到 794，触发 HBox 压缩导航列；20 列后偏好宽 ~500 与内容区匹配
            cmdWhitelist = new TextArea(config.get("confirm.whitelist.commands", ""));
            cmdWhitelist.setPrefRowCount(2);
            cmdWhitelist.setPrefColumnCount(20);
            allowOutside = new CheckBox("允许读取工作区外文件（Read/Grep/Glob）");
            allowOutside.setSelected(config.readAllowOutside());
            skipConfirm = new CheckBox("跳过高危操作确认");
            skipConfirm.setSelected(config.confirmSkip());
            enterSends = new CheckBox("Enter 发送消息（Ctrl+Enter 换行）");
            enterSends.setSelected(config.enterSends());
            // 勾选立即生效：直接写回 Config（内存+落盘），InputView 按键时读取 → 下次按键即新键位；无需点「应用」
            enterSends.selectedProperty().addListener((obs, ov, nv) ->
                    config.set("input.enterSends", String.valueOf(nv)));
            Label browserNote = new Label("浏览器配置（以下项需重启后生效）");
            browserNote.getStyleClass().add("msg-thinking");
            browserPath = new TextField(config.browserPath());
            HBox browserPathBox = new HBox(6);
            HBox.setHgrow(browserPath, Priority.ALWAYS);
            Button browseExe = new Button("浏览…");
            browseExe.getStyleClass().add("btn-ghost");
            browseExe.setOnAction(e -> {
                javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                fc.setTitle("选择浏览器程序");
                fc.getExtensionFilters().addAll(
                        new javafx.stage.FileChooser.ExtensionFilter("可执行文件", "*.exe"),
                        new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*"));
                // 当前值若是存在的文件，初始定位到其父目录
                String cur = browserPath.getText().trim();
                java.io.File f = new java.io.File(cur);
                if (f.isFile() && f.getParentFile() != null && f.getParentFile().isDirectory()) {
                    fc.setInitialDirectory(f.getParentFile());
                }
                java.io.File file = fc.showOpenDialog(owner);
                if (file != null) browserPath.setText(file.getAbsolutePath());
            });
            browserPathBox.getChildren().addAll(browserPath, browseExe);
            browserPort = new TextField(String.valueOf(config.browserPort()));
            browserUserData = new TextField(config.browserUserDataDir());
            browserHeadless = new CheckBox("无头模式");
            browserHeadless.setSelected(config.browserHeadless());
            browserTimeout = new TextField(String.valueOf(config.browserTimeoutMs()));

            VBox rows = new VBox(10);
            rows.getChildren().addAll(
                    row("技能目录 skills.dir:", skillsBox),
                    row("确认白名单\n(工具, 逗号分隔):", toolWhitelist),
                    row("确认白名单\n(命令, 逗号分隔):", cmdWhitelist),
                    row("读逃逸:", allowOutside),
                    row("确认开关:", skipConfirm),
                    row("发送键:", enterSends),
                    browserNote,
                    row("browser.path:", browserPathBox),
                    row("browser.port:", browserPort),
                    row("browser.userDataDir:", browserUserData),
                    row("browser.headless:", browserHeadless),
                    row("browser.timeoutMs:", browserTimeout));

            VBox contentBox = new VBox(10);
            contentBox.getChildren().addAll(rows);
            contentBox.setPadding(new Insets(12));
            // 去 ScrollPane：JavaFX 8 裁剪内文字回退灰阶 AA 是整页发虚根因；
            // 内容高约 453px，620x500 固定窗放得下，无需滚动
            this.root = contentBox;
        }

        /** 「应用」按钮：全部配置项写入，窗口不关闭；port/timeoutMs 非法弹错且该项不写 */
        void apply() {
            config.set("skills.dir", skillsDir.getText().trim());
            // 白名单是逗号分隔的单行配置：多行粘贴的换行替换为空格，否则落盘后重载会静默丢内容
            config.set("confirm.whitelist.tools",
                    toolWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
            config.set("confirm.whitelist.commands",
                    cmdWhitelist.getText().trim().replace('\n', ' ').replace('\r', ' '));
            config.set("paths.read.allowOutside", String.valueOf(allowOutside.isSelected()));
            config.set("confirm.skip", String.valueOf(skipConfirm.isSelected()));
            config.set("browser.path", browserPath.getText().trim());
            if (!setInt("browser.port", browserPort.getText(), config)) {
                error("保存失败", "browser.port 必须是整数，未保存");
            }
            config.set("browser.userDataDir", browserUserData.getText().trim());
            config.set("browser.headless", String.valueOf(browserHeadless.isSelected()));
            if (!setInt("browser.timeoutMs", browserTimeout.getText(), config)) {
                error("保存失败", "browser.timeoutMs 必须是整数，未保存");
            }
        }
    }

    /** 表单行：标签固定宽 160 不收缩（GridPane+ColumnConstraints 在 JavaFX 8 下仍挤压截断，弃用），输入控件铺满剩余宽度 */
    private static HBox row(String labelText, Region control) {
        Label l = new Label(labelText);
        l.setMinWidth(160);
        l.setPrefWidth(160);
        l.setWrapText(true);
        control.setMaxWidth(Double.MAX_VALUE);
        HBox box = new HBox(8);
        HBox.setHgrow(control, Priority.ALWAYS);
        box.getChildren().addAll(l, control);
        return box;
    }

    // ===== 关于页 =====

    private static VBox aboutPane() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.getChildren().addAll(
                new Label("兼容win7的代码开发助手"),
                new Separator(),
                new Label("作者：尹承"),
                new Label("联系方式：258915527@qq.com"),
                new Label("开发语言：Java 8 + JavaFX"));
        return box;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    /** 保存前校验整数型配置项：非法（非整数/负数/空）→ 不写回并返回 false（调用方弹错）；合法 → 写回 */
    static boolean setInt(String key, String text, Config config) {
        if (parseInt(text, -1) < 0) return false;
        config.set(key, text.trim());
        return true;
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        Theme.style(a);
        a.showAndWait();
    }
}
