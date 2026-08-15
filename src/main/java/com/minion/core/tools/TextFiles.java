package com.minion.core.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 文本文件编码辅助：UTF-8 严格解码优先，失败自动降级 GBK（Windows 记事本
 * ANSI 保存的常见编码）。GBK 对任意字节宽容、几乎不失败；降级结果带标志，
 * 写回类工具（EditTool）据此按原编码写回，避免重编码破坏文件其余内容。
 * （"Input length = N" 即 JDK MalformedInputException 的不可读消息，见
 * docs/superpowers/specs/2026-08-15-read-gbk-fallback-design.md）
 */
public final class TextFiles {

    public static final Charset GBK = Charset.forName("GBK");

    private TextFiles() { }

    /** 读全部行（同 Files.readAllLines 语义）：UTF-8 优先，失败降级 GBK */
    public static Lines readAllLines(Path p) throws IOException {
        try {
            return new Lines(Files.readAllLines(p, StandardCharsets.UTF_8), false);
        } catch (CharacterCodingException e) {
            return new Lines(Files.readAllLines(p, GBK), true);
        }
    }

    /** 解码字节（同 new String(bytes, cs) 语义）：UTF-8 严格优先，失败降级 GBK */
    public static Decoded decode(byte[] bytes) {
        try {
            return new Decoded(StandardCharsets.UTF_8, decodeStrict(bytes, StandardCharsets.UTF_8));
        } catch (CharacterCodingException e) {
            try {
                return new Decoded(GBK, decodeStrict(bytes, GBK));
            } catch (CharacterCodingException impossible) {
                throw new IllegalStateException("GBK 解码失败（对任意字节宽容，不应发生）", impossible);
            }
        }
    }

    /** 严格解码：非法序列/不可映射字符抛异常，而非静默替换为 U+FFFD */
    private static String decodeStrict(byte[] bytes, Charset cs) throws CharacterCodingException {
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** 读行结果：gbk=true 表示经 GBK 降级 */
    public static final class Lines {
        public final List<String> lines;
        public final boolean gbk;
        public Lines(List<String> lines, boolean gbk) {
            this.lines = lines;
            this.gbk = gbk;
        }
    }

    /** 解码结果：charset 为实际使用的编码（写回时须保持一致） */
    public static final class Decoded {
        public final Charset charset;
        public final String text;
        public Decoded(Charset charset, String text) {
            this.charset = charset;
            this.text = text;
        }
    }
}
