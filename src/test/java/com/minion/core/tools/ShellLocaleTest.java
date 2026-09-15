package com.minion.core.tools;

import org.junit.After;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * ShellLocale 探测选择逻辑单测：用假探针（{@link FakeShellProbe}）模拟不同机器的 bash 编码行为，
 * 验证候选顺序、判定条件（exit==0 且输出含预期中文名）与异常回退。
 * 真实 bash 的端到端验证在 BashToolTest。
 */
public class ShellLocaleTest {

    @After
    public void resetCache() { ShellLocale.resetForTest(); }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 现状正常环境（Win10 新 msys / 麒麟）：默认即 UTF-8 → 第一个候选命中，不注入任何 env */
    @Test
    public void utf8Bash_firstCandidateWins_noEnvInjected() {
        FakeShellProbe probe = new FakeShellProbe(null);
        ShellLocale sl = ShellLocale.detect("bash", probe);
        assertTrue("不应注入 locale: " + sl.extraEnv, sl.extraEnv.isEmpty());
        assertEquals(StandardCharsets.UTF_8, sl.scriptCharset);
        assertEquals("应一次命中，实际探测 " + probe.calls + " 次", 1, probe.calls);
    }

    /** Win7 老 msys（默认 ANSI/GBK，但支持 C.UTF-8）：候选1 失败 → 候选2 注入 C.UTF-8 */
    @Test
    public void gbkDefaultBash_injectsCUtf8() {
        FakeShellProbe probe = new FakeShellProbe("C.UTF-8");
        ShellLocale sl = ShellLocale.detect("bash", probe);
        assertEquals("C.UTF-8", sl.extraEnv.get("LC_ALL"));
        assertEquals("C.UTF-8", sl.extraEnv.get("LANG"));
        assertEquals(StandardCharsets.UTF_8, sl.scriptCharset);
        assertEquals(2, probe.calls);
    }

    /** 只认 xx_YY.UTF-8 的 msys：前两个候选失败 → 候选3 注入 zh_CN.UTF-8（用户 Win7 现场形态） */
    @Test
    public void onlyZhCnUtf8Supported_thirdCandidateChosen() {
        FakeShellProbe probe = new FakeShellProbe("zh_CN.UTF-8");
        ShellLocale sl = ShellLocale.detect("bash", probe);
        assertEquals("zh_CN.UTF-8", sl.extraEnv.get("LC_ALL"));
        assertEquals("zh_CN.UTF-8", sl.extraEnv.get("LANG"));
        assertEquals(StandardCharsets.UTF_8, sl.scriptCharset);
        assertEquals(3, probe.calls);
    }

    /** 完全不支持 UTF-8 locale（如 MSYS 1.0）：兜底改按 ANSI(GBK) 写脚本、不注入 env */
    @Test
    public void noUtf8LocaleSupport_fallsBackToAnsiScript() {
        assumeTrue("仅 Windows 有 GBK 兜底候选", isWindows());
        FakeShellProbe probe = new FakeShellProbe("GBK");
        ShellLocale sl = ShellLocale.detect("bash", probe);
        assertTrue("兜底不应注入 locale: " + sl.extraEnv, sl.extraEnv.isEmpty());
        assertEquals(Charset.forName("GBK"), sl.scriptCharset);
        assertEquals(4, probe.calls);
    }

    /** exit==0 不足以判定通过：输出里没有预期中文名（说明转换仍错位）→ 全候选失败 → 回退现状 */
    @Test
    public void exitZeroButOutputMismatch_allRejected_fallsBackToPlain() {
        ShellLocale.ProbeRunner probe = new ShellLocale.ProbeRunner() {
            @Override
            public ShellLocale.ProbeResult run(String shell, Map<String, String> extraEnv, Charset scriptCharset,
                                               Path cnDir, String expectFileName) {
                return new ShellLocale.ProbeResult(0, "garbage-not-the-file-name".getBytes(StandardCharsets.UTF_8));
            }
        };
        ShellLocale sl = ShellLocale.detect("bash", probe);
        assertTrue(sl.extraEnv.isEmpty());
        assertEquals(StandardCharsets.UTF_8, sl.scriptCharset);
    }

    /** 探针抛异常：不上传异常，回退候选1（等价现状，绝不劣化） */
    @Test
    public void probeThrows_fallsBackToPlain() {
        ThrowingProbe probe = new ThrowingProbe();
        ShellLocale sl = ShellLocale.detect("bash", probe);
        assertTrue(sl.extraEnv.isEmpty());
        assertEquals(StandardCharsets.UTF_8, sl.scriptCharset);
        assertTrue("异常时应尝试过候选", probe.calls > 0);
    }

    /** 进程级缓存：多会话/子代理共用一次探测结果，第二次 get 不再派生探针 */
    @Test
    public void get_cachesResult_probedOnlyOnce() {
        FakeShellProbe probe = new FakeShellProbe(null);
        ShellLocale first = ShellLocale.get("bash", probe);
        int callsAfterFirst = probe.calls;
        ShellLocale second = ShellLocale.get("bash", probe);
        assertEquals("第二次 get 不应再探测", callsAfterFirst, probe.calls);
        assertTrue(first == second);
    }

    /** cmd /c 分支（无 Git Bash）不探测：直接用 PLAIN，零派生开销 */
    @Test
    public void plainConstant_noEnvUtf8() {
        assertTrue(ShellLocale.PLAIN.extraEnv.isEmpty());
        assertEquals(StandardCharsets.UTF_8, ShellLocale.PLAIN.scriptCharset);
    }

    /** 抛异常的探针：验证探测失败绝不把异常抛给上层 */
    private static final class ThrowingProbe implements ShellLocale.ProbeRunner {
        int calls;

        @Override
        public ShellLocale.ProbeResult run(String shell, Map<String, String> extraEnv, Charset scriptCharset,
                                           Path cnDir, String expectFileName) {
            calls++;
            throw new IllegalStateException("probe boom");
        }
    }
}
