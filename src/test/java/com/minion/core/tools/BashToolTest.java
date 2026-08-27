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
    private Path tmpDir;

    @org.junit.Before
    public void setup() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        tmpDir = tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
        bash = new BashTool(new Workspace(workDir), tmpDir);
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

    /** 退出清理根因回归：关闭窗口时工具线程被 interrupt（shutdownNow），旧实现
     *  waitFor 抛 InterruptedException 直接上抛 → killTree 不执行 → bash 子进程成孤儿
     *  继续运行（占 CPU）；reader 线程（非 daemon）阻塞读管道 → JVM 永不退出。
     *  修复后：中断同样走进程树清理，execute 快速返回并传播 InterruptedException */
    @Test
    public void execute_interrupted_killsProcessAndReturnsPromptly() throws Exception {
        final java.util.concurrent.ExecutorService ex =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            final Throwable[] thrown = new Throwable[1];
            java.util.concurrent.Future<?> f = ex.submit(new Runnable() {
                @Override public void run() {
                    try {
                        bash.execute(args("{\"command\":\"sleep 30\"}"));
                    } catch (Throwable t) {
                        thrown[0] = t; // 捕获（含 InterruptedException），finally 置完成标志
                    } finally {
                        done.set(true);
                    }
                }
            });
            Thread.sleep(800); // 等子进程启动（探针文件已写）
            f.cancel(true);    // 等价于关闭窗口时工具池 shutdownNow 的中断
            long start = System.currentTimeMillis();
            while (!done.get() && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(50);
            }
            assertTrue("中断后 execute 仍阻塞（子进程未清理，reader 被拖住）", done.get());
            assertTrue("应传播 InterruptedException，实际: " + thrown[0],
                    thrown[0] instanceof InterruptedException);
            Thread.sleep(1200); // 等 taskkill/组杀在 OS 层生效
            assertNoProcess("sleep.exe");
        } finally {
            ex.shutdownNow();
        }
    }

    /** 后台任务回归：`sleep 30 &` 时旧实现 bash 立即退出（waitFor 返回 true 走正常路径），
     *  sleep 成孤儿继续运行 30 秒（持有 stdout 管道拖住 reader，进程残留占资源）。
     *  修复：脚本加 `trap 'wait' EXIT`，bash 退出前等待所有后台任务 → 命令保持运行
     *  直至超时，超时路径组杀整体清杀，无孤儿残留 */
    @Test
    public void execute_backgroundChild_blocksUntilTimeoutAndNotLeaked() throws Exception {
        long start = System.currentTimeMillis();
        ToolResult r = bash.execute(args("{\"command\":\"sleep 30 &\",\"timeoutSeconds\":3}"));
        long elapsed = System.currentTimeMillis() - start;
        assertFalse("后台任务应让命令保持运行直至超时，实际 ok=" + r.ok + " 输出: " + r.output, r.ok);
        assertTrue("超时后应快速返回（组杀清理），实际耗时 " + elapsed + "ms", elapsed < 6000);
        assertNoProcess("sleep.exe");
    }

    /** 子 shell 后台任务回归：`(sleep 30 &)` 时 trap 'wait' EXIT 等不到孙进程（bash 立即
     *  退出走正常路径），sleep 持有 stdout 管道拖住 reader。修复：内存 pid 组杀兜底——
     *  killTree 不依赖探针文件（e2e 实测关闭时文件会被提前删除导致 readPid=-1 组杀跳过），
     *  用启动时读入的 bash pid 组杀清理孙进程，无孤儿残留 */
    @Test
    public void execute_subshellBackground_noLeak() throws Exception {
        long start = System.currentTimeMillis();
        ToolResult r = bash.execute(args("{\"command\":\"(sleep 30 &)\",\"timeoutSeconds\":3}"));
        long elapsed = System.currentTimeMillis() - start;
        // bash 立即退出 = 命令"完成"（子 shell 后台任务不被 trap 等待），但孙进程必须被兜底清杀
        assertTrue("命令应完成（bash 已退出）: " + r.output, r.ok);
        assertTrue("返回过慢（reader 被孤儿进程拖住）: " + elapsed, elapsed < 8000);
        assertNoProcess("sleep.exe");
    }

    /** Windows 上按进程名查 tasklist，断言无残留进程（子进程树已清杀） */
    private static void assertNoProcess(String imageName) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return; // 非 Windows 环境跳过（进程组杀由 kill -9 覆盖）
        Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq " + imageName)
                .redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), "GBK");
        p.waitFor();
        assertFalse("残留进程: " + imageName + "\n" + out, out.contains(imageName));
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
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
        // 上限 = 30k 预算 + 提示行（含绝对落盘路径，长度随临时目录变化）
        assertTrue(r.output.length() < 30500);
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
        BashTool tool = new BashTool(new Workspace(workDir), tmpDir);
        ToolResult r1 = tool.execute(args("{\"command\":\"cd sub\"}"));
        assertTrue(r1.output, r1.output.contains("当前目录"));
        ToolResult r2 = tool.execute(args("{\"command\":\"pwd\"}"));
        assertTrue(r2.output, r2.output.contains("sub"));
    }

    @Test
    public void cdOutsideWorkDirRejected() throws Exception {
        BashTool tool = new BashTool(new Workspace(workDir), tmpDir);
        ToolResult r = tool.execute(args("{\"command\":\"cd ..\"}"));
        assertTrue(r.output, r.output.contains("失败"));
        ToolResult r2 = tool.execute(args("{\"command\":\"pwd\"}"));
        assertFalse(r2.output.contains(".."));
    }

    @Test
    public void cdUnknownDirRejected() throws Exception {
        BashTool tool = new BashTool(new Workspace(workDir), tmpDir);
        ToolResult r = tool.execute(args("{\"command\":\"cd 不存在\"}"));
        assertTrue(r.output, r.output.contains("失败"));
    }

    @Test
    public void cdCompoundCommandNotPersisted() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        BashTool tool = new BashTool(new Workspace(workDir), tmpDir);
        // cd a && pwd 走 shell:进程内生效,不持久化
        ToolResult r1 = tool.execute(args("{\"command\":\"cd sub && pwd\"}"));
        assertTrue(r1.output, r1.output.contains("sub"));
        ToolResult r2 = tool.execute(args("{\"command\":\"pwd\"}"));
        assertFalse(r2.output, r2.output.contains("sub"));
    }

    @Test
    public void cdNoArgReturnsToWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        BashTool tool = new BashTool(new Workspace(workDir), tmpDir);
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
        assertTrue(r.output.contains("完整输出已保存到 "));
        assertTrue(r.output.contains(tmpDir.toAbsolutePath().toString()));
        assertTrue(r.output.contains("可用 Read 查看"));
        String[] lines = r.output.split("\\r?\\n");
        assertTrue("截断后行数应远小于 2000，实际 " + lines.length, lines.length < 1000);
        // 落盘文件：全量 2000 行
        Path dumpDir = tmpDir;
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
        assertFalse("不超限不落盘", Files.exists(tmpDir));
    }

    /** P0 回归：输出落在 (18k, 30k] 区间必须返回全量——旧实现内存只保留 18k 且
     *  此区间删落盘文件，18k~30k 段数据永久丢失 */
    @Test
    public void outputBetween18kAnd30k_fullReturned_noDump() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"yes 0123456789 | head -c 25000\"}"));
        assertTrue(r.ok);
        // 25000 字节 = 2272 行 + 末行 "01234567"（无换行）；reader 给末行补 \n，故内存 25001 字符
        assertEquals("应返回全量，实际 " + r.output.length(), 25001, r.output.length());
        assertTrue("18k 之后数据丢失: " + r.output.length(), r.output.length() > 18000);
        assertTrue("尾部数据丢失: " + r.output, r.output.trim().endsWith("01234567"));
        assertFalse("不应出现截断提示: " + r.output, r.output.contains("输出已截断"));
        assertFalse("18k-30k 区间不应落盘", Files.exists(tmpDir));
    }

    /** P2 降级文案：落盘失败（会话临时目录被占位成文件导致目录创建失败）时，
     *  不得提示"完整输出已保存到 <空路径>，可用 Read 查看"误导模型去读空路径 */
    @Test
    public void longOutput_dumpFailure_honestNote() throws Exception {
        Path tmpDirAsFile = tmp.getRoot().toPath().resolve("jar2").resolve(".session")
                .resolve("tmp").resolve("s1");
        Files.createDirectories(tmpDirAsFile.getParent());
        Files.write(tmpDirAsFile, "x".getBytes()); // 占位成普通文件 → 目录创建失败 → 落盘失败
        BashTool tool = new BashTool(new Workspace(workDir), tmpDirAsFile);
        ToolResult r = tool.execute(args("{\"command\":\"yes 0123456789 | head -c 40000\"}"));
        assertTrue(r.ok);
        assertTrue("缺降级说明: " + r.output, r.output.contains("落盘失败未保存完整输出"));
        assertFalse("不应提示保存到空路径: " + r.output, r.output.contains("完整输出已保存到 "));
        assertFalse("不应提示可 Read 查看: " + r.output, r.output.contains("可用 Read 查看"));
        // 降级时 head 取内存全量，仍保留 30k 预算内的内容
        assertTrue(r.output.contains("0123456789"));
    }

    /** 一行级：head 截断点（18000）切在代理对（emoji）中间时丢弃孤立高代理。
     *  行长 41（emoji+38x+\n），18000 = 439×41 + 1 → 边界恰落在 emoji 高代理上 */
    @Test
    public void longOutput_headTruncation_noLoneSurrogate() throws Exception {
        ToolResult r = bash.execute(args(
                "{\"command\":\"yes '\uD83D\uDE00xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' | head -c 60000\"}"));
        assertTrue(r.ok);
        assertTrue("应触发截断: " + r.output, r.output.contains("输出已截断"));
        String headPart = r.output.substring(0, r.output.indexOf("输出已截断"));
        // headPart = head + note 前缀 "\n... "（5 字符），去掉前缀才是 head 本体
        String head = headPart.substring(0, headPart.length() - 5);
        assertNoLoneSurrogate("head 段含孤立代理: " + r.output, head);
        // 孤立高代理被丢弃后，head 尾部应为完整行尾的换行
        assertTrue("head 尾部应为换行: " + head.substring(Math.max(0, head.length() - 8)),
                head.endsWith("\n"));
    }

    /** 遍历检查字符串无孤立代理（每个高/低代理必须成对出现） */
    private static void assertNoLoneSurrogate(String msg, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(msg + "（孤立高代理 @" + i + "）",
                        i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
            } else if (Character.isLowSurrogate(c)) {
                assertTrue(msg + "（孤立低代理 @" + i + "）",
                        i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            }
        }
    }
}
