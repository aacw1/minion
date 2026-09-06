package com.minion.core.tools.plugin;

import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.CdpClient;
import com.minion.core.tools.browser.ChromeLauncher;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.*;

/** BrowserManager：path 空返回 null、懒建复用、ofSession 固定注入、reconfigure 换配置 */
public class BrowserManagerTest {

    @Test
    public void sessionNullWhenPathBlank() {
        BrowserConfig c = new BrowserConfig();          // path 默认 ""
        BrowserManager m = new BrowserManager(c);
        assertNull(m.session());
        c.path = "   ";
        assertNull("纯空白 path 同样视为未配置", m.session());
    }

    @Test
    public void sessionLazilyCreatedAndCached() {
        BrowserConfig c = new BrowserConfig();
        c.path = "C:\\nonexistent\\chrome.exe";         // 只建对象，不拉进程
        BrowserManager m = new BrowserManager(c);
        BrowserSession s1 = m.session();
        assertNotNull(s1);
        assertSame("同一配置下应复用同一 session（不重复建 launcher）", s1, m.session());
    }

    @Test
    public void reconfigureDropsOldSession() {
        BrowserConfig c = new BrowserConfig();
        c.path = "C:\\a\\chrome.exe";
        BrowserManager m = new BrowserManager(c);
        BrowserSession s1 = m.session();
        BrowserConfig next = c.copy();
        next.path = "C:\\b\\chrome.exe";
        next.port = 9444;
        m.reconfigure(next);
        assertEquals(9444, m.config().port);
        assertEquals("C:\\b\\chrome.exe", m.config().path);
        BrowserSession s2 = m.session();
        assertNotNull(s2);
        assertNotSame("重建后应是新的 session 对象", s1, s2);
    }

    @Test
    public void reconfigureToBlankPathDisables() {
        BrowserConfig c = new BrowserConfig();
        c.path = "C:\\a\\chrome.exe";
        BrowserManager m = new BrowserManager(c);
        assertNotNull(m.session());
        BrowserConfig next = c.copy();
        next.path = "";
        m.reconfigure(next);
        assertNull(m.session());
    }

    @Test
    public void ofSessionAlwaysReturnsInjectedInstance() {
        BrowserSession injected = new BrowserSession(
                new ChromeLauncher("", 1, Paths.get("."), false, 100), new CdpClient(100, 100));
        BrowserManager m = BrowserManager.ofSession(injected);
        assertSame(injected, m.session());
        m.reconfigure(new BrowserConfig());     // 固定注入模式：无操作
        assertSame(injected, m.session());
        m.shutdown();                           // 不应抛异常
        assertSame(injected, m.session());
    }

    @Test
    public void notReadyMessagePointsToSettingsPage() {
        assertEquals("浏览器未就绪：请在 设置 → 工具 → 浏览器操作 → 配置 中填写浏览器路径",
                BrowserManager.NOT_READY);
    }
}
