package com.minion.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读文件。参数: path(必), offset(行偏移), limit(默认2000), lineNumbers(是否带行号),
 *  full(一次读回大字段原文：单次上限放宽到 FULL_MAX_OUTPUT_CHARS，单行上限由 MAX_LINE_CHARS 放宽到约同值，
 *  超过该值的极端单行仍截断并提示改用 Bash——不支持行内续读)。
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
    /** full 模式为尾部提示预留的字符数：正文按 FULL_MAX_OUTPUT_CHARS - 本值累积，保证
     *  「内容 + 全部尾提示（offset 续读 / 分页 / 翻页）」总长 ≤ FULL_MAX_OUTPUT_CHARS，
     *  从而逐字通过入历史闸门（ToolOutputGate 对 Read 放宽到同值）不被换成通用分页提示。
     *  尾提示最长约 93 字符（大文件行数取 10 位数字的极端口径），128 留有余量。 */
    public static final int FULL_TAIL_RESERVE = 128;

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
                + " 字符（full=true 时 " + FULL_MAX_OUTPUT_CHARS + " 字符，用于读取大字段原文：单行上限由 "
                + MAX_LINE_CHARS + " 放宽到约 " + FULL_MAX_OUTPUT_CHARS + " 字符，超过约 "
                + FULL_MAX_OUTPUT_CHARS + " 字符的单行仍会截断并提示改用 Bash；代价是单次上下文占用变大；"
                + "超限请用 offset 分页续读）";
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
                + " 字符、单行上限从 " + MAX_LINE_CHARS + " 放宽到约 " + FULL_MAX_OUTPUT_CHARS
                + " 字符（超过约 " + FULL_MAX_OUTPUT_CHARS + " 字符的单行仍会截断并提示改用 Bash，"
                + "不支持行内续读）；代价是单次上下文占用变大（10 万字符约 2.5 万 token）");
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
        // 行累积预算：full 模式预留 FULL_TAIL_RESERVE 给尾提示，保证「内容 + 全部尾提示」总长 ≤
        // FULL_MAX_OUTPUT_CHARS，从而逐字通过入历史闸门（ToolOutputGate 对 Read 放宽到同值，
        // 不再把 offset 精确续读提示换成通用分页提示）；默认模式仍是 MAX_OUTPUT_CHARS，行为逐字符不变。
        int budget = full ? maxChars - FULL_TAIL_RESERVE : MAX_OUTPUT_CHARS;

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
            String body = full ? clipLineFull(raw, budget) : clipLine(raw);
            String chunk = (lineNumbers ? (i + 1) + ": " : "") + body + '\n';
            if (sb.length() + chunk.length() > budget) {
                if (shown == 0) {
                    // 兜底（防无进展循环）：首个可见行就装不下时（预留空间被 GBK 标注/行号等开销吃掉），
                    // 强制输出裁剪后的本行（clipLineFull 自带「请用 Bash」指引），
                    // 绝不给 offset 不推进的「请用 offset=」自指提示（模型重发会得到逐字相同结果）。
                    if (full) {
                        // full：裁剪点按剩余预算反推（正文让位、「交 Bash」说明保留），并尽量保留行号前缀；
                        // 末尾换行也计入预算，故再减 1——总长仍受 maxChars 约束（尾提示另有 FULL_TAIL_RESERVE）
                        String prefix = lineNumbers ? (i + 1) + ": " : "";
                        int room = Math.max(0, budget - sb.length() - prefix.length() - 1);
                        sb.append(prefix).append(clipLineFull(raw, room)).append('\n');
                    } else {
                        sb.append(body).append('\n');   // 默认模式：单行已受 MAX_LINE_CHARS 约束，此分支实际不可达
                    }
                    shown++;
                } else {
                    charLimited = true; // 本行及之后未显示：提示 offset 续读（不丢行、可无限分页推进）
                }
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

    /** full 模式单行裁剪：返回串（含「该行超长、无法 offset 续读、请用 Bash」说明）总长 ≤ maxLen——
     *  正常循环传 budget（= FULL_MAX_OUTPUT_CHARS - FULL_TAIL_RESERVE），兜底路径传首行剩余可用空间。
     *  说明本身必须保留（它是模型改用 Bash 的唯一指引），故正文让位：正文裁到 maxLen - 说明长度。
     *  说明存在时该行无法用 offset 续读，模型应转 Bash（如 split/head -c）。 */
    private static String clipLineFull(String line, int maxLen) {
        if (line.length() <= maxLen) return line;
        String tail = "…[本行超长，共 " + line.length()
                + " 字符，超过单次上限；该行无法用 offset 续读，请用 Bash 处理（如 split/head -c）]";
        int keep = Math.max(0, maxLen - tail.length());
        if (keep > 0 && Character.isHighSurrogate(line.charAt(keep - 1))) keep--;
        return line.substring(0, keep) + tail;
    }

    /** 单行截断：超长行截断并标注原始长度；截断点落在代理对中间时回退一位（对齐 TruncatedOutput 口径） */
    private static String clipLine(String line) {
        if (line.length() <= MAX_LINE_CHARS) return line;
        int cut = MAX_LINE_CHARS;
        if (Character.isHighSurrogate(line.charAt(cut - 1))) cut--;
        return line.substring(0, cut) + "…[本行超长，共 " + line.length() + " 字符已截断]";
    }
}
