package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.BufferedWriter;
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

/** 正则内容搜索。参数: pattern(必), path(可选搜索起点), ignoreCase, maxResults。
 *  单行内容截断 1000 字符；显示层 250 条 + 30k 字符双上限，超限全量落盘
 *  会话临时目录 <jarDir>/.session/tmp/<sessionId>/grep-*.txt（模型可用 Read 查看）。 */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;    // 显示条数默认与钳制上限
    private static final int LINE_MAX = 1000;      // 单行内容截断（防 minified JS/单行 JSON 爆炸）
    private static final int DISPLAY_CHARS = 30000; // 显示总字符上限

    private final Workspace workspace;
    private final String skillsDir;
    /** 会话临时目录（jarDir/.session/tmp/<sessionId>；null = 落盘降级） */
    private final Path tmpDir;
    private final ConfirmGate confirm;

    public GrepTool(Workspace workspace) { this(workspace, null); }

    public GrepTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GrepTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this(workspace, skillsDir, null, confirm);
    }

    public GrepTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir == null ? null : Paths.get(tmpDir);
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
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir,
                tmpDir == null ? null : tmpDir.toString(), root);
        if (guard != null) {
            if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
        }
        // 结果路径格式：cwd 内输出相对路径（模型可直接 Read）；cwd 外（技能目录等）输出绝对路径
        final Path rootAbs = workspace.cwd().toAbsolutePath().normalize();
        final boolean rootInWork = root.toAbsolutePath().normalize().startsWith(rootAbs);
        final int max;
        try {
            // 钳制到 MAX_RESULTS：模型传超大值不放大显示量（防上下文爆炸）
            max = args.has("maxResults")
                    ? Math.min(args.get("maxResults").getAsInt(), MAX_RESULTS)
                    : MAX_RESULTS;
        } catch (NumberFormatException e) {
            return ToolResult.error("参数 maxResults 格式错误: " + e.getMessage());
        }
        if (max < 0) return ToolResult.error("参数 maxResults 不能为负: " + max);

        // 落盘：全量匹配流式写入；失败降级（dumpWriter == null 纯内存截断，不阻断执行）
        final Path dumpPath = OutputDump.write(tmpDir, "grep", "");
        final BufferedWriter dumpWriter = dumpPath == null ? null
                : Files.newBufferedWriter(dumpPath, StandardCharsets.UTF_8);
        // 跳过落盘目录：jar 目录可能嵌套/重叠工作空间（如 jar 放在项目目录运行），
        // dump 文件内容本身含匹配文本，遍历到会自噬（自身被匹配再写入自身）
        final Path dumpDirPath = tmpDir == null ? null : tmpDir.toAbsolutePath().normalize();

        final StringBuilder disp = new StringBuilder();  // 显示层（≤max 条 且 ≤30k 字符）
        final int[] dispCount = {0};   // 已入显示层条数
        final int[] totalCount = {0};  // 全量匹配条数（含落盘）
        final boolean[] dispFull = {false}; // 显示层已满：继续遍历落盘，不再 append

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                boolean isDumpDir = dumpDirPath != null
                        && dir.toAbsolutePath().normalize().equals(dumpDirPath);
                // 仅当命中落盘目录且不是搜索根时才跳过：root==tmpDir 时根自身即落盘目录，
                // 跳过会导致"未匹配"（模型 grep 自己的临时目录与"可自由读写"承诺相悖）
                if (isDumpDir && !dir.toAbsolutePath().normalize().equals(root.toAbsolutePath().normalize())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // 落盘文件本身跳过：遍历中持续写入，读它会自噬（匹配内容写入自身）
                if (dumpPath != null
                        && file.toAbsolutePath().normalize().equals(dumpPath.toAbsolutePath().normalize())) {
                    return FileVisitResult.CONTINUE;
                }
                Path rel = rootInWork ? workspace.cwd().relativize(file) : file;
                try {
                    java.util.List<String> lines = TextFiles.readAllLines(file).lines;
                    // 不提前终止：全量收集写落盘，显示层受双上限约束
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (p.matcher(line).find()) {
                            totalCount[0]++;
                            String entry = rel.toString().replace('\\', '/') + ":" + (i + 1)
                                    + ": " + truncateLine(line.trim()) + "\n";
                            if (dumpWriter != null) {
                                try { dumpWriter.write(entry); } catch (IOException ignored) { }
                            }
                            if (!dispFull[0]) {
                                // 先检查后 append：max=0 时显示 0 条（旧实现先 append 后检查会多显示 1 条）
                                if (dispCount[0] >= max || disp.length() >= DISPLAY_CHARS) {
                                    dispFull[0] = true; // 显示层满了，继续遍历但不再 append
                                } else {
                                    disp.append(entry);
                                    dispCount[0]++;
                                }
                            }
                        }
                    }
                } catch (IOException ignored) { }
                return FileVisitResult.CONTINUE;
            }
        });
        if (dumpWriter != null) {
            try { dumpWriter.close(); } catch (IOException ignored) { }
        }

        boolean truncated = totalCount[0] > max || disp.length() >= DISPLAY_CHARS;
        if (!truncated) {
            if (dumpPath != null) {
                dumpPath.toFile().delete();
                // 顺带删空的 tmp 目录，保证"不超限不落盘"零磁盘痕迹
                try { Files.deleteIfExists(dumpPath.getParent()); } catch (IOException ignored) { }
            }
            return ToolResult.success(disp.toString().trim().isEmpty()
                    ? "未匹配: " + pattern : disp.toString());
        }
        String note = dumpPath == null
                ? "\n... 共 " + totalCount[0] + " 条，落盘失败未保存完整结果，以上为仅存内容\n"
                : "\n... 共 " + totalCount[0] + " 条，完整结果已保存到 "
                        + dumpPath.toAbsolutePath()
                        + "，可用 Read 查看\n";
        return ToolResult.success(disp.toString() + note);
    }

    /** 单行截断：>1000 字符截断并加省略号（落盘与显示同格式，文件大小可控） */
    private static String truncateLine(String s) {
        if (s.length() <= LINE_MAX) return s;
        String cut = s.substring(0, LINE_MAX);
        // 截断点可能切在代理对（如 emoji）中间，丢弃尾部孤立高代理，避免输出非法字符
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }
}
