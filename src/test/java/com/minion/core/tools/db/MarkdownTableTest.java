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
        assertEquals(121, out.length());          // 120 + 省略号
        assertTrue(out.endsWith("…"));
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
        assertTrue(out.contains("…（结果过长已截断，共 31000 字符）"));
        assertTrue(out.length() < 31000);
    }

    @Test
    public void fitDumpsToTmpDirWhenOverBudget() throws Exception {
        Path tmp = Files.createTempDirectory("md-table-test");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 31000; i++) sb.append('z');
        String out = MarkdownTable.fit(sb.toString(), tmp);
        assertTrue(out.contains("完整结果 31000 字符已落盘："));
        assertTrue(out.contains("可用 Read 查看"));
        // 落盘文件确实存在且是全量
        String path = out.substring(out.indexOf("落盘：") + 3, out.indexOf("，可用 Read"));
        assertEquals(31000, new String(Files.readAllBytes(new java.io.File(path).toPath()),
                "UTF-8").length());
    }
}
