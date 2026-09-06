package com.minion.core.tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式输出截断助手：内存头尾预算 + 全量落盘（同 BashTool 历史口径，抽取共用）。
 * 打开即建落盘文件；append 双写内存缓冲与文件；未超限时 finish() 删盘返回全量（零磁盘痕迹），
 * 超限时返回 头(18k) + 截断提示(含文件路径) + 尾(12k)，文件保留供 Read 查看。
 * 落盘失败/tmpDir 为 null 时降级为纯内存截断（提示文案明示未保存完整输出）。
 */
public final class TruncatedOutput {

    /** 内存保留头部上限 */
    public static final int HEAD_MAX = 18000;
    /** 落盘文件尾部读取上限 */
    public static final int TAIL_MAX = 12000;
    /** 总预算。内存保留上限为 TOTAL_MAX（与删除落盘的阈值一致）——若只保留 HEAD_MAX，
     *  输出落在 (18k, 30k] 区间时删盘后无尾部可补，该段数据永久丢失（P0 回归点） */
    public static final int TOTAL_MAX = HEAD_MAX + TAIL_MAX;

    private final StringBuilder buffer = new StringBuilder();
    private final AtomicLong totalChars = new AtomicLong();
    private final Path dump;                 // null = 纯内存降级
    private final BufferedWriter dumpWriter; // 与 dump 同 null
    private boolean closed;

    private TruncatedOutput(Path dump, BufferedWriter writer) {
        this.dump = dump;
        this.dumpWriter = writer;
    }

    /** 打开累积器并建落盘文件；tmpDir 为 null 或落盘失败 → 纯内存实例（不抛异常） */
    public static TruncatedOutput open(Path tmpDir, String prefix) {
        Path dump = OutputDump.write(tmpDir, prefix, "");
        if (dump == null) return new TruncatedOutput(null, null);
        try {
            BufferedWriter w = Files.newBufferedWriter(dump, StandardCharsets.UTF_8);
            return new TruncatedOutput(dump, w);
        } catch (IOException e) {
            System.err.println("[minion] 输出落盘失败: " + e.getMessage());
            return new TruncatedOutput(null, null);
        }
    }

    /** 追加一段输出：内存截断到 TOTAL_MAX，落盘写全量；线程安全由调用方保证（单 reader 线程） */
    public void append(String s) {
        if (s == null || s.length() == 0) return;
        if (buffer.length() < TOTAL_MAX) {
            int room = TOTAL_MAX - buffer.length();
            buffer.append(s, 0, Math.min(room, s.length()));
        }
        totalChars.addAndGet(s.length());
        if (dumpWriter != null) {
            try {
                dumpWriter.write(s);
            } catch (IOException ignored) { }
        }
    }

    /** 关闭落盘 writer（幂等）；调用方保证 finish() 之前已 close */
    public void close() {
        if (closed) return;
        closed = true;
        if (dumpWriter != null) {
            try {
                dumpWriter.close();
            } catch (IOException ignored) { }
        }
    }

    /** 已累积字符数（超限判定用） */
    public long totalChars() {
        return totalChars.get();
    }

    /** 组装返回（语义与 BashTool.finishOutput 逐字等价，调用前必须已 close） */
    public String finish() {
        if (totalChars.get() <= TOTAL_MAX) {
            if (dump != null) {
                dump.toFile().delete();
                // 顺带删空的 tmp 目录，保证"不超限不落盘"零痕迹
                try {
                    Files.deleteIfExists(dump.getParent());
                } catch (IOException ignored) { }
            }
            return buffer.toString();
        }
        // 超限：头部只取 HEAD_MAX（内存已保留 TOTAL_MAX，需显式截取）；
        // 落盘失败降级时 head 取内存全量，避免比既有行为再多丢一段
        String head = dump == null || buffer.length() <= HEAD_MAX
                ? buffer.toString() : buffer.substring(0, HEAD_MAX);
        // 截断点可能切在代理对（如 emoji）中间，丢弃尾部孤立高代理，避免输出非法字符
        if (head.length() > 0 && Character.isHighSurrogate(head.charAt(head.length() - 1))) {
            head = head.substring(0, head.length() - 1);
        }
        String tailStr = dump == null ? "" : OutputDump.tail(dump, TAIL_MAX);
        String note = dump == null
                ? "\n... 输出已截断（共 " + totalChars.get() + " 字符，落盘失败未保存完整输出，以上为仅存内容）...\n"
                : "\n... 输出已截断（共 " + totalChars.get() + " 字符，完整输出已保存到 "
                        + dump.toAbsolutePath()
                        + "，可用 Read 查看）...\n";
        return head + note + tailStr;
    }
}
