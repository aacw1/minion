package com.minion.core.tools;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * 测试用假探针：按「注入的 locale + 脚本编码」决定成败，模拟不同机器的 bash 实际行为，
 * 免去真实派生 shell。ShellLocaleTest（选择逻辑）与 BashToolTest（接入链路）共用。
 */
class FakeShellProbe implements ShellLocale.ProbeRunner {

    /**
     * 能让 bash 正确工作的条件：
     * null   = 默认即 UTF-8（Win10 新 msys / 麒麟）；
     * "GBK"  = 完全不支持 UTF-8 locale，只有脚本按 ANSI(GBK) 写才对得上（MSYS 1.0 类）；
     * 其他   = 需注入该 locale（如 "C.UTF-8" / "zh_CN.UTF-8"）才切到 UTF-8（Win7 老 msys）。
     */
    private final String workingLocale;
    int calls;

    FakeShellProbe(String workingLocale) { this.workingLocale = workingLocale; }

    @Override
    public ShellLocale.ProbeResult run(String shell, Map<String, String> extraEnv, Charset scriptCharset,
                                       Path cnDir, String expectFileName) {
        calls++;
        boolean utf8Script = StandardCharsets.UTF_8.equals(scriptCharset);
        boolean works = "GBK".equals(workingLocale)
                ? !utf8Script
                : utf8Script && (workingLocale == null || workingLocale.equals(extraEnv.get("LC_ALL")));
        if (!works) {
            // 复现真实现场：ls 失败，报错回显被误解的路径字节
            return new ShellLocale.ProbeResult(2,
                    "ls: cannot access: No such file or directory".getBytes(StandardCharsets.UTF_8));
        }
        Charset out = utf8Script ? StandardCharsets.UTF_8 : scriptCharset;
        return new ShellLocale.ProbeResult(0, (expectFileName + "\n").getBytes(out));
    }
}
