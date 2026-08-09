package com.minion.cli;

import static org.junit.Assert.fail;

/**
 * 终端字体安全字符断言。
 *
 * 背景：mintty（git bash）默认字体链（Consolas + SimSun 等）缺少 ❯ ⏱ 🔧 ✗ ⚠ ⌁
 * 等 Unicode 符号（仅 Segoe UI Symbol 收录，而 mintty 不回退到它），渲染为 ?。
 * 因此 CLI 格式化输出只允许 ASCII 或已实测可渲染的字符：· × → ─ 全角标点、中文。
 */
public final class SafeGlyphs {

    private SafeGlyphs() { }

    /** 断言文本中每个字符都能在常见 Windows 终端字体（Consolas/SimSun/GBK）中渲染 */
    public static void assertSafe(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) continue;                    // ASCII
            if (c == 0xB7 || c == 0xD7) continue;      // · ×（Latin-1）
            if (c >= 0x2013 && c <= 0x2014) continue;  // – —（短/长破折号）
            if (c == 0x2192 || c == 0x2500) continue;  // → ─（箭头/制表符）
            if (c >= 0x3000 && c <= 0x303F) continue;  // 全角标点【】等
            if (c >= 0x4E00 && c <= 0x9FFF) continue;  // 中文
            fail("CLI 输出了终端字体无法渲染的字符 U+" + Integer.toHexString(c)
                    + " in: " + text);
        }
    }
}
