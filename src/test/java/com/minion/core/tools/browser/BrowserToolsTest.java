package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** 浏览器工具:参数校验与启动失败路径(连接成功路径留给真实 Chrome 集成测试) */
public class BrowserToolsTest {

    /** 永远启动失败的 launcher:模拟未装 Chrome */
    private static class FailingLauncher extends ChromeLauncher {
        FailingLauncher() {
            super("", 1, Paths.get("."), false, 100);
        }
        @Override
        public String pageEndpoint() throws Exception {
            throw new IOException("未找到 Chrome(测试)");
        }
    }

    /** 永远返回假调试端点的 launcher:配合 FakeCdpClient 走完连接流程,不真正拉起 Chrome */
    private static class FakeLauncher extends ChromeLauncher {
        FakeLauncher() {
            super("", 1, Paths.get("."), false, 100);
        }
        @Override
        public String pageEndpoint() {
            return "ws://127.0.0.1:1/devtools/page/1";
        }
    }

    /** 模拟 CDP 客户端:记录命令调用,不真正连接 Chrome */
    private static class FakeCdpClient extends CdpClient {
        final java.util.List<String> commands = new java.util.ArrayList<String>();
        boolean connected;
        JsonObject evalResponse = new JsonObject(); // 测试注入
        String screenshotData; // 测试注入:Page.captureScreenshot 返回的 base64 数据
        FakeCdpClient() { super(100, 100); }
        @Override
        public void connect(String wsUrl) { connected = true; }
        @Override
        public boolean isConnected() { return connected; }
        @Override
        public JsonObject command(String method, JsonObject params) {
            commands.add(method);
            if ("Runtime.evaluate".equals(method) && evalResponse != null) return evalResponse;
            if ("Page.captureScreenshot".equals(method) && screenshotData != null) {
                JsonObject r = new JsonObject();
                r.addProperty("data", screenshotData);
                return r;
            }
            return new JsonObject();
        }
    }

    private static JsonObject json(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static BrowserSession session() {
        return new BrowserSession(new FailingLauncher(), new CdpClient(100, 100));
    }

    @Test
    public void browserToolMissingAction() {
        ToolResult r = new BrowserTool(session()).execute(new JsonObject());
        assertTrue(r.output, r.output.contains("action"));
    }

    @Test
    public void browserToolOpenWithoutUrl() {
        ToolResult r = new BrowserTool(session()).execute(json("action", "open"));
        assertTrue(r.output, r.output.contains("url"));
    }

    @Test
    public void browserToolUnknownAction() {
        ToolResult r = new BrowserTool(session()).execute(json("action", "fly"));
        assertTrue(r.output, r.output.contains("未知 action"));
    }

    @Test
    public void browserToolOpenFailsWhenChromeMissing() {
        ToolResult r = new BrowserTool(session()).execute(
                json2("action", "open", "url", "https://example.com"));
        assertTrue(r.output, r.output.contains("启动失败"));
    }

    @Test
    public void browserEvalMissingExpression() {
        ToolResult r = new BrowserEvalTool(session()).execute(new JsonObject());
        assertTrue(r.output, r.output.contains("expression"));
    }

    @Test
    public void browserEvalFailsWhenChromeMissing() {
        ToolResult r = new BrowserEvalTool(session()).execute(json("expression", "1+1"));
        assertTrue(r.output, r.output.contains("启动失败"));
    }

    @Test
    public void browserScreenshotMissingPath() {
        ToolResult r = new BrowserScreenshotTool(session(), new Workspace("."), null)
                .execute(new JsonObject());
        assertTrue(r.output, r.output.contains("path"));
    }

    @Test
    public void browserScreenshotOutsideWorkDirRejected() throws Exception {
        Workspace ws = new Workspace(java.nio.file.Files.createTempDirectory("ws").toString());
        ToolResult r = new BrowserScreenshotTool(session(), ws, null)
                .execute(json("path", "C:\\Windows\\x.png"));
        assertTrue(r.output, r.output.contains("工作路径之外"));
    }

    /**
     * 回归(对应线上 bug:模型创建网页小游戏→截图保存被拒→换文案无限重试):
     * 目标文件还不存在(新文件)且在工作区内的截图必须成功保存,不得误报"工作路径之外"。
     */
    @Test
    public void browserScreenshotNewFileInsideWorkDir_saves() throws Exception {
        java.nio.file.Path wsDir = java.nio.file.Files.createTempDirectory("minion-ws");
        try {
            FakeCdpClient fake = new FakeCdpClient();
            fake.screenshotData = "aGVsbG8="; // base64("hello")
            BrowserSession session = new BrowserSession(new FakeLauncher(), fake);
            ToolResult r = new BrowserScreenshotTool(session, new Workspace(wsDir.toString()), null)
                    .execute(json("path", "shot.png"));
            assertTrue(r.output, r.ok);
            java.nio.file.Path saved = wsDir.resolve("shot.png");
            assertTrue(saved.toString(), java.nio.file.Files.exists(saved));
            assertEquals("hello", new String(java.nio.file.Files.readAllBytes(saved),
                    java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            java.nio.file.Files.deleteIfExists(wsDir.resolve("shot.png"));
        }
    }

    /** 越界截图+用户拒绝确认:必须保持拒绝(弹框决策生效) */
    @Test
    public void browserScreenshotOutside_confirmReject_rejects() throws Exception {
        Path wsDir = Files.createTempDirectory("minion-ws");
        Path outsideDir = Files.createTempDirectory("minion-outside");
        try {
            ConfirmGate gate = new ConfirmGate(config(), new FakeConfirmUi(ConfirmUi.Decision.REJECT));
            ToolResult r = new BrowserScreenshotTool(session(), new Workspace(wsDir.toString()), null, gate)
                    .execute(json("path", outsideDir.resolve("x.png").toString()));
            assertFalse(r.ok);
            assertTrue(r.output, r.output.contains("工作路径之外"));
        } finally {
            Files.deleteIfExists(outsideDir.resolve("x.png"));
            Files.deleteIfExists(outsideDir);
            Files.deleteIfExists(wsDir);
        }
    }

    /** 越界截图+用户允许确认:弹框放行后保存到工作区外(决策链:守卫→确认→执行) */
    @Test
    public void browserScreenshotOutside_confirmApprove_savesOutside() throws Exception {
        Path wsDir = Files.createTempDirectory("minion-ws");
        Path outsideDir = Files.createTempDirectory("minion-outside");
        try {
            FakeCdpClient fake = new FakeCdpClient();
            fake.screenshotData = "aGVsbG8="; // base64("hello")
            BrowserSession bs = new BrowserSession(new FakeLauncher(), fake);
            ConfirmGate gate = new ConfirmGate(config(), new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
            ToolResult r = new BrowserScreenshotTool(bs, new Workspace(wsDir.toString()), null, gate)
                    .execute(json("path", outsideDir.resolve("x.png").toString()));
            assertTrue(r.output, r.ok);
            assertTrue(Files.exists(outsideDir.resolve("x.png")));
        } finally {
            Files.deleteIfExists(outsideDir.resolve("x.png"));
            Files.deleteIfExists(outsideDir);
            Files.deleteIfExists(wsDir);
        }
    }

    /** 测试用 Config:加载临时目录(默认无 confirm.skip/allowOutside) */
    private static com.minion.core.config.Config config() throws Exception {
        return com.minion.core.config.Config.load(Files.createTempDirectory("minion-cfg"));
    }

    @Test
    public void browserDebugUnknownAction() {
        ToolResult r = new BrowserDebugTool(session()).execute(json("action", "x"));
        assertTrue(r.output, r.output.contains("未知 action"));
    }

    /** 断线重连后:Network/Runtime 域重新启用、辅助函数重新注入 */
    @Test
    public void testReconnectReenablesDomains() throws Exception {
        FakeCdpClient fake = new FakeCdpClient();
        BrowserSession session = new BrowserSession(new FakeLauncher(), fake);
        // 首次连接:域启用 + 辅助函数注入各一次
        session.evaluate("1+1");
        assertTrue(fake.connected);
        assertEquals(1, count(fake.commands, "Network.enable"));
        assertEquals(1, count(fake.commands, "Runtime.enable"));
        assertEquals(2, count(fake.commands, "Runtime.evaluate")); // 辅助函数注入 + 本次执行

        // 模拟断线(连接中断 → 重新 open 场景),重连后域与辅助函数必须重新生效
        fake.connected = false;
        session.evaluate("1+1");
        assertEquals(2, count(fake.commands, "Network.enable"));
        assertEquals(2, count(fake.commands, "Runtime.enable"));
        assertEquals(4, count(fake.commands, "Runtime.evaluate")); // 辅助函数再次注入 + 第二次执行
    }

    /** JS 异常按设计文档返回失败 ToolResult,消息含异常文本 */
    @Test
    public void testEvalJsExceptionIsError() {
        FakeCdpClient fake = new FakeCdpClient();
        fake.connected = true; // 已连接:跳过启动,直接命中 JS 异常分支
        JsonObject exceptionDetails = new JsonObject();
        exceptionDetails.addProperty("text", "ReferenceError: x is not defined");
        fake.evalResponse.add("exceptionDetails", exceptionDetails);
        fake.evalResponse.add("result", new JsonObject());
        BrowserSession session = new BrowserSession(new FailingLauncher(), fake);
        ToolResult r = new BrowserEvalTool(session).execute(json("expression", "x()"));
        assertFalse(r.ok);
        assertTrue(r.output, r.output.contains("JS 异常"));
    }

    private static int count(java.util.List<String> list, String method) {
        int n = 0;
        for (String s : list) {
            if (method.equals(s)) n++;
        }
        return n;
    }

    private static JsonObject json2(String k1, String v1, String k2, String v2) {
        JsonObject o = new JsonObject();
        o.addProperty(k1, v1);
        o.addProperty(k2, v2);
        return o;
    }
}
