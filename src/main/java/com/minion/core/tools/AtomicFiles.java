package com.minion.core.tools;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写文件：先写同目录下的唯一临时文件，再 move 覆盖目标。
 *
 * 背景见 docs/superpowers/specs/2026-09-15-文件写入原子性与并发保护-design.md：
 * 直接 Files.write 在并发下会交错写同一 inode —— 各自独立 channel position + 各自
 * open 时 TRUNCATE，长写方先完成把文件长度撑到 L，短写方后完成只覆盖前 S 字节，
 * 于是 [S,L) 成为长写方残留尾部；切割点落在多字节字符内部时即产生孤立续接字节
 * （事故文件末尾的 0x89 即由此而来）。改为"写 tmp + rename 覆盖"后，读者要么看到
 * 旧文件、要么看到新文件，不会看到半截内容。
 *
 * 与项目既有落盘（SessionStore / ModelManager / McpStore / ToolStore）的做法一致，
 * 但补上了两处它们没有的兜底：ATOMIC_MOVE 失败降级、写后长度校验。
 */
public final class AtomicFiles {

    private AtomicFiles() { }

    /** 原子写文本（按指定编码编码后写字节） */
    public static void writeText(Path target, String content, Charset cs) throws IOException {
        writeBytes(target, content.getBytes(cs));
    }

    /** 原子写字节 */
    public static void writeBytes(Path target, byte[] data) throws IOException {
        Path abs = target.toAbsolutePath();
        Path parent = abs.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (parent == null) {
            // 无父目录（文件系统根下的相对名）：无处放 tmp，退化为直接写
            Files.write(abs, data);
            verifyLength(abs, data.length);
            return;
        }

        // tmp 必须与目标同目录（跨目录 move 不保证原子），且名字唯一：
        // 唯一的 tmp 名避免并发写者互踩对方的临时文件（既有实现多用固定后缀名）
        Path tmp = parent.resolve(abs.getFileName().toString()
                + ".mt" + System.nanoTime() + "-" + Thread.currentThread().getId() + ".tmp");
        try {
            try {
                Files.write(tmp, data);
            } catch (IOException e) {
                // tmp 名让路径变长，可能在 Windows MAX_PATH(260) 边界上超限；
                // 降级直接写目标，避免"原本能写的文件因为加 tmp 反而写不了"的回归
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                Files.write(abs, data);
                verifyLength(abs, data.length);
                return;
            }
            move(tmp, abs);
            verifyLength(abs, data.length);
        } finally {
            // move 成功后 tmp 已不存在，此处为 no-op；失败路径保证不留垃圾文件
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** 覆盖 move：优先 ATOMIC_MOVE，失败降级为普通替换 move */
    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Windows 上目标被其它句柄持读（编辑器/IDE/杀软/并发 Read）时 ATOMIC_MOVE 抛
            // AccessDeniedException（实测）。降级为 REPLACE_EXISTING：写入内容与长度仍然
            // 完整正确，仅失去"读侧零撕裂"保证；若目标被独占锁定，这里同样会失败并上报
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 写后长度校验：不一致说明目标被并发改动，宁可报错也不静默留下坏文件 */
    private static void verifyLength(Path target, int expected) throws IOException {
        long actual = Files.size(target);
        if (actual != expected) {
            throw new IOException("写入校验失败：目标长度 " + actual + " 与预期 " + expected
                    + " 不一致，可能被其它进程并发修改: " + target);
        }
    }
}
