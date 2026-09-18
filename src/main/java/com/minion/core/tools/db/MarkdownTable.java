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
    /** 单元格默认截断长度（防长文本列把表格撑爆）；截断处追加省略号 + 真实长度标注，见 {@link #cell(Object, int)} */
    public static final int CELL_MAX = 120;

    private MarkdownTable() { }

    /** null → 字面量 NULL；竖线转义；换行转 <br>；**不截断**（落盘全量与 cell 共用同一转义口径） */
    public static String escape(Object v) {
        if (v == null) return "NULL";
        return String.valueOf(v)
                .replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>")
                .replace("|", "\\|");
    }

    /** null → 字面量 NULL；竖线转义；换行转 &lt;br&gt;；超 120 截断并标注真实长度 */
    public static String cell(Object v) { return cell(v, CELL_MAX); }

    /** null → 字面量 NULL；竖线转义；换行转 &lt;br&gt;；超 cellMax 截断：省略号 + 真实长度标注 */
    public static String cell(Object v, int cellMax) {
        if (v == null) return "NULL";
        int rawLen = String.valueOf(v).length();   // 标注口径：转义（<br> 膨胀）前的真实长度
        String s = escape(v);
        if (s.length() > cellMax) {
            int cut = cellMax;
            // 截断点可能切在代理对（如 emoji）中间：丢弃尾部孤立高代理（对齐 TruncatedOutput 口径）
            if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
            s = s.substring(0, cut) + "…[完整 " + rawLen + " 字符]";
        }
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
     * 字符预算处理（旧口径，等价于 display == complete）：≤30000 原样返回；超出则全量落盘并返回头部 + 路径提示。
     */
    public static String fit(String full, Path tmpDir) { return fit(full, full, tmpDir); }

    /**
     * 字符预算 + 截断落盘统一入口：
     * - display 是展示文本（单元格可能已按 cellMax 截断、含「…[完整 N 字符]」标注）；
     * - complete 是未截断全量文本，落盘内容永远是它（"完整内容已落盘"名副其实）。
     * 触发条件：display 超 CHAR_BUDGET **或** display 与 complete 不一致（存在被截断的单元格——
     * 修此前的漏洞：单值超 cellMax 但截断后总长不足 30000 时既截断又不落盘，全文无路径可取）。
     * 返回文本总长恒 ≤ CHAR_BUDGET（为提示预留头部空间），保证过 ToolOutputGate 不再被二次截断（提示不被切掉）。
     * tmpDir 为 null 或落盘失败 → 降级为纯内存截断。表格式数据头部比尾部有用（首行是表头），故不用 OutputDump.tail。
     */
    public static String fit(String display, String complete, Path tmpDir) {
        if (display == null) return "";
        if (complete == null) complete = display;
        boolean overBudget = display.length() > CHAR_BUDGET;
        boolean cellCut = !display.equals(complete);
        if (!overBudget && !cellCut) return display;

        Path dumped = OutputDump.write(tmpDir, "db", complete);
        String hint;
        if (dumped == null) {
            hint = cellCut && !overBudget
                    ? "\n\n…（单元格超长已截断：完整内容共 " + complete.length()
                      + " 字符未能落盘。请用 WHERE/LIMIT 缩小范围，或分列查询）"
                    : "\n\n…（结果过大：共 " + complete.length()
                      + " 字符，完整内容未能落盘。请拆分查询：加 WHERE/LIMIT 缩小范围、分页或分列查询）";
        } else if (cellCut && !overBudget) {
            hint = "\n\n…（单元格超长已截断：完整内容共 " + complete.length() + " 字符已落盘："
                    + dumped.toAbsolutePath()
                    + "。需要全文可用 Read 分页查看；也可加 WHERE/LIMIT 限定到需要的行）";
        } else {
            hint = "\n\n…（结果过大：共 " + complete.length() + " 字符，完整内容已落盘："
                    + dumped.toAbsolutePath()
                    + "。请拆分查询：加 WHERE/LIMIT 缩小范围、分页或分列查询；需要全文可用 Read 分页查看）";
        }
        int headBudget = CHAR_BUDGET - hint.length();
        if (headBudget < 0) headBudget = 0;
        String head = display.length() <= headBudget ? display : truncateAt(display, headBudget);
        return head + hint;
    }

    /** 截断到 max 字符：落在代理对中间时回退一位（对齐 ToolOutputGate 口径） */
    private static String truncateAt(String s, int max) {
        int cut = max;
        if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, cut);
    }
}
