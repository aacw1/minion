package com.minion.gui.chat;

import org.junit.Test;

import static org.junit.Assert.*;

/** 流式正文增量展平（P1-4，纯逻辑）：增量语义、块规则近似、定稿与精确转换一致 */
public class StreamingPlainTextTest {

    private static final String SAMPLE =
            "# 标题\n"
          + "\n"
          + "第一段 **粗体** 与 `代码` 和 [链接](http://x)\n"
          + "\n"
          + "```java\n"
          + "int x = 1;\n"
          + "**原样保留**\n"
          + "```\n"
          + "\n"
          + "- 甲\n"
          + "- 乙\n"
          + "\n"
          + "| A | B |\n"
          + "|---|---|\n"
          + "| 1 | 2 |\n";

    /** 一次性展平：段落去行内标记、标题去 #、代码块原样、列表 "• "、表格 " | " */
    @Test
    public void rendersTypicalMarkdownBlocks() {
        StreamingPlainText p = new StreamingPlainText();
        String out = p.render(SAMPLE);
        assertTrue("标题去 #: " + out, out.startsWith("标题"));
        assertTrue("粗体/代码/链接去标记", out.contains("第一段 粗体 与 代码 和 链接"));
        assertTrue("代码块内容原样（含行内标记）", out.contains("int x = 1;\n**原样保留**"));
        assertTrue("列表加 • 前缀", out.contains("• 甲\n• 乙"));
        assertTrue("表格单元格用 | 分隔且跳过分隔行", out.contains("A | B\n1 | 2"));
    }

    /** 与 MarkdownRenderer.toPlainText（定稿用的精确转换）在常规内容上逐字一致 */
    @Test
    public void matchesExactConversionOnTypicalMarkdown() {
        StreamingPlainText p = new StreamingPlainText();
        assertEquals(MarkdownRenderer.toPlainText(SAMPLE), p.render(SAMPLE));
    }

    /** 增量输入（逐字符追加）结果与一次性输入完全一致：已完成行只处理一次，不破坏语义 */
    @Test
    public void incrementalChunks_matchWholeRender() {
        StreamingPlainText whole = new StreamingPlainText();
        String expected = whole.render(SAMPLE);

        StreamingPlainText inc = new StreamingPlainText();
        String last = "";
        for (int i = 0; i < SAMPLE.length(); i++) {
            last = inc.render(SAMPLE.substring(0, i + 1));
        }
        assertEquals("逐字符追加的最终结果应与一次性展平一致", expected, last);
        assertEquals(SAMPLE.length(), inc.consumed());
    }

    /** 未完成行立即出现在输出里（不等换行）：首包即时可见 */
    @Test
    public void partialLine_visibleBeforeNewline() {
        StreamingPlainText p = new StreamingPlainText();
        assertEquals("hello", p.render("hello"));
        // 段落内软换行直接拼接（与 toPlainText 一致：Text 节点不含换行）
        assertEquals("helloworld", p.render("hello\nworld"));
        assertEquals("helloworld", p.render("hello\nworld"));
    }

    /** 未完成行的行内展平：已闭合的标记即时去掉；未闭合标记暂留（闭合到达后自动清除） */
    @Test
    public void partialLine_inlineMarkup() {
        StreamingPlainText p = new StreamingPlainText();
        assertEquals("hello **bo", p.render("hello **bo"));
        assertEquals("hello bo", p.render("hello **bo**"));
    }

    /** 未完成行不推进状态：后续补全同一行时不会重复输出或错误切块 */
    @Test
    public void partialLine_doesNotDoubleEmit() {
        StreamingPlainText p = new StreamingPlainText();
        assertEquals("ab", p.render("ab"));
        assertEquals("abc", p.render("abc"));      // 同一未完成行变长：整行重算、不叠加
        assertEquals("abc", p.render("abc\n"));
        assertEquals("abc\n\nnext", p.render("abc\n\nnext"));
    }

    /** 软换行（段落内）直接拼接（与 flexmark collectText 一致），空行才分块 */
    @Test
    public void softBreak_concatenatedBlankLineSeparates() {
        StreamingPlainText p = new StreamingPlainText();
        assertEquals("ab", p.render("a\nb"));
        assertEquals("a\n\nb", p.render("a\n\nb"));
    }

    /** 围栏代码块：围栏行不输出、空白与缩进原样保留、关闭后恢复普通块 */
    @Test
    public void fencedCode_preservesContent() {
        StreamingPlainText p = new StreamingPlainText();
        String out = p.render("```\n  indented **x**\n```\n\nafter");
        assertEquals("  indented **x**\n\nafter", out);
    }

    /** 缩进与有序列表：序号标记去掉，统一 "• " 前缀 */
    @Test
    public void orderedList_markerStripped() {
        StreamingPlainText p = new StreamingPlainText();
        assertEquals("• 一\n• 二", p.render("1. 一\n2. 二"));
    }

    /** reset 后从头开始（轮次边界清空缓冲） */
    @Test
    public void reset_startsOver() {
        StreamingPlainText p = new StreamingPlainText();
        p.render("# 旧");
        p.reset();
        assertEquals(0, p.consumed());
        assertEquals("新", p.render("新"));
    }

    /** 内容变短（缓冲被替换）自动整体重置，不残留旧缓存 */
    @Test
    public void shrunkContent_resets() {
        StreamingPlainText p = new StreamingPlainText();
        p.render("abc\ndef");
        assertEquals("x", p.render("x"));
    }

    /** 纯逻辑：行级识别函数可单独断言（围栏/标题/分隔线/列表/表格） */
    @Test
    public void lineClassifiers() {
        assertTrue(StreamingPlainText.isFenceOpen("```java"));
        assertTrue(StreamingPlainText.isFenceClose("```"));
        assertFalse(StreamingPlainText.isFenceClose("```java"));
        assertTrue(StreamingPlainText.isThematicBreak("---"));
        assertFalse(StreamingPlainText.isThematicBreak("a---"));
        assertEquals("标题", StreamingPlainText.headingText("## 标题"));
        assertNull(StreamingPlainText.headingText("正文"));
        assertNull("C# 这类非标题行不误判", StreamingPlainText.headingText("#标签"));
        assertEquals("引用", StreamingPlainText.stripQuote("> 引用"));
        assertEquals("项", StreamingPlainText.listItemText("- 项"));
        assertEquals("项", StreamingPlainText.listItemText("12. 项"));
        assertEquals("A | B", StreamingPlainText.tableCells("| A | B |"));
        assertTrue(StreamingPlainText.isTableSeparator("|---|---|"));
        assertFalse(StreamingPlainText.isTableSeparator("| 1 | 2 |"));
    }
}
