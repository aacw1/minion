package com.minion.core.tools.db;

import com.minion.core.tools.OutputDump;

import java.nio.file.Path;
import java.util.List;

/**
 * 查询结果 → Markdown 表格（纯函数）。
 * 选 Markdown 而非 JSON/TSV：模型训练语料里表格最多、理解最稳，且 GUI 消息区能直接渲染成真表格。
 * 字符预算 30000 与 GrepTool.DISPLAY_CHARS / BashTool 总预算同口径。
 */
public final class MarkdownTable {

    /** 返回给模型的字符预算；超出则全量落盘 + 返回头部 */
    public static final int CHAR_BUDGET = 30000;
    /** 单元格截断长度（防长文本列把表格撑爆） */
    public static final int CELL_MAX = 120;

    private MarkdownTable() { }

    /** null → 字面量 NULL；竖线转义；换行转 &lt;br&gt;；超长截断加省略号 */
    public static String cell(Object v) {
        if (v == null) return "NULL";
        String s = String.valueOf(v);
        s = s.replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>");
        s = s.replace("|", "\\|");
        if (s.length() > CELL_MAX) s = s.substring(0, CELL_MAX) + "…";
        return s;
    }

    /** 表头 + 分隔行 + 数据行；rows 为空时只有表头与分隔行 */
    public static String render(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('|');
        for (String c : columns) sb.append(' ').append(c).append(" |");
        sb.append('\n').append('|');
        for (int i = 0; i < columns.size(); i++) sb.append(" --- |");
        for (List<String> row : rows) {
            sb.append('\n').append('|');
            for (int i = 0; i < columns.size(); i++) {
                String v = i < row.size() ? row.get(i) : null;
                sb.append(' ').append(v == null ? "NULL" : v).append(" |");
            }
        }
        return sb.toString();
    }

    /** 元信息行：数据源 · 耗时 · 行数（截断时追加上限说明） */
    public static String header(String dsName, long elapsedMs, int rowCount,
                                boolean truncated, int maxRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据源: ").append(dsName)
          .append(" · 耗时: ").append(String.format("%.2f", elapsedMs / 1000.0)).append('s')
          .append(" · 行数: ").append(rowCount);
        if (truncated) {
            sb.append("（已达上限 ").append(maxRows)
              .append(" 行，结果被截断，可加 LIMIT/WHERE 细化）");
        }
        return sb.toString();
    }

    /**
     * 字符预算处理：≤30000 原样返回；超出则全量落盘（前缀 db）并返回头部 + 路径提示。
     * 表格式数据头部比尾部有用（首行是表头），故不用 OutputDump.tail。
     * tmpDir 为 null 或落盘失败 → 降级为纯内存截断。
     */
    public static String fit(String full, Path tmpDir) {
        if (full == null) return "";
        if (full.length() <= CHAR_BUDGET) return full;
        String head = full.substring(0, CHAR_BUDGET);
        Path dumped = OutputDump.write(tmpDir, "db", full);
        if (dumped == null) {
            return head + "\n\n…（结果过长已截断，共 " + full.length() + " 字符）";
        }
        return head + "\n\n…（完整结果 " + full.length() + " 字符已落盘："
                + dumped.toAbsolutePath() + "，可用 Read 查看）";
    }
}
