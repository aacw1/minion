package com.minion.core.tools;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/** 工具超限输出落盘：会话临时目录（jarDir/.session/tmp/<sessionId>）写入/尾部读取/清理。JDK8 兼容，无新依赖。 */
public final class OutputDump {

    public static final long RETENTION_MS = 3L * 24 * 3600 * 1000;

    private static final AtomicLong SEQ = new AtomicLong();

    private OutputDump() { }

    /** 写落盘文件 prefix-<ts>-<seq>.txt 到会话临时目录；tmpDir 为 null 或失败返回 null（调用方降级） */
    public static Path write(Path tmpDir, String prefix, String content) {
        if (tmpDir == null) return null;
        try {
            Files.createDirectories(tmpDir);
            Path f = tmpDir.resolve(prefix + "-" + System.currentTimeMillis()
                    + "-" + SEQ.incrementAndGet() + ".txt");
            Files.write(f, content.getBytes(StandardCharsets.UTF_8));
            return f;
        } catch (IOException e) {
            System.err.println("[minion] 输出落盘失败: " + e.getMessage());
            return null;
        }
    }

    /** 读文件末尾最多 maxChars 字符；UTF-8 多字节边界安全（首字符非法则丢弃）；
     *  失败返回 "" */
    public static String tail(Path file, int maxChars) {
        try {
            if (maxChars <= 0) return "";
            long len = Files.size(file);
            if (len == 0) return "";
            // UTF-8 单字符最多 4 字节：窗口读 min(len, maxChars*4+4) 字节。
            // 文件字符数 ≤ maxChars 时字节数必 ≤ maxChars*4，读全量返回；
            // 超限时窗口可解码出 ≥ maxChars+1 字符（+4 字节容错起点切割的半个字符），
            // 保证尾部取满 maxChars 且不丢内容。
            long readLen = Math.min(len, maxChars * 4L + 4);
            byte[] buf;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(len - readLen);
                buf = new byte[(int) readLen];
                raf.readFully(buf);
            }
            String s = new String(buf, StandardCharsets.UTF_8);
            // 窗口起点切在多字节字符中间时首字符为 U+FFFD，丢弃
            if (s.charAt(0) == '\uFFFD') s = s.substring(1);
            String cut = s.length() <= maxChars ? s : s.substring(s.length() - maxChars);
            // substring 可能切在代理对（如 emoji）中间，丢弃两端孤立代理
            if (cut.length() > 0 && Character.isLowSurrogate(cut.charAt(0))) cut = cut.substring(1);
            if (cut.length() > 0 && Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut;
        } catch (Exception e) {
            return "";
        }
    }

    /** 删除 tmpRoot（jarDir/.session/tmp）下所有会话子目录中修改时间超过 olderThanMillis 的文件；
     *  子目录本身保留（会话未删除时旧文件允许保留供回溯）。目录不存在静默返回 */
    public static void cleanup(Path tmpRoot, long olderThanMillis) {
        try {
            if (!Files.isDirectory(tmpRoot)) return;
            long deadline = System.currentTimeMillis() - olderThanMillis;
            try (Stream<Path> dirs = Files.list(tmpRoot)) {
                dirs.filter(Files::isDirectory).forEach(d -> {
                    try (Stream<Path> stream = Files.list(d)) { // 流须关闭，否则 Windows 目录句柄泄漏
                        stream.forEach(p -> {
                            try {
                                FileTime t = Files.getLastModifiedTime(p);
                                if (t.toMillis() < deadline) Files.deleteIfExists(p);
                            } catch (IOException ignored) { }
                        });
                    } catch (IOException ignored) { }
                });
            }
        } catch (IOException ignored) { }
    }
}
