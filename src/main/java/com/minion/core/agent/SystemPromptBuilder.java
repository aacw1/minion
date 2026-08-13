package com.minion.core.agent;

import com.minion.core.skills.Skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** 系统提示词组装：内置提示 → 项目介绍(project.md) → 可用技能提示 → 已加载技能指令 */
public class SystemPromptBuilder {

    private static final String BUILTIN =
            "你是运行用户电脑里的代码开发助手。你可以调用工具读写文件、执行命令、搜索代码。\n"
          + "规则：\n"
          + "1. 语言要求(重要):思考和回答使用中文，回答要简洁。\n"
          + "2. 用户指令不明确、信息不足或存在多种可能理解时，先列出需要补充的问题，等待用户回答后再行动；不要猜测用户意图。\n"
          + "3. 使用工具前先想清楚目标，避免无谓调用；\n"
          + "4. 修改文件前先 Read 确认当前内容；Edit 必须精确匹配原文。\n"
          + "5. 复杂任务可用 task 工具派发子 agent 并行处理，子 agent 会返回结果摘要。\n"
          + "6. 涉及删除/覆盖等破坏性操作时，等待用户确认（系统会拦截）。\n"
          + "7. 当工具连续失败、或发现缺少完成任务所必需的信息/权限时，停止调用工具；向用户说明已尝试的方案、失败原因，并列出需要用户补充的信息或需要用户选择的方案，等待用户回复。不要反复重试同一方法。";

    private final String projectMdPath;

    public SystemPromptBuilder(String projectMdPath) { this.projectMdPath = projectMdPath; }

    public String build(List<Skill> allSkills, List<Skill> loadedSkills) {
        StringBuilder sb = new StringBuilder(BUILTIN);
        String projectMd = loadProjectMd(projectMdPath);
        if (!projectMd.isEmpty()) {
            sb.append("\n\n=== 项目介绍 ===\n").append(projectMd.trim());
        }
        if (allSkills != null && !allSkills.isEmpty()) {
            sb.append("\n\n=== 可用技能 ===\n");
            sb.append("以下是可用的技能，当任务与之匹配时，建议用户输入 /skill <技能名> 加载。\n");
            sb.append("技能目录可能在工作路径之外，如需读取技能源文件，可直接用 Read 工具读取其绝对路径：\n");
            for (Skill s : allSkills) sb.append("- ").append(s.hint()).append("（").append(s.file).append("）\n");
        }
        if (loadedSkills != null && !loadedSkills.isEmpty()) {
            sb.append("\n\n=== 已加载技能 ===\n");
            for (Skill s : loadedSkills) {
                sb.append("\n## 技能 ").append(s.name).append("\n\n").append(s.instructions).append('\n');
            }
        }
        return sb.toString();
    }

    static String loadProjectMd(String path) {
        try {
            Path p = Paths.get(path);
            if (Files.exists(p) && Files.isRegularFile(p)) {
                byte[] bytes = Files.readAllBytes(p);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[minion] 读取 project.md 失败: " + e.getMessage());
        }
        return "";
    }
}
