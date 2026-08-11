package com.minion.core.tools;

import com.google.gson.JsonObject;

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

    public GlobTool(Workspace workspace) { this(workspace, null); }

    public GlobTool(Workspace workspace, String skillsDir) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() { return "按 glob 模式在工作路径内查找文件，如 **/*.java"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("按 glob 模式查找文件",
                new String[]{"pattern"}, new String[]{"pattern"});
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
        // 遍历根：cwd + 技能目录（若在 cwd 之外且存在）。
        // cwd 内的结果输出相对路径；技能目录内的结果输出绝对路径（模型可直接 Read）
        final List<Path> roots = new ArrayList<Path>();
        roots.add(workRoot);
        if (skillsDir != null && !skillsDir.isEmpty() && Files.isDirectory(Paths.get(skillsDir))) {
            Path skillsAbs = Paths.get(skillsDir).toAbsolutePath().normalize();
            if (!skillsAbs.startsWith(workRoot.toAbsolutePath().normalize())) roots.add(skillsAbs);
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
