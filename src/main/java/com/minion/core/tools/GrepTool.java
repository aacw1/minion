package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 正则内容搜索。参数: pattern(必), path(可选搜索起点), ignoreCase, maxResults */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;

    private final String workDir;
    private final String skillsDir;

    public GrepTool(String workDir) { this(workDir, null); }

    public GrepTool(String workDir, String skillsDir) {
        this.workDir = workDir;
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() { return "Grep"; }

    @Override
    public String description() { return "在工作路径内按正则搜索文件内容，输出 文件:行号:内容"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("正则搜索文件内容",
                new String[]{"pattern", "path", "ignoreCase", "maxResults"},
                new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (pattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        final Pattern p;
        try {
            int flags = (args.has("ignoreCase") && args.get("ignoreCase").getAsBoolean())
                    ? Pattern.CASE_INSENSITIVE : 0;
            p = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("正则语法错误: " + e.getMessage());
        }
        String start = args.has("path") ? args.get("path").getAsString() : ".";
        final Path root = PathsGuard.resolve(workDir, start);
        if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
        ToolResult guard = PathsGuard.errorIfOutside(workDir, skillsDir, root);
        if (guard != null) return guard;
        // 结果路径格式：工作路径内输出相对路径（模型可直接 Read）；工作路径外（技能目录）输出绝对路径
        final Path rootAbs = Paths.get(workDir).toAbsolutePath().normalize();
        final boolean rootInWork = root.toAbsolutePath().normalize().startsWith(rootAbs);
        final int max;
        try {
            max = args.has("maxResults") ? args.get("maxResults").getAsInt() : MAX_RESULTS;
        } catch (NumberFormatException e) {
            return ToolResult.error("参数 maxResults 格式错误: " + e.getMessage());
        }
        if (max < 0) return ToolResult.error("参数 maxResults 不能为负: " + max);
        final StringBuilder sb = new StringBuilder();
        final int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (count[0] >= max) return FileVisitResult.TERMINATE;
                Path rel = rootInWork ? Paths.get(workDir).relativize(file) : file;
                try {
                    java.util.List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && count[0] < max; i++) {
                        if (p.matcher(lines.get(i)).find()) {
                            sb.append(rel.toString().replace('\\', '/')).append(':')
                              .append(i + 1).append(": ").append(lines.get(i).trim()).append('\n');
                            count[0]++;
                        }
                    }
                } catch (IOException ignored) { }
                return count[0] >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (count[0] >= max) sb.append("... 结果超过 ").append(max).append(" 条，已截断\n");
        return ToolResult.success(sb.toString().trim().isEmpty()
                ? "未匹配: " + pattern : sb.toString());
    }
}
