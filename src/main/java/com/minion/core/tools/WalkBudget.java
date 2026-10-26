package com.minion.core.tools;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 文件遍历预算器（Grep/Glob 共用）：时间/条目/深度上限 + 目录环去重 + 中断检查 + 跳过计数。
 *
 * <p>为什么需要（2026-10-26，见 docs/superpowers/specs/2026-10-26-grep-glob-遍历有界化-design.md）：
 * ① {@code Files.walkFileTree} 默认实现遇到「拒绝访问」直接抛异常 → 整次 Grep/Glob 失败
 * （公司机器上权限受限目录常见，表现为「并发工具里其中一个随机失败」）；
 * ② Java 8 在 Windows 上只把 reparse tag 为 SYMLINK 的项识别为符号链接，junction
 * （MOUNT_POINT）会被当成普通目录递归进入 → 目录环无限遍历；
 * ③ 遍历本身无时间/条目上限且不检查线程中断 → 用户点「停止」后遍历线程不退出，
 * 工具池只有 4 个线程，被占死后续所有工具只入队不执行（会话假死，需重启）。</p>
 *
 * <p>去重口径：按 {@link Path#toRealPath()} 的真实路径判断（junction/软链接指回祖先时真实路径
 * 重复 → SKIP_SUBTREE）。多根场景（Glob：工作区 + 技能目录）**共享时间/条目预算**，
 * 但每个根独立去重集合——同一真实目录在不同根下的相对路径不同，跨根去重会漏匹配。</p>
 *
 * <p>线程模型：单次工具执行内单线程使用，不加锁。</p>
 */
final class WalkBudget {

    /** 时间预算：30s（用户评审由 10s 调整）。到点终止遍历，返回已收集结果 + 截断说明 */
    static final long MAX_MILLIS = 30_000L;
    /** 条目预算：访问的目录 + 文件总数上限（海量目录兜底） */
    static final int MAX_ENTRIES = 200_000;
    /** 深度上限（相对搜索根）；junction 未被 toRealPath 兜住时的最后一道防线 */
    static final int MAX_DEPTH = 64;

    /** 多根共享部分：预算、计数与终止原因 */
    private static final class Shared {
        final long start = System.currentTimeMillis();
        final int maxEntries;
        final long maxMillis;
        int entries;
        boolean exhausted;      // 预算耗尽（时间或条目）
        String exhaustedReason; // 文案用
        boolean interrupted;    // 遍历期间线程被中断
        int skippedInaccessible; // visitFileFailed / toRealPath 失败
        int skippedLargeFiles;   // 超单文件上限（Grep 专用）
        int skippedBinaryFiles;  // 二进制（Grep 专用）

        Shared(int maxEntries, long maxMillis) {
            this.maxEntries = maxEntries;
            this.maxMillis = maxMillis;
        }
    }

    private final Shared shared;
    private final int maxDepth;
    private final Set<Path> seenDirs = new HashSet<Path>();

    private WalkBudget(Shared shared, int maxDepth) {
        this.shared = shared;
        this.maxDepth = maxDepth;
    }

    /** 生产默认预算 */
    static WalkBudget defaults() { return new WalkBudget(new Shared(MAX_ENTRIES, MAX_MILLIS), MAX_DEPTH); }

    /** 单测注入小预算（条目/时间/深度） */
    WalkBudget(int maxEntries, long maxMillis, int maxDepth) {
        this(new Shared(maxEntries, maxMillis), maxDepth);
    }

    /** 共享同一时间/条目预算的新遍历根（Glob 多根用）；目录去重集合独立 */
    WalkBudget newRoot() { return new WalkBudget(shared, maxDepth); }

    /**
     * 进入目录前调用：中断/预算 → TERMINATE；深度超限、目录环、真实路径不可得 → SKIP_SUBTREE。
     * 返回值直接交给 {@code preVisitDirectory}。
     */
    FileVisitResult enterDir(Path dir, Path root) {
        if (stopForInterrupt() || stopForBudget()) return FileVisitResult.TERMINATE;
        if (depthOf(dir, root) > maxDepth) return FileVisitResult.SKIP_SUBTREE;
        final Path real;
        try {
            real = dir.toRealPath();
        } catch (IOException e) {
            shared.skippedInaccessible++; // 无权限/失效链接：跳过该子树，不让整次遍历失败
            return FileVisitResult.SKIP_SUBTREE;
        } catch (RuntimeException e) {
            shared.skippedInaccessible++; // SecurityException 等：同上
            return FileVisitResult.SKIP_SUBTREE;
        }
        if (!seenDirs.add(real)) return FileVisitResult.SKIP_SUBTREE; // 环/重复真实目录
        return FileVisitResult.CONTINUE;
    }

    /** 访问文件前调用：false = 终止遍历（预算耗尽或已中断） */
    boolean checkFile() {
        return !stopForInterrupt() && !stopForBudget();
    }

    /** visitFileFailed 兜底：跳过不可读项，不抛出、不终止整次遍历 */
    FileVisitResult fileFailed() {
        shared.skippedInaccessible++;
        if (shared.interrupted || shared.exhausted) return FileVisitResult.TERMINATE;
        // 中断/预算可能在失败回调前刚发生：再检查一次，避免继续白跑
        return checkFile() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
    }

    /** Grep 专用：跳过超大文件（计数进说明文案） */
    void skipLargeFile() { shared.skippedLargeFiles++; }

    /** Grep 专用：跳过二进制文件（计数进说明文案） */
    void skipBinaryFile() { shared.skippedBinaryFiles++; }

    /** 结果是否因预算/中断而不完整（调用方据此决定保留落盘文件） */
    boolean incomplete() { return shared.exhausted || shared.interrupted; }

    /**
     * 追加说明文案（无预算截断且无跳过项时返回 null，调用方不追加任何内容）：
     * 「（遍历已截断：达到 30 秒上限，已扫描 N 项，结果可能不完整）」
     * 「（已跳过 3 个大于 5MB 的文件、2 个二进制文件、1 个无权限项）」
     */
    String summary() {
        StringBuilder sb = new StringBuilder();
        if (shared.interrupted) {
            sb.append("（遍历已中断，结果可能不完整）");
        } else if (shared.exhausted) {
            sb.append("（遍历已截断：").append(shared.exhaustedReason)
                    .append("，已扫描 ").append(shared.entries).append(" 项，结果可能不完整）");
        }
        java.util.List<String> skipped = new java.util.ArrayList<String>();
        if (shared.skippedLargeFiles > 0) skipped.add(shared.skippedLargeFiles + " 个大于 5MB 的文件");
        if (shared.skippedBinaryFiles > 0) skipped.add(shared.skippedBinaryFiles + " 个二进制文件");
        if (shared.skippedInaccessible > 0) skipped.add(shared.skippedInaccessible + " 个无权限项");
        if (!skipped.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("（已跳过 ");
            for (int i = 0; i < skipped.size(); i++) {
                if (i > 0) sb.append('、');
                sb.append(skipped.get(i));
            }
            sb.append("）");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 把说明追加到正文末尾（note 为 null 时原样返回，保证旧输出契约逐字符不变） */
    static String appendNote(String body, String note) {
        if (note == null) return body;
        return body + (body.endsWith("\n") ? "" : "\n") + note + "\n";
    }

    /** 深度：搜索根为 0；walkFileTree 保证 dir 是 root 的后代 */    private static int depthOf(Path dir, Path root) {
        try {
            Path rel = root.relativize(dir);
            return rel.getNameCount() == 1 && rel.toString().isEmpty() ? 0 : rel.getNameCount();
        } catch (RuntimeException e) {
            return 0; // 不同根（理论不可达）：不参与深度判定
        }
    }

    private boolean stopForInterrupt() {
        if (Thread.currentThread().isInterrupted()) {
            shared.interrupted = true;
            return true;
        }
        return false;
    }

    private boolean stopForBudget() {
        if (shared.exhausted) return true;
        shared.entries++;
        if (shared.entries > shared.maxEntries) {
            exhaust("达到条目预算 " + shared.maxEntries + " 项");
            return true;
        }
        if (System.currentTimeMillis() - shared.start > shared.maxMillis) {
            exhaust("达到时间预算 " + timeText(shared.maxMillis));
            return true;
        }
        return false;
    }

    private void exhaust(String reason) {
        if (shared.exhausted) return;
        shared.exhausted = true;
        shared.exhaustedReason = reason;
    }

    /** 时间预算文案：整秒 → "N 秒"；否则毫秒（单测注入 1ms 时也读得懂） */
    static String timeText(long millis) {
        return millis >= 1000 && millis % 1000 == 0 ? (millis / 1000) + " 秒" : millis + " 毫秒";
    }
}
