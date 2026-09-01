package com.minion.core.mcp;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** stdio 命令组装：Windows 下 npx→npx.cmd 解析 + cmd /c 包装；非 Windows 原样 */
public class McpCommandsTest {

    private static List<String> args(String... a) { return new ArrayList<String>(Arrays.asList(a)); }

    @Test
    public void nonWindows_noWrap_noResolve() {
        List<String> cmd = McpCommands.build("node", args("-v"), false, null);
        assertEquals(Arrays.asList("node", "-v"), cmd);
    }

    @Test
    public void windows_bareNpx_resolvedToCmd_andWrapped() {
        // PATH 探测注入：npx → C:\nvm\npx.cmd
        List<String> cmd = McpCommands.build("npx", args("@playwright/mcp"), true,
                name -> name.equals("npx") ? "C:\\nvm\\npx.cmd" : null);
        assertEquals(Arrays.asList("cmd", "/c", "C:\\nvm\\npx.cmd", "@playwright/mcp"), cmd);
    }

    @Test
    public void windows_absoluteExe_noWrap() {
        List<String> cmd = McpCommands.build("C:\\tools\\node.exe", args("-v"), true, null);
        assertEquals(Arrays.asList("C:\\tools\\node.exe", "-v"), cmd);
    }

    @Test
    public void windows_cmdAlreadyTyped_wrapped() {
        List<String> cmd = McpCommands.build("npx.cmd", args("x"), true, null);
        assertEquals(Arrays.asList("cmd", "/c", "npx.cmd", "x"), cmd);
    }

    @Test
    public void windows_resolveFailed_fallsBackRaw() {
        List<String> cmd = McpCommands.build("npx", args("x"), true, name -> null);
        assertEquals(Arrays.asList("npx", "x"), cmd);
    }
}
