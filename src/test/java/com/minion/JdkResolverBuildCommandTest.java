package com.minion;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** buildCommand：cmd start 开新控制台 / 直接派生两形态，args 透传，--relaunched 标记 */
public class JdkResolverBuildCommandTest {

    @Test
    public void newConsole_cmdStartWithTitleAndDir() {
        List<String> cmd = JdkResolver.buildCommand(
                "C:\\jdk8\\bin\\java.exe", "C:\\minion", "C:\\minion\\minion.jar", true, new String[]{"a"});
        assertEquals(11, cmd.size());   // 简报误计 12；实测 11 = cmd /c start "Minion" /D <dir> <java> -jar <jar> --relaunched <args>（与设计文档/实现一致）
        assertEquals("cmd", cmd.get(0));
        assertEquals("/c", cmd.get(1));
        assertEquals("start", cmd.get(2));
        assertEquals("\"Minion\"", cmd.get(3));      // cmd 首个引号参数 = 窗口标题
        assertEquals("/D", cmd.get(4));
        assertEquals("C:\\minion", cmd.get(5));      // 工作目录
        assertEquals("C:\\jdk8\\bin\\java.exe", cmd.get(6));
        assertEquals("-jar", cmd.get(7));
        assertEquals("C:\\minion\\minion.jar", cmd.get(8));
        assertEquals("--relaunched", cmd.get(9));
        assertEquals("a", cmd.get(10));
    }

    @Test
    public void noNewConsole_directSpawnInheritConsole() {
        List<String> cmd = JdkResolver.buildCommand(
                "C:\\jdk8\\bin\\java.exe", "C:\\minion", "C:\\minion\\minion.jar", false, new String[]{"a", "b"});
        assertEquals(6, cmd.size());
        assertEquals("C:\\jdk8\\bin\\java.exe", cmd.get(0));
        assertEquals("-jar", cmd.get(1));
        assertEquals("C:\\minion\\minion.jar", cmd.get(2));
        assertEquals("--relaunched", cmd.get(3));
        assertEquals("a", cmd.get(4));
        assertEquals("b", cmd.get(5));
    }

    @Test
    public void noOriginalArgs_endsWithRelaunchedFlag() {
        List<String> cmd = JdkResolver.buildCommand(
                "java", "dir", "jar", false, null);
        assertEquals(4, cmd.size());
        assertEquals("--relaunched", cmd.get(cmd.size() - 1));
    }
}
