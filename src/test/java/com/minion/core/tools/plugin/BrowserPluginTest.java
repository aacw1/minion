package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.Workspace;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 浏览器插件：状态文案三分支、工具产出、启用开关触发落盘、配置变更触发重建 */
public class BrowserPluginTest {

    private static class CountingSaver implements Runnable {
        int count;
        @Override public void run() { count++; }
    }

    private static BrowserPlugin plugin(BrowserConfig c, CountingSaver saver) {
        return new BrowserPlugin(c, new BrowserManager(c), saver);
    }

    @Test
    public void idAndDisplayName() {
        BrowserPlugin p = plugin(new BrowserConfig(), new CountingSaver());
        assertEquals("browser", p.id());
        assertEquals("浏览器操作", p.displayName());
        assertFalse(p.enabled());
    }

    @Test
    public void statusTextWithoutPath() {
        assertEquals("未配置浏览器路径", plugin(new BrowserConfig(), new CountingSaver()).statusText());
    }

    /** 未配置浏览器路径时不能点启用（工具页勾选框禁用）；配置后放行 */
    @Test
    public void canEnableRequiresPath() {
        BrowserConfig c = new BrowserConfig();
        assertFalse(plugin(c, new CountingSaver()).canEnable());
        c.path = "C:\\chrome.exe";
        assertTrue(plugin(c, new CountingSaver()).canEnable());
    }

    @Test
    public void statusTextShowsFileNamePortAndMode() {
        BrowserConfig c = new BrowserConfig();
        c.path = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
        c.port = 9333;
        assertEquals("chrome.exe · 端口 9333 · 有头", plugin(c, new CountingSaver()).statusText());
        c.headless = true;
        assertEquals("chrome.exe · 端口 9333 · 无头", plugin(c, new CountingSaver()).statusText());
    }

    @Test
    public void createsFourBrowserToolsInOrder() {
        BrowserConfig c = new BrowserConfig();
        c.path = "C:\\chrome.exe";
        ToolContext ctx = new ToolContext(new Workspace("."), "./skills", null, null);
        List<Tool> tools = plugin(c, new CountingSaver()).createTools(ctx);
        assertEquals(4, tools.size());
        List<String> names = new ArrayList<String>();
        for (Tool t : tools) names.add(t.name());
        assertEquals("[Browser, BrowserEval, BrowserScreenshot, BrowserDebug]", names.toString());
    }

    @Test
    public void toolsCreatedEvenWhenDisabledOrPathBlank() {
        // 工具无条件创建，启停由 ToolRegistry 的 gate 过滤（拉模式）
        ToolContext ctx = new ToolContext(new Workspace("."), "./skills", null, null);
        assertEquals(4, plugin(new BrowserConfig(), new CountingSaver()).createTools(ctx).size());
    }

    @Test
    public void setEnabledPersistsViaSaver() {
        BrowserConfig c = new BrowserConfig();
        CountingSaver saver = new CountingSaver();
        BrowserPlugin p = plugin(c, saver);
        p.setEnabled(true);
        assertTrue(c.enabled);
        assertEquals(1, saver.count);
    }

    @Test
    public void onConfigChangedRebuildsSession() {
        BrowserConfig c = new BrowserConfig();
        c.path = "C:\\a\\chrome.exe";
        CountingSaver saver = new CountingSaver();
        BrowserPlugin p = plugin(c, saver);
        assertNotNull(p.manager().session());
        c.path = "C:\\b\\chrome.exe";
        p.onConfigChanged();
        // 重建后是新对象（旧 session 已丢弃）
        assertNotNull(p.manager().session());
        assertEquals("C:\\b\\chrome.exe", p.manager().config().path);
    }

    @Test
    public void setEnabledWorksWithoutSaver() {
        BrowserPlugin p = plugin(new BrowserConfig(), null);
        p.setEnabled(true);       // 不抛 NPE
        assertTrue(p.enabled());
    }
}
