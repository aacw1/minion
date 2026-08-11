package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Test;

import java.io.IOException;
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

    @Test
    public void browserDebugUnknownAction() {
        ToolResult r = new BrowserDebugTool(session()).execute(json("action", "x"));
        assertTrue(r.output, r.output.contains("未知 action"));
    }

    private static JsonObject json2(String k1, String v1, String k2, String v2) {
        JsonObject o = new JsonObject();
        o.addProperty(k1, v1);
        o.addProperty(k2, v2);
        return o;
    }
}
