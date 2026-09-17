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

/** 路径模式匹配。参数: pattern(必, 如 **\/*.java)、path(可选搜索起点)。
 *  匹配基准多候选：相对工作空间（模型自然写法）/ 相对搜索根（path 语义）/ 绝对路径，
 *  命中任一即算——模型写绝对路径或相对工作空间都能搜到（2026-09-17 修复）。 */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;

    /** 仅 Windows 归一 pattern 中的反斜杠（Linux 文件名可含反斜杠，glob 转义语义保留） */
    private static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    private final Workspace workspace;
    private final String skillsDir;
    private final String tmpDir;
    private final ConfirmGate confirm;

    public GlobTool(Workspace workspace) { this(workspace, null); }

    public GlobTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GlobTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this(workspace, skillsDir, null, confirm);
    }

    public GlobTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
        this.confirm = confirm;
    }

    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() {
        return "按 glob 模式查找文件；pattern 支持工作空间相对路径（如 src/**/*.java）或绝对路径；"
                + "path 为搜索起点（默认工作空间）";
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("按 glob 模式查找文件",
                new String[][]{
                        {"pattern", "glob 模式；可写工作空间相对路径（如 src/**/*.java）或绝对路径"},
                        {"path", "搜索起点目录；默认工作空间；工作路径外需确认"}},
                new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String rawPattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (rawPattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        final String pattern = normalizePattern(rawPattern);
        final List<PathMatcher> matchers;
        try {
            matchers = compileMatchers(pattern);
        } catch (IllegalArgumentException e) { // 含 PatternSyntaxException（其子类）与空模式
            return ToolResult.error("glob 模式语法错误: " + e.getMessage());
        }
        final Path workRoot = workspace.cwd();
        final Path workRootAbs = workRoot.toAbsolutePath().normalize();
        final List<String> found = new ArrayList<String>();
        // 遍历根：无 path 时 = cwd + 技能目录（若在 cwd 之外且存在）；
        // 指定 path 时 = 该路径（工作区/技能目录/会话临时目录内直搜；其外经确认放行后直搜）。
        // 结果路径格式：工作区内输出相对路径；工作区外输出绝对路径（模型可直接 Read）
        final List<Path> roots = new ArrayList<Path>();
        String start = args.has("path") ? args.get("path").getAsString() : null;
        if (start != null && !start.isEmpty()) {
            final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
            if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
            ToolResult guard = PathsGuard.errorIfOutsideRead(workspace, skillsDir, tmpDir, root);
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
            final Path rootAbs = root.toAbsolutePath().normalize();
            final boolean rootIsWork = rootAbs.equals(workRootAbs);
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Path abs = file.toAbsolutePath().normalize();
                        if (matchesAny(matchers, baseCandidates(abs, rootAbs, workRootAbs, rootIsWork))) {
                            // 输出：工作区内相对 cwd（与 Read 同口径，模型可直接解析）；区外绝对路径
                            found.add(abs.startsWith(workRootAbs)
                                    ? workRootAbs.relativize(abs).toString().replace('\\', '/')
                                    : abs.toString().replace('\\', '/'));
                        }
                        return found.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE
                                : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return ToolResult.error("无法遍历路径 " + root + ": " + e.getMessage());
            }
        }
        if (found.isEmpty()) {
            StringBuilder hint = new StringBuilder();
            hint.append("未找到匹配文件: ").append(pattern).append("\n搜索根: ");
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) hint.append(", ");
                hint.append(roots.get(i));
            }
            hint.append("\npattern 支持绝对路径或相对工作空间（如 src/**/*.java），也支持相对搜索根的写法");
            return ToolResult.success(hint.toString());
        }
        StringBuilder sb = new StringBuilder();
        for (String f : found) sb.append(f).append('\n');
        if (found.size() >= MAX_RESULTS) sb.append("... 结果超过 ").append(MAX_RESULTS).append(" 条，已截断\n");
        return ToolResult.success(sb.toString());
    }

    /** pattern 归一化：Windows 下反斜杠 → '/'（glob 里 '\' 是转义符，模型习惯写路径分隔符）；
     *  去掉开头的 './'、'.\'（模型常用写法）。Linux/麒麟不做反斜杠转换（文件名可含 '\'）。 */
    static String normalizePattern(String pattern) {
        String s = WINDOWS ? pattern.replace('\\', '/') : pattern;
        while (s.startsWith("./") || s.startsWith(".\\")) s = s.substring(2);
        return s;
    }

    /** 编译匹配器：原文 + 双星斜杠可省略的零层变体，任一命中即算 */
    static List<PathMatcher> compileMatchers(String pattern) {
        List<PathMatcher> matchers = new ArrayList<PathMatcher>();
        for (String variant : zeroLevelVariants(pattern)) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + variant));
        }
        return matchers;
    }

    /** 双星斜杠零层变体：每处 "**\/" 可选择省略（2^n 组合；n>4 不展开，避免组合爆炸）。
     *  Java glob 的 "**\/" 至少跨一层目录，缺此展开会漏掉搜索根下与一级直接子文件。 */
    static List<String> zeroLevelVariants(String pattern) {
        List<Integer> marks = new ArrayList<Integer>();
        for (int i = 0; i + 3 <= pattern.length(); i++) {
            if (pattern.startsWith("**/", i)) { marks.add(i); i += 2; }
        }
        List<String> variants = new ArrayList<String>();
        if (marks.isEmpty() || marks.size() > 4) { variants.add(pattern); return variants; }
        java.util.Set<String> uniq = new java.util.LinkedHashSet<String>();
        for (int mask = 0; mask < (1 << marks.size()); mask++) {
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            for (int k = 0; k < marks.size(); k++) {
                int at = marks.get(k);
                sb.append(pattern, pos, at);
                if ((mask & (1 << k)) != 0) sb.append("**/");
                pos = at + 3;
            }
            sb.append(pattern.substring(pos));
            if (!sb.toString().isEmpty()) uniq.add(sb.toString());   // 全省略可能得到空模式，跳过
        }
        variants.addAll(uniq);
        return variants;
    }

    /** 匹配基准候选：cwd 相对（在区内时） / 搜索根相对（搜索根≠cwd 时） / 绝对路径（分隔符归一 '/'） */
    static List<Path> baseCandidates(Path abs, Path rootAbs, Path workRootAbs, boolean rootIsWork) {
        List<Path> bases = new ArrayList<Path>(3);
        if (abs.startsWith(workRootAbs)) bases.add(workRootAbs.relativize(abs));
        if (!rootIsWork && abs.startsWith(rootAbs)) bases.add(rootAbs.relativize(abs));
        bases.add(Paths.get(abs.toString().replace('\\', '/')));
        return bases;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, List<Path> bases) {
        for (PathMatcher m : matchers) {
            for (Path b : bases) {
                if (m.matches(b)) return true;
            }
        }
        return false;
    }
}
