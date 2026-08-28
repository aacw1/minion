package com.minion.core.tools;

public class ToolResult {
    public final boolean ok;
    public final String output;

    private ToolResult(boolean ok, String output) {
        this.ok = ok;
        this.output = output;
    }

    public static ToolResult success(String output) { return new ToolResult(true, output); }
    public static ToolResult error(String message) { return new ToolResult(false, message); }

    /**
     * 发送给 API 的 tool 消息 content：开启占位且成功输出为空（null/空白）时返回「输出内容为空」，
     * 否则原样返回。用于规避空 content 的 tool 消息触发服务端稳定拒绝（重试永不成功，直至 20 分钟超时）。
     */
    public static String outputForApi(String output, boolean emptyPlaceholder) {
        if (emptyPlaceholder && (output == null || output.trim().isEmpty())) {
            return "输出内容为空";
        }
        return output;
    }

    /** 首个非空行 + 总行数摘要；单行原样返回 */
    public String preview() {
        String[] lines = output.split("\\r?\\n");
        if (lines.length <= 1) return output;
        int first = 0;
        while (first < lines.length && lines[first].trim().isEmpty()) first++;
        if (first >= lines.length) return "(" + lines.length + " lines)";
        return lines[first] + "\n(" + lines.length + " lines)";
    }
}
