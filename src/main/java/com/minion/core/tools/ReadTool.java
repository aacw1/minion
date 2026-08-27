package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读文件。参数: path(必), offset(行偏移), limit(默认2000), lineNumbers(是否带行号) */
public class ReadTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;

    private final Workspace workspace;
    private final String skillsDir;
    private final String tmpDir;
    private final ConfirmGate confirm;

    public ReadTool(Workspace workspace) { this(workspace, null); }

    public ReadTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public ReadTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this(workspace, skillsDir, null, confirm);
    }

    public ReadTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
        this.confirm = confirm;
    }

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
        Path p = PathsGuard.resolve(workspace.cwd().toString(), path);
        if (!Files.exists(p)) {
            // 不存在且在工作区外（含技能目录）：明确提示当前工作目录，防模型编造路径误入其他项目
            if (!PathsGuard.inside(workspace.workDir(), p) && !PathsGuard.inside(skillsDir, p)
                    && !PathsGuard.inside(tmpDir, p)) {
                return ToolResult.error("文件不存在: " + p
                        + "（路径在工作目录之外，访问将被拒绝。当前工作目录: " + workspace.workDir() + "）");
            }
            return ToolResult.error("文件不存在: " + p);
        }
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, tmpDir, p);
        if (guard != null) {
            if (confirm == null || !confirm.checkReadOutside(this, args, p.toString())) return guard;
        }

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

        // UTF-8 严格解码优先；非 UTF-8 文本（Windows 记事本 ANSI 保存的 GBK）自动
        // 降级 GBK 重读并首行标注转码（"Input length = N" 即 MalformedInputException 消息）
        TextFiles.Lines r;
        try {
            r = TextFiles.readAllLines(p);
        } catch (IOException e) {
            return ToolResult.error("文件解码失败（UTF-8 与 GBK 均失败，疑似二进制或未知编码）");
        }
        List<String> lines = r.lines;
        StringBuilder sb = new StringBuilder();
        if (r.gbk) sb.append("[GBK 编码文件，已自动转码显示]\n");
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
