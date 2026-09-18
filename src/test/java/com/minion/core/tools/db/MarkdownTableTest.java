package com.minion.core.tools.db;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Markdown 表格渲染：转义/截断/元信息行/字符预算 */
public class MarkdownTableTest {

    @Test
    public void cellNullBecomesLiteralNULL() {
        assertEquals("NULL", MarkdownTable.cell(null));
    }

    @Test
    public void cellEscapesPipeAndNewlines() {
        assertEquals("a\\|b", MarkdownTable.cell("a|b"));
        assertEquals("a<br>b", MarkdownTable.cell("a\nb"));
        assertEquals("a<br>b", MarkdownTable.cell("a\r\nb"));
    }

    @Test
    public void cellTruncatesOver120Chars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 130; i++) sb.append('x');
        String out = MarkdownTable.cell(sb.toString());
        assertEquals(sb.substring(0, 120), out.substring(0, 120));  // 前 120 保留
        assertTrue(out.endsWith("…[完整 130 字符]"));
    }

    @Test
    public void cellWithCustomMaxKeepsContentWithinLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('y');
        assertEquals("5000 字符在 20000 上限内应全文返回", sb.toString(),
                MarkdownTable.cell(sb.toString(), 20000));
    }

    @Test
    public void cellWithCustomMaxTruncatesAndNotesRealLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25000; i++) sb.append('z');
        String out = MarkdownTable.cell(sb.toString(), 20000);
        assertTrue(out.startsWith(sb.substring(0, 20000)));
        assertTrue(out.endsWith("…[完整 25000 字符]"));
    }

    @Test
    public void cellLengthNoteCountsOriginalNotEscapedLength() {
        // 10 个换行 → 转义后 40+ 字符，但原文只有 10 字符；上限 30 触发截断时
        // 标注必须报原文长度 10，而非转义后长度
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 10; i++) raw.append('\n');
        String out = MarkdownTable.cell(raw.toString(), 30);
        assertTrue(out.endsWith("…[完整 10 字符]"));
    }

    @Test
    public void cellTruncationNeverSplitsSurrogatePair() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 119; i++) sb.append('x');
        sb.append("\uD83D\uDE00");                       // emoji 占 2 个 char，共 121
        String out = MarkdownTable.cell(sb.toString(), 120);
        assertEquals("截断点落在高代理上应回退一位", 119, out.indexOf('…'));
        assertFalse("高代理字符不得泄漏到输出", out.contains("\uD83D"));
        assertTrue(out.endsWith("…[完整 121 字符]"));
    }

    @Test
    public void renderBuildsHeaderSeparatorAndRows() {
        String md = MarkdownTable.render(Arrays.asList("id", "name"),
                Arrays.asList(Arrays.asList("1", "张三"), Arrays.asList("2", "NULL")));
        assertEquals("| id | name |\n| --- | --- |\n| 1 | 张三 |\n| 2 | NULL |", md);
    }

    @Test
    public void renderEmptyRowsYieldsNoDataRow() {
        assertEquals("| id |\n| --- |", MarkdownTable.render(
                Arrays.asList("id"), new ArrayList<List<String>>()));
    }

    @Test
    public void headerWithoutTruncation() {
        assertEquals("数据源: prod · 耗时: 1.24s · 行数: 37",
                MarkdownTable.header("prod", 1240, 37, false, 100));
    }

    @Test
    public void headerWithTruncation() {
        assertEquals("数据源: prod · 耗时: 1.24s · 行数: 100"
                        + "（已达上限 100 行，结果被截断，可加 LIMIT/WHERE 细化）",
                MarkdownTable.header("prod", 1240, 100, true, 100));
    }

    @Test
    public void headerZeroRows() {
        assertEquals("数据源: prod · 耗时: 0.01s · 行数: 0",
                MarkdownTable.header("prod", 10, 0, false, 100));
    }

    @Test
    public void fitKeepsShortContent() {
        assertEquals("abc", MarkdownTable.fit("abc", null));
    }

    @Test
    public void fitTruncatesWhenNoTmpDir() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 31000; i++) sb.append('y');
        String out = MarkdownTable.fit(sb.toString(), null);
        assertTrue(out.startsWith("yyyy"));
        assertTrue(out.contains("…（结果过大：共 31000 字符，完整内容未能落盘。请拆分查询"));
        assertTrue(out.contains("加 WHERE/LIMIT"));
        assertTrue(out.length() < 31000);
    }

    @Test
    public void escapeKeepsLongContentUncut() {
        assertEquals("NULL", MarkdownTable.escape(null));
        assertEquals("a\\|b", MarkdownTable.escape("a|b"));
        assertEquals("a<br>b", MarkdownTable.escape("a\nb"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25000; i++) sb.append('q');
        assertEquals("落盘口径：只转义不截断", 25000, MarkdownTable.escape(sb.toString()).length());
    }

    /** full=true 单值超 20000：展示被截断（[完整 N 字符]）时，必须落盘未截断全量并给出路径（本次修复的回归用例） */
    @Test
    public void fitDumpsUncutCompleteWhenCellTruncated() throws Exception {
        Path tmp = Files.createTempDirectory("md-table-cellcut");
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 37457; i++) raw.append('x');
        String head = "数据源: prod · 耗时: 0.12s · 行数: 1\n\n| body |\n| --- |\n| ";
        String display = head + MarkdownTable.cell(raw.toString(), 20000) + " |";
        String complete = head + MarkdownTable.escape(raw.toString()) + " |";
        String out = MarkdownTable.fit(display, complete, tmp);

        assertTrue("单元格截断必须落盘", out.contains("已落盘："));
        assertTrue("引导 Read 取全文", out.contains("Read"));
        assertTrue("返回总长不得超闸门口径，防提示被 ToolOutputGate 二次截断",
                out.length() <= MarkdownTable.CHAR_BUDGET);

        Path dumped;
        try (java.util.stream.Stream<Path> s = Files.list(tmp)) {
            dumped = s.findFirst().get();
        }
        String dumpedText = new String(Files.readAllBytes(dumped), "UTF-8");
        assertEquals("落盘内容必须是未截断全量（长度 = complete）", complete, dumpedText);
    }

    /** 超预算 + 单元格截断：返回头部 + 路径提示，总长同样不得超闸门口径 */
    @Test
    public void fitCapsTotalLengthWhenOverBudgetAndCellCut() throws Exception {
        Path tmp = Files.createTempDirectory("md-table-cellcut-over");
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 50000; i++) raw.append('w');
        String head = "数据源: prod · 耗时: 0.12s · 行数: 1\n\n| a | b |\n| --- | --- |\n| ";
        // 两格各截断到 20000 → display 超 30000 预算，同时存在单元格截断
        String display = head + MarkdownTable.cell(raw.toString(), 20000)
                + " | " + MarkdownTable.cell(raw.toString(), 20000) + " |";
        String complete = head + MarkdownTable.escape(raw.toString())
                + " | " + MarkdownTable.escape(raw.toString()) + " |";
        String out = MarkdownTable.fit(display, complete, tmp);
        assertTrue(out.contains("已落盘："));
        assertTrue(out.length() <= MarkdownTable.CHAR_BUDGET);
        assertTrue("超预算时保留拆分查询引导", out.contains("请拆分查询"));
    }

    /** 展示与全量一致（无截断）且不超预算：原样返回，不落盘 */
    @Test
    public void fitReturnsUnchangedWhenNoCutAndWithinBudget() {
        assertEquals("abc", MarkdownTable.fit("abc", "abc", null));
    }

    @Test
    public void fitDumpsToTmpDirWhenOverBudget() throws Exception {
        Path tmp = Files.createTempDirectory("md-table-test");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 31000; i++) sb.append('z');
        String out = MarkdownTable.fit(sb.toString(), tmp);
        assertTrue(out.contains("结果过大：共 31000 字符，完整内容已落盘："));
        assertTrue("引导拆分查询", out.contains("请拆分查询：加 WHERE/LIMIT 缩小范围、分页或分列查询"));
        assertTrue("全文可 Read 分页查看", out.contains("需要全文可用 Read 分页查看"));
        // 落盘文件确实存在且是全量
        String path = out.substring(out.indexOf("已落盘：") + 4, out.indexOf("。请拆分查询"));
        assertEquals(31000, new String(Files.readAllBytes(new java.io.File(path).toPath()),
                "UTF-8").length());
    }
}
