package com.minion.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/** 会话临时目录孤儿清理：落盘文件生命周期 = 会话生命周期。
 *  正常路径由 SessionManager 删除会话/工作空间时递归清理；本类只在启动时兜底覆盖
 *  崩溃/删除失败（Windows 句柄占用）残留——tmp 下没有对应会话文件的目录（孤儿）整目录删除。
 *  近期目录（mtime 晚于 now-minAgeMs）跳过：防极端竞态误删尚未落盘的活跃会话临时文件。
 *  取代旧的「3 天过期清理」——用户裁定文件生命周期应随会话，而非按时间自然过期。 */
public final class SessionTempCleaner {

    private SessionTempCleaner() { }

    /** @param sessionRoot 会话文件根（jarDir/session，结构 <workspace>/<sessionId>.json）
     *  @param tmpRoot 临时目录根（jarDir/.session/tmp，子目录名 = 会话 id）
     *  @param minAgeMs 孤儿判定宽限：目录 mtime 在此时间之后跳过（防竞态） */
    public static void cleanOrphans(Path sessionRoot, Path tmpRoot, long minAgeMs) {
        if (tmpRoot == null || !Files.isDirectory(tmpRoot)) return;
        Set<String> alive = collectSessionIds(sessionRoot);
        long deadline = System.currentTimeMillis() - minAgeMs;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tmpRoot)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                if (alive.contains(dir.getFileName().toString())) continue;
                try {
                    if (Files.getLastModifiedTime(dir).toMillis() > deadline) continue; // 宽限期内不动
                } catch (IOException ignored) {
                    continue;
                }
                deleteRecursively(dir);
            }
        } catch (IOException ignored) { }
    }

    /** 收集现存会话 id：扫描 sessionRoot 下一层工作空间目录里的 *.json（文件名去后缀）；失败返回空集
     *  （会话根不存在=全部 tmp 目录视为孤儿；空集只会多清，宽限期兜底竞态） */
    private static Set<String> collectSessionIds(Path sessionRoot) {
        Set<String> ids = new HashSet<String>();
        if (sessionRoot == null || !Files.isDirectory(sessionRoot)) return ids;
        try (DirectoryStream<Path> wsDirs = Files.newDirectoryStream(sessionRoot)) {
            for (Path ws : wsDirs) {
                if (!Files.isDirectory(ws)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(ws, "*.json")) {
                    for (Path f : files) {
                        String n = f.getFileName().toString();
                        ids.add(n.substring(0, n.length() - ".json".length()));
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        return ids;
    }

    /** 递归删除（文件占用/失败静默跳过；JDK8 Files.walk 必须关流，否则 Windows 目录句柄泄漏） */
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
