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

    /** workDir 可变（工作空间路径修改实时生效；volatile 供会话线程读） */
    private volatile String workDir;
    private volatile Path cwd;
    /** 额外放行目录（项目级技能目录等）：替换语义，随工作空间配置热更新；volatile 供会话线程读 */
    private volatile java.util.List<String> extraAllowedDirs = new java.util.ArrayList<String>();

    public Workspace(String workDir) {
        this.workDir = workDir;
        this.cwd = Paths.get(workDir).toAbsolutePath().normalize();
    }

    public String workDir() { return workDir; }

    public Path cwd() { return cwd; }

    /**
     * 热更新工作区根目录（设置窗修改 work.dir 实时生效）：旧 cwd 可能已越出新根，回新根。
     * 运行中会话的 Bash/文件工具共用本实例，下一轮工具调用即按新根守卫。
     */
    public synchronized void setWorkDir(String newDir) {
        if (newDir == null || newDir.trim().isEmpty()) return;
        this.workDir = newDir;
        resetCwd();
    }

    /** 当前额外放行目录快照（永不返回 null） */
    public java.util.List<String> extraAllowedDirs() { return extraAllowedDirs; }

    /** 替换额外放行目录（null/空 = 清空）；与 setWorkDir 同为热更新，不保留旧目录 */
    public synchronized void setExtraAllowedDirs(java.util.List<String> dirs) {
        this.extraAllowedDirs = dirs == null
                ? new java.util.ArrayList<String>() : new java.util.ArrayList<String>(dirs);
    }

    /**
     * 切换当前目录。成功返回新 cwd;目标不在工作区内或不存在返回 null(不切换)。
     * 空串/空白表示回到工作区根。
     */
    public Path cd(String path) {
        Path base = root(); // 守卫基准一次读取，防 setWorkDir 竞态
        Path target;
        if (path == null || path.trim().isEmpty()) {
            target = base;
        } else {
            target = cwd.resolve(path).normalize().toAbsolutePath();
        }
        if (!target.startsWith(base)) return null;
        if (!Files.isDirectory(target)) return null;
        cwd = target;
        return cwd;
    }

    /** 恢复会话时用:路径有效则恢复,无效(已删除/越界)保持现状 */
    public void restore(String cwdStr) {
        if (cwdStr == null || cwdStr.isEmpty()) return;
        Path p = Paths.get(cwdStr).toAbsolutePath().normalize();
        if (p.startsWith(root()) && Files.isDirectory(p)) {
            cwd = p;
        }
    }

    private Path root() {
        return Paths.get(workDir).toAbsolutePath().normalize();
    }

    /** 回到工作区根(新会话/清理时用) */
    public void resetCwd() {
        cwd = Paths.get(workDir).toAbsolutePath().normalize();
    }
}
