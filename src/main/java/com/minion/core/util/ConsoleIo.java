package com.minion.core.util;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 控制台编码适配：让 minion 在所有终端下输出 UTF-8。
 *
 * 背景：Java 8 的 System.out 按 JVM 默认字符集编码（中文 Windows 为 GBK），导致：
 *   - Git Bash（mintty，UTF-8）把 GBK 字节当 UTF-8 显示 → 中文乱码
 *   - PowerShell / cmd（代码页 936）中文正常，但 GBK 编不出的符号会被替换成 ?
 *     （⏱ ❯ 🔧 等符号当时因此从 UI 中移除，改用 ASCII/可渲染字符，见测试 SafeGlyphs）
 *   - IDEA 因默认 -Dfile.encoding=UTF-8 才正常
 * 解决（两件事都做，缺一不可）：
 *   1. System.out/err 换成显式 UTF-8 的 PrintStream —— 决定字节内容；
 *   2. Windows 上执行 chcp 65001 把控制台代码页切到 UTF-8 —— 决定字节如何被解释。
 *      无条件执行：真实控制台（cmd/PowerShell）生效；mintty、SSH、管道、重定向等
 *      场景下 cmd 失败被忽略，对它们本来就按 UTF-8 读写，无需切换。
 *
 * 另外处理 jline 在非真实控制台（VS Code/Windows Terminal 的 ConPTY、SSH、mintty）
 * 下建不出真实终端、退回 dumb 终端的情况：dumb 终端的读写器按 JVM 默认字符集
 * （GBK）编码，❯ 等符号会变成 ?，中文输入也会乱。buildTerminal() 在此时直接构造
 * 显式 UTF-8 的 DumbTerminal，保证提示符与输入都正确。
 */
public class ConsoleIo {

    private static volatile boolean installed = false;
    /** 当前会话控制台编码：install() 根据实际代码页决定，buildTerminal() 复用（默认 UTF-8） */
    private static volatile Charset consoleCharset = StandardCharsets.UTF_8;

    /** 主入口最先调用一次；任何失败都静默忽略（最坏回到现状，不阻断启动） */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Charset cs = StandardCharsets.UTF_8;
        if (isWindows()) {
            if (System.console() != null) {
                // 真实控制台（cmd/PowerShell 窗口）：先尝试切 UTF-8，再按实际代码页确定输出编码，
                // 保证字节编码与控制台代码页一致——即使 chcp 未生效（子进程 chcp 改不到本控制台）也不乱码
                switchConsoleCodePage();
                int cp = queryConsoleCodePage();
                if (cp > 0) cs = charsetForCodePage(cp);
            } else {
                // ConPTY / mintty / 管道：无真实控制台，输出保持 UTF-8；chcp 尽力而为（生效则更好）
                switchConsoleCodePage();
            }
        }
        consoleCharset = cs;
        rebindStdStreams(cs);
        // jline 内部编码属性（防御）：真实终端走原生 UTF-16 不受影响，dumb 路径兜底见 buildTerminal()
        System.setProperty("jline.terminal.encoding", cs.name());
        // 静音 jline 的 JUL 警告：非真实控制台时每次启动必打，且编码还是启动前的 GBK
        Logger.getLogger("org.jline").setLevel(Level.OFF);
    }

    /**
     * 构建终端：真实控制台（Windows 原生或 Unix pty）用 jline 的 system terminal；
     * Windows 上拿不到控制台（ConPTY / SSH / 管道）时直接用 UTF-8 的 DumbTerminal，
     * 避免 jline 退回的 GBK dumb 终端把非 ASCII 符号打丢、中文输入乱码。
     */
    public static Terminal buildTerminal() {
        try {
            if (!isWindows() || System.console() != null) {
                Terminal t = TerminalBuilder.builder().system(true).build();
                if (t instanceof DumbTerminal) {
                    return dumbTerminal();
                }
                return t;
            }
        } catch (Exception ignored) { }
        return dumbTerminal();
    }

    /** DumbTerminal 的读写编码跟随控制台代码页（install 已定），保证输入输出与控制台一致 */
    private static Terminal dumbTerminal() {
        try {
            return new DumbTerminal("dumb", "dumb",
                    System.in, System.out, consoleCharset);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建终端", e);
        }
    }

    /** Windows 控制台切到 UTF-8 代码页（65001）。chcp 是 cmd 内建命令，需经 cmd 执行 */
    private static void switchConsoleCodePage() {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "chcp 65001>nul")
                    .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ignored) { }
    }

    /**
     * 查询当前控制台代码页（chcp 输出 "Active code page: 936" 或 "活动代码页: 936"，取数字）。
     * 查询失败返回 -1（无控制台 / 管道 / 重定向场景）。
     */
    static int queryConsoleCodePage() {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "chcp")
                    .redirectErrorStream(true).start();
            String out = readAscii(p.getInputStream());
            p.waitFor();
            Matcher m = Pattern.compile("\\b(\\d{3,5})\\b").matcher(out);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) { }
        return -1;
    }

    private static String readAscii(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[256];
        int n;
        while ((n = in.read(b)) != -1) buf.write(b, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    /**
     * 控制台代码页 → Java Charset：65001=UTF-8、936=GBK、437=Cp437、950=Big5、932=Shift_JIS，
     * 其余尝试 Cp&lt;n&gt;，失败回退 UTF-8。输出编码必须与控制台代码页一致才不会乱码。
     */
    static Charset charsetForCodePage(int cp) {
        if (cp == 65001) return StandardCharsets.UTF_8;
        if (cp == 936) return charsetOrFallback("GBK");
        if (cp == 437) return charsetOrFallback("Cp437");
        if (cp == 950) return charsetOrFallback("Big5");
        if (cp == 932) return charsetOrFallback("Shift_JIS");
        if (cp <= 0) return StandardCharsets.UTF_8;
        return charsetOrFallback("Cp" + cp);
    }

    private static Charset charsetOrFallback(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void rebindStdStreams(Charset cs) {
        try {
            // JDK8 无 PrintStream(OutputStream, boolean, Charset) 构造，用编码名字符串版
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, cs.name()));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, cs.name()));
        } catch (Exception ignored) { }
    }

    /** 显式 UTF-8 的 PrintStream（测试可直接喂 ByteArrayOutputStream 断言字节） */
    public static PrintStream utf8PrintStream(OutputStream out) {
        try {
            return new PrintStream(out, true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e); // JDK 必有 UTF-8
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
