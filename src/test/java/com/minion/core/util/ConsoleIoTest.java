package com.minion.core.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;

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
}
