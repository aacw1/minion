package com.minion.core.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MinionStdioTransport 回归：服务器 stdout 混入非协议行（banner/空行/日志）时仍能完成握手与调用；
 * 服务器退出/stderr 场景下失败原因可诊断。
 *
 * <p>对照：库自带的 StdioTransport 在读线程遇到任何非 JSON 行时抛 RuntimeException 并杀死读线程，
 * 报「MCP process stdout closed unexpectedly」——本组用例在库实现下必然失败，是新实现的存在理由。
 */
public class MinionStdioTransportTest {

    private static List<String> cmd(String... args) {
        List<String> c = new ArrayList<String>();
        c.add(System.getProperty("java.home") + "/bin/java");
        c.add("-cp");
        c.add(System.getProperty("java.class.path"));
        c.add(NoisyMcpServer.class.getName());
        c.addAll(Arrays.asList(args));
        return c;
    }

    private static MinionStdioTransport transport(String... args) {
        return MinionStdioTransport.builder().command(cmd(args)).logEvents(false).build();
    }

    private static JsonObject args(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    @Test
    public void bannerBlankAndLogLines_stillHandshakeAndCall() throws Exception {
        AjMcpClient c = new AjMcpClient(transport("banner", "blank", "log"));
        try {
            c.connect();
            List<McpToolInfo> tools = c.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo_zh", tools.get(0).name);
            assertTrue("中文工具描述应零损耗: " + tools.get(0).description, tools.get(0).description.contains("中文回声工具"));
            assertEquals("回声: 你好，世界", c.callTool("echo_zh", args("文本", "你好，世界")));
        } finally {
            c.close();
        }
    }

    @Test
    public void cleanServer_baselineStillWorks() throws Exception {
        AjMcpClient c = new AjMcpClient(transport());
        try {
            c.connect();
            assertEquals("回声: ok", c.callTool("echo_zh", args("文本", "ok")));
        } finally {
            c.close();
        }
    }

    @Test
    public void processDiesBeforeHandshake_failureReasonCarriesStderrTail() {
        AjMcpClient c = new AjMcpClient(transport("dieNow", "stderr=3"));
        try {
            c.connect();
            fail("进程未启动应握手失败");
        } catch (McpException e) {
            assertTrue("失败原因应包含服务器 stderr 末尾: " + e.getMessage(),
                    e.getMessage().contains("noisy-stderr-line-3"));
        } finally {
            c.close();
        }
    }

    @Test
    public void processDiesRightAfterInitialize_handshakeIsNotMisjudged() throws Exception {
        AjMcpClient c = new AjMcpClient(transport("dieAfterInit"));
        try {
            c.connect();   // 响应已到即握手成功（不再因进程紧接着退出而误报「握手失败」）
            try {
                c.listTools();
                fail("进程已退出，tools/list 应报连接层异常");
            } catch (McpConnectionException expected) {
                // 期望：连接层失败（进程已退出），而不是误导性的 stdout closed 握手失败
            }
        } finally {
            c.close();
        }
    }

    @Test
    public void connect_retriesAndSucceedsOnThirdAttempt() throws Exception {
        Path counter = Files.createTempFile("mcp-flaky-", ".txt");
        Files.delete(counter);   // 计数文件从无到有：服务器前 2 次启动即退，第 3 次正常服务
        AjMcpClient c = new AjMcpClient(() -> transport("flaky=" + counter));
        try {
            c.connect();
            assertEquals("3", new String(Files.readAllBytes(counter), StandardCharsets.UTF_8).trim());
            assertEquals("回声: 好", c.callTool("echo_zh", args("文本", "好")));
        } finally {
            c.close();
            Files.deleteIfExists(counter);
        }
    }

    @Test
    public void connect_allAttemptsFail_throwsMcpException() throws Exception {
        Path counter = Files.createTempFile("mcp-flaky-", ".txt");
        Files.delete(counter);   // flaky(前 2 次退) + dieNow（第 3 次也退）→ 三次尝试全失败
        AjMcpClient c = new AjMcpClient(() -> transport("flaky=" + counter, "dieNow"));
        try {
            c.connect();
            fail("三次尝试均失败应抛 McpException");
        } catch (McpException expected) {
            assertEquals("3", new String(Files.readAllBytes(counter), StandardCharsets.UTF_8).trim());
            assertTrue(expected.getMessage().contains("MCP 握手失败"));
        } finally {
            c.close();
            Files.deleteIfExists(counter);
        }
    }
}
