package com.minion.core.tools;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/** 入历史闸门：生产类超限落盘留档、读取类只截断不落盘（防套娃）、边界与降级路径 */
public class ToolOutputGateTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static List<Path> filesIn(Path dir) throws Exception {
        if (!Files.exists(dir)) return java.util.Collections.emptyList();
        return Files.list(dir).collect(java.util.stream.Collectors.toList());
    }

    /** 未超限：原样返回同一引用（零开销路径） */
    @Test
    public void apply_notOverLimit_returnsSameInstance() {
        String out = repeat('a', ToolOutputGate.MAX_CHARS);
        assertSame(out, ToolOutputGate.apply("Bash", out, tmp.getRoot().toPath()));
        assertSame(out, ToolOutputGate.apply("Read", out, tmp.getRoot().toPath()));
    }

    /** 空输出不受影响（空输出占位逻辑在调用方，先于本闸门） */
    @Test
    public void apply_nullOutput_returnsNull() {
        assertNull(ToolOutputGate.apply("Bash", null, tmp.getRoot().toPath()));
    }

    /** 生产类超限：头部截断 + 完整内容落盘 + 提示路径与应用方式 */
    @Test
    public void apply_producerOverLimit_truncatesDumpsAndHints() throws Exception {
        Path dir = tmp.newFolder("tmp").toPath();
        String out = repeat('a', ToolOutputGate.MAX_CHARS) + "TAIL-标记" + repeat('b', 10000);
        String gated = ToolOutputGate.apply("Bash", out, dir);
        assertTrue("头部保留", gated.startsWith(repeat('a', ToolOutputGate.MAX_CHARS)));
        assertTrue("提示已截断", gated.contains("输出过大已截断"));
        assertTrue("提示总量", gated.contains("共 " + out.length() + " 字符"));
        List<Path> files = filesIn(dir);
        assertEquals("应有且仅有一个落盘文件", 1, files.size());
        String dumped = new String(Files.readAllBytes(files.get(0)), StandardCharsets.UTF_8);
        assertEquals("落盘内容为全量原文", out, dumped);
        assertTrue("提示含落盘路径", gated.contains(files.get(0).toAbsolutePath().toString()));
        assertTrue("提示可用 Read 分页查看", gated.contains("Read 分页查看"));
    }

    /** 读取类超限：只截断 + 分页续读提示，绝不落盘（防"读→落盘→再读"套娃） */
    @Test
    public void apply_readerOverLimit_noDumpAndPagingHint() throws Exception {
        Path dir = tmp.newFolder("tmp").toPath();
        String out = repeat('x', ToolOutputGate.MAX_CHARS + 5000);
        String gated = ToolOutputGate.apply("Read", out, dir);
        assertTrue(gated.startsWith(repeat('x', ToolOutputGate.MAX_CHARS)));
        assertTrue("提示分页续读", gated.contains("offset/limit"));
        assertFalse("不得落盘", gated.contains("已落盘"));
        assertTrue("目录中不得产生文件", filesIn(dir).isEmpty());
        // Grep/Glob 同属读取类
        assertTrue(filesIn(dir).isEmpty());
        assertTrue(ToolOutputGate.apply("Grep", out, dir).contains("offset/limit"));
        assertTrue(filesIn(dir).isEmpty());
    }

    /** 落盘失败（tmpDir 为 null）：降级纯截断提示，不抛异常不阻断 */
    @Test
    public void apply_dumpFails_nullTmpDir_degrades() {
        String out = repeat('a', ToolOutputGate.MAX_CHARS + 100);
        String gated = ToolOutputGate.apply("Bash", out, null);
        assertTrue(gated.startsWith(repeat('a', ToolOutputGate.MAX_CHARS)));
        assertTrue("降级提示未落盘", gated.contains("未能落盘"));
    }

    /** 截断点落在代理对（emoji）中间：回退一位，不产生孤立高代理 */
    @Test
    public void apply_surrogatePairBoundary_trimsHighSurrogate() {
        String emoji = "\uD83D\uDE00"; // U+1F600
        String out = repeat('a', ToolOutputGate.MAX_CHARS - 1) + emoji + repeat('b', 100);
        String gated = ToolOutputGate.apply("Read", out, null);
        String head = gated.substring(0, gated.indexOf("\n\n…（"));
        assertEquals("原内容共 " + out.length() + " 字符", ToolOutputGate.MAX_CHARS - 1, head.length());
        assertFalse("不得以孤立高代理结尾", Character.isHighSurrogate(head.charAt(head.length() - 1)));
    }

    /** 工具名含非法文件名字符：落盘前缀清洗后仍可落盘 */
    @Test
    public void apply_illegalToolNameChars_sanitized() throws Exception {
        Path dir = tmp.newFolder("tmp").toPath();
        String out = repeat('z', ToolOutputGate.MAX_CHARS + 10);
        String gated = ToolOutputGate.apply("mcp:we/rd*", out, dir);
        assertEquals(1, filesIn(dir).size());
        assertTrue(gated.contains(filesIn(dir).get(0).getFileName().toString()));
    }
}
