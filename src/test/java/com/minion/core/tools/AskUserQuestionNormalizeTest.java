package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AskUserQuestion 参数容错规范化测试。
 * 背景（线上实证，run/session/minion/*.json）：模型偶发输出畸形 arguments，旧渲染只认
 * question(string)+options(array)，任何偏差静默返回空串 → 消息区只剩「模型向你提问」一行，
 * 用户看不到问题内容（实证用户回复：「第二个问题没显示」「提问没有选项，再发一次」）。
 * 用例为两段线上真实数据的同形态最小化；泄漏标签字面量用常量拼接（源码不落字面标签，
 * 避免与工具调用协议标记冲突）。
 */
public class AskUserQuestionNormalizeTest {

    /** 泄漏标签开：模型把 Anthropic parameter 标记吐进了 JSON 字符串值 */
    private static final String TAG_OPEN = "<" + "parameter=question" + ">";
    /** 泄漏标签闭 */
    private static final String TAG_CLOSE = "</" + "parameter" + ">";

    private static JsonObject opt(String label, String desc) {
        JsonObject o = new JsonObject();
        if (label != null) o.addProperty("label", label);
        if (desc != null) o.addProperty("description", desc);
        return o;
    }

    /** 线上实证 A（会话 20260830-121307 msg[42]）：parameter 标签泄漏进 JSON 值，
     *  question 键整体丢失、问题文本被吞进 options 值、options 从数组退化为字符串 */
    @Test
    public void leakedXmlTag_extractsQuestionAndOptions() {
        JsonArray opts = new JsonArray();
        opts.add(opt("保持本地估算（推荐）", "零回归风险"));
        opts.add(opt("统一走锚点口径", null));
        opts.add(opt("estimate 整体改", null));
        StringBuilder swallowed = new StringBuilder();
        swallowed.append('\n').append(opts.toString()).append('\n');
        swallowed.append(TAG_OPEN).append('\n');
        swallowed.append("自动压缩的触发判断（shouldCompress，阈值默认 80%）要不要也一起换成接口口径？");
        swallowed.append('\n').append(TAG_CLOSE);

        JsonObject args = new JsonObject();
        args.addProperty("header", "压缩判断");
        args.addProperty("options", swallowed.toString()); // options 退化为字符串

        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertEquals("自动压缩的触发判断（shouldCompress，阈值默认 80%）要不要也一起换成接口口径？",
                a.question);
        assertEquals(3, a.options.size());
        assertEquals("保持本地估算（推荐）", a.options.get(0).label);
        assertEquals("零回归风险", a.options.get(0).description);
        assertEquals("description 缺失回空串", "", a.options.get(1).description);
        assertFalse(a.isEmpty());

        String t = a.renderText();
        assertTrue(t.contains("自动压缩的触发判断"));
        assertTrue(t.contains("[1] 保持本地估算（推荐） — 零回归风险"));
        assertTrue(t.contains("[2] 统一走锚点口径"));
        assertFalse("渲染文本不得残留泄漏标签", t.contains(TAG_OPEN));
        assertFalse("渲染文本不得残留泄漏闭合标签", t.contains(TAG_CLOSE));
    }

    /** 线上实证 B（会话 20260830-122251 msg[68]）：选项被写到 questions 键且整段数组
     *  序列化成字符串；顶层 question 正常 → 旧渲染丢弃全部选项 */
    @Test
    public void optionsUnderQuestionsKeySerializedAsString_areRecovered() {
        JsonArray opts = new JsonArray();
        opts.add(opt("① 仅 UI 兜底", "只改 ChatView"));
        opts.add(opt("② UI+core 规范化", "共用 normalize"));
        JsonObject args = new JsonObject();
        args.addProperty("questions", opts.toString());
        args.addProperty("header", "修复范围");
        args.addProperty("question", "这次修到哪一层？");

        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertEquals("这次修到哪一层？", a.question);
        assertEquals("修复范围", a.header);
        assertEquals("questions 键里的字符串化数组应恢复为选项", 2, a.options.size());
        assertEquals("① 仅 UI 兜底", a.options.get(0).label);
        String t = a.renderText();
        assertTrue(t.contains("[1] ① 仅 UI 兜底 — 只改 ChatView"));
        assertTrue(t.contains("[2] ② UI+core 规范化 — 共用 normalize"));
    }

    /** Claude Code 真实形态：questions 为对象数组，每项自带 question/header/options */
    @Test
    public void questionsArrayOfObjects_flattensToSingleAsk() {
        JsonArray opts = new JsonArray();
        opts.add(opt("子 agent 并行", null));
        opts.add(opt("内联执行", null));
        JsonObject item = new JsonObject();
        item.addProperty("question", "用哪种方式执行？");
        item.addProperty("header", "执行方式");
        item.add("options", opts);
        JsonArray qs = new JsonArray();
        qs.add(item);
        JsonObject args = new JsonObject();
        args.add("questions", qs);

        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertEquals("用哪种方式执行？", a.question);
        assertEquals("执行方式", a.header);
        assertEquals(2, a.options.size());
        assertEquals("子 agent 并行", a.options.get(0).label);
    }

    /** 规范参数行为不变（回归锚点：与改造前 askQuestionOf 输出逐字一致） */
    @Test
    public void wellFormedArgs_behaviorUnchanged() {
        JsonArray opts = new JsonArray();
        opts.add(opt("方式A", "子 agent 并行"));
        opts.add(opt("方式B", "内联执行"));
        JsonObject args = new JsonObject();
        args.addProperty("question", "用哪种方式执行？");
        args.add("options", opts);
        assertEquals("用哪种方式执行？\n[1] 方式A — 子 agent 并行\n[2] 方式B — 内联执行",
                AskUserQuestionTool.normalize(args).renderText());
    }

    /** options 是「JSON 数组的字符串形式」也能救回（模型高频笔误） */
    @Test
    public void optionsAsJsonArrayString_isParsedAsArray() {
        JsonArray opts = new JsonArray();
        opts.add(opt("A", null));
        opts.add(opt("B", null));
        JsonObject args = new JsonObject();
        args.addProperty("question", "选哪个？");
        args.addProperty("options", opts.toString());
        assertEquals("选哪个？\n[1] A\n[2] B", AskUserQuestionTool.normalize(args).renderText());
    }

    /** 线上实证 A 原文形态：泄漏标签**未闭合**（闭标签随流式截断丢失）——
     *  只认配对闭标签的提取会失败，降级到 header 导致问题文本仍不可见 */
    @Test
    public void unclosedLeakedTag_extractsQuestionText() {
        JsonArray opts = new JsonArray();
        opts.add(opt("保持本地估算（推荐）", "改动面最小、零回归风险"));
        opts.add(opt("统一走锚点口径", "显示走新入口，判断走旧入口"));
        StringBuilder swallowed = new StringBuilder();
        swallowed.append('\n').append(opts.toString()).append('\n');
        swallowed.append(TAG_OPEN).append('\n');
        swallowed.append("自动压缩的触发判断（shouldCompress，阈值默认 80%）要不要也一起换成接口口径？");
        swallowed.append('\n'); // 无闭标签

        JsonObject args = new JsonObject();
        args.addProperty("header", "压缩判断");
        args.addProperty("options", swallowed.toString());

        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertEquals("未闭合标签也必须救回问题正文",
                "自动压缩的触发判断（shouldCompress，阈值默认 80%）要不要也一起换成接口口径？",
                a.question);
        assertEquals(2, a.options.size());
        String t = a.renderText();
        assertFalse(t.contains(TAG_OPEN));
        assertTrue(t.indexOf("自动压缩") < t.indexOf("[1]")); // 问题在选项之前
    }

    /** 只有 header：标题也比空白强（question 逐级降级最后一档） */
    @Test
    public void onlyHeader_stillDisplayable() {
        JsonObject args = new JsonObject();
        args.addProperty("header", "上下文口径");
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertEquals("上下文口径", a.question);
        assertFalse(a.isEmpty());
        assertEquals("上下文口径", a.renderText());
    }

    /** 选项对象缺 label：退回 question 字段，再退 description */
    @Test
    public void optionLabelMissing_fallsBackToOtherFields() {
        JsonArray opts = new JsonArray();
        JsonObject x = new JsonObject();
        x.addProperty("question", "选项甲");
        opts.add(x);
        opts.add(opt(null, "只有说明"));
        JsonObject args = new JsonObject();
        args.addProperty("question", "选哪个？");
        args.add("options", opts);

        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertEquals(2, a.options.size());
        assertEquals("选项甲", a.options.get(0).label);
        assertEquals("只有说明", a.options.get(1).label);
    }

    /** multiSelect=true 须提示可多选（否则用户按单选答，答案不满足要求） */
    @Test
    public void multiSelectFlag_carriedThrough() {
        JsonArray opts = new JsonArray();
        opts.add(opt("A", null));
        JsonObject args = new JsonObject();
        args.addProperty("question", "要带哪些？");
        args.add("options", opts);
        args.addProperty("multiSelect", true);
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertTrue(a.multiSelect);
        assertTrue(a.renderText().contains("可多选"));

        JsonObject args2 = new JsonObject();
        args2.addProperty("question", "选哪个？");
        args2.add("options", opts);
        AskUserQuestionTool.Ask b = AskUserQuestionTool.normalize(args2);
        assertFalse(b.multiSelect);
        assertFalse(b.renderText().contains("可多选"));
    }

    /** 一点内容都提不出 → isEmpty()=true（工具侧据此快速失败）；renderText 回退原始参数供 UI 兜底 */
    @Test
    public void nothingExtractable_marksEmptyAndKeepsRawText() {
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(new JsonObject());
        assertTrue("空参数应判定为提不出内容", a.isEmpty());
        assertNull(a.question);
        assertEquals("{}", a.renderText());

        JsonObject args = new JsonObject();
        args.addProperty("options", "not a json array at all");
        AskUserQuestionTool.Ask b = AskUserQuestionTool.normalize(args);
        assertTrue(b.options.isEmpty());
        assertTrue(b.isEmpty());
        assertTrue("兜底原文须可回显模型写的内容", b.renderText().contains("not a json array at all"));
    }

    /** 字符串入口（ChatView 传事件原文）：非 JSON / null 都不抛异常，整段作兜底原文 */
    @Test
    public void parseFailure_stringEntry_noThrow() {
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize("not-json");
        assertTrue(a.isEmpty());
        assertEquals("not-json", a.renderText());
        AskUserQuestionTool.Ask b = AskUserQuestionTool.normalize((String) null);
        assertTrue(b.isEmpty());
        assertEquals("{}", b.renderText());
    }

    /** 问题提不出、只有选项：正文不得以空行开头（旧实现无条件前置换行 → 消息区首行空白） */
    @Test
    public void renderText_optionsWithoutQuestion_noLeadingBlankLine() {
        JsonArray opts = new JsonArray();
        opts.add(opt("方案甲", "说明甲"));
        opts.add(opt("方案乙", null));
        JsonObject args = new JsonObject();
        args.add("options", opts);
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize(args);
        assertFalse("有选项即可展示", a.isEmpty());
        assertNull(a.question);
        assertEquals("[1] 方案甲 — 说明甲\n[2] 方案乙", a.renderText());
    }

    /** 占位短文本：question → 首个选项 label → header → 常量兜底，永不为空 */
    @Test
    public void placeholder_neverEmptyAndPrefersQuestion() {
        JsonArray opts = new JsonArray();
        opts.add(opt("A", null));
        opts.add(opt("B", null));

        JsonObject q = new JsonObject();
        q.addProperty("question", "选哪个？");
        q.add("options", opts);
        assertEquals("选哪个？", AskUserQuestionTool.normalize(q).placeholder());

        JsonObject onlyOpts = new JsonObject();
        onlyOpts.add("options", opts);
        assertEquals("A", AskUserQuestionTool.normalize(onlyOpts).placeholder());

        JsonObject h = new JsonObject();
        h.addProperty("header", "标题");
        assertEquals("标题", AskUserQuestionTool.normalize(h).placeholder());

        assertFalse(AskUserQuestionTool.normalize(new JsonObject()).placeholder().isEmpty());
    }

    /** 畸形 JSON（模型截断/多吐逗号）经字符串入口不炸，兜底原文仍可见 */
    @Test
    public void malformedJsonString_rejectedGracefully() {
        AskUserQuestionTool.Ask a = AskUserQuestionTool.normalize("{\"question\":\"选哪个？\",,}");
        assertNotNull(a);
        assertTrue("畸形 JSON 提不出内容时按原文兜底", a.renderText().contains("选哪个？"));
    }
}
