package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 精确字符串替换。始终为高危操作（修改现有文件）。 */
public class EditTool implements Tool {

    private final String workDir;

    public EditTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Edit"; }

    @Override
    public String description() { return "在文件中做精确字符串替换，需严格匹配原文"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("编辑文件",
                new String[]{"path", "oldString", "newString", "replaceAll"},
                new String[]{"path", "oldString", "newString"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return true; }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        if (!args.has("path") || !args.has("oldString") || !args.has("newString")) {
            return ToolResult.error("缺少 path/oldString/newString 参数");
        }
        Path p = PathsGuard.resolve(workDir, args.get("path").getAsString());
        // T8 约定：存在性/目录检查在守卫之前；守卫的 toRealPath 对不存在的路径会误报越界
        if (!Files.exists(p)) return ToolResult.error("文件不存在: " + p);
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = PathsGuard.errorIfOutside(workDir, p);
        if (guard != null) return guard;

        String oldString = args.get("oldString").getAsString();
        if (oldString.isEmpty()) return ToolResult.error("oldString 不能为空");
        String newString = args.get("newString").getAsString();
        String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);

        // 行尾归一化匹配：Read 工具按行读取并剥离行尾显示，agent 提供的 oldString 通常为 LF；
        // 而 Windows/Git 检出的文件多为 CRLF，直接精确匹配会"未找到待替换内容"。
        // 因此把文件内容与 oldString/newString 统一归一化为 \n 再匹配，
        // 写回时按原文件行尾风格恢复（LF 文件行为不变）。
        boolean crlf = content.contains("\r\n");
        String matchContent = crlf ? content.replace("\r\n", "\n") : content;
        String matchOld = oldString.replace("\r\n", "\n");
        String matchNew = newString.replace("\r\n", "\n");

        int count = countOccurrences(matchContent, matchOld);
        if (count == 0) {
            return ToolResult.error("未找到待替换内容，请先 Read 确认当前内容。oldString=" + preview(oldString));
        }
        boolean replaceAll = args.has("replaceAll") && args.get("replaceAll").getAsBoolean();
        if (count > 1 && !replaceAll) {
            return ToolResult.error("oldString 多处匹配（" + count + " 处），需 replaceAll=true 或提供更精确的 oldString");
        }
        String updated = replaceAll ? matchContent.replace(matchOld, matchNew)
                : matchContent.replaceFirst(java.util.regex.Pattern.quote(matchOld),
                        java.util.regex.Matcher.quoteReplacement(matchNew));
        if (crlf) updated = updated.replace("\n", "\r\n");
        Files.write(p, updated.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已替换 " + (replaceAll ? count : 1) + " 处: " + p);
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String preview(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }
}
