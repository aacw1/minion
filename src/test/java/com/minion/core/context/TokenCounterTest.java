package com.minion.core.context;

import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TokenCounterTest {

    @Test
    public void estimate_zhAndEn() {
        // 4 个中文字 ≈ 2.8 → ceil 3
        assertEquals(3, TokenCounter.estimate("你好世界"));
        // 8 个 ASCII ≈ 2
        assertEquals(2, TokenCounter.estimate("abcdefgh"));
        assertTrue(TokenCounter.estimate("中文 mixed 123") > 0);
    }

    @Test
    public void estimateMessages_countsAllFields() {
        Message u = Message.user("读取文件并分析");
        Message a = Message.assistant("好的");
        a.reasoningContent = "先看结构";
        ToolCall tc = new ToolCall();
        tc.name = "Read";
        tc.arguments = "{\"path\":\"src/Main.java\"}";
        a.toolCalls = Collections.singletonList(tc);
        Message t = Message.toolResult("c1", "Read", "public class Main {}");
        int n = TokenCounter.estimateMessages(Arrays.asList(u, a, t));
        // 每条消息至少 4 开销
        assertTrue(n >= 12);
    }

    @Test
    public void estimateMessages_countsImages() {
        Message u = Message.user("看图");
        List<ImagePart> imgs = new ArrayList<ImagePart>();
        ImagePart p = new ImagePart();
        p.mime = "image/png"; p.base64 = "QUJD"; p.name = "a.png";
        imgs.add(p);
        ImagePart q = new ImagePart();
        q.mime = "image/jpeg"; q.base64 = "QUJD"; q.name = "b.jpg";
        imgs.add(q);
        u.images = imgs;
        int withImages = TokenCounter.estimateMessages(Collections.singletonList(u));
        u.images = null;
        int withoutImages = TokenCounter.estimateMessages(Collections.singletonList(u));
        // 每张图粗估 500 token
        assertEquals(2 * ImagePart.IMAGE_TOKENS, withImages - withoutImages);
    }

    // ---- P1-3：日文/韩文/emoji/代理对不再按 ASCII 低估 ----

    /** 平假名/片假名按 CJK 权重（0.7/字）：5 个假名 ≈ 3.5 → 4（旧实现按 ASCII 只给 2） */
    @Test
    public void estimate_japaneseHiragana_countedAsCjk() {
        assertEquals(4, TokenCounter.estimate("こんにちは"));
        assertEquals(3, TokenCounter.estimate("テスト")); // 3 × 0.7 = 2.1 → 3
    }

    /** 韩文谚文按 CJK 权重：5 个音节 ≈ 3.5 → 4（旧实现 1.25 → 2） */
    @Test
    public void estimate_hangul_countedAsCjk() {
        assertEquals(4, TokenCounter.estimate("안녕하세요"));
    }

    /** 补充平面 CJK（扩展 B，代理对）：按 code point 计 0.7（旧实现按 2 个字符 × 0.25 = 0.5） */
    @Test
    public void estimate_supplementaryCjk_singleCodepoint() {
        assertEquals(3, TokenCounter.estimate("\uD840\uDC00\uD840\uDC00\uD840\uDC00")); // 3 × U+20000
    }

    /** emoji：每个 1.5 token（旧实现 2 个 char × 0.25 = 0.5，低估 4 倍） */
    @Test
    public void estimate_emoji_notUnderestimated() {
        assertEquals(2, TokenCounter.estimate("\uD83D\uDE00"));               // 单个 😀 = 1.5 → 2
        assertEquals(6, TokenCounter.estimate("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"));
        String emojis = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00";
        assertTrue("emoji 不应按 ASCII 低估：估算应 ≥ 每个 1 token",
                TokenCounter.estimate(emojis) >= 4);
    }

    /** 验收口径：日文/emoji 估算误差 < 30%（以「每个字符 ≈ 0.7~1.5 token」为参照） */
    @Test
    public void estimate_japaneseAndEmoji_within30Percent() {
        String ja = "これはテストです"; // 8 个假名/汉字
        int jaTokens = TokenCounter.estimate(ja);
        assertTrue("日文估算应 ≥ 0.6×字符数（实际 " + jaTokens + "）", jaTokens >= (int) (ja.length() * 0.6));
        assertTrue("日文估算应 ≤ 1.3×字符数（实际 " + jaTokens + "）", jaTokens <= (int) Math.ceil(ja.length() * 1.3));
        String emoji = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"; // 3 个 emoji
        int emojiTokens = TokenCounter.estimate(emoji);
        assertTrue("emoji 估算应 ≥ 每个 1 token（实际 " + emojiTokens + "）", emojiTokens >= 3);
    }

    /** estimateMessage 与 estimateMessages 单条口径一致（P1-3 单次扫描复用同一函数） */
    @Test
    public void estimateMessage_matchesSingleListEstimate() {
        Message u = Message.user("读取文件");
        u.reasoningContent = "先看结构";
        assertEquals(TokenCounter.estimateMessage(u), TokenCounter.estimateMessages(Collections.singletonList(u)));
    }
}
