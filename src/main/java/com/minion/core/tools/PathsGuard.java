package com.minion.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 路径守卫：限制文件工具只能访问工作路径（+ 可选技能目录/会话临时目录） */
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
            // 目标不存在（写新文件/新建目录，如截图保存）时 toRealPath 抛 NoSuchFileException
            // 会误判越界：向上找最深已存在祖先做真实路径校验（同 WriteTool.outsideGuard 的 T8 约定）。
            // NOFOLLOW_LINKS 探活：断链（指向不存在的目录）视为已存在，toRealPath 解析失败即拒绝，
            // 防止写穿断链逃逸到工作区外。
            Path probe = p;
            while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
                probe = probe.getParent();
            }
            if (probe == null) return false; // 整个祖先链缺失，无法校验
            return probe.toRealPath().startsWith(root);
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
        return errorIfOutside(workDir, skillsDir, null, p);
    }

    /** 越界守卫：工作路径/技能目录/会话临时目录内的路径均放行 */
    public static ToolResult errorIfOutside(String workDir, String skillsDir, String tmpDir, Path p) {
        if (!inside(workDir, p) && !inside(skillsDir, p) && !inside(tmpDir, p)) {
            return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
        }
        return null;
    }
}
