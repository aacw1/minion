package com.minion.core.skills;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 技能扫描：skills/<name>/SKILL.md（superpowers 格式）或 skills/<name>.skill.md */
public class SkillManager {

    private final String skillsDir;

    public SkillManager(String skillsDir) { this.skillsDir = skillsDir; }

    public List<Skill> scan() {
        List<Skill> skills = new ArrayList<Skill>();
        Path root = Paths.get(skillsDir);
        if (!Files.isDirectory(root)) return skills;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    Path md = entry.resolve("SKILL.md");
                    if (Files.exists(md)) {
                        skills.add(parse(md, entry.getFileName().toString(), Skill.SOURCE_GLOBAL));
                    }
                } else {
                    String name = entry.getFileName().toString();
                    if (name.endsWith(".skill.md")) {
                        skills.add(parse(entry, name.substring(0, name.length() - ".skill.md".length()), Skill.SOURCE_GLOBAL));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[minion] 扫描技能失败: " + e.getMessage());
        }
        skills.sort((a, b) -> a.name.compareTo(b.name));
        return skills;
    }

    /** 递归扫描结果：技能列表 + 面向用户的告警（null=一切正常；目录缺失/不可读/数量截断走此通道，不抛异常） */
    public static class ScanResult {
        public final List<Skill> skills;
        public final String warning;

        ScanResult(List<Skill> skills, String warning) {
            this.skills = skills;
            this.warning = warning;
        }
    }

    /** 递归扫描时跳过的目录名（版本库/依赖/构建产物/本程序自身数据） */
    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            ".git", ".svn", ".idea", ".vscode", "node_modules",
            "target", "build", "dist", "out", "bin", "obj", "session", ".session"));

    /**
     * 递归收集目录树中的技能：每个名为 SKILL.md 的文件（技能名取父目录名）与每个
     * 以 .skill.md 结尾的单文件各算一个技能。maxDepth 相对 root（walkFileTree 语义：
     * 1 = 只看 root 的直接子项），故进入「第 maxDepth 层目录内的文件」需传 maxDepth+1。
     * 已收集数达到 maxCount 立即 TERMINATE。任何 IO 问题都不抛异常：已收集部分照常
     * 返回，原因写进 ScanResult.warning（由上层 SessionManager 转 notifyError）。
     */
    public static ScanResult scanTree(final Path root, final int maxDepth, final int maxCount) {
        final List<Skill> skills = new ArrayList<Skill>();
        if (!Files.isDirectory(root)) {
            return new ScanResult(skills, "项目级技能路径不可用（目录不存在）: " + root);
        }
        final boolean[] truncated = new boolean[1]; // 因数量触顶被丢弃
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth + 1,
                    new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return SKIP_DIRS.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    boolean flat = fileName.toLowerCase().endsWith(".skill.md");
                    if (!"SKILL.md".equalsIgnoreCase(fileName) && !flat) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (skills.size() >= maxCount) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String fallback = flat
                            ? fileName.substring(0, fileName.length() - ".skill.md".length())
                            : file.getParent().getFileName().toString();
                    skills.add(parse(file, fallback, Skill.SOURCE_PROJECT));
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // 单个文件不可读不阻断整体扫描
                }
            });
        } catch (IOException e) {
            return new ScanResult(skills, "扫描项目级技能目录失败: " + e.getMessage());
        }
        String warning = truncated[0]
                ? "项目级技能数量超过上限(" + maxCount + ")，已截断，请把技能放进专门的子目录" : null;
        skills.sort((a, b) -> a.name.compareTo(b.name));
        return new ScanResult(skills, warning);
    }

    static Skill parse(Path file, String fallbackName, String source) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                if (end > 0) {
                    String fm = text.substring(3, end);
                    String body = text.substring(end + 4);
                    try {
                        Object loaded = new Yaml().load(fm);
                        Map<String, Object> yaml = loaded instanceof Map
                                ? (Map<String, Object>) loaded : null;
                        String name = fallbackName;
                        String desc = "";
                        if (yaml != null) {
                            if (yaml.get("name") != null) name = String.valueOf(yaml.get("name"));
                            if (yaml.get("description") != null) desc = String.valueOf(yaml.get("description"));
                        }
                        return new Skill(name, desc, body.trim(), file.toString(), source);
                    } catch (Exception e) {
                        System.err.println("[minion] 技能 frontmatter 解析失败(" + file + "): " + e.getMessage());
                        return new Skill(fallbackName, "", text.trim(), file.toString(), source);
                    }
                }
            }
            return new Skill(fallbackName, "", text.trim(), file.toString(), source);
        } catch (IOException e) {
            return new Skill(fallbackName, "", "(读取失败)", file.toString(), source);
        }
    }
}
