package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 正则内容搜索。参数: pattern(必), path(可选搜索起点), ignoreCase, maxResults */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;

    private final Workspace workspace;
    private final String skillsDir;
    private final ConfirmGate confirm;

    public GrepTool(Workspace workspace) { this(workspace, null, null); }

    public GrepTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GrepTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.confirm = confirm;
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
        final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
        if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, root);
        if (guard != null) {
            if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
        }
        // 结果路径格式：cwd 内输出相对路径（模型可直接 Read）；cwd 外（技能目录等）输出绝对路径
        final Path rootAbs = workspace.cwd().toAbsolutePath().normalize();
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
                Path rel = rootInWork ? workspace.cwd().relativize(file) : file;
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
