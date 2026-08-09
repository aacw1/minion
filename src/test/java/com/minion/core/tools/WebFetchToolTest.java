package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class WebFetchToolTest {

    private MockWebServer server;
    private WebFetchTool tool;

    @Before
    public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        // MockWebServer 绑定 127.0.0.1：SSRF 防护默认拦截回环地址，测试用放行构造
        tool = new WebFetchTool(true);
    }

    @After
    public void teardown() throws Exception { server.shutdown(); }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    public void fetch_stripsHtml() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "<html><head><title>测试页</title></head><body><h1>标题</h1><p>正文内容</p></body></html>"));
        ToolResult r = tool.execute(args("{\"url\":\"" + server.url("/page").toString() + "\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("测试页"));
        assertTrue(r.output.contains("正文内容"));
        assertFalse(r.output.contains("<h1>"));
    }

    @Test
    public void fetch_404_returnsError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        ToolResult r = tool.execute(args("{\"url\":\"" + server.url("/nope").toString() + "\"}"));
        assertFalse(r.ok);
    }

    @Test
    public void fetch_badUrl_returnsError() throws Exception {
        ToolResult r = tool.execute(args("{\"url\":\"not-a-url\"}"));
        assertFalse(r.ok);
    }

    @Test
    public void fetch_truncatesLongContent() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("content text ");
        server.enqueue(new MockResponse().setBody(sb.toString()));
        ToolResult r = tool.execute(args("{\"url\":\"" + server.url("/big").toString() + "\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("内容过长已截断"));
        assertTrue(r.output.length() <= 20000 + 20);
    }

    @Test
    public void fetch_stripsScriptStyle() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "<html><head><style>body{color:red}</style><script>var x=1;</script></head>" +
                "<body><p>可见文本</p></body></html>"));
        ToolResult r = tool.execute(args("{\"url\":\"" + server.url("/page2").toString() + "\"}"));
        assertTrue(r.ok);
        assertFalse(r.output.contains("color:red"));
        assertFalse(r.output.contains("var x"));
        assertTrue(r.output.contains("可见文本"));
    }

    @Test
    public void fetch_missingUrl_returnsError() throws Exception {
        ToolResult r = tool.execute(args("{}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("缺少 url 参数"));
    }

    /** S1：SSRF 防护默认拦截 loopback/link-local/私有网段（含 metadata IP）与 localhost */
    @Test
    public void ssrf_blocksPrivateHosts() throws Exception {
        WebFetchTool strict = new WebFetchTool(); // 默认拦截
        assertFalse(strict.execute(args("{\"url\":\"http://127.0.0.1/x\"}")).ok);
        assertFalse(strict.execute(args("{\"url\":\"http://localhost/x\"}")).ok);
        assertFalse(strict.execute(args("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}")).ok);
        assertFalse(strict.execute(args("{\"url\":\"http://10.0.0.1/x\"}")).ok);
        assertFalse(strict.execute(args("{\"url\":\"http://172.16.1.1/x\"}")).ok);
        assertFalse(strict.execute(args("{\"url\":\"http://192.168.1.1/x\"}")).ok);
        assertTrue(strict.execute(args("{\"url\":\"http://127.0.0.1/x\"}")).output.contains("SSRF"));
        // 放行构造不拦截回环地址：连接 127.0.0.1 空端口失败是网络层错误而非 SSRF 拦截
        ToolResult allowed = new WebFetchTool(true).execute(args("{\"url\":\"http://127.0.0.1:1/x\"}"));
        assertFalse(allowed.ok);
        assertFalse(allowed.output.contains("SSRF"));
    }

    /** S1：公网 URL 不受 SSRF 拦截影响（正常路径返回抓取失败而非 SSRF 错误） */
    @Test
    public void ssrf_allowsPublicHosts() throws Exception {
        WebFetchTool strict = new WebFetchTool();
        ToolResult r = strict.execute(args("{\"url\":\"http://example.invalid/x\"}"));
        assertFalse(r.ok);
        assertFalse(r.output.contains("SSRF")); // 域名解析失败是网络层错误，不是被防护拦截
    }
}
