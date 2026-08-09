package com.minion.core.skills;

/** 技能（frontmatter + 指令）。完整扫描/解析在 Task 19。 */
public class Skill {
    public final String name;
    public final String description;
    public final String instructions; // SKILL.md 正文
    public final String file;         // 展示用文件名

    public Skill(String name, String description, String instructions, String file) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.file = file;
    }

    public String hint() { return name + " — " + (description == null || description.isEmpty() ? "无描述" : description); }
}
