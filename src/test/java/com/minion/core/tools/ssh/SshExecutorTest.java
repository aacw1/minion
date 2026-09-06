package com.minion.core.tools.ssh;

import org.junit.Test;

import static org.junit.Assert.*;

/** SshExecutor：本测试不依赖真实 sshd。用 127.0.0.1:1（必然拒绝）验证
 *  「不会死等」：connect 失败应秒回且文案可读（若实现真的去等 10s 会暴露）。 */
public class SshExecutorTest {

    private static SshConnection unreachable(String mode) {
        SshConnection c = new SshConnection();
        c.name = "bad";
        c.host = "127.0.0.1";
        c.port = 1;              // 端口 1：本机无监听，connect 立即 ECONNREFUSED
        c.user = "u";
        if ("password".equals(mode)) {
            c.password = "p";
        } else {
            c.privateKeyPath = "/nonexistent/key";
        }
        return c;
    }

    @Test
    public void connectRefusedFailsFastWithReadableMessage() throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            new SshExecutor().exec(unreachable("password"), "ls", 5, null);
            fail("应抛 SshOpException");
        } catch (SshOpException e) {
            assertFalse("文案不能是裸异常堆栈", e.getMessage().contains("com.jcraft"));
            assertTrue("应含失败原因", e.getMessage().length() > 0);
        }
        long cost = System.currentTimeMillis() - t0;
        assertTrue("拒绝连接应在 3 秒内返回（实测 5s 超时上限）", cost < 3000);
    }

    @Test
    public void testConnectionReturnsFailureNotThrow() {
        SshExecutor.TestResult r = new SshExecutor().test(unreachable("key"));
        assertFalse(r.ok);
        assertFalse(r.message.isEmpty());
    }

    // ---------- LineDecoder 离线单测（不触网）：跨块 UTF-8 续读 + 行首前缀 ----------

    private static final String P = "[stderr] ";

    private static byte[] utf8(String s) throws Exception {
        return s.getBytes("UTF-8");
    }

    /** 参考实现：整段文本在每行行首插 prefix（含空行），与 LineDecoder 跨块拼接语义应一致 */
    private static String expect(String whole, String prefix) {
        StringBuilder sb = new StringBuilder(whole.length() + 32);
        boolean atLineStart = true;
        for (int i = 0; i < whole.length(); i++) {
            char c = whole.charAt(i);
            if (atLineStart) sb.append(prefix);
            atLineStart = false;
            sb.append(c);
            if (c == '\n') atLineStart = true;
        }
        return sb.toString();
    }

    private static String feedAll(SshExecutor.LineDecoder d, byte[][] chunks) {
        StringBuilder sb = new StringBuilder();
        for (byte[] chunk : chunks) {
            sb.append(d.feed(chunk, chunk.length));
        }
        sb.append(d.eof());
        return sb.toString();
    }

    @Test
    public void decoderJoinsMultibyteCharSplitAcrossChunks() throws Exception {
        // "你a好" = E4 BD A0 | 61 | E5 A5 BD；按 1/3/1/1/1 字节切开喂入
        byte[] b = utf8("你a好");
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(null);
        String out = feedAll(d, new byte[][]{
                {b[0]}, {b[1], b[2], b[3]}, {b[4]}, {b[5]}, {b[6]}});
        assertEquals("你a好", out);
        assertFalse("跨块不得出现替换符", out.contains("\uFFFD"));
    }

    @Test
    public void decoderJoinsEmoji4ByteSplitAcrossChunks() throws Exception {
        // "😀" = F0 9F 98 80（4 字节，1 emoji = 2 个 UTF-16 char）
        byte[] b = utf8("😀x");
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(null);
        String out = feedAll(d, new byte[][]{
                {b[0], b[1]}, {b[2], b[3]}, {b[4]}});
        assertEquals("😀x", out);
        assertFalse(out.contains("\uFFFD"));
    }

    @Test
    public void decoderEofFlushesIncompleteTailAsReplacement() throws Exception {
        byte[] b = utf8("你");
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(null);
        assertEquals("", d.feed(new byte[]{b[0]}, 1));   // 只给首字节，缺后两字节
        String out = d.feed(new byte[]{b[1]}, 1) + d.eof();
        assertEquals("尾字节未补齐时 eof 收尾为替换符", "\uFFFD", out);
    }

    @Test
    public void decoderPrefixOnlyAtTrueLineStarts() throws Exception {
        String whole = "alpha\nbeta\n\nline3\n";
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(P);
        // 故意把块切在行中间/行首/空行中间：前缀不得出现在行中，也不得因块界丢失
        byte[] b = utf8(whole);
        String out = feedAll(d, new byte[][]{
                java.util.Arrays.copyOfRange(b, 0, 8),   // "alpha\nbe"
                java.util.Arrays.copyOfRange(b, 8, 13),  // "ta\n\nli"
                java.util.Arrays.copyOfRange(b, 13, b.length)}); // "ne3\n"
        assertEquals(expect(whole, P), out);
    }

    @Test
    public void decoderChunkStartingWithNewlineAfterMidLineChunk() throws Exception {
        // 前块以行中结尾、后块以 \n 开头：\n 用于收尾上一行，前缀应插到下一行内容前
        String whole = "abc\nrest";
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(P);
        byte[] b = utf8(whole);
        String out = feedAll(d, new byte[][]{
                java.util.Arrays.copyOfRange(b, 0, 3),   // "abc"
                java.util.Arrays.copyOfRange(b, 3, b.length)}); // "\nrest"
        assertEquals(expect(whole, P), out);
    }

    @Test
    public void decoderCrlfSplitAcrossChunks() throws Exception {
        // CRLF 行尾的 \r 与 \n 分处两块的边界情形
        String whole = "abc\r\nrest\n";
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(P);
        byte[] b = utf8(whole);
        String out = feedAll(d, new byte[][]{
                java.util.Arrays.copyOfRange(b, 0, 4),   // "abc\r"
                java.util.Arrays.copyOfRange(b, 4, b.length)}); // "\nrest\n"
        assertEquals(expect(whole, P), out);
    }

    @Test
    public void decoderNoPrefixPlainConcatAndNoTrailingArtifact() throws Exception {
        String whole = "x\n你\n\n好";
        SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(null);
        byte[] b = utf8(whole);
        assertEquals(whole, feedAll(d, new byte[][]{
                java.util.Arrays.copyOfRange(b, 0, 2),
                java.util.Arrays.copyOfRange(b, 2, 5),
                java.util.Arrays.copyOfRange(b, 5, b.length)}));
    }

    @Test
    public void decoderRandomSplitsMatchWholeTextReference() throws Exception {
        // 随机切分（固定种子）回归保护：任意块界下拼接结果 == 整段一次性处理
        String whole = "line0\n中文第二行\nline2 with 😀 emoji\n\nline4\r\nline5\n";
        java.util.Random rnd = new java.util.Random(42);
        for (int trial = 0; trial < 50; trial++) {
            byte[] b = utf8(whole);
            SshExecutor.LineDecoder d = new SshExecutor.LineDecoder(P);
            StringBuilder got = new StringBuilder();
            int pos = 0;
            while (pos < b.length) {
                int n = 1 + rnd.nextInt(Math.min(7, b.length - pos)); // 1..7 字节的碎块
                got.append(d.feed(java.util.Arrays.copyOfRange(b, pos, pos + n), n));
                pos += n;
            }
            got.append(d.eof());
            assertEquals("trial=" + trial, expect(whole, P), got.toString());
        }
    }
}
