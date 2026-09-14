package com.minion.core.tools;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 工具结果入历史闸门：单条输出超上限时，生产类截断 + 落盘留档 + 路径提示；
 *  读取类只截断 + 分页续读提示（**不落盘**，杜绝"读→落盘→再读→再落盘"套娃循环）。
 *  与 BashTool/GrepTool/MarkdownTable 的工具内预算同口径（30000 字符）；工具内预算未覆盖的通道
 *  （Read 全量读回、MCP/浏览器等第三方输出）由本闸门兜底——工具层是各自防线，本类是统一防线。
 *  只作用于"写入历史"的字符串：GUI 展示（ui.onToolResult）仍用原始结果，行为不变。 */
public final class ToolOutputGate {

    /** 单条工具结果入历史字符上限（与 BashTool/GrepTool/MarkdownTable 同口径） */
    public static final int MAX_CHARS = 30000;

    /** 读取类工具：超限不落盘——它们的输出本身就是"可再读"的内容，
     *  落盘会形成"读→落盘→再读"的新落盘来源，套娃无穷；改为原地分页续读 */
    private static final Set<String> READ_TOOLS =
            new HashSet<String>(Arrays.asList("Read", "Grep", "Glob"));

    private ToolOutputGate() { }

    /** 处理将写入历史的工具输出：未超限原样返回（含 null）；超限截断并附提示。
     *  @param toolName 工具名（读取类判定用）
     *  @param output 工具输出原文（空输出占位逻辑在调用方，先于本闸门）
     *  @param tmpDir 会话临时目录（落盘位置）；null/落盘失败时降级为纯截断提示 */
    public static String apply(String toolName, String output, Path tmpDir) {
        if (output == null || output.length() <= MAX_CHARS) return output;
        String head = truncateAt(output, MAX_CHARS);
        if (READ_TOOLS.contains(toolName)) {
            return head + "\n\n…（内容超单次上限（" + MAX_CHARS + " 字符）已截断，原内容共 "
                    + output.length() + " 字符；请缩小范围或用 offset/limit 分页继续读取）";
        }
        Path dumped = OutputDump.write(tmpDir, "tool-" + safeName(toolName), output);
        if (dumped == null) {
            return head + "\n\n…（输出过大已截断：共 " + output.length()
                    + " 字符；完整内容未能落盘，请改用更精确的查询/参数缩小输出）";
        }
        return head + "\n\n…（输出过大已截断：共 " + output.length() + " 字符，完整内容已落盘："
                + dumped.toAbsolutePath() + "；可用 Read 分页查看，或改用更精确的查询/参数）";
    }

    /** 截断到 max 字符：落在代理对中间时回退一位（对齐 MarkdownTable/OutputDump 口径） */
    private static String truncateAt(String s, int max) {
        int cut = max;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, cut);
    }

    /** 落盘文件名前缀清洗：非 [A-Za-z0-9_-] 一律替换为 _（工具名可能是 mcp:xx/yy 等） */
    private static String safeName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return "tool";
        return toolName.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
