package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 写文件。覆盖已存在文件为高危操作（需确认）。 */
public class WriteTool implements Tool {

    private final Workspace workspace;
    private final String skillsDir;
    private final String tmpDir;

    public WriteTool(Workspace workspace) { this(workspace, null); }

    public WriteTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public WriteTool(Workspace workspace, String skillsDir, String tmpDir) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
    }

    @Override
    public String name() { return "Write"; }

    @Override
    public String description() { return "写入文件内容（覆盖已存在文件前会请求确认）"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("写入文件",
                new String[]{"path", "content"}, new String[]{"path", "content"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) {
        if (!args.has("path")) return false;
        Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
        return Files.exists(p);
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        if (!args.has("path") || !args.has("content")) return ToolResult.error("缺少 path/content 参数");
        Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
        // T8 约定：存在性/目录检查在守卫之前；守卫的 toRealPath 对不存在的路径会误报越界
        if (Files.exists(p) && Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = outsideGuard(p);
        if (guard != null) return guard;
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        String content = args.get("content").getAsString();
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已写入 " + p + " (" + content.length() + " 字符)");
    }

    /** 越界守卫：已存在路径交给 PathsGuard（toRealPath 防符号链接）；不存在路径向上找最深已存在祖先做真实路径校验 */
    private ToolResult outsideGuard(Path p) {
        if (Files.exists(p)) return PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, tmpDir, p);
        Path probe = p;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            // 整个祖先链都不存在（工作路径本身缺失）：无符号链接可绕过，退回规范化词法包含检查
            if (!insideLexical(workspace.workDir(), p) && !insideLexical(skillsDir, p)
                    && !insideLexical(tmpDir, p)) {
                return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
            }
            return null;
        }
        try {
            Path probeReal = probe.toRealPath();
            if (!probeReal.startsWith(Paths.get(workspace.workDir()).toRealPath())
                    && !insideReal(skillsDir, probeReal)
                    && !insideReal(tmpDir, probeReal)
                    && !insideLexical(tmpDir, p)) {   // tmpDir 尚不存在（会话首次写/目录已被清）时词法兜底：不存在路径上无符号链接可绕过
                return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
            }
        } catch (IOException e) {
            return ToolResult.error("无法解析路径: " + p);
        }
        return null;
    }

    /** 词法包含检查（dir 为 null/空时恒为 false）：不触发磁盘访问，用于祖先链全缺失的场景 */
    private static boolean insideLexical(String dir, Path p) {
        if (dir == null || dir.isEmpty()) return false;
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        return p.toAbsolutePath().normalize().startsWith(root);
    }

    /** 真实路径包含检查（dir 为 null/空或不存在时恒为 false） */
    private static boolean insideReal(String dir, Path p) {
        if (dir == null || dir.isEmpty()) return false;
        try {
            return p.startsWith(Paths.get(dir).toRealPath());
        } catch (IOException e) {
            return false;
        }
    }
}
