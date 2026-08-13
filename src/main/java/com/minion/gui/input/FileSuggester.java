package com.minion.gui.input;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 工作空间文件补全数据：walk workDir 收集相对路径（跳过点目录/.git，上限 200 条），10 秒缓存。 */
public class FileSuggester {

    /** 结果上限（与 GlobTool 口径一致） */
    static final int MAX_RESULTS = 200;
    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 10_000;

    private String cachedDir;
    private long cachedAt;
    private List<Suggestion> cached = new ArrayList<Suggestion>();

    /** 列出工作空间文件（带 10 秒缓存：弹层每次新 @ 词打开时调用，按键过滤走 SuggestionPopup.filter） */
    public synchronized List<Suggestion> list(String workDir) {
        if (workDir == null) return new ArrayList<Suggestion>();
        long now = System.currentTimeMillis();
        if (cachedDir != null && cachedDir.equals(workDir) && now - cachedAt < CACHE_TTL_MS) {
            return new ArrayList<Suggestion>(cached);
        }
        cachedDir = workDir;
        cached = walk(workDir);
        cachedAt = now;
        return new ArrayList<Suggestion>(cached);
    }

    /** 遍历工作空间（纯静态，可单测）：相对路径 / 分隔；跳过点开头目录与文件、.git；IO 异常静默 */
    static List<Suggestion> walk(String workDir) {
        List<Suggestion> out = new ArrayList<Suggestion>();
        Path root = Paths.get(workDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return out;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_RESULTS) return FileVisitResult.TERMINATE;
                    String name = file.getFileName().toString();
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE; // 跳过点文件（.gitignore 等）
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    out.add(new Suggestion(rel, rel, null, Suggestion.Type.FILE));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 补全为增强体验：遍历异常静默返回已收集部分
        }
        return out;
    }
}
