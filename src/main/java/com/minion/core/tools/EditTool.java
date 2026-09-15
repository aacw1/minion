package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.confirm.ConfirmGate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 精确字符串替换。始终为高危操作（修改现有文件）。 */
public class EditTool implements Tool {

    private final Workspace workspace;
    private final String skillsDir;
    private final String tmpDir;
    private final ConfirmGate confirm;

    public EditTool(Workspace workspace) { this(workspace, null); }

    public EditTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir) {
        this(workspace, skillsDir, tmpDir, null);
    }

    public EditTool(Workspace workspace, String skillsDir, String tmpDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.tmpDir = tmpDir;
        this.confirm = confirm;
    }

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
        Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
        // T8 约定：存在性/目录检查在守卫之前；守卫的 toRealPath 对不存在的路径会误报越界
        if (!Files.exists(p)) return ToolResult.error("文件不存在: " + p);
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);
        ToolResult guard = PathsGuard.errorIfOutside(workspace, skillsDir, tmpDir, p);
        if (guard != null && (confirm == null || !confirm.checkEscapeWrite(this, args, p.toString()))) {
            return guard;
        }

        String oldString = args.get("oldString").getAsString();
        if (oldString.isEmpty()) return ToolResult.error("oldString 不能为空");
        String newString = args.get("newString").getAsString();

        // 锁覆盖"读-改-写"全周期：同一轮里的多个 tool_call 会并行执行（AgentLoop:661-665），
        // 若读取阶段不受锁保护，两次 read 都早于任一 write 时后写者会整份覆盖先写者，静默丢更新。
        // 锁必须在所有 confirm 调用之后获取 —— 越界确认会阻塞等待用户点击，持锁等待会挂死其他写者。
        ReentrantLock lock = PathLocks.forPath(p);
        try {
            if (!lock.tryLock(PathLocks.WAIT_SECONDS, TimeUnit.SECONDS)) {
                return ToolResult.error("文件正被其他操作占用，稍后重试: " + p);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("编辑被取消（等待文件锁时线程中断）: " + p);
        }
        try {
            // 编码探测：UTF-8 严格优先，GBK 文件（记事本 ANSI 保存）降级，写回须保持原编码
            TextFiles.Decoded d;
            try {
                d = TextFiles.decode(Files.readAllBytes(p));
            } catch (RuntimeException e) {
                // UTF-8 与 GBK 均不可解码（如事故残留的孤立字节 0x89）并非"不应发生"：
                // 转为明确的失败结果交给模型自救，避免 unchecked 异常穿透到 AgentLoop 报出误导性错误
                return ToolResult.error("文件编码已损坏（UTF-8 与 GBK 均无法解码），无法安全编辑；"
                        + "请先用 Read 或 Bash 确认文件状态: " + p);
            }
            String content = d.text;

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
            // 原子写（tmp + move）：读-改-写全周期在锁内，且读者不会看到半截内容
            AtomicFiles.writeBytes(p, updated.getBytes(d.charset)); // 按原编码写回，不破坏文件其余内容
            return ToolResult.success("已替换 " + (replaceAll ? count : 1) + " 处: " + p);
        } finally {
            lock.unlock();
        }
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
