package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AskUserQuestion 提问展示纯逻辑测试：question 文本 + options 选项列表渲染 + 畸形参数兜底。
 * 背景一（2026-08-16 线上实证）：模型问「用哪种方式执行？」并附 options 时消息区只显示问题
 * 不显示选项——askQuestionOf 只解析 question 字段。
 * 背景二（2026-08-30 线上实证）：模型偶发畸形参数（parameter 标签泄漏吞掉 question 键、
 * options 退化为字符串、选项写到 questions 键），旧实现静默返回空串，
 * 消息区只剩「模型向你提问」一行 → 用户回「第二个问题没显示」「提问没有选项，再发一次」。
 * 新契约：渲染文本永不为空（提不出内容时回退原始参数，对齐 toolCallBody 的兜底做法）。
 */
public class ChatViewAskQuestionOfTest {

    @Test
    public void questionOnly_returnsQuestionText() {
        String out = ChatView.askQuestionOf("{\"question\":\"选哪个？\"}");
        assertEquals("选哪个？", out);
    }

    @Test
    public void questionWithOptions_rendersOptionsWithNumberAndDescription() {
        String out = ChatView.askQuestionOf("{\"question\":\"用哪种方式执行？\","
                + "\"options\":[{\"label\":\"方式A\",\"description\":\"子 agent 并行\"},"
                + "{\"label\":\"方式B\",\"description\":\"内联执行\"}]}");
        assertEquals("用哪种方式执行？\n[1] 方式A — 子 agent 并行\n[2] 方式B — 内联执行", out);
    }

    @Test
    public void optionWithoutDescription_rendersLabelOnly() {
        String out = ChatView.askQuestionOf("{\"question\":\"选哪个？\","
                + "\"options\":[{\"label\":\"方案X\"},{\"label\":\"方案Y\"}]}");
        assertEquals("选哪个？\n[1] 方案X\n[2] 方案Y", out);
    }

    /** 线上实证 B 形态：选项被写到 questions 键、整段数组序列化成字符串 → 必须渲染出选项 */
    @Test
    public void optionsSerializedUnderQuestionsKey_stillRendered() {
        String out = ChatView.askQuestionOf("{\"questions\":"
                + "\"[{\\\"label\\\":\\\"① 仅 UI 兜底\\\",\\\"description\\\":\\\"只改 ChatView\\\"},"
                + "{\\\"label\\\":\\\"② UI+core\\\",\\\"description\\\":\\\"共用 normalize\\\"}]\","
                + "\"header\":\"修复范围\",\"question\":\"这次修到哪一层？\"}");
        assertTrue("问题不可丢: " + out, out.contains("这次修到哪一层？"));
        assertTrue("选项不可丢: " + out, out.contains("[1] ① 仅 UI 兜底 — 只改 ChatView"));
        assertTrue("选项不可丢: " + out, out.contains("[2] ② UI+core — 共用 normalize"));
    }

    /** options 是 JSON 数组的字符串形式（模型高频笔误）→ 二次解析渲染 */
    @Test
    public void optionsAsJsonArrayString_rendered() {
        String out = ChatView.askQuestionOf("{\"question\":\"选哪个？\","
                + "\"options\":\"[{\\\"label\\\":\\\"A\\\"},{\\\"label\\\":\\\"B\\\"}]\"}");
        assertEquals("选哪个？\n[1] A\n[2] B", out);
    }

    /** 行为变更：非法 JSON 不再静默丢弃，回退原始参数文本（对齐 toolCallBody 兜底） */
    @Test
    public void malformedJson_fallsBackToRawArgsText() {
        assertEquals("not-json", ChatView.askQuestionOf("not-json"));
        assertEquals("{}", ChatView.askQuestionOf(null));
        assertTrue("空参数也必须渲染出可见内容",
                !ChatView.askQuestionOf("{}").trim().isEmpty());
    }

    // ---- askSummaryText：摘要行携带 header（header 是模型给的问题主题，此前从未显示）----

    @Test
    public void askSummaryText_includesHeader() {
        assertEquals("模型向你提问 · 修复范围",
                ChatView.askSummaryText("{\"question\":\"选哪个？\",\"header\":\"修复范围\"}"));
    }

    @Test
    public void askSummaryText_noHeader_plainText() {
        assertEquals("模型向你提问",
                ChatView.askSummaryText("{\"question\":\"选哪个？\"}"));
    }

    /** header 被当作 question 兜底显示时（只有 header 的畸形提问），摘要行不得再带一遍同一句
     *  —— 否则「模型向你提问 · 压缩判断」与正文首行「压缩判断」重复两遍 */
    @Test
    public void askSummaryText_headerUsedAsQuestion_notDuplicated() {
        assertEquals("模型向你提问",
                ChatView.askSummaryText("{\"header\":\"压缩判断\"}"));
    }

    @Test
    public void askSummaryText_longHeader_truncated() {
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < 40; i++) h.append('标');
        String s = ChatView.askSummaryText("{\"question\":\"选哪个？\",\"header\":\"" + h + "\"}");
        assertTrue("header 超长须截断: " + s, s.length() <= "模型向你提问 · ".length() + 21);
        assertTrue(s.endsWith("…"));
    }

    // ---- askExpanded：提问段不得被折叠（折叠即「看不见提问内容」的第二类成因）----

    @Test
    public void askExpanded_longQuestion_staysExpanded() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CollapsibleText.COLLAPSE_THRESHOLD + 200; i++) sb.append('选');
        assertTrue("超折叠阈值的提问仍须默认展开", ChatView.askExpanded(sb.toString()));
    }

    @Test
    public void askExpanded_absurdlyLong_collapsesToProtectLayout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4001; i++) sb.append('选');
        assertFalse("超长异常段仍折叠防爆屏", ChatView.askExpanded(sb.toString()));
    }

    @Test
    public void askExpanded_shortText_expanded() {
        assertTrue(ChatView.askExpanded("选哪个？"));
        assertTrue(ChatView.askExpanded(null));
    }
}
