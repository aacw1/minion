package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MarkdownRendererTest {

    @Test
    public void parse_plainText() {
        List<Block> blocks = MarkdownRenderer.parse("hello world");
        assertEquals(1, blocks.size());
        assertEquals(Block.Type.PARAGRAPH, blocks.get(0).type);
        assertEquals("hello world", blocks.get(0).text);
    }

    @Test
    public void parse_headingAndCodeFence() {
        List<Block> blocks = MarkdownRenderer.parse("# 标题\n\n```java\nint a = 1;\n```");
        assertEquals(2, blocks.size());
        assertEquals(Block.Type.HEADING, blocks.get(0).type);
        assertEquals(1, blocks.get(0).level);
        assertEquals("标题", blocks.get(0).text);
        assertEquals(Block.Type.CODE, blocks.get(1).type);
        assertEquals("java", blocks.get(1).lang);
        assertTrue(blocks.get(1).text.contains("int a = 1;"));
    }

    @Test
    public void parse_inlineMarkup() {
        List<Block> blocks = MarkdownRenderer.parse("**加粗** 和 `行内码`");
        assertEquals(1, blocks.size());
        assertEquals(3, blocks.get(0).spans.size());
        assertEquals("加粗", blocks.get(0).spans.get(0).text);
        assertEquals("bold", blocks.get(0).spans.get(0).style);
        assertEquals("行内码", blocks.get(0).spans.get(2).text);
        assertTrue(blocks.get(0).spans.get(2).style.contains("code"));
    }

    @Test
    public void parse_unorderedList() {
        List<Block> blocks = MarkdownRenderer.parse("- 甲\n- 乙");
        assertEquals(1, blocks.size());
        assertEquals(Block.Type.LIST, blocks.get(0).type);
        assertEquals(2, blocks.get(0).items.size());
        assertEquals("甲", blocks.get(0).items.get(0).text);
    }

    @Test
    public void parse_strikethrough() {
        List<Block> blocks = MarkdownRenderer.parse("~~删除~~");
        assertEquals(1, blocks.size());
        assertEquals("删除", blocks.get(0).spans.get(0).text);
        assertTrue(blocks.get(0).spans.get(0).style.contains("strike"));
    }

    @Test
    public void parse_empty() {
        List<Block> blocks = MarkdownRenderer.parse("");
        assertEquals(0, blocks.size());
    }

    @Test
    public void parse_table() {
        List<Block> blocks = MarkdownRenderer.parse("| a | b |\n|---|---|\n| 1 | 2 |");
        assertEquals(1, blocks.size());
        assertEquals(Block.Type.TABLE, blocks.get(0).type);
        assertEquals(2, blocks.get(0).rows.size());
        assertEquals(2, blocks.get(0).rows.get(0).cells.size());
        assertEquals("a", blocks.get(0).rows.get(0).cells.get(0));
    }

    // ===== toPlainText：markdown → 可读纯文本（消息区 TextArea 展示用） =====

    @Test
    public void toPlainText_headingAndParagraph() {
        assertEquals("标题\n\n正文内容", MarkdownRenderer.toPlainText("# 标题\n\n正文内容"));
    }

    @Test
    public void toPlainText_codeBlock_noFence() {
        assertEquals("int a = 1;\nreturn a;", MarkdownRenderer.toPlainText(
                "```java\nint a = 1;\nreturn a;\n```"));
    }

    @Test
    public void toPlainText_list_withBullets() {
        assertEquals("• 甲\n• 乙", MarkdownRenderer.toPlainText("- 甲\n- 乙"));
    }

    @Test
    public void toPlainText_table_pipeJoined() {
        assertEquals("a | b\n1 | 2", MarkdownRenderer.toPlainText("| a | b |\n|---|---|\n| 1 | 2 |"));
    }

    @Test
    public void toPlainText_inlineMarkup_stripped() {
        assertEquals("加粗 和 行内码", MarkdownRenderer.toPlainText("**加粗** 和 `行内码`"));
    }

    @Test
    public void toPlainText_quote() {
        assertEquals("引用文字", MarkdownRenderer.toPlainText("> 引用文字"));
    }

    @Test
    public void toPlainText_empty() {
        assertEquals("", MarkdownRenderer.toPlainText(""));
    }

    @Test
    public void toPlainText_ruleIgnored() {
        assertEquals("", MarkdownRenderer.toPlainText("---"));
    }

    @Test
    public void toPlainText_mixedDocument_blankLineSeparated() {
        String md = "# 标题\n\n```java\nint a = 1;\n```\n\n- 甲\n- 乙";
        assertEquals("标题\n\nint a = 1;\n\n• 甲\n• 乙", MarkdownRenderer.toPlainText(md));
    }
}
