package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class BashToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BashTool bash;
    private String workDir;

    @org.junit.Before
    public void setup() {
        workDir = tmp.getRoot().getAbsolutePath();
        bash = new BashTool(new Workspace(workDir));
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    public void dangerousCommands_detected() {
        assertTrue(DangerousCommands.isDangerous("rm -rf /tmp/x"));
        assertTrue(DangerousCommands.isDangerous("RM -RF x"));
        assertTrue(DangerousCommands.isDangerous("del /s /q x"));
        assertTrue(DangerousCommands.isDangerous("taskkill /f /im java.exe"));
        assertFalse(DangerousCommands.isDangerous("ls -la"));
        assertFalse(DangerousCommands.isDangerous("git status"));
        assertFalse(DangerousCommands.isDangerous("mvn clean package")); // clean 不是危险词
    }

    /** M1：引号包装（"rm" / 'rm'）不得绕过首 token 危险判定 */
    @Test
    public void quotedCommands_stillDetected() {
        assertTrue(DangerousCommands.isDangerous("\"rm\" -rf x"));
        assertTrue(DangerousCommands.isDangerous("'rm' -rf /tmp/x"));
        assertTrue(DangerousCommands.isDangerous("\"taskkill\" /f /im java.exe"));
        assertFalse(DangerousCommands.isDangerous("\"ls\" -la")); // 非危险词不受影响
    }

    /** M1：路径前缀/扩展名包装（\rm、/usr/bin/rm、rm.exe）不得绕过危险判定 */
    @Test
    public void prefixedCommands_stillDetected() {
        assertTrue(DangerousCommands.isDangerous("/usr/bin/rm -rf /tmp/x"));
        assertTrue(DangerousCommands.isDangerous("rm.exe -rf x"));
        assertTrue(DangerousCommands.isDangerous("\\rm -rf x"));
        assertTrue(DangerousCommands.isDangerous("C:\\Windows\\System32\\rm.exe -rf x"));
        assertFalse(DangerousCommands.isDangerous("/usr/bin/git status"));
        assertFalse(DangerousCommands.isDangerous("notepad.exe"));
    }

    @Test
    public void bash_isHighRisk_matchesDetection() {
        assertTrue(bash.isHighRisk(args("{\"command\":\"rm -rf x\"}")));
        assertFalse(bash.isHighRisk(args("{\"command\":\"echo hi\"}")));
    }

    @Test
    public void execute_echo() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"echo hello-from-minion\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("hello-from-minion"));
    }

    @Test
    public void execute_exitCode() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"exit 3\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("exit code 3"));
    }

    @Test
    public void execute_timeout() throws Exception {
        long start = System.currentTimeMillis();
        ToolResult r = bash.execute(args("{\"command\":\"sleep 30\",\"timeoutSeconds\":1}"));
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(r.ok);
        assertTrue(r.output.contains("超时"));
        assertTrue(elapsed < 10000);
        // 诊断字段：实际耗时 + 退出码（区分真/假超时：退出码 0 = 命令实际已完成）
        assertTrue("缺实际耗时: " + r.output, r.output.contains("实际耗时"));
        assertTrue("缺退出码: " + r.output, r.output.contains("退出码"));
    }

    /** 超时 kill 必须清理整个进程树：只杀直接子进程会让后台孙进程存活并持有 stdout
     *  管道，把 reader 的 join(5000) 拖满、耗时虚增 5s（孤儿进程根因）。进程树被杀光后
     *  管道关闭，reader 立即结束，总耗时应远小于 4s */
    @Test
    public void execute_timeout_killsProcessTree() throws Exception {
        long start = System.currentTimeMillis();
        ToolResult r = bash.execute(args("{\"command\":\"sleep 30 & sleep 30\",\"timeoutSeconds\":1}"));
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(r.ok);
        assertTrue(r.output.contains("超时"));
        assertTrue("进程树未清理，reader 被孤儿进程拖住: elapsed=" + elapsed, elapsed < 4000);
    }

    /** 截止前自然结束的命令绝不能报超时：单线程 waitFor(timeout) 语义回归钉 */
    @Test
    public void execute_completesBeforeDeadline_noTimeout() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"sleep 1\",\"timeoutSeconds\":5}"));
        assertTrue("误报超时: " + r.output, r.ok);
        assertFalse(r.output.contains("超时"));
    }

    @Test
    public void execute_negativeTimeout_returnsError() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"echo hi\",\"timeoutSeconds\":-1}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("timeoutSeconds"));
    }

    @Test
    public void execute_truncatesLongOutput() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"yes 0123456789 | head -c 40000\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("输出已截断"));
        assertTrue(r.output.length() > 30000);
        assertTrue(r.output.length() < 30100);
    }

    @Test
    public void execute_mergesStderr() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"echo err 1>&2\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("err"));
    }

    /** UTF-8 输出（Git Bash 默认）正常解码 */
    @Test
    public void execute_utf8Output_decoded() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"echo 你好minion\"}"));
        assertTrue("命令失败: " + r.output, r.ok);
        assertTrue("乱码: " + r.output, r.output.contains("你好minion"));
    }

    /** GBK 输出探测：原始 GBK 字节（你好 的 GBK 编码）应回退 GBK 解码，不乱码 */
    @Test
    public void execute_gbkOutput_decoded() throws Exception {
        // 直接构造 JsonObject：JSON 层不支持 \x 转义，需绕过 args(json) 解析
        JsonObject o = new JsonObject();
        o.addProperty("command", "printf '\\xc4\\xe3\\xba\\xc3'");
        ToolResult r = bash.execute(o);
        assertTrue(r.ok);
        assertTrue("乱码: " + r.output, r.output.contains("你好"));
    }

    @Test
    public void cdPersistsAcrossCommands() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        BashTool tool = new BashTool(new Workspace(workDir));
        ToolResult r1 = tool.execute(args("{\"command\":\"cd sub\"}"));
        assertTrue(r1.output, r1.output.contains("当前目录"));
        ToolResult r2 = tool.execute(args("{\"command\":\"pwd\"}"));
        assertTrue(r2.output, r2.output.contains("sub"));
    }

    @Test
    public void cdOutsideWorkDirRejected() throws Exception {
        BashTool tool = new BashTool(new Workspace(workDir));
        ToolResult r = tool.execute(args("{\"command\":\"cd ..\"}"));
        assertTrue(r.output, r.output.contains("失败"));
        ToolResult r2 = tool.execute(args("{\"command\":\"pwd\"}"));
        assertFalse(r2.output.contains(".."));
    }

    @Test
    public void cdUnknownDirRejected() throws Exception {
        BashTool tool = new BashTool(new Workspace(workDir));
        ToolResult r = tool.execute(args("{\"command\":\"cd 不存在\"}"));
        assertTrue(r.output, r.output.contains("失败"));
    }

    @Test
    public void cdCompoundCommandNotPersisted() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        BashTool tool = new BashTool(new Workspace(workDir));
        // cd a && pwd 走 shell:进程内生效,不持久化
        ToolResult r1 = tool.execute(args("{\"command\":\"cd sub && pwd\"}"));
        assertTrue(r1.output, r1.output.contains("sub"));
        ToolResult r2 = tool.execute(args("{\"command\":\"pwd\"}"));
        assertFalse(r2.output, r2.output.contains("sub"));
    }

    @Test
    public void cdNoArgReturnsToWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        BashTool tool = new BashTool(new Workspace(workDir));
        tool.execute(args("{\"command\":\"cd sub\"}"));
        tool.execute(args("{\"command\":\"cd\"}"));
        ToolResult r = tool.execute(args("{\"command\":\"pwd\"}"));
        assertFalse(r.output.contains("sub"));
    }

    /** 长输出：头 18k + 尾 12k 保留，中间提示行含落盘路径；落盘文件为全量 */
    @Test
    public void longOutput_headTailPreserved_andDumpWritten() throws Exception {
        // 2000 行 × 41 字符 ≈ 82k > 30k
        ToolResult r = bash.execute(args("{\"command\":\"for i in $(seq 1 2000); do echo 0123456789012345678901234567890123456789; done\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.startsWith("0123456789012345678901234567890123456789"));
        assertTrue(r.output.trim().endsWith("0123456789012345678901234567890123456789"));
        assertTrue(r.output.contains("输出已截断"));
        assertTrue(r.output.contains("完整输出已保存到 .minion/tmp/bash-"));
        assertTrue(r.output.contains("可用 Read 查看"));
        String[] lines = r.output.split("\\r?\\n");
        assertTrue("截断后行数应远小于 2000，实际 " + lines.length, lines.length < 1000);
        // 落盘文件：全量 2000 行
        Path dumpDir = Paths.get(workDir, ".minion", "tmp");
        java.util.List<Path> files = Files.list(dumpDir).collect(java.util.stream.Collectors.toList());
        assertEquals(1, files.size());
        assertEquals(2000, Files.readAllLines(files.get(0)).size());
    }

    /** 短输出：不落盘 */
    @Test
    public void shortOutput_noDumpFile() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"echo hello\"}"));
        assertTrue(r.ok);
        assertEquals("hello", r.output.trim());
        Path dumpDir = Paths.get(workDir, ".minion", "tmp");
        assertFalse(Files.exists(dumpDir));
    }
}
