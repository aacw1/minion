package com.minion.core.tools.browser;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class ChromeLauncherTest {

    private static final String JSON_LIST =
            "[{\"type\":\"page\",\"url\":\"about:blank\",\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/ABC\"},"
            + "{\"type\":\"other\",\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/other/XYZ\"}]";

    @Test
    public void pageEndpointPicksFirstPage() {
        assertEquals("ws://127.0.0.1:9222/devtools/page/ABC",
                ChromeLauncher.pageEndpoint(JSON_LIST));
    }

    @Test
    public void pageEndpointEmptyWithoutPage() {
        assertNull(ChromeLauncher.pageEndpoint("[{\"type\":\"other\"}]"));
        assertNull(ChromeLauncher.pageEndpoint("不是 json"));
    }

    @Test
    public void buildCommandHeadless() {
        ChromeLauncher launcher = new ChromeLauncher("C:\\chrome.exe", 9222,
                Paths.get("C:\\profile"), true, 10000);
        List<String> cmd = launcher.buildCommand("C:\\chrome.exe");
        assertTrue(cmd.contains("--remote-debugging-port=9222"));
        assertTrue(cmd.contains("--user-data-dir=C:\\profile"));
        assertTrue(cmd.contains("--headless=new"));
    }

    @Test
    public void buildCommandHeadedNoHeadlessFlag() {
        ChromeLauncher launcher = new ChromeLauncher("C:\\chrome.exe", 9223,
                Paths.get("C:\\profile"), false, 10000);
        List<String> cmd = launcher.buildCommand("C:\\chrome.exe");
        assertFalse(cmd.contains("--headless"));
        assertTrue(cmd.contains("--remote-debugging-port=9223"));
    }
}
