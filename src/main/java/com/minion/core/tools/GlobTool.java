package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** 路径模式匹配。参数: pattern(必, 如 **\/*.java) */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;

    private final Workspace workspace;
    private final String skillsDir;
    private final ConfirmGate confirm;

    public GlobTool(Workspace workspace) { this(workspace, null, null); }

    public GlobTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GlobTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.confirm = confirm;
    }

    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() { return "按 glob 模式在工作路径内查找文件，如 **/*.java"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("按 glob 模式查找文件",
                new String[]{"pattern", "path"}, new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (pattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        final PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("glob 模式语法错误: " + e.getMessage());
        }
        final Path workRoot = workspace.cwd();
        final List<String> found = new ArrayList<String>();
        // 遍历根：无 path 时 = cwd + 技能目录（若在 cwd 之外且存在）；
        // 指定 path 时 = 该路径（工作区内直搜；工作区外经确认放行后直搜）。
        // 结果路径格式：工作区内输出相对路径；工作区外输出绝对路径（模型可直接 Read）
        final List<Path> roots = new ArrayList<Path>();
        String start = args.has("path") ? args.get("path").getAsString() : null;
        if (start != null && !start.isEmpty()) {
            final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
            if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
            ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, root);
            if (guard != null) {
                if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
            }
            roots.add(root);
        } else {
            roots.add(workRoot);
            if (skillsDir != null && !skillsDir.isEmpty() && Files.isDirectory(Paths.get(skillsDir))) {
                Path skillsAbs = Paths.get(skillsDir).toAbsolutePath().normalize();
                if (!skillsAbs.startsWith(workRoot.toAbsolutePath().normalize())) roots.add(skillsAbs);
            }
        }
        for (final Path root : roots) {
            final boolean inWork = root.toAbsolutePath().normalize()
                    .startsWith(workRoot.toAbsolutePath().normalize());
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path rel = root.relativize(file);
                        if (matcher.matches(rel)) {
                            found.add(inWork
                                    ? rel.toString().replace('\\', '/')
                                    : file.toString().replace('\\', '/'));
                        }
                        return found.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return ToolResult.error("无法遍历路径 " + root + ": " + e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String f : found) sb.append(f).append('\n');
        if (found.size() >= MAX_RESULTS) sb.append("... 结果超过 ").append(MAX_RESULTS).append(" 条，已截断\n");
        return ToolResult.success(sb.toString().trim().isEmpty()
                ? "未找到匹配文件: " + pattern : sb.toString());
    }
}
