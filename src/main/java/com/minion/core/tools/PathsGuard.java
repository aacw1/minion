package com.minion.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathsGuard {

    /** 解析相对工作路径的绝对路径（相对路径以 workDir 为基准） */
    public static Path resolve(String workDir, String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p.normalize();
        return Paths.get(workDir, path).normalize();
    }

    /** 是否在 workDir 内（含 workDir 本身） */
    public static boolean inside(String workDir, Path p) {
        try {
            Path root = Paths.get(workDir).toRealPath();
            Path target = p.toRealPath();
            return target.startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    public static ToolResult errorIfOutside(String workDir, Path p) {
        if (!inside(workDir, p)) {
            return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
        }
        return null;
    }
}
