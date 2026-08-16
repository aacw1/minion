package com.minion.core.agent;

import com.minion.core.skills.Skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** 系统提示词组装：内置提示 → 项目介绍(project.md) → 技能目录（路由引导，正文经 Skill 工具按需注入） */
public class SystemPromptBuilder {

    private static final String BUILTIN =
            "你是运行用户电脑里的代码开发助手。你可以调用工具读写文件、执行命令、搜索代码。\n"
          + "规则：\n"
          + "1. 语言要求(重要):思考和回答使用中文，回答要简洁。\n"
          + "2. 用户指令不明确、信息不足或存在多种可能理解时，先列出需要补充的问题，调用 AskUserQuestion 工具向用户提问，等待用户回答后再行动；不要猜测用户意图。"
          + "完成需要用户审查的产出（如设计文档、实施计划、关键方案）后，必须先调用 AskUserQuestion 请求审查确认，未获批准不得继续下一步。\n"
          + "3. 使用工具前先想清楚目标，避免无谓调用；Bash 命令在项目工作目录下执行。\n"
          + "4. 修改文件前先 Read 确认当前内容；Edit 必须精确匹配原文。\n"
          + "5. 复杂任务可用 task 工具派发子 agent 并行处理，子 agent 会返回结果摘要。\n"
          + "6. 涉及删除/覆盖等破坏性操作时，等待用户确认（系统会拦截）。\n"
          + "7. 当工具连续失败、或发现缺少完成任务所必需的信息/权限时，停止调用工具；向用户说明已尝试的方案、失败原因，并列出需要用户补充的信息或需要用户选择的方案（可调用 AskUserQuestion 工具提问），等待用户回复。不要反复重试同一方法。";

    private final String projectMdPath;
    /** 工作目录（可空=不注入；模型必须知道当前目录，否则会猜测/编造路径，曾实测编造出旧项目目录） */
    private final String workDir;

    public SystemPromptBuilder(String projectMdPath) { this(projectMdPath, null); }

    public SystemPromptBuilder(String projectMdPath, String workDir) {
        this.projectMdPath = projectMdPath;
        this.workDir = workDir;
    }

    public String build(List<Skill> allSkills) {
        StringBuilder sb = new StringBuilder(BUILTIN);
        if (workDir != null && !workDir.trim().isEmpty()) {
            sb.append("\n\n=== 当前工作目录 ===\n")
              .append("工作目录: ").append(workDir).append("\n")
              .append("- 工具的相对路径与 Bash 命令均以工作目录为基准；不要猜测或编造其他项目路径。\n")
              .append("- 不确定当前目录时，用 Bash 执行 pwd 查看。\n")
              .append("- 工作目录之外：读取需经系统授权确认；修改/新建将被拒绝。确需访问外部路径时说明用途，等待系统确认。");
        }
        String projectMd = loadProjectMd(projectMdPath);
        if (!projectMd.isEmpty()) {
            sb.append("\n\n=== 项目介绍 ===\n").append(projectMd.trim());
        }
        if (allSkills != null && !allSkills.isEmpty()) {
            sb.append("\n\n=== 可用技能 ===\n");
            sb.append("当任务与某技能描述匹配时，调用 Skill 工具加载（正文将作为一条用户消息注入，立即生效）。")
              .append("技能描述是路由条件——先看\"何时用/何时不用\"，匹配才加载，不匹配不要加载。")
              .append("同一技能不要重复加载；技能正文中引用的 Claude Code 专属机制（plan mode、ExitPlanMode、/loop 等）在本环境中不可用，相关审查确认用 AskUserQuestion 代替。\n");
            sb.append("技能源文件可能在工作路径之外，如需读取技能参考文档，可直接用 Read 工具读取其绝对路径：\n");
            for (Skill s : allSkills) sb.append("- ").append(s.hint()).append("（").append(s.file).append("）\n");
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
