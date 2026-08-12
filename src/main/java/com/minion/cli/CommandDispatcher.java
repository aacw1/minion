package com.minion.cli;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.AgentUi;
import com.minion.core.config.Config;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;

import java.util.List;
import java.util.Locale;

/** /命令分发。返回 null = 不是命令（按普通消息处理）。 */
public class CommandDispatcher {

    public enum Command { HELP, EXIT, SKILLS, SKILL, RESUME, COMPACT, TOKENS, CLEAR, MODEL, NEW, DELETE }

    private final AgentLoop loop;
    private final Config config;
    private final SessionStore store;
    private final SkillManager skillManager;
    private final AgentUi ui;

    public CommandDispatcher(AgentLoop loop, Config config, SessionStore store,
                             SkillManager skillManager, AgentUi ui) {
        this.loop = loop;
        this.config = config;
        this.store = store;
        this.skillManager = skillManager;
        this.ui = ui;
    }

    /** 返回 Command / String(展示内容) / null(非命令) */
    public Object dispatch(String input) {
        if (input == null || !input.startsWith("/")) return null;
        String trimmed = input.trim();
        String[] parts = trimmed.split("\\s+");
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "/help":
                return Command.HELP;
            case "/exit":
            case "/quit":
                return Command.EXIT;
            case "/skills":
                return Command.SKILLS;
            case "/skill":
                if (parts.length < 2) return "用法: /skill <技能名>（/skills 查看列表）";
                return dispatchSkillByName(parts[1]);
            case "/resume":
                return Command.RESUME;
            case "/compact":
                loop.compactNow();
                return Command.COMPACT;
            case "/tokens":
                return formatTokens();
            case "/clear":
                return Command.CLEAR;
            case "/model":
                return formatModel();
            case "/new":
                return Command.NEW;
            case "/delete":
                return Command.DELETE;
            default:
                return null;
        }
    }

    public void dispatchSkill(Skill skill) {
        loop.loadSkill(skill);
        ui.onWarning("已加载技能: " + skill.name);
    }

    private Object dispatchSkillByName(String name) {
        if (skillManager == null) return "技能系统未启用";
        for (Skill s : skillManager.scan()) {
            if (s.name.equalsIgnoreCase(name)) {
                dispatchSkill(s);
                return "已加载技能: " + s.name;
            }
        }
        return "未找到技能: " + name + "（/skills 查看列表）";
    }

    private String formatTokens() {
        com.minion.core.llm.UsageTracker t = loop.usage();
        return String.format(Locale.ROOT,
                "会话统计: in %d · out %d · thinking %d · 合计 %d",
                t.sessionInput(), t.sessionOutput(), t.sessionThinking(), t.sessionTotal());
    }

    private String formatModel() {
        return "模型配置已迁移至 model.json（GUI 版本提供可视化配置）";
    }
}
