package com.minion.core.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 会话级工作区:workDir 固定(守卫边界),cwd 可变(Bash cd 更新,初始 = workDir)。
 * Bash 与文件工具共用同一实例:cd 后相对路径行为与 shell 一致。
 * cd 仅限工作区内,保证与 PathsGuard 守卫口径一致。
 */
public class Workspace {

    private final String workDir;
    private volatile Path cwd;

    public Workspace(String workDir) {
        this.workDir = workDir;
        this.cwd = Paths.get(workDir).toAbsolutePath().normalize();
    }

    public String workDir() { return workDir; }

    public Path cwd() { return cwd; }

    /**
     * 切换当前目录。成功返回新 cwd;目标不在工作区内或不存在返回 null(不切换)。
     * 空串/空白表示回到工作区根。
     */
    public Path cd(String path) {
        Path target;
        if (path == null || path.trim().isEmpty()) {
            target = Paths.get(workDir).toAbsolutePath().normalize();
        } else {
            target = cwd.resolve(path).normalize().toAbsolutePath();
        }
        if (!target.startsWith(Paths.get(workDir).toAbsolutePath().normalize())) return null;
        if (!Files.isDirectory(target)) return null;
        cwd = target;
        return cwd;
    }

    /** 恢复会话时用:路径有效则恢复,无效(已删除/越界)保持现状 */
    public void restore(String cwdStr) {
        if (cwdStr == null || cwdStr.isEmpty()) return;
        Path p = Paths.get(cwdStr).toAbsolutePath().normalize();
        if (p.startsWith(Paths.get(workDir).toAbsolutePath().normalize())
                && Files.isDirectory(p)) {
            cwd = p;
        }
    }

    /** 回到工作区根(新会话/清理时用) */
    public void resetCwd() {
        cwd = Paths.get(workDir).toAbsolutePath().normalize();
    }
}
