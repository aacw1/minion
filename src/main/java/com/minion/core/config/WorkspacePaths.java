package com.minion.core.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工作空间内路径解析：一切相对路径以「该空间项目路径（workDir 绝对化后的值）」为基准。
 * 刻意不用进程当前目录作基准——那会让多个空间共享同一份 ./project.md，导致提示词跨空间串台。
 */
public final class WorkspacePaths {

    /** 绝对化：path 空白→null；绝对→normalize；相对→按 baseDir 拼接后 normalize。baseDir 缺失也不抛。 */
    public static String resolve(String baseDir, String path) {
        if (path == null || path.trim().isEmpty()) return null;
        Path p = Paths.get(path.trim());
        if (p.isAbsolute()) return p.normalize().toString();
        Path base = Paths.get(baseDir == null || baseDir.trim().isEmpty() ? "." : baseDir.trim())
                .toAbsolutePath().normalize();
        return base.resolve(p).normalize().toString();
    }

    /** 该空间的绝对项目路径（workDir 为空按进程当前目录，与 Workspace 内部口径一致） */
    public static String workDirAbs(WorkspaceConfig w) {
        String wd = w == null || w.workDir == null || w.workDir.trim().isEmpty() ? "." : w.workDir.trim();
        return Paths.get(wd).toAbsolutePath().normalize().toString();
    }

    /**
     * 项目主说明文件绝对路径；**未配置（null/空白）返回 null = 不使用主说明文件**。
     * 旧行为是回落 <项目路径>/project.md，会让「留空」在界面上看不出差别、
     * 又让 core 无法校验（不存在的兜底路径照样发给读文件处），故取消回落。
     */
    public static String projectMd(WorkspaceConfig w) {
        if (w == null) return null;
        return resolve(workDirAbs(w), w.projectMd);
    }

    /** 项目级技能目录绝对路径；未配置 → null */
    public static String projectSkillsDir(WorkspaceConfig w) {
        return w == null ? null : resolve(workDirAbs(w), w.projectSkillsDir);
    }

    /**
     * 是否指向一个**实际存在的文件夹**：相对路径按 baseDir（null = 进程当前目录，与 workDir 口径一致）
     * 解析后判断。非法字符（手改配置、乱填）不抛异常，一律判为不可用。
     * core 的 add/update 与界面校验共用本方法，保证「界面拦得住、API 也绕不过」口径一致。
     */
    public static boolean isExistingDir(String rawPath, String baseDir) {
        if (rawPath == null || rawPath.trim().isEmpty()) return false;
        try {
            return Files.isDirectory(Paths.get(resolve(baseDir, rawPath)));
        } catch (IllegalArgumentException e) { // 含 InvalidPathException
            return false;
        }
    }

    /**
     * 是否指向一个**实际存在的普通文件**（主说明文件用）：解析与容错口径同
     * {@link #isExistingDir}——空白/不存在/指向文件夹/非法字符一律 false，不抛异常。
     */
    public static boolean isExistingFile(String rawPath, String baseDir) {
        if (rawPath == null || rawPath.trim().isEmpty()) return false;
        try {
            return Files.isRegularFile(Paths.get(resolve(baseDir, rawPath)));
        } catch (IllegalArgumentException e) { // 含 InvalidPathException
            return false;
        }
    }

    private WorkspacePaths() { }
}
