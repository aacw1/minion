package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 写文件。覆盖已存在文件为高危操作（需确认）。 */
public class WriteTool implements Tool {

    private final String workDir;

    public WriteTool(String workDir) { this.workDir = workDir; }

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
        Path p = PathsGuard.resolve(workDir, args.get("path").getAsString());
        return Files.exists(p);
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        if (!args.has("path") || !args.has("content")) return ToolResult.error("缺少 path/content 参数");
        Path p = PathsGuard.resolve(workDir, args.get("path").getAsString());
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
        if (Files.exists(p)) return PathsGuard.errorIfOutside(workDir, p);
        Path probe = p;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            // 整个祖先链都不存在（工作路径本身缺失）：无符号链接可绕过，退回规范化词法包含检查
            Path root = Paths.get(workDir).toAbsolutePath().normalize();
            Path target = p.toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
            }
            return null;
        }
        try {
            Path root = Paths.get(workDir).toRealPath();
            if (!probe.toRealPath().startsWith(root)) {
                return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
            }
        } catch (IOException e) {
            return ToolResult.error("无法解析路径: " + p);
        }
        return null;
    }
}
