package com.minion.gui.input;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 工作空间文件补全数据：walk workDir 收集相对路径（跳过点目录/.git 与 .gitignore
 *  忽略的目录文件；输出按字典序稳定排序）。每个工作空间一份独立缓存（5 分钟），
 *  会话绑定/输入 @ 时可先查缓存、未命中再后台扫描（扫描线程在 InputView 侧）。
 *
 *  并发约定（P0-6）：本类是「多生产者（后台扫描线程）+ 单消费者（FX 线程）」的读多写少缓存——
 *  - 所有方法**不再持有监视器做 IO**：`walk` 全程在锁外执行，完成后 put 一个不可变 Entry；
 *    后台扫描 10 万文件期间，FX 线程的 listStaleOk/isFresh/listCached 都是无阻塞的 map 读。
 *  - 缓存条目内的文件列表是**不可变视图**：读接口（listCached/listStaleOk/load 命中）直接返回
 *    同一实例，不再每次敲键都 `new ArrayList` 全量拷贝（十万条 × 每个输入字符的分配）。
 *  - `walk` 条目上限 {@link #MAX_ENTRIES}：十万级仓库遍历是数十秒级，补全本身只需前若干命中；
 *    到上限即提前收敛（不再深入剩余目录），避免内存与排序成本无界。 */
public class FileSuggester {

    /** 缓存有效期（毫秒）：每个工作空间独立缓存。缓存过期不阻塞输入——UI 先用旧缓存
     *  即时显示，后台异步刷新后替换（见 InputView）。测试可临时改小以模拟过期 */
    static long CACHE_TTL_MS = 300_000;

    /** 单次遍历收集的文件条目上限（P0-6）：到上限提前收敛，防十万级仓库无限收集/排序。
     *  2 万条 × 每条约 100 字符 ≈ 数 MB 上限，补全列表足够（弹层每次只展示前若干命中）。
     *  测试可临时改小以验证截断（同 CACHE_TTL_MS 的可调约定） */
    static int MAX_ENTRIES = 20_000;

    /** 单工作空间缓存条目（不可变）：files 为不可变列表，可安全共享给 FX 线程遍历 */
    private static final class Entry {
        final List<Suggestion> files;
        final long at;

        Entry(List<Suggestion> files, long at) {
            this.files = files;
            this.at = at;
        }
    }

    /** 工作空间绝对路径 → 缓存条目；并发读写用 CHM（不做全量同步，遍历不在锁内） */
    private final Map<String, Entry> caches = new ConcurrentHashMap<String, Entry>();

    /** 列出工作空间文件（同步全量接口：缓存命中直接返回，未命中 walk 并写缓存；供测试与既有调用） */
    public List<Suggestion> list(String workDir) {
        List<Suggestion> hit = listCached(workDir);
        return hit != null ? hit : load(workDir);
    }

    /** 仅查缓存：命中返回缓存列表（不可变，无需拷贝）；未命中（无缓存/过期）返回 null——不触发任何 IO。
     *  UI 侧先查本方法，未命中再走后台线程 load，避免大项目首次 @ 在 FX 线程同步遍历卡顿 */
    public List<Suggestion> listCached(String workDir) {
        if (workDir == null) return null;
        Entry e = caches.get(workDir);
        if (e != null && isFresh(e)) return e.files;
        return null;
    }

    /** 缓存是否存在且新鲜（TTL 内）。UI 侧用它判断是否需要后台刷新 */
    public boolean isFresh(String workDir) {
        if (workDir == null) return false;
        Entry e = caches.get(workDir);
        return e != null && isFresh(e);
    }

    /** 过期也返回缓存（快速路径）：存在条目即给（含过期），保证 @ 弹层零等待；
     *  配合 isFresh 为 false 时后台异步刷新。无任何缓存才返回 null */
    public List<Suggestion> listStaleOk(String workDir) {
        if (workDir == null) return null;
        Entry e = caches.get(workDir);
        return e == null ? null : e.files;
    }

    private static boolean isFresh(Entry e) {
        return System.currentTimeMillis() - e.at < CACHE_TTL_MS;
    }

    /** 全量加载并写缓存（后台线程调用；新鲜缓存命中时直接返回缓存）。IO 在调用线程执行，
     *  但**不在任何锁内**——并发读者不会因遍历而阻塞；同目录并发 load 各扫一次后互相覆盖
     *  （InputView 已用 fileLoading 闸门保证同一时刻至多一次 walk，此处只做兜底） */
    public List<Suggestion> load(String workDir) {
        if (workDir == null) return Collections.emptyList();
        List<Suggestion> hit = listCached(workDir);
        if (hit != null) return hit;
        List<Suggestion> files = Collections.unmodifiableList(walk(workDir));
        caches.put(workDir, new Entry(files, System.currentTimeMillis()));
        return files;
    }

    /** 遍历工作空间（纯静态，可单测）：相对路径 / 分隔；跳过点开头目录与文件、.git；
     *  .gitignore 忽略的目录/文件整棵/单个跳过（仅根级正向规则，见 GitignoreMatcher）；
     *  结果按相对路径字典序排序（稳定可预期）；IO 异常静默；超过 {@link #MAX_ENTRIES} 提前收敛 */
    static List<Suggestion> walk(String workDir) {
        final List<Suggestion> out = new ArrayList<Suggestion>();
        Path root = Paths.get(workDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return out;
        final GitignoreMatcher gi = GitignoreMatcher.load(root.toString());
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_ENTRIES) return FileVisitResult.TERMINATE;
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    if (gi != null && gi.matchesDir(rel(root, dir))) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_ENTRIES) return FileVisitResult.TERMINATE;
                    String name = file.getFileName().toString();
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE; // 跳过点文件（.gitignore 等）
                    if (gi != null && gi.matchesFile(rel(root, file))) return FileVisitResult.CONTINUE;
                    out.add(new Suggestion(rel(root, file), rel(root, file), null, Suggestion.Type.FILE));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 补全为增强体验：遍历异常静默返回已收集部分
        }
        Collections.sort(out, new Comparator<Suggestion>() {
            @Override public int compare(Suggestion a, Suggestion b) {
                return a.label.compareTo(b.label);
            }
        });
        return out;
    }

    /** 根相对路径，统一 / 分隔（GitignoreMatcher 匹配约定） */
    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
