package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/** ChatView 工具调用/结果渲染纯函数测试（无 JavaFX 依赖） */
public class ChatViewToolBodyTest {

    // ---- toolCallBody ----

    @Test
    public void toolCallBody_edit_producesDiff() {
        String args = "{\"path\":\"a.java\",\"oldString\":\"旧行1\\n旧行2\\n旧行3\","
                + "\"newString\":\"旧行1\\n新行2\\n旧行3\"}";
        String body = ChatView.toolCallBody("Edit", args);
        assertEquals("- 旧行2\n+ 新行2", body);
    }

    @Test
    public void toolCallBody_write_producesDiff() {
        String args = "{\"path\":\"b.txt\",\"oldString\":\"\",\"newString\":\"第一行\\n第二行\"}";
        String body = ChatView.toolCallBody("Write", args);
        assertEquals("+ 第一行\n+ 第二行", body);
    }

    @Test
    public void toolCallBody_otherTool_fullJson() {
        String args = "{\"command\":\"git status\",\"timeoutSeconds\":30}";
        assertEquals(args, ChatView.toolCallBody("Bash", args));
    }

    @Test
    public void toolCallBody_edit_malformedJson_fallbackToRaw() {
        assertEquals("not-json", ChatView.toolCallBody("Edit", "not-json"));
    }

    @Test
    public void toolCallBody_nullData_emptyJson() {
        assertEquals("{}", ChatView.toolCallBody("Bash", null));
    }

    @Test
    public void toolCallBody_edit_noChanges_returnsRawJson() {
        String args = "{\"path\":\"a.java\",\"oldString\":\"x\",\"newString\":\"x\"}";
        assertEquals(args, ChatView.toolCallBody("Edit", args));
    }

    // ---- toolCallSummary ----

    @Test
    public void toolCallSummary_edit_includesPath() {
        assertEquals("⛭ Edit → src/foo.java",
                ChatView.toolCallSummary("Edit", "{\"path\":\"src/foo.java\"}"));
    }

    @Test
    public void toolCallSummary_otherTool_nameOnly() {
        assertEquals("⛭ Bash", ChatView.toolCallSummary("Bash", "{\"command\":\"ls\"}"));
    }

    // ---- toolResultBody ----

    @Test
    public void toolResultBody_success_returnsOutput() {
        assertEquals("line1\nline2", ChatView.toolResultBody("ok\nline1\nline2"));
    }

    @Test
    public void toolResultBody_error_returnsReason() {
        assertEquals("第一行\n第二行", ChatView.toolResultBody("error:第一行\n第二行"));
    }

    @Test
    public void toolResultBody_legacyOk_noOutput() {
        assertEquals("", ChatView.toolResultBody("ok"));
        assertEquals("", ChatView.toolResultBody(null));
    }

    // ---- defaultExpanded（折叠语义：长内容默认折叠、短内容默认展开）----

    @Test
    public void defaultExpanded_longText_false() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CollapsibleText.COLLAPSE_THRESHOLD + 100; i++) sb.append('x');
        assertFalse("长内容应默认折叠", ChatView.defaultExpanded(sb.toString()));
    }

    @Test
    public void defaultExpanded_shortText_true() {
        assertTrue("短内容应默认展开", ChatView.defaultExpanded("短内容"));
    }

    @Test
    public void defaultExpanded_nullOrEmpty_true() {
        assertTrue(ChatView.defaultExpanded(null));
        assertTrue(ChatView.defaultExpanded(""));
    }
}
