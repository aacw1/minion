package com.minion.core.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * stdio 协议一致性回归（对照 MCP 规范与官方 SDK 行为）：
 * <ol>
 *   <li>initialize 使用最新协议版本（2025-06-18），旧版服务端由协商/降级兜底；</li>
 *   <li>消息帧以单个 LF 结束（不用平台行尾 CRLF）；</li>
 *   <li>tools/call 不携带 null 的 params._meta；</li>
 *   <li>握手后进程退出时，失败原因带 stderr 末尾与退出码。</li>
 * </ol>
 */
public class MinionStdioProtocolConformanceTest {

    private static List<String> cmd(String... args) {
        List<String> c = new ArrayList<String>();
        c.add(System.getProperty("java.home") + "/bin/java");
        c.add("-cp");
        c.add(System.getProperty("java.class.path"));
        c.add(NoisyMcpServer.class.getName());
        c.addAll(Arrays.asList(args));
        return c;
    }

    private static AjMcpClient client(final String... args) {
        return new AjMcpClient(new AjMcpClient.TransportFactory() {
            @Override public com.ajaxjs.mcp.client.transport.McpTransport create() {
                return MinionStdioTransport.builder().command(cmd(args)).build();
            }
        });
    }

    private static Path tempCapture() throws IOException {
        Path p = Files.createTempFile("mcp-capture-", ".txt");
        Files.delete(p);
        return p;
    }

    private static String read(Path p) throws IOException {
        return Files.exists(p) ? new String(Files.readAllBytes(p), StandardCharsets.UTF_8) : "";
    }

    private static JsonObject textArg(String value) {
        JsonObject o = new JsonObject();
        o.addProperty("文本", value);
        return o;
    }

    @Test
    public void initialize_usesLatestProtocolVersion() throws Exception {
        Path capture = tempCapture();
        AjMcpClient c = client("capture=" + capture.toAbsolutePath());
        try {
            c.connect();
            String frames = read(capture);
            assertTrue("initialize 应使用最新协议版本，实际帧:\n" + frames,
                    frames.contains("\"protocolVersion\":\"2025-06-18\""));
        } finally {
            c.close();
            Files.deleteIfExists(capture);
        }
    }

    @Test
    public void frames_endWithUnixLineFeedOnly() throws Exception {
        Path capture = tempCapture();
        AjMcpClient c = client("capture=" + capture.toAbsolutePath());
        try {
            c.connect();
            c.listTools();
            c.callTool("echo_zh", textArg("你好"));
            String frames = read(capture);
            assertFalse("消息帧必须以单个 LF 结束（不得用平台行尾 CRLF），实际帧:\n" + frames,
                    frames.contains("CR|"));
            assertTrue("应捕获到完整会话帧:\n" + frames, frames.contains("LF|{\"jsonrpc\""));
        } finally {
            c.close();
            Files.deleteIfExists(capture);
        }
    }

    @Test
    public void toolsCall_omitsNullMeta() throws Exception {
        Path capture = tempCapture();
        AjMcpClient c = client("capture=" + capture.toAbsolutePath());
        try {
            c.connect();
            c.callTool("echo_zh", textArg("你好"));
            String frames = read(capture);
            assertFalse("tools/call 不得携带 null 的 params._meta，实际帧:\n" + frames,
                    frames.contains("\"_meta\":null"));
        } finally {
            c.close();
            Files.deleteIfExists(capture);
        }
    }

    @Test
    public void connect_succeedsWhenServerOnlyAcceptsLatestVersion() throws Exception {
        AjMcpClient c = client("onlyVersion=2025-06-18");
        try {
            c.connect();
            assertTrue("握手后应能列出工具", c.listTools().size() >= 1);
        } finally {
            c.close();
        }
    }

    @Test
    public void connect_fallsBackWhenServerRejectsLatestVersions() throws Exception {
        AjMcpClient c = client("onlyVersion=2024-11-05");
        try {
            c.connect();   // 服务端拒绝较新版本：应降级到 2024-11-05 后成功
            assertTrue(c.listTools().size() >= 1);
        } finally {
            c.close();
        }
    }

    @Test
    public void processExitAfterHandshake_messageCarriesStderrAndExitCode() throws Exception {
        AjMcpClient c = client("dieAfterInit", "stderr=3");
        try {
            c.connect();
            c.listTools();
            fail("进程已退出，tools/list 应失败");
        } catch (McpConnectionException e) {
            String msg = e.getMessage();
            assertTrue("失败原因应带服务器 stderr 末尾，实际: " + msg, msg.contains("noisy-stderr-line-3"));
            assertTrue("失败原因应带进程退出码，实际: " + msg, msg.contains("3"));
        } finally {
            c.close();
        }
    }
}
