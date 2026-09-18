package com.minion.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读文件。参数: path(必), offset(行偏移), limit(默认2000), lineNumbers(是否带行号),
 *  full(一次读回大字段原文：上限放宽到 FULL_MAX_OUTPUT_CHARS 且单行不截断)。
 *  单次输出受字符上限约束（MAX_OUTPUT_CHARS；full=true 时 FULL_MAX_OUTPUT_CHARS）：超限截断并提示 offset 续读位置；
 *  单行超长（minified JSON/超长日志行）截断并标注原始长度——防单行撑爆上下文。 */
public class ReadTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;
    /** 单次读取输出字符上限（与 Bash/Grep/Db 同口径）：超限截断 + offset 续读提示（不落盘，防套娃） */
    public static final int MAX_OUTPUT_CHARS = 30000;
    /** 单行截断上限（一行可达数百 KB，防一行撑爆上下文） */
    public static final int MAX_LINE_CHARS = 2000;
    /** full=true 时的单次输出上限：几万字大字段一次读回（默认路径仍 MAX_OUTPUT_CHARS=30000） */
    public static final int FULL_MAX_OUTPUT_CHARS = 100000;

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
    public String description() {
        return "读取文件内容，支持行号、偏移与行数限制；单次输出上限 " + MAX_OUTPUT_CHARS
                + " 字符（full=true 时 " + FULL_MAX_OUTPUT_CHARS + " 字符且单行不截断，用于读取大字段原文，"
                + "代价是单次上下文占用变大；超限请用 offset 分页续读）";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = SchemaGenerator.objectSchema("读取文件内容",
                new String[][]{{"path", "文件路径"}, {"offset", "起始行（0-based，默认 0）"},
                        {"limit", "最多读取行数（默认 2000）"}, {"lineNumbers", "输出行号（默认 false）"}},
                new String[]{"path"});
        JsonObject full = new JsonObject();
        full.addProperty("type", "boolean");
        full.addProperty("description", "需要整段大字段原文时设 true：单次上限从 "
                + MAX_OUTPUT_CHARS + " 放宽到 " + FULL_MAX_OUTPUT_CHARS
                + " 字符且单行不再截断；代价是单次上下文占用变大（10 万字符约 2.5 万 token）");
        schema.getAsJsonObject("properties").add("full", full);
        return schema;
    }

    /** full 参数容错解析：布尔原值 / 字符串 "true"（大小写、首尾空白容忍），缺省或畸形一律 false */
    public static boolean fullOf(JsonObject args) {
        if (args == null || !args.has("full")) return false;
        try {
            JsonElement e = args.get("full");
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            return Boolean.parseBoolean(e.getAsString().trim());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String path = args.has("path") ? args.get("path").getAsString() : "";
        if (path.isEmpty()) return ToolResult.error("缺少 path 参数");
        Path p = PathsGuard.resolve(workspace.cwd().toString(), path);
        if (!Files.exists(p)) {
            // 不存在且不在任何放行范围（工作区/额外放行/技能目录/会话临时目录/会话存储目录/
            // 越界读开关开或本会话已放行）：明确提示当前工作目录，防模型编造路径误入其他项目。
            // 放行范围内一律直接报「文件不存在」——放行目录可能尚未创建（惰性创建）。
            boolean allowed = PathsGuard.inside(workspace.workDir(), p)
                    || PathsGuard.insideExtra(workspace, p)
                    || PathsGuard.insideReadExtra(workspace, p)
                    || PathsGuard.inside(skillsDir, p)
                    || PathsGuard.inside(tmpDir, p)
                    || (confirm != null && confirm.readOutsideAllowed());
            if (!allowed) {
                return ToolResult.error("文件不存在: " + p
                        + "（路径在工作目录之外，访问将被拒绝。当前工作目录: " + workspace.workDir() + "）");
            }
            return ToolResult.error("文件不存在: " + p);
        }
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = PathsGuard.errorIfOutsideRead(workspace, skillsDir, tmpDir, p);
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
        boolean full = fullOf(args);
        int maxChars = full ? FULL_MAX_OUTPUT_CHARS : MAX_OUTPUT_CHARS;

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
        int shown = 0;            // 实际输出的行数（受单次字符上限约束）
        boolean charLimited = false;
        for (int i = offset; i < to; i++) {
            String raw = lines.get(i);
            String body = full ? clipLineFull(raw) : clipLine(raw);
            String chunk = (lineNumbers ? (i + 1) + ": " : "") + body + '\n';
            if (sb.length() + chunk.length() > maxChars) {
                charLimited = true; // 本行及之后未显示：提示 offset 续读（不丢行、可无限分页推进）
                break;
            }
            sb.append(chunk);
            shown++;
        }
        int lastLine = offset + shown; // 已显示区间的末行（1-based 行号）
        if (charLimited) {
            sb.append("... 单次输出上限 ").append(maxChars).append(" 字符，已显示第 ")
              .append(offset + 1).append('-').append(lastLine).append(" 行（共 ")
              .append(lines.size()).append(" 行）；请用 offset=").append(lastLine)
              .append(" 继续读取\n");
        } else if (lastLine < lines.size()) {
            sb.append("... 共 ").append(lines.size()).append(" 行，已显示 ")
              .append(shown).append(" 行（可用 offset/limit 翻页）\n");
        }
        return ToolResult.success(sb.toString());
    }

    /** full 模式单行上限 = 单次输出上限：超过则截断并说明该行无法用 offset 续读（交 Bash 处理） */
    private static String clipLineFull(String line) {
        if (line.length() <= FULL_MAX_OUTPUT_CHARS) return line;
        int cut = FULL_MAX_OUTPUT_CHARS - 100;   // 预留提示文本空间
        if (Character.isHighSurrogate(line.charAt(cut - 1))) cut--;
        return line.substring(0, cut) + "…[本行超长，共 " + line.length()
                + " 字符，超过单次上限；该行无法用 offset 续读，请用 Bash 处理（如 split/head -c）]";
    }

    /** 单行截断：超长行截断并标注原始长度；截断点落在代理对中间时回退一位（对齐 TruncatedOutput 口径） */
    private static String clipLine(String line) {
        if (line.length() <= MAX_LINE_CHARS) return line;
        int cut = MAX_LINE_CHARS;
        if (Character.isHighSurrogate(line.charAt(cut - 1))) cut--;
        return line.substring(0, cut) + "…[本行超长，共 " + line.length() + " 字符已截断]";
    }
}
