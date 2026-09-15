package com.minion.core.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 编码探测（进程级缓存）：让「命令脚本编码 + 注入的 locale」与 bash 实际 charset 对上。
 *
 * <p>根因：msys/cygwin 程序把 argv 原始字节按 LC_CTYPE 的 charset 转 UTF-16 再调 Win32 API，
 * 输出时按同一 charset 转回字节。BashTool 恒以 UTF-8 写命令脚本，而 Win7 上较老的 Git Bash
 * 在未设 LANG/LC_ALL 时默认取系统 ANSI 代码页（GBK）→ 脚本里的中文路径被按 GBK 解释 → 转出的
 * UTF-16 是错字 → `ls` 报 No such file or directory，且报错回显的字节与解码假设错位 → 乱码。
 * 新 msys（Win10/11 默认 C.UTF-8）与 Linux UTF-8 环境不受影响。
 *
 * <p>不按 OS 版本特判：根因与 os.version 无因果关系（Win10 装老 Git 同样乱码，Win7 装新 Git
 * 则不会），故用一次性探测自适应。探测失败一律回退 {@link #PLAIN}（等价探测前的行为，不劣化）。
 *
 * <p>设计与实测证据见 docs/superpowers/specs/2026-09-15-shell中文乱码locale探测-design.md。
 */
public final class ShellLocale {

    /** 探针超时（秒）：探针只跑一次 `ls`，正常毫秒级；超时即判该候选失败 */
    private static final int PROBE_TIMEOUT_SEC = 10;
    /** 探针用中文目录名与中文文件名：一次覆盖 argv→UTF-16 与 UTF-16→stdout 两次编码转换 */
    private static final String CN_DIR = "中文探针";
    private static final String CN_FILE = "中文文件.txt";

    /** 现状等价：不注入 env、脚本按 UTF-8 写（候选 1，也是探测失败时的回退） */
    public static final ShellLocale PLAIN = new ShellLocale(
            Collections.<String, String>emptyMap(), StandardCharsets.UTF_8, StandardCharsets.UTF_8);

    /** 需注入子进程的 locale 变量（空 = 不注入） */
    public final Map<String, String> extraEnv;
    /** 写命令脚本用的编码 */
    public final Charset scriptCharset;
    /** 期望的 stdout 编码（仅用于探针判定；实际解码仍由 BashTool.detectCharset 兜混合输出） */
    public final Charset outputCharset;

    private ShellLocale(Map<String, String> extraEnv, Charset scriptCharset, Charset outputCharset) {
        this.extraEnv = extraEnv;
        this.scriptCharset = scriptCharset;
        this.outputCharset = outputCharset;
    }

    /** 探测结果缓存：多会话/子代理共享，仅首次 Bash 调用付一次探测成本 */
    private static volatile ShellLocale cached;

    /** 取探测结果（首次调用真实派生探针，之后命中缓存）；shell = bash/sh 可执行文件路径 */
    public static ShellLocale get(String shell) {
        return get(shell, REAL_RUNNER);
    }

    static ShellLocale get(String shell, ProbeRunner runner) {
        ShellLocale c = cached;
        if (c != null) return c;
        synchronized (ShellLocale.class) {
            if (cached == null) cached = detect(shell, runner);
            return cached;
        }
    }

    static void resetForTest() { cached = null; }

    /**
     * 按候选顺序探测，第一个通过者胜出。判定 = 探针 exit==0 且输出按候选 outputCharset 解码后
     * 含预期中文文件名（只看 exit==0 不够：路径被误解时 ls 也可能"成功"列出别的目录）。
     * 任何异常/全部失败 → {@link #PLAIN}。
     */
    static ShellLocale detect(String shell, ProbeRunner runner) {
        Path root = null;
        try {
            root = Files.createTempDirectory("minion-enc-probe");
            Path cnDir = root.resolve(CN_DIR);
            Files.createDirectories(cnDir);
            Files.write(cnDir.resolve(CN_FILE), "probe".getBytes(StandardCharsets.UTF_8));
            for (ShellLocale candidate : candidates()) {
                try {
                    ProbeResult r = runner.run(shell, candidate.extraEnv, candidate.scriptCharset, cnDir, CN_FILE);
                    if (r != null && r.exitCode == 0
                            && new String(r.output, candidate.outputCharset).contains(CN_FILE)) {
                        return candidate;
                    }
                } catch (Exception ignored) {
                    // 单个候选探测失败（派生异常/超时）→ 试下一个候选
                }
            }
        } catch (Exception ignored) {
            // 探针目录都建不出来（无写权限等）→ 回退现状
        } finally {
            deleteQuietly(root);
        }
        return PLAIN;
    }

    /** 候选表：不动环境（新 msys/Linux 默认 UTF-8）→ C.UTF-8 → zh_CN.UTF-8 → 脚本改按 ANSI 写 */
    private static List<ShellLocale> candidates() {
        List<ShellLocale> list = new ArrayList<ShellLocale>();
        list.add(PLAIN);
        list.add(utf8Locale("C.UTF-8"));
        list.add(utf8Locale("zh_CN.UTF-8"));
        Charset ansi = ansiCharset();
        // 兜底：msys 完全不支持 UTF-8 locale（如 MSYS 1.0）时，脚本按 ANSI 写即可对上，
        // 输出同为 ANSI，由 BashTool.detectCharset 解码。非 Windows 无此候选（与候选 1 重复）
        if (ansi != null) list.add(new ShellLocale(Collections.<String, String>emptyMap(), ansi, ansi));
        return list;
    }

    private static ShellLocale utf8Locale(String locale) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("LC_ALL", locale);
        env.put("LANG", locale);
        return new ShellLocale(Collections.unmodifiableMap(env), StandardCharsets.UTF_8, StandardCharsets.UTF_8);
    }

    /** Windows 的 ANSI 代码页 charset（中文系统 = GBK）；非 Windows 或不可用时返回 null */
    private static Charset ansiCharset() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return null;
        try {
            return Charset.forName("GBK");
        } catch (Exception e) {
            return null;
        }
    }

    /** 探针执行抽象（单测用假实现编排各候选成败；生产用 {@link #REAL_RUNNER}） */
    interface ProbeRunner {
        ProbeResult run(String shell, Map<String, String> extraEnv, Charset scriptCharset,
                        Path cnDir, String expectFileName);
    }

    /** 探针结果：退出码 + stdout（含 stderr，已合并）原始字节 */
    static final class ProbeResult {
        final int exitCode;
        final byte[] output;

        ProbeResult(int exitCode, byte[] output) {
            this.exitCode = exitCode;
            this.output = output == null ? new byte[0] : output;
        }
    }

    /**
     * 真实探针：按候选编码写 `ls "<中文目录>"` 脚本并直接派生 shell（不复用 BashTool.execute，
     * 否则递归触发探测，还会牵进确认闸门/截断落盘/pid 探针等无关逻辑）。
     * 先 waitFor 再读流：输出仅一行，远小于管道缓冲区，不会因缓冲写满而死锁。
     */
    private static final ProbeRunner REAL_RUNNER = new ProbeRunner() {
        @Override
        public ProbeResult run(String shell, Map<String, String> extraEnv, Charset scriptCharset,
                               Path cnDir, String expectFileName) {
            File script = null;
            try {
                script = File.createTempFile("minion-enc", ".sh");
                String path = cnDir.toString().replace('\\', '/');
                Files.write(script.toPath(), ("ls \"" + path + "\"\n").getBytes(scriptCharset));
                ProcessBuilder pb = new ProcessBuilder(shell, script.getAbsolutePath());
                pb.directory(cnDir.toFile());
                pb.redirectErrorStream(true);
                if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
                Process p = pb.start();
                if (!p.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new ProbeResult(-1, null);
                }
                return new ProbeResult(p.exitValue(), readAll(p.getInputStream()));
            } catch (Exception e) {
                return new ProbeResult(-1, null);
            } finally {
                if (script != null) script.delete();
            }
        }
    };

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private static void deleteQuietly(Path root) {
        if (root == null) return;
        try {
            File dir = root.toFile();
            File[] kids = dir.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
            dir.delete();
        } catch (Exception ignored) { }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
