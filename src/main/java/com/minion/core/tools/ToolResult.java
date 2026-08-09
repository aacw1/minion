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
