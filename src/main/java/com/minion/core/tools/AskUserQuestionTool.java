package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.AgentUi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 向用户提问：execute 挂起等待回答（无超时），回答经 complete() 送达后作为工具结果返回。
 *  与 Claude Code 同名 AskUserQuestion；子 agent 禁用（SubAgentLoop 过滤 schema + 同名防御）。
 *  参数容错见 normalize()：模型偶发畸形 arguments（键名写成 questions、数组被序列化成字符串、
 *  工具调用标签泄漏进 JSON 值吞掉 question 键），旧实现静默丢弃 → 消息区只剩「模型向你提问」
 *  一行，用户看不到任何提问内容（线上实证 2026-08-30，两份会话 json）。 */
public class AskUserQuestionTool implements Tool {

    /** 泄漏的 Anthropic 工具调用标签：开标签 "&lt;名称=参数名&gt;" + 闭标签 "&lt;/名称&gt;"。
     *  尖括号/斜杠以常量拼接，源码与字节码中都不出现完整标签字面量。 */
    private static final String LT = "<";
    private static final String GT = ">";
    private static final String SLASH = "/";
    private static final Pattern LEAKED_TAG = Pattern.compile(
            LT + "\\s*(\\w+)\\s*=\\s*(\\w+)\\s*" + GT + "([^" + LT + GT + "]*)" + LT + "\\s*" + SLASH + "\\s*\\1\\s*" + GT);
    /** 未闭合形态（线上实证 A：闭标签随流式截断丢失）：开标签后取到下一个尖括号前 */
    private static final Pattern LEAKED_TAG_OPEN = Pattern.compile(
            LT + "\\s*(\\w+)\\s*=\\s*(\\w+)\\s*" + GT + "([^" + LT + "]*)");

    /** 提问内容完全提不出时的失败输出前缀。历史 TOOL 消息不落成败标记，AskUserQuestion 的
     *  output 既可能是用户回答也可能是失败原因——恢复路径据此前缀区分，避免把失败原因
     *  当成用户回答重演成【输入】行 */
    public static final String INVALID_PREFIX = "AskUserQuestion 调用无效：";

    private final AgentUi ui;
    /** 当前挂起的等待（单槽：同轮多次调用共享同一回答；null=未挂起） */
    private volatile CompletableFuture<String> pending;
    /** 连续「提不出提问内容」次数：任一规范提问即归零（AgentLoop 无轮次上限，须自带刹车） */
    private int invalidStreak;
    /** 畸形提问挂起阈值：第 1 次回传失败让模型自纠，累计到此次数的畸形提问改为挂起交回用户 */
    static final int MAX_INVALID_STRIKES = 2;

    public AskUserQuestionTool(AgentUi ui) { this.ui = ui; }

    @Override
    public String name() { return "AskUserQuestion"; }

    @Override
    public String description() {
        return "向用户提问或请求选择。当缺少完成任务所需信息、需要用户确认方案或做出选择时调用。"
                + "参数 question 为必填问题文本；header 为可选简短标题；options 为可选答案列表"
                + "（2-4 个，每项含 label/description）；multiSelect 表示是否可多选。"
                + "调用后将挂起等待用户回答，回答会作为工具结果返回。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "向用户提问（缺少信息/需确认方案/需选择时）");
        JsonObject props = new JsonObject();
        JsonObject question = new JsonObject();
        question.addProperty("type", "string");
        question.addProperty("description", "要问用户的问题");
        JsonObject header = new JsonObject();
        header.addProperty("type", "string");
        header.addProperty("description", "简短标题（可选）");
        JsonObject options = new JsonObject();
        options.addProperty("type", "array");
        options.addProperty("description", "可选答案列表，2-4 个");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        JsonObject label = new JsonObject();
        label.addProperty("type", "string");
        label.addProperty("description", "选项文本");
        JsonObject desc = new JsonObject();
        desc.addProperty("type", "string");
        desc.addProperty("description", "选项说明");
        itemProps.add("label", label);
        itemProps.add("description", desc);
        items.add("properties", itemProps);
        options.add("items", items);
        JsonObject multiSelect = new JsonObject();
        multiSelect.addProperty("type", "boolean");
        multiSelect.addProperty("description", "是否可多选（可选）");
        props.add("question", question);
        props.add("header", header);
        props.add("options", options);
        props.add("multiSelect", multiSelect);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("question");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        // 参数先规范化：question 键可能被模型写丢（见类注释），逐级降级后仍为空才算真提不出内容
        Ask ask = normalize(args);
        final boolean malformed;
        final boolean suspendMalformed;
        synchronized (this) {
            malformed = ask.isEmpty();
            if (malformed) {
                invalidStreak++;
                // 第 1 次回传失败让模型自纠（线上实证第二次即发对）；连续第 2 次起挂起软兜底——
                // AgentLoop 主循环无轮次上限，纯快速失败会「失败→重发→失败」把会话卡死
                suspendMalformed = invalidStreak >= MAX_INVALID_STRIKES;
            } else {
                invalidStreak = 0;
                suspendMalformed = false;
            }
        }
        if (malformed && !suspendMalformed) {
            return ToolResult.error(INVALID_PREFIX + "必须提供非空 question（options 可选），"
                    + "无法向用户展示提问内容，请重新发起提问");
        }
        String question = ask.placeholder(); // 软兜底挂起时即「参数格式异常」提示文案
        CompletableFuture<String> fut;
        final boolean owner;
        synchronized (this) {
            fut = this.pending; // 同轮多次调用共享同一 future：重入链到已有挂起而非新建
            if (fut == null) {
                fut = new CompletableFuture<String>();
                this.pending = fut;
                owner = true;
            } else {
                owner = false;
            }
        }
        if (owner) ui.onAskUserStart(question);
        try {
            String answer = fut.get(); // 阻塞到 answerAskUser 或线程中断（终止）
            if (owner) ui.onAskUserDone(answer);
            return ToolResult.success(answer);
        } finally {
            // 仅清自己占据的槽位：跟随者先结束时不得误清新一轮的 pending
            if (this.pending == fut) this.pending = null;
        }
    }

    /** 用户回答入口（AgentLoop.answerAskUser 转发）；无挂起时忽略并返回 false */
    public boolean complete(String answer) {
        CompletableFuture<String> fut = pending;
        if (fut == null) return false;
        fut.complete(answer);
        return true;
    }

    // ==================== 参数规范化（单一真源：工具挂起文案、消息区渲染、输入框占位共用） ====================

    /** 规范化后的提问快照（不可变）：core 判定是否挂起、gui 渲染正文都读它 */
    public static class Ask {
        /** 问题文本（逐级降级后的结果；null=提不出） */
        public final String question;
        /** 简短标题（可空） */
        public final String header;
        /** 选项列表（永不为 null，可为空） */
        public final List<Option> options;
        /** 是否多选 */
        public final boolean multiSelect;
        /** 原始参数文本（渲染兜底用，永不为 null） */
        public final String rawText;

        Ask(String question, String header, List<Option> options, boolean multiSelect, String rawText) {
            this.question = question;
            this.header = header;
            this.options = options == null ? Collections.<Option>emptyList() : options;
            this.multiSelect = multiSelect;
            this.rawText = rawText == null ? "{}" : rawText;
        }

        /** 问题与选项都提不出 → 该提问对用户毫无信息量（工具侧据此快速失败） */
        public boolean isEmpty() {
            return (question == null || question.trim().isEmpty()) && options.isEmpty();
        }

        /** 消息区正文：问题 + "[N] label — description" 选项 + 多选提示；
         *  一点内容都没有时回退原始参数（渲染路径永不产出空白，对齐 toolCallBody 兜底做法） */
        public String renderText() {
            if (isEmpty()) {
                return rawText;
            }
            StringBuilder sb = new StringBuilder();
            if (question != null && !question.trim().isEmpty()) {
                sb.append(clean(question));
            }
            for (int i = 0; i < options.size(); i++) {
                Option o = options.get(i);
                if (sb.length() > 0) sb.append('\n'); // 问题提不出时正文以首个选项开头，不留空行
                sb.append('[').append(i + 1).append("] ").append(o.label);
                if (!o.description.isEmpty()) sb.append(" — ").append(o.description);
            }
            if (multiSelect && sb.length() > 0) {
                sb.append('\n').append("（可多选：用逗号、顿号分隔多个选项）");
            }
            return sb.toString();
        }

        /** 输入框占位短文本：question → 首个选项 label → header → 常量兜底（永不为空） */
        public String placeholder() {
            if (question != null && !question.trim().isEmpty()) return clean(question);
            if (!options.isEmpty() && !options.get(0).label.isEmpty()) return options.get(0).label;
            if (header != null && !header.trim().isEmpty()) return clean(header);
            // 软兜底挂起文案（连续畸形提问）：说明为何看不到问题，指引用户怎么回
            return "模型未给出可显示的提问内容（参数格式异常），"
                    + "可直接说明你的想法，或回复「重问」让模型再问一次";
        }
    }

    /** 单个选项（label 已清洗，description 可为空串） */
    public static class Option {
        public final String label;
        public final String description;

        Option(String label, String description) {
            this.label = label;
            this.description = description == null ? "" : description;
        }
    }

    /** 规范化 JSON 文本入口（ChatView 拿的是 TOOL_CALL 事件里的参数原文）；非 JSON 整段作兜底原文 */
    public static Ask normalize(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new Ask(null, null, Collections.<Option>emptyList(), false, "{}");
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (e != null && e.isJsonObject()) {
                return normalize(e.getAsJsonObject());
            }
        } catch (Exception ignored) {
            // 畸形 JSON：走下面的原文兜底（旧实现这里直接 return ""，提问内容全丢）
        }
        return new Ask(null, null, Collections.<Option>emptyList(), false, json);
    }

    /** 规范化模型给出的工具参数：question/options/header/multiSelect 逐级降级提取 */
    public static Ask normalize(JsonObject args) {
        if (args == null) {
            return new Ask(null, null, Collections.<Option>emptyList(), false, "{}");
        }
        String raw = args.toString();
        String question = str(args, "question");
        String header = str(args, "header");
        boolean multi = bool(args, "multiSelect");

        List<Option> options = readOptions(args.get("options"));
        // options 一条没拿到 → 试 questions 键（模型按 Claude Code 习惯把整份提问写到该键，
        // 且常被序列化成字符串——线上实证「提问没有选项」即此形态）
        JsonElement alt = args.get("questions");
        if (options.isEmpty()) {
            List<Option> fromAlt = readOptions(alt);
            if (!fromAlt.isEmpty()) options = fromAlt;
        }
        if (alt != null && !alt.isJsonNull()) {
            JsonObject first = firstObject(alt);
            if (first != null) {
                if (question == null) question = str(first, "question");
                if (header == null) header = str(first, "header");
                if (options.isEmpty()) options = readOptions(first.get("options"));
            }
        }
        if (question == null) question = fromLeakedTag(args); // 标签泄漏吞掉的正文救回
        if (question == null) question = header;              // 标题也比空白强
        return new Ask(question, header, options, multi, raw);
    }

    /** 值 → 选项列表：数组逐项；对象按单项；字符串先反解（含内嵌数组抽取）后逐项 */
    private static List<Option> readOptions(JsonElement v) {
        List<Option> out = new ArrayList<Option>();
        JsonArray arr = asArray(v);
        if (arr == null) return out;
        for (JsonElement it : arr) {
            Option o = toOption(it);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** 单个选项对象 → Option：label 缺失退回 question/description；三个字段都没有则丢弃。
     *  无 label 却带 options 键的对象是「提问对象」（Claude Code questions 数组的元素形态），
     *  不得当成选项——否则 questions 的内层 options 永远取不到 */
    private static Option toOption(JsonElement it) {
        if (it == null || it.isJsonNull()) return null;
        if (it.isJsonPrimitive()) {
            String s = clean(it.getAsString());
            return s.isEmpty() ? null : new Option(s, "");
        }
        if (!it.isJsonObject()) return null;
        JsonObject o = it.getAsJsonObject();
        String label = str(o, "label");
        String desc = str(o, "description");
        if (label == null && o.has("options")) return null; // 提问对象，非选项
        if (label == null) label = str(o, "question");
        if (label == null) label = desc;
        if (label == null) return null;
        return new Option(label, desc == null ? "" : desc);
    }

    /** 值 → JsonArray：数组直取；单对象包成一项数组；字符串交 unwrap 反解后同上 */
    private static JsonArray asArray(JsonElement v) {
        JsonElement e = unwrap(v);
        if (e == null) return null;
        if (e.isJsonArray()) return e.getAsJsonArray();
        if (e.isJsonObject()) {
            JsonArray one = new JsonArray();
            one.add(e);
            return one;
        }
        return null;
    }

    /** 取数组/字符串里的第一个对象（questions 内嵌 question/header 用） */
    private static JsonObject firstObject(JsonElement v) {
        JsonElement e = unwrap(v);
        if (e == null) return null;
        if (e.isJsonObject()) return e.getAsJsonObject();
        if (e.isJsonArray()) {
            for (JsonElement it : e.getAsJsonArray()) {
                if (it != null && it.isJsonObject()) return it.getAsJsonObject();
            }
        }
        return null;
    }

    /** 字符串形态的 JSON（数组或对象）反解回 JsonElement：整段解不动时抽取内嵌数组子串
     *  （实证 A：options 值是 "[…3 选项…]\n泄漏标签" 形态，尾部杂讯使整段解析必失败）；
     *  仍解不出返回原元素（rawText 兜底保证内容可见） */
    private static JsonElement unwrap(JsonElement v) {
        if (v == null || v.isJsonNull()) return null;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return v;
        String s = v.getAsString().trim();
        if (s.length() < 2) return null;
        char first = s.charAt(0);
        if (first != '[' && first != '{') return v;
        try {
            JsonElement parsed = JsonParser.parseString(s);
            if (parsed != null && !parsed.isJsonNull() && (parsed.isJsonArray() || parsed.isJsonObject())) {
                return parsed;
            }
        } catch (Exception ignored) {
            // 落到内嵌子串抽取
        }
        String fragment = firstJsonFragment(s);
        if (fragment != null) {
            try {
                JsonElement parsed = JsonParser.parseString(fragment);
                if (parsed != null && !parsed.isJsonNull()) return parsed;
            } catch (Exception ignored) { }
        }
        return v; // 解不动保持原样（rawText 兜底仍可见）
    }

    /** 抽取首个配对的 JSON 数组/对象子串（引号内与转义符不参与括号计数）；无配对返回 null */
    private static String firstJsonFragment(String s) {
        int start = s.indexOf('[');
        int objStart = s.indexOf('{');
        char open = '[';
        char close = ']';
        if (start < 0 && objStart < 0) return null;
        if (start < 0 || (objStart >= 0 && objStart < start)) {
            start = objStart;
            open = '{';
            close = '}';
        }
        int depth = 0;
        boolean inStr = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 非空字符串取值（清洗泄漏标签），无值返回 null */
    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        JsonElement e = o.get(key);
        String s;
        if (e.isJsonPrimitive()) {
            s = e.getAsString();
        } else {
            return null; // 数组/对象不在此处转文本（由 readOptions 处理）
        }
        s = clean(s);
        return s.isEmpty() ? null : s;
    }

    /** 布尔取值：真布尔或字符串 "true" */
    private static boolean bool(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return false;
        JsonElement e = o.get(key);
        try {
            if (e.isJsonPrimitive()) {
                return e.getAsJsonPrimitive().isBoolean()
                        ? e.getAsBoolean() : Boolean.parseBoolean(e.getAsString().trim());
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** 从所有字符串值里救回被泄漏标签吞掉的正文：先按配对标签扫，全无线索再按未闭合形态扫
     *  （线上实证 A 的闭标签随流式截断丢失）；question 语义标签优先，同级取最长 */
    private static String fromLeakedTag(JsonObject args) {
        String best = scanLeaked(args, LEAKED_TAG);
        if (best == null) best = scanLeaked(args, LEAKED_TAG_OPEN);
        return best;
    }

    /** 按给定标签模式扫描所有字符串值，返回优先级最高的正文（question 语义标签 > 最长正文） */
    private static String scanLeaked(JsonObject args, Pattern p) {
        String best = null;
        boolean bestQuestion = false;
        for (String key : args.keySet()) {
            JsonElement e = args.get(key);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            Matcher m = p.matcher(e.getAsString());
            while (m.find()) {
                String name = m.group(1) == null ? "" : m.group(1).toLowerCase();
                String body = clean(m.group(3));
                if (body.isEmpty()) continue;
                boolean question = name.contains("question") || name.contains("prompt");
                if ((question && !bestQuestion)
                        || (question == bestQuestion && (best == null || body.length() > best.length()))) {
                    best = body;
                    bestQuestion = question;
                }
            }
        }
        return best;
    }

    /** 清洗：剥掉残留标签、压缩空白、去首尾空白（永不返回 null） */
    private static String clean(String s) {
        if (s == null) return "";
        String out = LEAKED_TAG.matcher(s).replaceAll(" ");
        // 未闭合的残标签（模型只吐了开标签）也剥掉
        out = out.replaceAll(LT + "\\s*\\w*\\s*=\\s*\\w*\\s*" + GT, " ");
        out = out.replaceAll(LT + "\\s*" + SLASH + "\\s*\\w*\\s*" + GT, " ");
        out = out.replaceAll("[ \\t]+", " ");
        return out.trim();
    }
}
