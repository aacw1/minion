package com.minion.core.tools;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;

/** 工具超限输出落盘：统一落盘目录/写入/尾部读取/清理。JDK8 兼容，无新依赖。 */
public final class OutputDump {

    public static final long RETENTION_MS = 3L * 24 * 3600 * 1000;

    private static final AtomicLong SEQ = new AtomicLong();

    private OutputDump() { }

    public static Path dumpDir(Path workDir) {
        return workDir.resolve(".minion").resolve("tmp");
    }

    /** 写落盘文件 prefix-<ts>-<seq>.txt，返回路径；失败返回 null（调用方降级） */
    public static Path write(Path workDir, String prefix, String content) {
        try {
            Path dir = dumpDir(workDir);
            Files.createDirectories(dir);
            Path f = dir.resolve(prefix + "-" + System.currentTimeMillis()
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
            long len = Files.size(file);
            // 多读 4 字节容错 UTF-8 边界（单字符最多 4 字节）
            long start = Math.max(0, len - maxChars * 1L - 4);
            byte[] buf;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(start);
                buf = new byte[(int) (len - start)];
                raf.readFully(buf);
            }
            String s = new String(buf, StandardCharsets.UTF_8);
            if (s.length() <= maxChars) return s;
            // 从尾部截到 maxChars；若首字符是不完整多字节则再丢一个字符
            String cut = s.substring(s.length() - maxChars);
            if (cut.charAt(0) == '\uFFFD' && cut.length() > 1) cut = cut.substring(1);
            return cut;
        } catch (Exception e) {
            return "";
        }
    }

    /** 删除 dumpDir 下修改时间超过 olderThanMillis 的文件；目录不存在静默返回 */
    public static void cleanup(Path workDir, long olderThanMillis) {
        try {
            Path dir = dumpDir(workDir);
            if (!Files.isDirectory(dir)) return;
            long deadline = System.currentTimeMillis() - olderThanMillis;
            Files.list(dir).forEach(p -> {
                try {
                    FileTime t = Files.getLastModifiedTime(p);
                    if (t.toMillis() < deadline) Files.deleteIfExists(p);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /** workDir 相对路径（反斜杠转正斜杠），供返回提示与模型 Read 使用 */
    public static String workDirRelative(Path workDir, Path file) {
        return workDir.toAbsolutePath().normalize().relativize(
                file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
