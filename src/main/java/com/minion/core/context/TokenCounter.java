package com.minion.core.context;

import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;

/**
 * 启发式 token 估算：CJK（中日韩文字/假名/谚文/全角/中西文标点）≈ 0.7 token/字，
 * 其他 ≈ 0.25 token/字符，emoji 等补充平面符号 ≈ 1.5 token/个。
 *
 * P1-3 修正（旧实现按 char 遍历且 CJK 只覆盖 4 个区段）：
 * — 按 **code point** 遍历：代理对（emoji/CJK Ext-B）不再被当成 2 个 ASCII 字符（旧值 0.5，低估约 4 倍）；
 * — CJK 判定补齐日文假名、韩文（Jamo/兼容字母/音节）、CJK 扩展 A/B+、兼容形式、全角区；
 * — 纯 ASCII 与 BMP 汉字的权重不变（既有 token 断言口径不受影响）。
 */
public class TokenCounter {

    private static final int MSG_OVERHEAD = 4;
    /** 单个 emoji/补充平面符号的估算 token（主流分词器 1~3；取中位 1.5，旧实现仅 0.5） */
    private static final double EMOJI_TOKENS = 1.5;

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            tokens += weight(cp);
        }
        return (int) Math.ceil(tokens);
    }

    /** 单 code point 的估算权重 */
    static double weight(int cp) {
        if (isCjk(cp)) return 0.7;
        if (isEmoji(cp)) return EMOJI_TOKENS;
        return 0.25;
    }

    /** CJK 及东亚文字/标点/全角区（含补充平面 CJK 扩展） */
    static boolean isCjk(int cp) {
        return (cp >= 0x1100 && cp <= 0x11FF)     // 谚文字母（Jamo）
                || (cp >= 0x2E80 && cp <= 0x2EFF) // CJK 部首补充
                || (cp >= 0x2F00 && cp <= 0x2FDF) // 康熙部首
                || (cp >= 0x3000 && cp <= 0x303F) // CJK 标点（含【】「」等）
                || (cp >= 0x3040 && cp <= 0x30FF) // 平假名 / 片假名
                || (cp >= 0x3100 && cp <= 0x312F) // 注音符号
                || (cp >= 0x3130 && cp <= 0x318F) // 谚文兼容字母
                || (cp >= 0x31C0 && cp <= 0x31EF) // CJK 笔画
                || (cp >= 0x3200 && cp <= 0x33FF) // 带圈 CJK / 兼容
                || (cp >= 0x3400 && cp <= 0x4DBF) // CJK 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF) // CJK 基本区
                || (cp >= 0xA960 && cp <= 0xA97F) // 谚文字母扩展 A
                || (cp >= 0xAC00 && cp <= 0xD7AF) // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK 兼容表意
                || (cp >= 0xFE10 && cp <= 0xFE1F) // 竖排形式
                || (cp >= 0xFE30 && cp <= 0xFE4F) // CJK 兼容形式
                || (cp >= 0xFF00 && cp <= 0xFFEF) // 半角/全角形式
                || (cp >= 0x20000 && cp <= 0x3FFFF); // CJK 扩展 B 及以后（补充平面）
    }

    /** emoji / 区域指示符 / 补充平面符号（BMP 内的箭头、几何图形等仍按 0.25 计，避免过度高估） */
    static boolean isEmoji(int cp) {
        return cp >= 0x1F000 && cp <= 0x1FAFF;
    }

    /** 单条消息的估算（正文 + 图片 + reasoning + tool_calls + 固定开销） */
    public static int estimateMessage(Message m) {
        if (m == null) return 0;
        int total = MSG_OVERHEAD;
        total += estimate(m.content);
        // 图片粗估：每张固定 500 token（base64 编码文本未计入 content）
        if (m.images != null) total += ImagePart.IMAGE_TOKENS * m.images.size();
        if (m.reasoningContent != null) total += estimate(m.reasoningContent);
        if (m.toolCalls != null) {
            for (ToolCall tc : m.toolCalls) {
                total += estimate(tc.name) + estimate(tc.arguments);
            }
        }
        return total;
    }

    public static int estimateMessages(java.util.List<Message> messages) {
        if (messages == null) return 0;
        long total = 0;
        for (Message m : messages) total += estimateMessage(m);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }
}
