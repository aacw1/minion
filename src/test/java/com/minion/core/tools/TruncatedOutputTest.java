package com.minion.core.tools;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** 截断助手：未超限零痕迹全量 / 超限头尾 + 落盘可读 / emoji 代理对边界 / 降级不炸 */
public class TruncatedOutputTest {

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("trunc-out-test");
        d.toFile().deleteOnExit();
        return d;
    }

    @Test
    public void underBudgetReturnsFullAndLeavesNoDump() throws Exception {
        Path d = dir();
        TruncatedOutput out = TruncatedOutput.open(d, "t");
        for (int i = 0; i < 100; i++) out.append("line " + i + "\n");
        out.close();
        String s = out.finish();
        assertTrue(s.contains("line 99"));
        // 0-9 行 "line X\n"=7 字符、10-99 行 8 字符 → 10*7 + 90*8 = 790
        assertEquals(10 * 7 + 90 * 8, out.totalChars());
        assertEquals("未超限不留落盘痕迹", 0, listFiles(d));
    }

    @Test
    public void overBudgetKeepsHeadTailAndDumpReadable() throws Exception {
        Path d = dir();
        TruncatedOutput out = TruncatedOutput.open(d, "t");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40000; i++) big.append('x');
        out.append(big.toString());
        out.close();
        String s = out.finish();
        int noteAt = s.indexOf("... 输出已截断");
        assertTrue("应有截断提示", noteAt > 0);
        // 头部恰为 18k（无 emoji 截断时）；截断提示文案以 "\n" 开头 → 命中位置 = HEAD_MAX + 1
        assertEquals("头部恰为 18k（无 emoji 截断时）", TruncatedOutput.HEAD_MAX + 1, noteAt);
        assertTrue(s.contains(d.toAbsolutePath().toString()));
        assertTrue("tail 以 x 结尾", s.endsWith("x"));
        assertEquals("落盘文件保留", 1, listFiles(d));
        // 落盘文件是全量
        Path dump = Files.list(d).filter(p -> p.getFileName().toString().endsWith(".txt")).findFirst().get();
        assertEquals(40000, Files.readAllBytes(dump).length);
    }

    @Test
    public void surrogatePairAtCutPointIsDropped() throws Exception {
        Path d = dir();
        TruncatedOutput out = TruncatedOutput.open(d, "t");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < TruncatedOutput.HEAD_MAX - 1; i++) b.append('a');
        b.append("🎉");   // 高代理恰好落在 18k 截断点末尾
        for (int i = 0; i < 20000; i++) b.append('b');
        out.append(b.toString());
        out.close();
        String s = out.finish();
        String head = s.substring(0, s.indexOf("... 输出已截断"));
        assertFalse("孤立的低代理不应进入返回", Character.isLowSurrogate(head.charAt(head.length() - 1)));
    }

    @Test
    public void nullTmpDirDegradesToMemoryOnly() {
        TruncatedOutput out = TruncatedOutput.open(null, "t");
        out.append(new String(new char[40000]).replace('\0', 'x'));
        out.close();
        String s = out.finish();
        assertTrue(s.contains("落盘失败未保存完整输出"));
    }

    private static long listFiles(Path d) throws Exception {
        if (!Files.exists(d)) return 0;
        return Files.list(d).count();
    }
}
