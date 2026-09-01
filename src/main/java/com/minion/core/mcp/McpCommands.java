package com.minion.core.mcp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** stdio 命令组装：库的 StdioTransport 直接 new ProcessBuilder，Windows 下 npx 实为 npx.cmd 必须 cmd /c 包装 */
public final class McpCommands {

    /** 探测器：命令名 → 绝对路径；找不到返回 null（测试注入，生产用 PATH 扫描） */
    public interface Probe { String resolve(String name); }

    private McpCommands() { }

    /** 生产入口：按当前 OS 组装（Windows 探测 PATH，其余原样） */
    public static List<String> build(String command, List<String> args) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return build(command, args, windows, name -> findInPath(name, ".cmd"));
    }

    /** 测试入口：windows 与 probe 可注入 */
    static List<String> build(String command, List<String> args, boolean windows, Probe probe) {
        List<String> out = new ArrayList<String>();
        String head = command == null ? "" : command.trim();
        boolean needsShell = false;
        if (windows && !head.isEmpty() && head.indexOf(File.separatorChar) < 0 && head.indexOf('.') < 0) {
            String found = probe == null ? null : probe.resolve(head);
            if (found != null) head = found;
        }
        String lower = head.toLowerCase();
        if (windows && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) needsShell = true;
        if (needsShell) { out.add("cmd"); out.add("/c"); }
        out.add(head);
        if (args != null) out.addAll(args);
        return out;
    }

    /** PATH 扫描：找 name+ext（如 npx.cmd）命中返回绝对路径，否则 null */
    static String findInPath(String name, String ext) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.trim().isEmpty()) continue;
            File f = new File(dir.trim(), name + ext);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }
}
