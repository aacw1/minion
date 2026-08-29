package com.minion.core.skills;

/** 技能（frontmatter + 指令 + 来源）。来源仅用于展示与合并排序，不参与匹配。 */
public class Skill {

    /** 内置技能（config: skills.dir） */
    public static final String SOURCE_GLOBAL = "global";
    /** 项目级技能（工作空间 projectSkillsDir） */
    public static final String SOURCE_PROJECT = "project";

    public final String name;
    public final String description;
    public final String instructions; // SKILL.md 正文
    public final String file;         // 源文件完整路径（供模型用 Read 读取）
    public final String source;       // SOURCE_GLOBAL / SOURCE_PROJECT

    public Skill(String name, String description, String instructions, String file, String source) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.file = file;
        this.source = source;
    }

    /** 带来源标注的单行提示：提示词技能段、/skills 列表、补全弹层共用 */
    public String hint() {
        String tag = SOURCE_PROJECT.equals(source) ? "[项目] " : "[内置] ";
        return tag + name + " — " + (description == null || description.isEmpty() ? "无描述" : description);
    }
}
