package com.minion.core.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleIoTest {

    /** 输出的 PrintStream 必须产出 UTF-8 字节，GBK 环境下尤其关键 */
    @Test
    public void utf8PrintStream_writesUtf8Bytes() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = ConsoleIo.utf8PrintStream(buf);
        ps.print("中文⏱");
        ps.flush();
        byte[] bytes = buf.toByteArray();
        // 按 UTF-8 解码必须还原原文
        assertEquals("中文⏱", new String(bytes, "UTF-8"));
        // "中" 的 UTF-8 首字节是 0xE4；若按 GBK 编码会以 0xD6 开头——证明不是平台默认编码
        assertEquals(0xE4, bytes[0] & 0xFF);
    }

    /** 输出编码必须匹配控制台代码页：65001=UTF-8、936=GBK、437=Cp437、950=Big5、932=Shift_JIS */
    @Test
    public void charsetForCodePage_mapsCommonPages() {
        assertEquals("UTF-8", ConsoleIo.charsetForCodePage(65001).name());
        assertEquals("GBK", ConsoleIo.charsetForCodePage(936).name());
        assertEquals("IBM437", ConsoleIo.charsetForCodePage(437).name());
        assertEquals("Big5", ConsoleIo.charsetForCodePage(950).name());
        assertEquals("Shift_JIS", ConsoleIo.charsetForCodePage(932).name());
    }

    /** 未知/查询失败回退 UTF-8（无控制台、管道、重定向场景保持 UTF-8 字节） */
    @Test
    public void charsetForCodePage_unknown_fallsBackToUtf8() {
        assertEquals("UTF-8", ConsoleIo.charsetForCodePage(-1).name());
        assertEquals("UTF-8", ConsoleIo.charsetForCodePage(0).name());
        assertEquals("UTF-8", ConsoleIo.charsetForCodePage(99999).name());
    }

    /** Win7 真实控制台：输出跟随现有代码页（936→GBK），查询失败回退 JVM 默认编码 */
    @Test
    public void consoleCharsetFor_win7_followsConsoleCodePage() {
        assertEquals("GBK", ConsoleIo.consoleCharsetFor(true, 936).name());
        assertEquals("UTF-8", ConsoleIo.consoleCharsetFor(true, 65001).name());
        // 查询失败：回退 JVM 默认编码（JDK8 中文系统为 GBK，与控制台 936 一致）
        assertEquals(Charset.defaultCharset(), ConsoleIo.consoleCharsetFor(true, -1));
        assertEquals(Charset.defaultCharset(), ConsoleIo.consoleCharsetFor(true, 0));
    }

    /** Win8.1+：按代码页映射，查询失败回退 UTF-8（现状不变） */
    @Test
    public void consoleCharsetFor_modern_fallsBackToUtf8() {
        assertEquals("UTF-8", ConsoleIo.consoleCharsetFor(false, 65001).name());
        assertEquals("GBK", ConsoleIo.consoleCharsetFor(false, 936).name());
        assertEquals("UTF-8", ConsoleIo.consoleCharsetFor(false, -1).name());
        assertEquals("UTF-8", ConsoleIo.consoleCharsetFor(false, 0).name());
    }

    /** Win7 识别：6.1 = Win7 / Server 2008 R2（含 SP 版本号） */
    @Test
    public void isWindows7_detectsSixPointOne() {
        assertTrue(ConsoleIo.isWindows7("6.1"));
        assertTrue(ConsoleIo.isWindows7("6.1.7600"));
        assertTrue(ConsoleIo.isWindows7("6.1.7601"));
        assertFalse(ConsoleIo.isWindows7("6.2"));
        assertFalse(ConsoleIo.isWindows7("6.3.9600"));
        assertFalse(ConsoleIo.isWindows7("10.0"));
        assertFalse(ConsoleIo.isWindows7(""));
    }
}
