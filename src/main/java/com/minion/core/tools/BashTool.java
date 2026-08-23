package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 命令执行：工作路径内、默认超时 120s、输出头尾截断（头 18k + 尾 12k，超限落盘）。危险命令需确认。 */
public class BashTool implements Tool {

    public static final int DEFAULT_TIMEOUT = 120;
    private static final int HEAD_MAX = 18000;   // 内存保留头部上限
    private static final int TAIL_MAX = 12000;   // 落盘文件尾部读取上限
    private static final int TOTAL_MAX = HEAD_MAX + TAIL_MAX; // 30000 总预算

    // 纯 cd 命令识别：整命令只有 cd [dir]，不含 && / | 等复合语法
    private static final Pattern CD_PATTERN =
            Pattern.compile("^cd(?:[ \\t]+(\\S+))?[ \\t]*$");

    private final Workspace workspace;

    public BashTool(Workspace workspace) { this.workspace = workspace; }

    @Override
    public String name() { return "Bash"; }

    @Override
    public String description() { return "在工作目录执行 shell 命令（默认超时120秒，危险命令需确认）"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("执行 shell 命令",
                new String[]{"command", "timeoutSeconds"}, new String[]{"command"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) {
        return args.has("command") && DangerousCommands.isDangerous(args.get("command").getAsString());
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        String command = args.has("command") ? args.get("command").getAsString() : "";
        if (command.isEmpty()) return ToolResult.error("缺少 command 参数");
        final int timeout;
        try {
            timeout = args.has("timeoutSeconds") ? args.get("timeoutSeconds").getAsInt() : DEFAULT_TIMEOUT;
        } catch (NumberFormatException e) {
            return ToolResult.error("参数 timeoutSeconds 格式错误: " + e.getMessage());
        }
        if (timeout < 1) return ToolResult.error("timeoutSeconds 非法: " + timeout);

        // 纯 cd 命令直接改会话级 cwd（跨命令持久化）；复合命令（含 && 等）走 shell 原样执行
        String trimmed = command.trim();
        Matcher cdM = CD_PATTERN.matcher(trimmed);
        if (cdM.matches()) {
            Path target = workspace.cd(cdM.group(1));
            if (target == null) {
                return ToolResult.error("cd 失败: 目录不存在或在工作路径之外(cd 仅限工作区内),当前目录: " + workspace.cwd());
            }
            return ToolResult.success("当前目录: " + target);
        }

        // pid 探针文件：bash 启动时把自身 pid（$$）写进来，超时后据此按进程组清杀
        File pidFile = File.createTempFile("minion-pid", ".tmp");
        pidFile.deleteOnExit();
        // 命令脚本：探针 + 用户命令写入临时脚本再执行。不能用 bash -c 内嵌探针——
        // JDK8 Windows ProcessBuilder 不转义 -c 参数中的双引号，命令行会被拆碎（已实测）
        File scriptFile = File.createTempFile("minion-cmd", ".sh");
        scriptFile.deleteOnExit();
        Files.write(scriptFile.toPath(), probe(command, pidFile).getBytes(StandardCharsets.UTF_8));
        List<String> cmd = buildShellCommand(command, scriptFile);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspace.cwd().toFile());
        pb.redirectErrorStream(true);
        long start = System.currentTimeMillis();
        final Process process = pb.start();
        final StringBuilder output = new StringBuilder();
        // 落盘：先建文件拿到路径，reader 线程流式写全量；失败降级（dump == null 纯内存截断）
        final Path dump = OutputDump.write(Paths.get(workspace.workDir()), "bash", "");
        final BufferedWriter dumpWriter = dump == null ? null
                : Files.newBufferedWriter(dump, StandardCharsets.UTF_8);
        final AtomicLong totalChars = new AtomicLong();

        Thread reader = new Thread(() -> {
            try {
                // 探测输出编码：Git Bash 输出 UTF-8，cmd /c 输出 GBK，混用硬解码会乱码
                BufferedInputStream bin = new BufferedInputStream(process.getInputStream());
                Charset cs = detectCharset(bin);
                BufferedReader br = new BufferedReader(new InputStreamReader(bin, cs));
                String line;
                while ((line = br.readLine()) != null) {
                    appendTruncated(output, line);
                    appendTruncated(output, "\n");
                    if (dumpWriter != null) {
                        try {
                            dumpWriter.write(line);
                            dumpWriter.write("\n");
                        } catch (IOException ignored) { }
                    }
                    totalChars.addAndGet(line.length() + 1L);
                }
            } catch (IOException ignored) { } finally {
                // 确保 join 后落盘文件内容完整可见
                if (dumpWriter != null) {
                    try { dumpWriter.close(); } catch (IOException ignored) { }
                }
            }
        });
        reader.start();

        // 超时判定：单线程 waitFor(timeout) 权威判定，返回 false 即截止时进程仍存活，
        // 保证"命令超时"报告必然对应真实超时。旧实现用 killer 线程 isAlive() + 共享
        // boolean[]：跨线程无 happens-before，且未收割的残留进程会让 isAlive 误判
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        int exitCode;
        if (finished) {
            exitCode = process.exitValue();
        } else {
            // 进程树强杀：只杀直接子进程（destroyForcibly）会留下持有 stdout 管道的孤儿
            // 孙进程——reader 的 join 被拖满（实测 +5s），进程本身还继续跑（如 find /）
            killTree(process, pidFile);
            process.waitFor();
            exitCode = process.exitValue();
        }
        reader.join(5000);
        scriptFile.delete();
        pidFile.delete(); // 正常完成路径清理探针文件（超时路径在 killTree 内已删）
        if (!finished) {
            // 诊断字段：实际耗时 + 退出码（退出码 0 = 命令实际已自然完成，属假超时）
            long elapsedSec = (System.currentTimeMillis() - start) / 1000;
            return ToolResult.error("命令超时（" + timeout + "s），已终止: " + command
                    + "（实际耗时 " + elapsedSec + "s，退出码 " + exitCode + "）\n"
                    + finishOutput(output, totalChars, dump));
        }
        if (exitCode != 0) {
            return ToolResult.error("exit code " + exitCode + "（命令失败，输出如下）\n"
                    + finishOutput(output, totalChars, dump));
        }
        return ToolResult.success(finishOutput(output, totalChars, dump));
    }

    /** 组装返回：未超限删落盘返回全量（零磁盘痕迹）；超限保留落盘，返回 头 + 提示 + 尾 */
    private String finishOutput(StringBuilder output, AtomicLong totalChars, Path dump) {
        if (totalChars.get() <= TOTAL_MAX) {
            if (dump != null) {
                dump.toFile().delete();
                // 顺带删空的 tmp 目录，保证"不超限不落盘"零痕迹
                try { Files.deleteIfExists(dump.getParent()); } catch (IOException ignored) { }
            }
            return output.toString();
        }
        String head = output.toString();
        String tailStr = dump == null ? "" : OutputDump.tail(dump, TAIL_MAX);
        String rel = dump == null ? "" : OutputDump.workDirRelative(
                Paths.get(workspace.workDir()), dump);
        String note = "\n... 输出已截断（共 " + totalChars.get() + " 字符，完整输出已保存到 "
                + rel + "，可用 Read 查看）...\n";
        return head + note + tailStr;
    }

    private static void appendTruncated(StringBuilder sb, String s) {
        if (sb.length() >= HEAD_MAX) return;
        int room = HEAD_MAX - sb.length();
        if (s.length() > room) {
            sb.append(s, 0, room);
        } else {
            sb.append(s);
        }
    }

    /** 构造命令。Windows 优先 Git Bash，否则 cmd /c；Unix 用 setsid + /bin/sh。
     *  Git Bash / Unix 分支执行命令脚本（内含 pid 探针）：bash 把自身 pid（$$，
     *  MSYS 下即 Windows pid）写入探针文件，超时后 Java 据此按进程组整体清杀——
     *  原生 JVM spawn 的 MSYS bash 自成进程组（已实测），Unix 靠 setsid 使其成为组长。
     *  cmd /c 分支无探针，超时只能杀直接子进程（降级） */
    private static List<String> buildShellCommand(String command, File scriptFile) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String bash = findGitBash();
            if (bash != null) return Arrays.asList(bash, scriptFile.getAbsolutePath());
            return Arrays.asList("cmd", "/c", command);
        }
        // setsid 使命令成为独立进程组组长：超时 kill 才能按进程组整体清杀（kill -9 -pid），
        // 否则孙进程会成为孤儿。命令输出走管道，脱离控制终端无副作用
        return Arrays.asList("setsid", "/bin/sh", scriptFile.getAbsolutePath());
    }

    /** 命令脚本内容：bash 先写 pid 到探针文件，再执行用户命令。文件路径加引号防空格；
     *  脚本内容走文件不进 stdout，不污染命令输出 */
    private static String probe(String command, File pidFile) {
        return "echo \"$$\" > \"" + pidFile.getAbsolutePath() + "\"\n" + command + "\n";
    }

    /** 强杀整个进程树：按进程组整体杀（kill -9 -<pid>，负 pid = 进程组）。
     *  Windows 上 taskkill /T 因 MSYS fork 的 Windows 父链断裂找不到孙进程（已实测），
     *  必须用 MSYS 原生的组杀；直接 destroyForcibly 只杀直接子进程，持有 stdout 管道的
     *  孙进程会变孤儿继续运行 */
    private static void killTree(Process process, File pidFile) {
        int pid = readPid(pidFile);
        pidFile.delete();
        if (pid > 0) {
            String[] cmd = null;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                String bash = findGitBash();
                if (bash != null) cmd = new String[]{bash, "-c", "kill -9 -" + pid};
            } else {
                cmd = new String[]{"kill", "-9", "-" + pid};
            }
            if (cmd != null) {
                try {
                    Process k = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    if (!k.waitFor(5, TimeUnit.SECONDS)) k.destroyForcibly();
                } catch (Exception ignored) { }
            }
        }
        process.destroyForcibly(); // 兜底：组杀失败或 pid 不可得时至少杀直接子进程
    }

    /** 读探针文件里的 shell pid；文件缺失/内容非法返回 -1 */
    private static int readPid(File pidFile) {
        try {
            return Integer.parseInt(Files.readAllLines(pidFile.toPath()).get(0).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 已解析的 bash 路径缓存（"" 表示已解析过但未找到）：每次执行都重新解析太浪费 */
    private static volatile String cachedBash;

    private static String findGitBash() {
        if (cachedBash != null) return cachedBash.isEmpty() ? null : cachedBash;
        String found = locateGitBash();
        cachedBash = found == null ? "" : found;
        return found;
    }

    private static String locateGitBash() {
        String[] candidates = {
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe"};
        for (String c : candidates) {
            if (new File(c).exists()) return c;
        }
        // Git 装在非标准位置（如 E:\javame\Git）时沿 PATH 查找 bash.exe。
        // 从 Git Bash 启动的 Java 收到 POSIX 格式 PATH（冒号分隔、/c/ 挂载点），
        // 从 cmd 启动则收到 Windows 格式（分号分隔），两种都要兼容。
        String path = System.getenv("PATH");
        if (path != null) {
            String sep = path.indexOf(';') >= 0 ? ";" : ":";
            for (String dir : path.split(java.util.regex.Pattern.quote(sep))) {
                if (dir.trim().isEmpty()) continue;
                File bash = new File(toWindowsPath(dir.trim()), "bash.exe");
                if (bash.isFile()) return bash.getAbsolutePath();
            }
        }
        // 最后兜底：读注册表定位 Git 安装目录（双击 .bat 启动时 PATH 只有注册表项，
        // 前两步会落空）。新版 Git 写 GitForWindows\InstallPath，老版本（Inno Setup）
        // 写 Uninstall\Git_is1\InstallLocation。
        String reg = regQuery("HKLM\\SOFTWARE\\GitForWindows", "InstallPath");
        if (reg == null) reg = regQuery("HKCU\\SOFTWARE\\GitForWindows", "InstallPath");
        if (reg == null) reg = regQuery(
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Git_is1", "InstallLocation");
        if (reg == null) reg = regQuery(
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Git_is1",
                "InstallLocation");
        if (reg != null) {
            if (reg.endsWith("\\")) reg = reg.substring(0, reg.length() - 1);
            String[] sub = {reg + "\\bin\\bash.exe", reg + "\\usr\\bin\\bash.exe"};
            for (String s : sub) {
                if (new File(s).isFile()) return s;
            }
        }
        return null;
    }

    /** 读注册表字符串值（reg query 输出随控制台代码页，GBK/UTF-8 用探测解码） */
    private static String regQuery(String key, String value) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", value)
                    .redirectErrorStream(true).start();
            BufferedInputStream bin = new BufferedInputStream(p.getInputStream());
            Charset cs = detectCharset(bin); // mark/reset 探测后字节原位返回
            BufferedReader br = new BufferedReader(new InputStreamReader(bin, cs));
            String line, out = null;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.regionMatches(true, 0, value, 0, value.length())) {
                    int idx = line.lastIndexOf("REG_SZ");
                    if (idx >= 0) out = line.substring(idx + "REG_SZ".length()).trim();
                }
            }
            p.waitFor();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** MSYS 挂载路径 /e/javame/Git/usr/bin → E:/javame/Git/usr/bin；Windows 路径原样返回 */
    private static String toWindowsPath(String p) {
        if (p.length() >= 3 && p.charAt(0) == '/' && p.charAt(2) == '/') {
            return p.charAt(1) + ":" + p.substring(2).replace('/', '\\');
        }
        return p;
    }

    /** 探测输出编码：前 8KB 按 UTF-8 严格解码，非法则 Windows 回退 GBK（cmd /c 默认输出） */
    private static Charset detectCharset(BufferedInputStream in) throws IOException {
        in.mark(8192);
        byte[] probe = new byte[8192];
        int n = in.read(probe);
        in.reset();
        if (n <= 0) return StandardCharsets.UTF_8;
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // endOfInput=false：探测截断在多字节字符中间时不算错误，只看已完整读入的部分
        CoderResult r = strict.decode(ByteBuffer.wrap(probe, 0, n), CharBuffer.allocate(n), false);
        if (!r.isError()) return StandardCharsets.UTF_8;
        if (isWindows()) {
            try {
                return Charset.forName("GBK");
            } catch (Exception ex) {
                return Charset.defaultCharset();
            }
        }
        return Charset.defaultCharset();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
