package com.minion.core.skills;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                        skills.add(parse(md, entry.getFileName().toString()));
                    }
                } else {
                    String name = entry.getFileName().toString();
                    if (name.endsWith(".skill.md")) {
                        skills.add(parse(entry, name.substring(0, name.length() - ".skill.md".length())));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[minion] 扫描技能失败: " + e.getMessage());
        }
        skills.sort((a, b) -> a.name.compareTo(b.name));
        return skills;
    }

    static Skill parse(Path file, String fallbackName) {
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
                        return new Skill(name, desc, body.trim(), file.toString());
                    } catch (Exception e) {
                        System.err.println("[minion] 技能 frontmatter 解析失败(" + file + "): " + e.getMessage());
                        return new Skill(fallbackName, "", text.trim(), file.toString());
                    }
                }
            }
            return new Skill(fallbackName, "", text.trim(), file.toString());
        } catch (IOException e) {
            return new Skill(fallbackName, "", "(读取失败)", file.toString());
        }
    }
}
