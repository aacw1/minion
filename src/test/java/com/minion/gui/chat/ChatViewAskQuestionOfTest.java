package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AskUserQuestion 提问展示纯逻辑测试：question 文本 + options 选项列表渲染。
 * 背景（线上实证）：模型问「用哪种方式执行？」并附 options（label/description）时，
 * 消息区只显示问题不显示选项，用户看不到可选方案——askQuestionOf 只解析 question 字段。
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

    @Test
    public void malformedJson_returnsEmptyString() {
        assertEquals("", ChatView.askQuestionOf("not-json"));
        assertEquals("", ChatView.askQuestionOf(null));
    }
}
