# 输入区大框+斜杠命令恢复+补全弹层 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复斜杠命令（/skills /skill /help /compact /tokens）客户端本地分发，新增 `/` 命令与 `@` 文件补全弹层（Popup+ListView），输入区重构为 4/9 居中大框（左输入右按钮+竖分割线），修复提问挂起时按钮误显方块与输入文字发虚，设置窗「应用」按钮归位。

**Architecture:** 补全弹层为通用 SuggestionPopup（Popup+ListView，锚定输入大框上方同宽），命令由 gui/command/CommandDispatcher 在会话工作线程本地执行、结果经 EventList 新增 SYSTEM 事件渲染为聊天区系统行（永不发给 LLM）；补全触发解析（CompletionParser）与过滤排序均为纯静态方法可单测。

**Tech Stack:** JDK 8 + JavaFX 8（jfxrt）、JUnit4、Maven（JAVA_HOME="E:/javame/jdk8"）。

**设计文档:** docs/superpowers/specs/2026-08-14-input-command-suggest-design.md（7a3871d）

## Global Constraints

- JDK 8 兼容，禁 Java 9+ API（如 List.of/var）；测试 JUnit4（org.junit.Test + assert*）
- 构建/测试命令：`JAVA_HOME="E:/javame/jdk8" mvn compile`、`JAVA_HOME="E:/javame/jdk8" mvn test`（bash 中不得出现中文，wrapper 会崩溃）
- 文档、注释、commit 均用中文；commit 用 conventional 格式；**commit 消息必须先经 Write 工具写入 `.git/COMMIT_MSG_TMP.txt` 再 `git commit -F`**（bash 传中文参数必崩）
- 跨线程回调一律 Platform.runLater；新代码落位：界面→gui、核心→core；资源目录 src/resource；CSS 文件 src/resource/theme/theme.css
- 补全弹层为 GUI 增强：任何异常不得打断输入主流程（try/catch 兜底隐藏弹层）
- API 契约不受影响：不改 LLM 消息结构（tool_call↔tool 配对、reasoning_content 原样回传）
- 用户 API key（sk-f496...）不得写入任何文件（本次全部用 FakeLlmClient，无需真实 key）
- 每任务独立提交：`git add` 只加本任务文件（并行 agent 共享 main 分支）

---

### Task 1: EventList SYSTEM 事件 + ChatView 渲染 + SessionController.onSystem

**Files:**
- Modify: `src/main/java/com/minion/gui/session/EventList.java:14-15`（Kind 枚举）
- Modify: `src/main/java/com/minion/gui/chat/ChatView.java`（onEventFx switch，STATS case 旁）
- Modify: `src/main/java/com/minion/gui/session/SessionController.java`（类尾加方法）
- Test: `src/test/java/com/minion/gui/session/EventListTest.java`（追加测试）

**Interfaces:**
- Produces: `EventList.Kind.SYSTEM`；`SessionController.onSystem(String)`（Task 5 使用）

- [ ] **Step 1: 写失败测试**（EventListTest 追加，镜像现有 inactive_buffersEvents 模式）

```java
    /** SYSTEM（斜杠命令结果）与非激活缓冲机制兼容：不激活入缓冲，激活重放 */
    @Test
    public void system_buffersAndReplays() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.SYSTEM, "已加载技能: x", null));
        assertEquals(1, l.size());
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        assertEquals(1, seen.size());
        assertEquals(EventList.Kind.SYSTEM, seen.get(0).kind);
        assertEquals("已加载技能: x", seen.get(0).text);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=EventListTest`
Expected: 编译失败（Kind.SYSTEM 不存在）

- [ ] **Step 3: 实现**（三处小改）

EventList.java 枚举行改为：

```java
    public enum Kind {
        USER_MESSAGE, USER_SUPPLEMENT, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT,
        SUB_AGENT_START, SUB_AGENT_DELTA, SUB_AGENT_DONE, STATS, SYSTEM, ERROR, WARNING
    }
```

ChatView.java onEventFx 的 `case STATS:` 块后追加：

```java
            case SYSTEM: // 斜杠命令结果等 GUI 本地事件（不入 LLM 历史）
                getChildren().add(alert(e.text, "msg-thinking"));
                break;
```

SessionController.java 在 onUserSupplement 方法后追加：

```java
    /** 系统行（斜杠命令结果等 GUI 本地事件；非 AgentUi 接口方法，仅命令分发路径使用） */
    public void onSystem(String text) {
        events.add(new EventList.Ev(EventList.Kind.SYSTEM, text, null));
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=EventListTest`
Expected: PASS（5 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/session/EventList.java src/main/java/com/minion/gui/chat/ChatView.java src/main/java/com/minion/gui/session/SessionController.java src/test/java/com/minion/gui/session/EventListTest.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息文件内容: feat: 事件流新增 SYSTEM 事件类型（斜杠命令结果渲染通道）
```

---

### Task 2: InputView 按钮状态机修复（ANSWER_DIM + Esc 终止）

**Files:**
- Modify: `src/main/java/com/minion/gui/input/InputView.java`（BtnMode 枚举、buttonMode、updateButton、onAction、keyPressed）
- Test: `src/test/java/com/minion/gui/input/InputViewTest.java`（新建）

**Interfaces:**
- Produces: `InputView.BtnMode.ANSWER_DIM`；`buttonMode(boolean running, boolean askPending, boolean hasContent)` 语义变更（Task 7 依赖）

- [ ] **Step 1: 写失败测试**（新建 InputViewTest，BtnMode 为包内枚举、buttonMode 为包内静态，测试同包访问）

```java
package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;

/** 按钮模式判定为纯静态状态机，脱离 JavaFX 单测（BtnMode/buttonMode 包内可见） */
public class InputViewTest {

    @Test public void idleEmpty_sendDim() {
        assertEquals(InputView.BtnMode.SEND_DIM, InputView.buttonMode(false, false, false));
    }

    @Test public void idleWithContent_send() {
        assertEquals(InputView.BtnMode.SEND, InputView.buttonMode(false, false, true));
    }

    @Test public void runningEmpty_stop() {
        assertEquals(InputView.BtnMode.STOP, InputView.buttonMode(true, false, false));
    }

    @Test public void runningWithContent_supplement() {
        assertEquals(InputView.BtnMode.SUPPLEMENT, InputView.buttonMode(true, false, true));
    }

    /** 提问挂起 + 有内容：回答箭头 */
    @Test public void askPendingWithContent_answer() {
        assertEquals(InputView.BtnMode.ANSWER, InputView.buttonMode(true, true, true));
    }

    /** 提问挂起 + 空输入：变淡回答箭头（模型在等回答而非忙碌，不显示终止方块） */
    @Test public void askPendingEmpty_answerDim() {
        assertEquals(InputView.BtnMode.ANSWER_DIM, InputView.buttonMode(true, true, false));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=InputViewTest`
Expected: 编译失败（ANSWER_DIM 不存在；askPendingEmpty 断言 STOP 失败）

- [ ] **Step 3: 实现**

InputView.java：

枚举行改为：

```java
    /** 按钮模式：图标/透明度/背景类/动作的判定依据（ANSWER_DIM=提问挂起且空输入，变淡箭头等待输入回答） */
    enum BtnMode { SEND, SEND_DIM, SUPPLEMENT, ANSWER, ANSWER_DIM, STOP }
```

buttonMode 方法体改为（提问挂起优先于运行中判定，挂起时不显示终止方块）：

```java
    /** 纯静态判定（可脱离 JavaFX 单测）：运行/提问挂起/有内容 → 按钮模式。
     *  提问挂起时模型在等待回答而非忙碌，空输入显示变淡箭头而非终止方块 */
    static BtnMode buttonMode(boolean running, boolean askPending, boolean hasContent) {
        if (!running) return hasContent ? BtnMode.SEND : BtnMode.SEND_DIM;
        if (askPending) return hasContent ? BtnMode.ANSWER : BtnMode.ANSWER_DIM;
        return hasContent ? BtnMode.SUPPLEMENT : BtnMode.STOP;
    }
```

updateButton switch 中 ANSWER case 后追加：

```java
            case ANSWER_DIM: applyStyle(arrowIcon, "btn-primary", 0.35, "输入回答后发送 (Ctrl+Enter)"); break;
```

onAction switch 中 SEND_DIM case 后追加（变淡状态点击无动作）：

```java
            case ANSWER_DIM:
                break;
```

keyPressed 监听器（`input.setOnKeyPressed`）改为（Esc = 终止入口，Ctrl+Enter 分支保持在前）：

```java
        input.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onAction();
                return;
            }
            // Esc：终止当前运行（提问挂起时亦可终止；补全弹层接线在 Task 7 前置拦截）
            if (e.getCode() == KeyCode.ESCAPE && current != null && running) {
                e.consume();
                manager.stop(current);
            }
        });
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=InputViewTest`
Expected: PASS（6 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/input/InputView.java src/test/java/com/minion/gui/input/InputViewTest.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: fix: 提问挂起时按钮改变淡回答箭头（不再误显终止方块）+ Esc 终止入口
```

---

### Task 3: CompletionParser 词提取与补全模式判定

**Files:**
- Create: `src/main/java/com/minion/gui/input/CompletionParser.java`
- Test: `src/test/java/com/minion/gui/input/CompletionParserTest.java`

**Interfaces:**
- Produces: `CompletionParser.parse(String text, int caret)` → `CompletionParser.Token{mode, query, start, end}`；`Mode{NONE, SLASH, SLASH_SKILL, FILE}`（Task 7 使用）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;
import static com.minion.gui.input.CompletionParser.*;

/** 补全触发解析：纯静态、无 JavaFX 依赖 */
public class CompletionParserTest {

    private Token p(String text, int caret) { return CompletionParser.parse(text, caret); }

    @Test public void emptyText_none() {
        Token t = p("", 0);
        assertEquals(Mode.NONE, t.mode);
    }

    @Test public void slashAlone_triggersWithEmptyQuery() {
        Token t = p("/", 1);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("", t.query);
        assertEquals(0, t.start);
        assertEquals(1, t.end);
    }

    @Test public void slashPartial_queryAfterSlash() {
        Token t = p("/ski", 4);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("ski", t.query);
    }

    @Test public void slashMidSentence_usesCurrentWord() {
        // 词边界在空白处：光标在第 2 个词内 → 取该词
        Token t = p("修复 /ski", 6);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("ski", t.query);
        assertEquals(3, t.start);
        assertEquals(7, t.end);
    }

    @Test public void skillArg_filtersSkillNames() {
        // "/skill bran" 光标在末尾：当前词 bran，前一词 /skill → 技能名补全
        Token t = p("/skill bran", 11);
        assertEquals(Mode.SLASH_SKILL, t.mode);
        assertEquals("bran", t.query);
    }

    @Test public void skillArgEmpty_afterSpaceShowsAllSkills() {
        // "/skill " 光标在空格后：当前词为空，前一词 /skill → 技能名补全、query 空
        Token t = p("/skill ", 7);
        assertEquals(Mode.SLASH_SKILL, t.mode);
        assertEquals("", t.query);
    }

    @Test public void atTriggers_fileMode() {
        Token t = p("@Ma", 3);
        assertEquals(Mode.FILE, t.mode);
        assertEquals("Ma", t.query);
    }

    @Test public void atAlone_fileModeEmptyQuery() {
        Token t = p("你好 @", 4);
        assertEquals(Mode.FILE, t.mode);
        assertEquals("", t.query);
    }

    @Test public void emailLike_doesNotTrigger() {
        Token t = p("发到 a@b.com", 8);
        assertEquals(Mode.NONE, t.mode);
    }

    @Test public void plainWord_none() {
        Token t = p("你好", 2);
        assertEquals(Mode.NONE, t.mode);
    }

    @Test public void multiline_wordAcrossLines() {
        Token t = p("第一行\n/ski", 7);
        assertEquals(Mode.SLASH, t.mode);
        assertEquals("ski", t.query);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=CompletionParserTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**（新建 CompletionParser.java，纯静态无 JavaFX）

```java
package com.minion.gui.input;

/** 补全触发解析：按光标位置提取当前「词」，判定 /命令、@文件 或普通文本。纯静态，可单测。
 *  词边界 = 空白（含换行）；前一词为 /skill 时当前词按技能名补全。 */
public final class CompletionParser {

    /** 补全模式：NONE 普通文本 / SLASH 斜杠命令 / SLASH_SKILL 技能名 / FILE 文件 */
    public enum Mode { NONE, SLASH, SLASH_SKILL, FILE }

    /** 解析结果：mode 模式 / query 过滤词（不含前缀）/ start,end 替换区间（含前缀） */
    public static final class Token {
        public final Mode mode;
        public final String query;
        public final int start;
        public final int end;

        public Token(Mode mode, String query, int start, int end) {
            this.mode = mode;
            this.query = query;
            this.start = start;
            this.end = end;
        }
    }

    public static Token parse(String text, int caret) {
        if (text == null || text.isEmpty()) return new Token(Mode.NONE, "", 0, 0);
        int c = Math.max(0, Math.min(caret, text.length()));
        // 当前词：空白分隔
        int s = c;
        while (s > 0 && !isSep(text.charAt(s - 1))) s--;
        int e = c;
        while (e < text.length() && !isSep(text.charAt(e))) e++;
        String word = text.substring(s, e);
        if (word.startsWith("/")) {
            return new Token(Mode.SLASH, word.substring(1), s, e);
        }
        if (word.startsWith("@")) {
            return new Token(Mode.FILE, word.substring(1), s, e);
        }
        // 普通词：前一词是 /skill → 技能名补全
        int ps = s;
        while (ps > 0 && isSep(text.charAt(ps - 1))) ps--;
        int pe = ps;
        while (pe > 0 && !isSep(text.charAt(pe - 1))) pe--;
        String prev = pe == ps ? "" : text.substring(pe, ps);
        if ("/skill".equalsIgnoreCase(prev)) {
            return new Token(Mode.SLASH_SKILL, word, s, e);
        }
        return new Token(Mode.NONE, "", s, e);
    }

    private static boolean isSep(char ch) {
        return Character.isWhitespace(ch);
    }

    private CompletionParser() { }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=CompletionParserTest`
Expected: PASS（11 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/input/CompletionParser.java src/test/java/com/minion/gui/input/CompletionParserTest.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: feat: 补全触发解析器（/命令、@文件、/skill 技能名三种模式，纯静态可单测）
```

---

### Task 4: Suggestion 条目 + SuggestionPopup 弹层组件

**Files:**
- Create: `src/main/java/com/minion/gui/input/Suggestion.java`
- Create: `src/main/java/com/minion/gui/input/SuggestionPopup.java`
- Test: `src/test/java/com/minion/gui/input/SuggestionPopupTest.java`（仅静态过滤排序，弹层本体为 UI 不做单测）

**Interfaces:**
- Produces: `Suggestion{label, insertText, desc, type}`，`Suggestion.Type{COMMAND, SKILL, FILE}`；`SuggestionPopup.filter(List<Suggestion>, String)`；`SuggestionPopup` 实例方法 `show(Node anchor, List<Suggestion> all, String query)`、`isShowing()`、`move(int delta)`、`confirmSelected()`（返回选中项 insertText，无选中 null）、`hide()`（Task 6/7 使用）

- [ ] **Step 1: 写失败测试**（仅测静态 filter 排序逻辑）

```java
package com.minion.gui.input;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 弹层过滤/排序为纯静态逻辑（弹层本体为 UI，由 run 启动目验） */
public class SuggestionPopupTest {

    private Suggestion s(String label) {
        return new Suggestion(label, label, null, Suggestion.Type.COMMAND);
    }

    @Test public void emptyQuery_keepsAll() {
        List<Suggestion> out = SuggestionPopup.filter(Arrays.asList(s("/skills"), s("/help")), "");
        assertEquals(2, out.size());
    }

    @Test public void filter_caseInsensitiveContains() {
        List<Suggestion> out = SuggestionPopup.filter(Arrays.asList(s("/skills"), s("/help")), "SKI");
        assertEquals(1, out.size());
        assertEquals("/skills", out.get(0).label);
    }

    @Test public void prefixMatch_ranksBeforeContains() {
        // "/skill" 前缀命中排第一；"/skills" 为包含命中排后（技能名条目同属包含命中）
        List<Suggestion> out = SuggestionPopup.filter(
                Arrays.asList(s("/skills"), s("/skill")), "skill");
        assertEquals("/skill", out.get(0).label);
    }

    @Test public void shorterPath_ranksFirstOnTie() {
        List<Suggestion> out = SuggestionPopup.filter(
                Arrays.asList(s("src/main/java/com/minion/Main.java"), s("src/main/Main.java")),
                "Main.java");
        assertEquals("src/main/Main.java", out.get(0).label);
    }

    @Test public void noMatch_returnsEmpty() {
        List<Suggestion> out = SuggestionPopup.filter(Arrays.asList(s("/skills")), "zzz");
        assertTrue(out.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SuggestionPopupTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

Suggestion.java：

```java
package com.minion.gui.input;

/** 补全条目：label 显示文本 / insertText 选中后插入文本 / desc 描述（右对齐灰字） */
public class Suggestion {

    /** 条目类型（样式/图标预留） */
    public enum Type { COMMAND, SKILL, FILE }

    public final String label;
    public final String insertText;
    public final String desc;
    public final Type type;

    public Suggestion(String label, String insertText, String desc, Type type) {
        this.label = label;
        this.insertText = insertText;
        this.desc = desc;
        this.type = type;
    }
}
```

SuggestionPopup.java：

```java
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

/** 通用补全弹层：Popup+ListView，锚定输入大框正上方、同宽（Claude Code 风格）。
 *  Popup 不抢焦点：键盘事件由 TextArea 拦截转发（move/confirm/hide）。 */
public class SuggestionPopup {

    /** 行高估算（CSS 未加载时定位用） */
    private static final double ROW_HEIGHT = 30;
    /** 可见条目上限（约 200px） */
    private static final int MAX_VISIBLE = 8;

    private final Popup popup = new Popup();
    private final ListView<Suggestion> list = new ListView<Suggestion>();

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
        list.setOnMouseClicked(e -> confirmSelected());
        popup.getContent().add(list);
        popup.setAutoHide(true);
    }

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

    /** 确认选中：返回选中项 insertText（无选中返回 null），弹层关闭 */
    public String confirmSelected() {
        Suggestion sel = list.getSelectionModel().getSelectedItem();
        hide();
        return sel == null ? null : sel.insertText;
    }

    public void hide() { popup.hide(); }

    public boolean isShowing() { return popup.isShowing(); }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SuggestionPopupTest`
Expected: PASS（5 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/input/Suggestion.java src/main/java/com/minion/gui/input/SuggestionPopup.java src/test/java/com/minion/gui/input/SuggestionPopupTest.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: feat: 通用补全弹层 SuggestionPopup（Popup+ListView，静态过滤排序可单测）
```

---

### Task 5: CommandDispatcher 本地命令分发 + SessionManager 接线

**Files:**
- Create: `src/main/java/com/minion/gui/command/CommandDispatcher.java`
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（字段+构造、skills()、currentWorkspaceDir()、dispatchCommand()）
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java:108`（loadSkill 加 synchronized）
- Test: `src/test/java/com/minion/gui/command/CommandDispatcherTest.java`

**Interfaces:**
- Produces: `CommandDispatcher(List<Skill>).dispatch(SessionHandle h, String input)` 返回 null=非命令 / String=已执行命令的展示文本；`SessionManager.skills()`、`SessionManager.currentWorkspaceDir()`、`SessionManager.dispatchCommand(SessionHandle, String)`（Task 6/7 使用）

- [ ] **Step 1: 写失败测试**（SessionHandle 直接构造，参照 AgentLoopCompactTest 的 AgentLoop 构造方式）

```java
package com.minion.gui.command;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import com.minion.gui.session.EventList;
import com.minion.gui.session.SessionController;
import com.minion.gui.session.SessionHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 斜杠命令本地分发（恢复 CLI 语义；结果永不发给 LLM） */
public class CommandDispatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CommandDispatcher dispatcher;
    private SessionHandle h;
    private SessionController controller;

    @Before
    public void setUp() {
        List<Skill> skills = Arrays.asList(
                new Skill("brainstorming", "需求头脑风暴", "指令正文", "/skills/brainstorming/SKILL.md"),
                new Skill("writing-plans", "编写实施计划", "指令正文", "/skills/writing-plans/SKILL.md"));
        dispatcher = new CommandDispatcher(skills);
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        controller = new SessionController();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        Session s = Session.create(tmp.getRoot().getPath(), "test-model");
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, controller, null, new Workspace(tmp.getRoot().getPath()), s);
        h = new SessionHandle("sid123456789", "ws", s, loop, controller, "标题", false, llm);
    }

    @After
    public void tearDown() {
        h.pool.shutdownNow();
        h.loop.shutdown();
    }

    @Test public void plainText_returnsNull() {
        assertNull(dispatcher.dispatch(h, "帮我修个 bug"));
    }

    @Test public void help_listsCommands() {
        String r = dispatcher.dispatch(h, "/help");
        assertNotNull(r);
        assertTrue(r.contains("/skills"));
        assertTrue(r.contains("/skill"));
        assertTrue(r.contains("/compact"));
        assertTrue(r.contains("/tokens"));
    }

    @Test public void skills_listsSkillNames() {
        String r = dispatcher.dispatch(h, "/skills");
        assertTrue(r.contains("brainstorming"));
        assertTrue(r.contains("writing-plans"));
    }

    @Test public void skill_loadsIntoLoop() {
        String r = dispatcher.dispatch(h, "/skill brainstorming");
        assertEquals("已加载技能: brainstorming", r);
        assertEquals(1, h.loop.loadedSkills().size());
        assertEquals("brainstorming", h.loop.loadedSkills().get(0).name);
        // 系统提示词包含已加载技能指令（下一轮请求生效）
        assertTrue(h.loop.buildSystemPrompt().contains("指令正文"));
    }

    @Test public void skill_caseInsensitive() {
        String r = dispatcher.dispatch(h, "/SKILL BRAINSTORMING");
        assertEquals("已加载技能: brainstorming", r);
    }

    @Test public void skill_missingArgShowsUsage() {
        String r = dispatcher.dispatch(h, "/skill");
        assertTrue(r.contains("用法"));
    }

    @Test public void skill_unknownName() {
        String r = dispatcher.dispatch(h, "/skill notexist");
        assertTrue(r.contains("未找到技能"));
    }

    @Test public void tokens_showsUsageStats() {
        String r = dispatcher.dispatch(h, "/tokens");
        assertTrue(r.startsWith("会话统计"));
    }

    /** /compact 在会话工作线程执行（阻塞 LLM 调用不进 FX 线程）；无压缩管理器时走 onWarning */
    @Test
    public void compact_dispatchesToPoolAndWarnsWithoutContextManager() throws Exception {
        String r = dispatcher.dispatch(h, "/compact");
        assertEquals("已请求压缩上下文（会话空闲后执行）", r);
        h.pool.shutdown();
        assertTrue(h.pool.awaitTermination(3, TimeUnit.SECONDS));
        boolean warned = false;
        for (EventList.Ev e : controller.eventList().snapshot()) {
            if (e.kind == EventList.Kind.WARNING && e.text.contains("未启用上下文压缩")) warned = true;
        }
        assertTrue("compactNow 应经 AgentUi 发出未启用提示", warned);
    }

    @Test public void unknownCommand_returnsErrorText() {
        String r = dispatcher.dispatch(h, "/nosuchcmd");
        assertTrue(r.contains("未知命令"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=CommandDispatcherTest`
Expected: 编译失败（CommandDispatcher 不存在）

- [ ] **Step 3: 实现**

CommandDispatcher.java：

```java
package com.minion.gui.command;

import com.minion.core.skills.Skill;
import com.minion.gui.session.SessionHandle;

import java.util.List;
import java.util.Locale;

/** 斜杠命令本地分发（恢复 CLI 语义）：返回 null = 非命令（按普通消息发送）；
 *  非 null = 已本地执行的命令展示文本。命令结果永不发给 LLM。 */
public class CommandDispatcher {

    private final List<Skill> skills;

    public CommandDispatcher(List<Skill> skills) { this.skills = skills; }

    public String dispatch(SessionHandle h, String input) {
        if (input == null || !input.trim().startsWith("/")) return null;
        String[] parts = input.trim().split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if ("/help".equals(cmd)) return helpText();
        if ("/skills".equals(cmd)) return skillsText();
        if ("/skill".equals(cmd)) return dispatchSkill(h, parts);
        if ("/tokens".equals(cmd)) return tokensText(h);
        if ("/compact".equals(cmd)) return dispatchCompact(h);
        return "未知命令 " + parts[0] + "（/help 查看）";
    }

    private String helpText() {
        return "可用命令：\n"
                + "/help        显示本帮助\n"
                + "/skills      列出可用技能\n"
                + "/skill <名>  加载技能到当前会话（下一轮请求生效）\n"
                + "/compact     立即压缩上下文\n"
                + "/tokens      显示 token 用量统计";
    }

    private String skillsText() {
        if (skills == null || skills.isEmpty()) {
            return "未发现可用技能。请检查 设置 → 基础设置 → 技能目录（skills.dir）";
        }
        StringBuilder sb = new StringBuilder("可用技能（").append(skills.size()).append(" 个）：");
        for (Skill s : skills) sb.append('\n').append("- ").append(s.hint());
        return sb.toString();
    }

    private String dispatchSkill(SessionHandle h, String[] parts) {
        if (parts.length < 2) return "用法: /skill <技能名>（/skills 查看列表）";
        for (Skill s : skills) {
            if (s.name.equalsIgnoreCase(parts[1])) {
                h.loop.loadSkill(s);
                return "已加载技能: " + s.name;
            }
        }
        return "未找到技能: " + parts[1] + "（/skills 查看列表）";
    }

    private String tokensText(SessionHandle h) {
        com.minion.core.llm.UsageTracker t = h.loop.usage();
        return String.format(Locale.ROOT, "会话统计: in %d · out %d · thinking %d · 合计 %d",
                t.sessionInput(), t.sessionOutput(), t.sessionThinking(), t.sessionTotal());
    }

    /** /compact 含阻塞 LLM 调用：提交会话工作线程执行，绝不在 FX 线程跑；运行中时排队等回合结束 */
    private String dispatchCompact(SessionHandle h) {
        h.pool.submit(new Runnable() {
            @Override public void run() { h.loop.compactNow(); }
        });
        return "已请求压缩上下文（会话空闲后执行）";
    }
}
```

AgentLoop.java loadSkill 方法签名加 synchronized：

```java
    /** 按 name 判重：重复加载同一技能会重复注入系统提示词（token 浪费 + 指令歧义）。
     *  synchronized：FX 线程 /skill 加载与会话线程读 loadedSkills 并发（ArrayList 非线程安全） */
    public synchronized void loadSkill(Skill skill) {
```

SessionManager.java：

构造器内（this.browserSession = browserSession; 后）追加字段初始化——先在字段区加声明：

```java
    private final List<Skill> allSkills;
    private final BrowserSession browserSession; // 可为 null（测试）
    private final CommandDispatcher dispatcher; // 斜杠命令本地分发（GUI 输入路径）
```

（字段区已有 `private final List<Skill> allSkills;`，只需在其后/旁加 dispatcher 声明并在构造器 `this.browserSession = browserSession;` 后追加）

```java
        this.dispatcher = new CommandDispatcher(allSkills);
```

import 追加：`import com.minion.gui.command.CommandDispatcher;`

类内加三个公开方法（persist 方法前）：

```java
    /** 全部技能（补全弹层/命令分发共用；启动时扫描） */
    public List<Skill> skills() { return allSkills; }

    /** 当前工作空间 workDir（文件补全遍历根；无当前空间返回 null） */
    public String currentWorkspaceDir() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        return ctx == null ? null : ctx.workspace.workDir();
    }

    /** 斜杠命令本地分发：命中 → 聊天区回显命令 + 系统行结果（不入 LLM 历史）；未命中 → 按普通消息发送 */
    public void dispatchCommand(SessionHandle h, String text) {
        String result = dispatcher.dispatch(h, text);
        if (result == null) { send(h, text); return; }
        h.controller.onUserMessage(text); // 仅展示回显，不注入 LLM 历史
        h.controller.onSystem(result);
    }
```

InputView.onSend 中 `manager.send(target, text);` 改为 `manager.dispatchCommand(target, text);`

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=CommandDispatcherTest,InputViewTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/command/CommandDispatcher.java src/main/java/com/minion/gui/session/SessionManager.java src/main/java/com/minion/core/agent/AgentLoop.java src/main/java/com/minion/gui/input/InputView.java src/test/java/com/minion/gui/command/CommandDispatcherTest.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: feat: 斜杠命令本地分发恢复（/help /skills /skill /compact /tokens，结果入事件流不发 LLM）
```

---

### Task 6: 补全提供器（SlashSuggester + FileSuggester）

**Files:**
- Create: `src/main/java/com/minion/gui/input/SlashSuggester.java`
- Create: `src/main/java/com/minion/gui/input/FileSuggester.java`
- Test: `src/test/java/com/minion/gui/input/SlashSuggesterTest.java`
- Test: `src/test/java/com/minion/gui/input/FileSuggesterTest.java`

**Interfaces:**
- Produces: `SlashSuggester.all(List<Skill>)` / `SlashSuggester.skillEntries(List<Skill>)`（返回 List<Suggestion>）；`FileSuggester.list(String workDir)`（10 秒缓存）、静态 `FileSuggester.walk(String workDir)`（Task 7 使用）

- [ ] **Step 1: 写失败测试**

SlashSuggesterTest.java：

```java
package com.minion.gui.input;

import com.minion.core.skills.Skill;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SlashSuggesterTest {

    @Test public void builtins_coverAllFiveCommands() {
        List<Suggestion> all = SlashSuggester.all(Arrays.<Skill>asList());
        assertEquals(5, all.size());
        assertEquals("/help", all.get(0).insertText);
        assertEquals("/skills", all.get(1).insertText);
        assertEquals("/skill", all.get(2).insertText);
        assertEquals("/compact", all.get(3).insertText);
        assertEquals("/tokens", all.get(4).insertText);
    }

    @Test public void skillEntries_insertFullSkillCommand() {
        List<Suggestion> all = SlashSuggester.all(Arrays.asList(
                new Skill("brainstorming", "需求头脑风暴", "正文", "f.md")));
        Suggestion skill = null;
        for (Suggestion s : all) if (s.type == Suggestion.Type.SKILL) skill = s;
        assertNotNull(skill);
        assertEquals("/skill brainstorming", skill.insertText);
        assertTrue(skill.desc.contains("需求头脑风暴"));
    }

    @Test public void skillsOnly_excludesBuiltins() {
        List<Suggestion> only = SlashSuggester.skillEntries(Arrays.asList(
                new Skill("brainstorming", "需求头脑风暴", "正文", "f.md")));
        assertEquals(1, only.size());
        assertEquals(Suggestion.Type.SKILL, only.get(0).type);
    }
}
```

FileSuggesterTest.java：

```java
package com.minion.gui.input;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class FileSuggesterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void walk_listsRelativePathsForwardSlashes() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.createDirectories(root.resolve("src/main/java/com/minion"));
        Files.write(root.resolve("src/main/java/com/minion/Main.java"),
                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(root.resolve("README.md"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<Suggestion> out = FileSuggester.walk(root.toString());
        boolean hasMain = false;
        boolean hasReadme = false;
        for (Suggestion s : out) {
            if ("src/main/java/com/minion/Main.java".equals(s.label)) hasMain = true;
            if ("README.md".equals(s.label)) hasReadme = true;
        }
        assertTrue("应包含相对路径文件", hasMain);
        assertTrue("应包含根文件", hasReadme);
    }

    @Test public void walk_skipsDotDirs() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve(".git/config"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve(".idea"));
        Files.write(root.resolve(".idea/misc.xml"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(root.resolve("a.txt"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<Suggestion> out = FileSuggester.walk(root.toString());
        for (Suggestion s : out) {
            assertFalse("不应包含 .git/.idea 内容: " + s.label, s.label.contains(".git"));
            assertFalse("不应包含 .idea 内容: " + s.label, s.label.contains(".idea"));
        }
        assertEquals(1, out.size());
    }

    @Test public void list_cachesWithin10Seconds() throws Exception {
        Path root = tmp.getRoot().toPath();
        Files.write(root.resolve("a.txt"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        FileSuggester fs = new FileSuggester();
        List<Suggestion> first = fs.list(root.toString());
        assertEquals(1, first.size());
        // 缓存期间新增文件不重扫
        Files.write(root.resolve("b.txt"), "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, fs.list(root.toString()).size());
    }

    @Test public void walk_missingDirReturnsEmpty() {
        assertTrue(FileSuggester.walk(tmp.getRoot().toPath().resolve("nope").toString()).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SlashSuggesterTest,FileSuggesterTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

SlashSuggester.java：

```java
package com.minion.gui.input;

import com.minion.core.skills.Skill;

import java.util.ArrayList;
import java.util.List;

/** 斜杠补全数据：内置 5 命令 + 技能条目（label=/skill <名>，desc=frontmatter 描述）。纯静态。 */
public final class SlashSuggester {

    /** 内置命令（含描述，供弹层右侧灰字展示） */
    private static List<Suggestion> builtins() {
        List<Suggestion> out = new ArrayList<Suggestion>();
        out.add(new Suggestion("/help", "/help", "显示本帮助", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/skills", "/skills", "列出可用技能", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/skill", "/skill", "加载技能到当前会话", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/compact", "/compact", "立即压缩上下文", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/tokens", "/tokens", "显示 token 用量统计", Suggestion.Type.COMMAND));
        return out;
    }

    /** 技能条目：选中插入 /skill <名> */
    public static List<Suggestion> skillEntries(List<Skill> skills) {
        List<Suggestion> out = new ArrayList<Suggestion>();
        if (skills == null) return out;
        for (Skill s : skills) {
            String label = "/skill " + s.name;
            out.add(new Suggestion(label, label, s.description, Suggestion.Type.SKILL));
        }
        return out;
    }

    /** SLASH 模式全集：内置命令 + 技能条目 */
    public static List<Suggestion> all(List<Skill> skills) {
        List<Suggestion> out = builtins();
        out.addAll(skillEntries(skills));
        return out;
    }

    private SlashSuggester() { }
}
```

FileSuggester.java：

```java
package com.minion.gui.input;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 工作空间文件补全数据：walk workDir 收集相对路径（跳过点目录/.git，上限 200 条），10 秒缓存。 */
public class FileSuggester {

    /** 结果上限（与 GlobTool 口径一致） */
    static final int MAX_RESULTS = 200;
    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 10_000;

    private String cachedDir;
    private long cachedAt;
    private List<Suggestion> cached = new ArrayList<Suggestion>();

    /** 列出工作空间文件（带 10 秒缓存：弹层每次新 @ 词打开时调用，按键过滤走 SuggestionPopup.filter） */
    public synchronized List<Suggestion> list(String workDir) {
        if (workDir == null) return new ArrayList<Suggestion>();
        long now = System.currentTimeMillis();
        if (cachedDir != null && cachedDir.equals(workDir) && now - cachedAt < CACHE_TTL_MS) {
            return new ArrayList<Suggestion>(cached);
        }
        cachedDir = workDir;
        cached = walk(workDir);
        cachedAt = now;
        return new ArrayList<Suggestion>(cached);
    }

    /** 遍历工作空间（纯静态，可单测）：相对路径 / 分隔；跳过点开头目录与文件、.git；IO 异常静默 */
    static List<Suggestion> walk(String workDir) {
        List<Suggestion> out = new ArrayList<Suggestion>();
        Path root = Paths.get(workDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return out;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    String name = file.getFileName().toString();
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE; // 跳过点文件（.gitignore 等）
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    out.add(new Suggestion(rel, rel, null, Suggestion.Type.FILE));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 补全为增强体验：遍历异常静默返回已收集部分
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SlashSuggesterTest,FileSuggesterTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/input/SlashSuggester.java src/main/java/com/minion/gui/input/FileSuggester.java src/test/java/com/minion/gui/input/SlashSuggesterTest.java src/test/java/com/minion/gui/input/FileSuggesterTest.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: feat: 补全提供器（斜杠命令+技能条目、工作空间文件遍历 10 秒缓存）
```

---

### Task 7: InputView 大框重构 + 弹层接线 + theme.css 样式

**Files:**
- Modify: `src/main/java/com/minion/gui/input/InputView.java`（构造器重构 + 弹层交互接线）
- Modify: `src/resource/theme/theme.css`（input-frame/suggest 样式 + LCD；删除旧 .input-area 规则）

**Interfaces:**
- Consumes: CompletionParser（Task 3）、SuggestionPopup/Suggestion（Task 4）、SessionManager.skills()/currentWorkspaceDir()/dispatchCommand()（Task 5）、SlashSuggester/FileSuggester（Task 6）
- Produces: 无新接口（纯视图层）

- [ ] **Step 1: 运行现有测试确认基线**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=InputViewTest,CommandDispatcherTest`
Expected: PASS（重构前基线）

- [ ] **Step 2: 重构 InputView**（完整替换构造器与新增弹层逻辑；InputView.java 全文替换为）

```java
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
    private volatile SessionHandle current;
    // FX 线程缓存的状态（bindSession/onRunningChanged/onAskChanged 维护）
    private boolean running;
    private boolean askPending;
    private String askQuestion;

    public InputView(final SessionManager manager) {
        this.manager = manager;
        getStyleClass().add("panel-dark");
        setPadding(new Insets(12, 16, 12, 16));

        input.getStyleClass().add("input-textarea");
        input.setWrapText(true);
        input.setPromptText("输入消息…  (@ 引用文件  / 命令  Ctrl+Enter 发送)");
        input.setPrefRowCount(2);
        input.setMaxHeight(6 * 24);
        input.textProperty().addListener((obs, ov, nv) -> { updateButton(); onTextChanged(); });
        input.caretPositionProperty().addListener((obs, ov, nv) -> onTextChanged());

        // 上箭头（Claude Code 同款语义：可发送）；方块 = 终止
        arrowIcon.setContent("M12 4 L20 13 L15 13 L15 21 L9 21 L9 13 L4 13 Z");
        arrowIcon.getStyleClass().add("icon-send");
        stopIcon.setContent("M7 7 L17 7 L17 17 L7 17 Z");
        stopIcon.getStyleClass().add("icon-stop");

        sendButton.setMinSize(36, 36);
        sendButton.setPrefSize(36, 36);
        sendButton.setOnAction(e -> onAction());
        updateButton();

        input.setOnKeyPressed(e -> {
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

        // 4/9 宽居中：3 列百分比（27.8% / 44.4% / 27.8%）
        GridPane root = new GridPane();
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(27.8);
        ColumnConstraints center = new ColumnConstraints();
        center.setPercentWidth(44.4);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(27.8);
        root.getColumnConstraints().addAll(left, center, right);
        root.add(frame, 1, 0);
        getChildren().add(root);
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

    /** 确认弹层选中：替换当前词为插入文本并移动光标 */
    private void confirmPopup() {
        if (lastToken == null) return;
        String insert = popup.confirmSelected();
        if (insert == null) return;
        input.replaceText(lastToken.start, lastToken.end, insert);
        input.positionCaret(lastToken.start + insert.length());
        lastToken = null;
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
```

- [ ] **Step 3: theme.css 样式**（`.input-area` 旧规则整体替换为以下内容，文件尾追加 suggest 样式）

```css
/* ===== 输入区大框（左输入右按钮+竖分割线，4/9 宽居中）；显式 LCD：JavaFX 8 默认灰阶 AA 发虚（同聊天正文修法） ===== */
.input-frame {
    -fx-background-color: #1a1d24;
    -fx-background-radius: 8;
    -fx-border-color: #232733;
    -fx-border-radius: 8;
    -fx-padding: 8 10 8 14;
    -fx-font-smoothing-type: lcd;
}
.input-frame .input-textarea {
    -fx-background-color: transparent;
    -fx-control-inner-background: transparent;
    -fx-text-fill: #f0f2f6;
    -fx-prompt-text-fill: #7a828e;
    -fx-highlight-fill: #3b6fe0;
}
.input-frame .input-textarea .content { -fx-background-color: transparent; }
.input-frame .input-textarea .scroll-pane { -fx-background-color: transparent; }
.input-divider { -fx-pref-width: 1; -fx-min-width: 1; -fx-max-width: 1; -fx-background-color: #232733; }

/* ===== 补全弹层（/命令、@文件；锚定输入大框上方同宽） ===== */
.suggest-list {
    -fx-background-color: #1a1d24;
    -fx-background-radius: 8;
    -fx-border-color: #2a2f3a;
    -fx-border-radius: 8;
    -fx-font-smoothing-type: lcd;
}
.suggest-list .list-cell { -fx-background-color: transparent; -fx-padding: 6 10 6 10; }
.suggest-list .list-cell:hover { -fx-background-color: #20242e; }
.suggest-list .list-cell:selected { -fx-background-color: #2a3344; }
.suggest-label { -fx-text-fill: #d3d7de; }
.suggest-list .list-cell:selected .suggest-label { -fx-text-fill: #f0f2f6; }
.suggest-desc { -fx-text-fill: #7a828e; -fx-font-size: 12px; }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=InputViewTest,CommandDispatcherTest,CompletionParserTest,SuggestionPopupTest,SlashSuggesterTest,FileSuggesterTest`
Expected: 全 PASS

- [ ] **Step 5: 编译全量验证**

Run: `JAVA_HOME="E:/javame/jdk8" mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/minion/gui/input/InputView.java src/resource/theme/theme.css
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: feat: 输入区 4/9 居中大框（竖分割线+LCD 抗锯齿）+ 补全弹层接线
```

---

### Task 8: 设置窗「应用」按钮归位

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java:44-52`

**Interfaces:** 无

- [ ] **Step 1: 运行现有测试确认基线**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SettingsDialogTest`
Expected: PASS

- [ ] **Step 2: 实现**（ButtonData.OTHER → APPLY，并固定按钮顺序 A C；APPLY 在 Windows 8u181 落在右区与关闭相邻，OTHER 落在左区即「应用单独在左边」根因）

SettingsDialog.java 按钮构造段改为：

```java
        final BasicPane basic = new BasicPane(config, owner);
        // 按钮栏「应用」「关闭」相邻：OTHER(U) 在 Win 8u181 落左区（应用被单独放左边根因），
        // APPLY(A) 落右区与 CANCEL_CLOSE(C) 同区；setButtonOrder("A C") 固定为 [应用][关闭]
        ButtonType applyType = new ButtonType("应用", ButtonBar.ButtonData.APPLY);
        d.getDialogPane().getButtonTypes().addAll(applyType, ButtonType.CLOSE);
        d.getDialogPane().getButtonBar().setButtonOrder("A C");
```

- [ ] **Step 3: 运行测试确认通过**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test -Dtest=SettingsDialogTest`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: fix: 设置窗应用按钮改 APPLY 数据归位右区，与关闭按钮相邻
```

---

### Task 9: 全量验证 + 启动目验 + 文档同步

**Files:**
- Modify: `README.md`（使用说明：斜杠命令、@ 文件补全、Esc 终止）
- Modify: `docs/ARCHITECTURE.md`（包结构：gui/command、gui/input 新组件）
- Modify: `CLAUDE.md`（包结构 gui/ 树同步 input/ 描述）
- Modify: `docs/superpowers/specs/2026-08-14-input-command-suggest-design.md`（状态回写：已实施）

- [ ] **Step 1: 全量测试**

Run: `JAVA_HOME="E:/javame/jdk8" mvn test`
Expected: 全 PASS（无既有测试回归）

- [ ] **Step 2: 构建产物**

Run: `JAVA_HOME="E:/javame/jdk8" mvn clean package`
Expected: BUILD SUCCESS（target/minion-0.1.0.jar）

- [ ] **Step 3: 启动目验**（用 run 技能启动 minion.bat）

检查项：
1. 窗口正常显示，无异常堆栈
2. 输入大框 4/9 宽居中、竖分割线、文字不虚（与聊天正文对比）
3. 输入 `/` 弹层出现 5 命令+技能；↑↓/鼠标可选中、Enter/Tab 插入、滚轮滚动、Esc 关闭
4. 输入 `@` 弹层出现工作空间文件，选中插入相对路径
5. 提交 `/skills` → 聊天区出现系统行结果（不经 LLM）
6. 设置窗「应用」「关闭」相邻
若目验发现问题，回 Task 修复后重跑 Step 1-2

- [ ] **Step 4: 文档同步**

README.md「使用说明」节追加：

```markdown
### 输入区快捷键与补全
- `@`：引用工作空间文件（按文件名反显，↑↓/鼠标选择，Enter/Tab 插入相对路径）
- `/`：斜杠命令与技能补全（/help /skills /skill <名> /compact /tokens；/skill 后继续输入按技能名过滤）
- Ctrl+Enter：发送；Esc：关闭补全弹层 / 终止当前运行
- 斜杠命令由客户端本地执行，结果以系统行显示在聊天区，不发给模型
```

docs/ARCHITECTURE.md 包结构 gui/ 节：`input/` 行后追加 `command/ CommandDispatcher（斜杠命令本地分发）`；`input/` 行补充「SuggestionPopup 补全弹层、CompletionParser 触发解析、Slash/FileSuggester 数据提供」。

CLAUDE.md 包结构树 `gui/` 下 `input/` 行同步为：

```
    │   ├── input/            InputView（4/9 居中大框+竖分割线+@//补全弹层）、SuggestionPopup、CompletionParser
    │   ├── command/          CommandDispatcher（斜杠命令本地分发）
```

设计文档状态行改「状态：已实施（2026-08-14）」，并在文档尾追加「## 8. 实施记录」小节：

```markdown
## 8. 实施记录

- Task 1-8 全部完成并提交（见 git log 2026-08-14）；全量 mvn test 通过、clean package 通过、启动目验通过。
- 设置窗按钮为 ButtonBar 声明式修复，Windows 8u181 实测布局需用户目验确认（兜底方案：自绘按钮行）。
```

- [ ] **Step 5: 提交**

```bash
git add README.md docs/ARCHITECTURE.md CLAUDE.md docs/superpowers/specs/2026-08-14-input-command-suggest-design.md
git commit -F .git/COMMIT_MSG_TMP.txt   # 消息: docs: 同步 README/ARCHITECTURE/CLAUDE.md（斜杠命令、@文件补全、输入大框说明）
```
