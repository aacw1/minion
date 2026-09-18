package com.minion.core.tools.db;

import com.minion.core.tools.OutputDump;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 大字段原样导出（纯函数，无 JDBC 依赖）：阈值判定 / 文件名前缀 / 写盘 / 表尾清单文案。
 * 逐字节原样落盘（不做 Markdown 转义、不做截断），供解析/解码场景避免转义污染与分页拼接错位。
 * 生命周期随会话临时目录（SessionManager 删会话递归删除）。
 */
public final class DbExport {

    /** 导出阈值：单元格原始值（转义前）超过此长度即原样导出（与 DbExecutor.FULL_CELL_MAX 同口径） */
    public static final int EXPORT_MIN_CHARS = 20000;
    /** 清单最多列出的条目数，超出折叠（防撑爆返回字符预算） */
    public static final int HINT_MAX_ITEMS = 5;
    /** 文件名中列名片段的长度上限（防超长列名撑爆文件名） */
    private static final int NAME_MAX = 40;

    private DbExport() { }

    /** 待导出条目：rowNum/colIdx 均为 1-based；path 为写盘后的绝对路径（未写盘为 null） */
    public static final class Entry {
        public final int rowNum;
        public final int colIdx;
        public final String colName;
        public final String value;
        public final String path;

        public Entry(int rowNum, int colIdx, String colName, String value, String path) {
            this.rowNum = rowNum;
            this.colIdx = colIdx;
            this.colName = colName;
            this.value = value;
            this.path = path;
        }
    }

    /** 原始值是否需要原样导出（null 与 ≤ 阈值不导出；恰为 20000 不导出） */
    public static boolean shouldExport(Object rawValue) {
        return rawValue != null && String.valueOf(rawValue).length() > EXPORT_MIN_CHARS;
    }

    /** 文件名前缀：db-r<行>-c<列>-<清洗列名>；列名仅保留 [A-Za-z0-9_-]，空名兜底 col<列序号> */
    public static String prefix(int rowNum, int colIdx, String colName) {
        String safe = colName == null ? "" : colName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) safe = "col" + colIdx;
        if (safe.length() > NAME_MAX) safe = safe.substring(0, NAME_MAX);
        return "db-r" + rowNum + "-c" + colIdx + "-" + safe;
    }

    /** 写盘全部待导出条目并返回清单文案：写盘失败自动降级（不抛异常），无条目返回 "" */
    public static String dump(Path tmpDir, List<Entry> pending) {
        if (pending == null || pending.isEmpty()) return "";
        List<Entry> written = new ArrayList<Entry>();
        int failed = 0;
        for (Entry e : pending) {
            Path f = OutputDump.write(tmpDir, prefix(e.rowNum, e.colIdx, e.colName), e.value);
            if (f == null) {
                failed++;
                continue;
            }
            written.add(new Entry(e.rowNum, e.colIdx, e.colName, e.value, f.toAbsolutePath().toString()));
        }
        return hint(written, failed);
    }

    /** 表尾清单：written 为已写盘条目（path 非 null），failed 为写盘失败数；无任何导出时返回 "" */
    public static String hint(List<Entry> written, int failed) {
        boolean hasWritten = written != null && !written.isEmpty();
        if (!hasWritten && failed <= 0) return "";
        StringBuilder sb = new StringBuilder("\n\n…（");
        if (!hasWritten) {
            return sb.append(failed).append(" 个大字段原样导出失败，请拆分查询或加 WHERE/LIMIT）").toString();
        }
        sb.append("以下单元格完整内容已原样导出（未转义、未截断），可用 Read full=true 一次读回，或用 Bash 直接处理）：");
        int shown = Math.min(written.size(), HINT_MAX_ITEMS);
        for (int i = 0; i < shown; i++) {
            Entry e = written.get(i);
            sb.append("\n  · 第 ").append(e.rowNum).append(" 行 · ").append(e.colName)
              .append("（").append(e.value.length()).append(" 字符）→ ").append(e.path);
        }
        if (written.size() > shown) {
            sb.append("\n  · …另有 ").append(written.size() - shown).append(" 个文件未列出");
        }
        if (failed > 0) {
            sb.append("\n  · 另有 ").append(failed).append(" 个大字段导出失败（请拆分查询或加 WHERE/LIMIT）");
        }
        return sb.append("）").toString();
    }
}
