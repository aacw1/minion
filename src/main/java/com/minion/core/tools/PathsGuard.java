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

    /** 是否在 dir 内（含 dir 本身）。dir 为 null/空返回 false；dir 尚未创建（会话临时目录/
     *  会话存储目录/技能目录惰性创建）时退化为规范化路径前缀比较——该分支只可能作用于
     *  「dir 下不存在的路径」：存在路径的祖先链上 dir 必然已存在，仍走真实路径校验 */
    public static boolean inside(String dir, Path p) {
        if (dir == null || dir.isEmpty()) return false;
        Path dirPath = Paths.get(dir);
        Path root;
        try {
            root = dirPath.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize()
                    .startsWith(dirPath.toAbsolutePath().normalize());
        }
        try {
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

    /** 任一额外放行目录（项目级技能目录等）命中即放行；无配置时恒 false */
    public static boolean insideExtra(Workspace ws, Path p) {
        if (ws == null) return false;
        for (String dir : ws.extraAllowedDirs()) {
            if (inside(dir, p)) return true;
        }
        return false;
    }

    /** 任一「只读放行目录」（会话存储目录等）命中即放行——仅读工具使用，写工具不适用；无配置时恒 false */
    public static boolean insideReadExtra(Workspace ws, Path p) {
        if (ws == null) return false;
        for (String dir : ws.extraReadDirs()) {
            if (inside(dir, p)) return true;
        }
        return false;
    }

    /** 越界守卫（读工具版）：读写放行目录 + 只读放行目录 任一命中即放行 */
    public static ToolResult errorIfOutsideRead(Workspace ws, String skillsDir, String tmpDir, Path p) {
        if (insideReadExtra(ws, p)) return null;
        return errorIfOutside(ws, skillsDir, tmpDir, p);
    }

    /** 越界守卫：工作路径 / 额外放行目录 / 内置技能目录 / 会话临时目录 任一命中即放行 */
    public static ToolResult errorIfOutside(Workspace ws, String skillsDir, String tmpDir, Path p) {
        if (inside(ws.workDir(), p) || insideExtra(ws, p)
                || inside(skillsDir, p) || inside(tmpDir, p)) {
            return null;
        }
        return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
    }
}
