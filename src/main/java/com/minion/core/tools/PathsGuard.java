package com.minion.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 路径守卫：限制文件工具只能访问工作路径（+ 可选技能目录） */
public class PathsGuard {

    /** 解析相对工作路径的绝对路径（相对路径以 workDir 为基准） */
    public static Path resolve(String workDir, String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p.normalize();
        return Paths.get(workDir, path).normalize();
    }

    /** 是否在 dir 内（含 dir 本身）。dir 为 null/空或不存在（无法 toRealPath）时视为不可访问 */
    public static boolean inside(String dir, Path p) {
        if (dir == null || dir.isEmpty()) return false;
        try {
            Path root = Paths.get(dir).toRealPath();
            Path target = p.toRealPath();
            return target.startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    /** 越界守卫：仅工作路径（技能目录传 null） */
    public static ToolResult errorIfOutside(String workDir, Path p) {
        return errorIfOutside(workDir, null, p);
    }

    /** 越界守卫：工作路径或技能目录内的路径均放行 */
    public static ToolResult errorIfOutside(String workDir, String skillsDir, Path p) {
        if (!inside(workDir, p) && !inside(skillsDir, p)) {
            return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
        }
        return null;
    }
}
