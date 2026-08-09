package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读文件。参数: path(必), offset(行偏移), limit(默认2000), lineNumbers(是否带行号) */
public class ReadTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;

    private final String workDir;

    public ReadTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Read"; }

    @Override
    public String description() { return "读取文件内容，支持行号、偏移与行数限制"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("读取文件内容",
                new String[]{"path", "offset", "limit", "lineNumbers"},
                new String[]{"path"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String path = args.has("path") ? args.get("path").getAsString() : "";
        if (path.isEmpty()) return ToolResult.error("缺少 path 参数");
        Path p = PathsGuard.resolve(workDir, path);
        if (!Files.exists(p)) return ToolResult.error("文件不存在: " + p);
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = PathsGuard.errorIfOutside(workDir, p);
        if (guard != null) return guard;

        int offset = 0;
        int limit = DEFAULT_LIMIT;
        try {
            if (args.has("offset")) offset = args.get("offset").getAsInt();
            if (args.has("limit")) limit = args.get("limit").getAsInt();
        } catch (NumberFormatException e) {
            return ToolResult.error("参数 offset/limit 格式错误: " + e.getMessage());
        }
        if (offset < 0) return ToolResult.error("参数 offset 不能为负: " + offset);
        if (limit <= 0) return ToolResult.error("参数 limit 必须大于 0: " + limit);
        if ((long) offset + (long) limit > Integer.MAX_VALUE) {
            return ToolResult.error("参数 offset+limit 超出范围");
        }
        boolean lineNumbers = args.has("lineNumbers") && args.get("lineNumbers").getAsBoolean();

        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int to = Math.min(lines.size(), offset + limit);
        for (int i = offset; i < to; i++) {
            if (lineNumbers) sb.append(i + 1).append(": ");
            sb.append(lines.get(i)).append('\n');
        }
        if (to < lines.size()) {
            sb.append("... 共 ").append(lines.size()).append(" 行，已显示 ")
              .append(to - offset).append(" 行（可用 offset/limit 翻页）\n");
        }
        return ToolResult.success(sb.toString());
    }
}
