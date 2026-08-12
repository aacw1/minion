package com.minion.gui.chat;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown → 块结构（纯函数，不依赖 JavaFX，可单测）。
 * UI 层把 Block 转 JavaFX 节点（BlockNodeFactory）。
 */
public class MarkdownRenderer {

    public static class Span {
        public final String text;
        public final String style; // plain / bold / italic / code / strike 组合
        public Span(String text, String style) {
            this.text = text;
            this.style = style;
        }
    }

    public static class TableRowData {
        public final List<String> cells = new ArrayList<String>();
        public final boolean header;
        public TableRowData(boolean header) { this.header = header; }
    }

    public static class Block {
        public enum Type { PARAGRAPH, HEADING, CODE, LIST, QUOTE, TABLE, RULE }

        public final Type type;
        public String text;
        public String lang;
        public int level;
        public List<Span> spans = new ArrayList<Span>();
        public List<Block> items = new ArrayList<Block>();
        public List<TableRowData> rows = new ArrayList<TableRowData>();

        public Block(Type type) { this.type = type; }
    }

    /** 解析 Markdown → 块列表（空/空白文本 → 空列表） */
    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<Block>();
        if (md == null || md.trim().isEmpty()) return out;
        Parser parser = Parser.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(),
                        StrikethroughExtension.create()))
                .build();
        Document doc = parser.parse(md);
        for (Node n : doc.getChildren()) {
            convert(n, out);
        }
        return out;
    }

    private static void convert(Node n, List<Block> out) {
        if (n instanceof Heading) {
            Heading h = (Heading) n;
            Block b = new Block(Block.Type.HEADING);
            b.level = h.getLevel();
            b.text = collectText(h);
            out.add(b);
        } else if (n instanceof Paragraph) {
            Block b = new Block(Block.Type.PARAGRAPH);
            b.spans = collectSpans((Paragraph) n);
            b.text = collectText(n);
            out.add(b);
        } else if (n instanceof FencedCodeBlock) {
            FencedCodeBlock f = (FencedCodeBlock) n;
            Block b = new Block(Block.Type.CODE);
            b.lang = f.getInfo() == null ? "" : f.getInfo().toString().trim();
            // flexmark 0.62.2/0.64.8 无 getContentChars()：内容行是 FencedCodeBlock 的 Text 子节点
            StringBuilder sb = new StringBuilder();
            for (Node child : f.getChildren()) sb.append(child.getChars());
            b.text = sb.toString().replaceAll("\\n$", "");
            out.add(b);
        } else if (n instanceof BulletList || n instanceof OrderedList) {
            Block b = new Block(Block.Type.LIST);
            for (Node child : n.getChildren()) {
                if (child instanceof ListItem) {
                    Block item = new Block(Block.Type.PARAGRAPH);
                    item.text = collectText(child);
                    item.spans = collectSpans(child);
                    b.items.add(item);
                }
            }
            out.add(b);
        } else if (n instanceof BlockQuote) {
            Block b = new Block(Block.Type.QUOTE);
            b.text = collectText(n);
            out.add(b);
        } else if (n instanceof ThematicBreak) {
            out.add(new Block(Block.Type.RULE));
        } else if (n instanceof TableBlock) {
            Block b = new Block(Block.Type.TABLE);
            for (Node section : n.getChildren()) {
                if (!(section instanceof TableHead) && !(section instanceof TableBody)) continue;
                boolean header = section instanceof TableHead;
                for (Node rowNode : section.getChildren()) {
                    if (!(rowNode instanceof TableRow)) continue;
                    TableRowData row = new TableRowData(header);
                    for (Node cellNode : rowNode.getChildren()) {
                        if (cellNode instanceof TableCell) row.cells.add(collectText(cellNode));
                    }
                    b.rows.add(row);
                }
            }
            out.add(b);
        } else {
            Block b = new Block(Block.Type.PARAGRAPH);
            b.text = collectText(n);
            out.add(b);
        }
    }

    private static List<Span> collectSpans(Node node) {
        List<Span> spans = new ArrayList<Span>();
        walkInline(node, "", spans);
        return spans;
    }

    /** 递归收集行内富文本 span；style 继承当前样式（可组合：boldcode 等） */
    private static void walkInline(Node node, String style, List<Span> spans) {
        if (node instanceof Text) {
            String t = ((Text) node).getChars().toString();
            if (!t.isEmpty()) spans.add(new Span(t, style));
        } else if (node instanceof Code) {
            String t = stripCodeMarks(((Code) node).getChars().toString());
            if (!t.isEmpty()) spans.add(new Span(t, style + "code"));
        } else if (node instanceof StrongEmphasis) {
            for (Node child : node.getChildren()) walkInline(child, style + "bold", spans);
        } else if (node instanceof Emphasis) {
            for (Node child : node.getChildren()) walkInline(child, style + "italic", spans);
        } else if (node instanceof Strikethrough) {
            for (Node child : node.getChildren()) walkInline(child, style + "strike", spans);
        } else if (node instanceof SoftLineBreak) {
            spans.add(new Span("\n", style));
        } else {
            for (Node child : node.getChildren()) walkInline(child, style, spans);
        }
    }

    private static String stripCodeMarks(String s) {
        if (s.startsWith("`")) s = s.substring(1);
        if (s.endsWith("`") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String collectText(Node node) {
        StringBuilder sb = new StringBuilder();
        appendText(node, sb);
        return sb.toString().trim();
    }

    private static void appendText(Node node, StringBuilder sb) {
        if (node instanceof Text) {
            sb.append(((Text) node).getChars().toString());
        } else {
            for (Node child : node.getChildren()) appendText(child, sb);
        }
    }
}
