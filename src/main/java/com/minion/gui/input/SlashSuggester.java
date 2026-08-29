package com.minion.gui.input;

import com.minion.core.skills.Skill;

import java.util.ArrayList;
import java.util.List;

/** 斜杠补全数据：内置 5 命令 + 技能条目（label=/skill <名>，desc=frontmatter 描述）。纯静态。 */
public final class SlashSuggester {

    /** 内置命令（含描述，供弹层右侧灰字展示） */
    private static List<Suggestion> builtins() {
        List<Suggestion> out = new ArrayList<Suggestion>();
        out.add(new Suggestion("/help", "/help", "显示本帮助", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/skills", "/skills", "列出可用技能", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/skill", "/skill", "加载技能到当前会话", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/compact", "/compact", "立即压缩上下文", Suggestion.Type.COMMAND));
        out.add(new Suggestion("/tokens", "/tokens", "显示 token 用量统计", Suggestion.Type.COMMAND));
        return out;
    }

    /** 技能条目：选中插入 /skill <名>；desc 前标注来源（与 /skills 的 hint() 一致） */
    public static List<Suggestion> skillEntries(List<Skill> skills) {
        List<Suggestion> out = new ArrayList<Suggestion>();
        if (skills == null) return out;
        for (Skill s : skills) {
            String label = "/skill " + s.name;
            out.add(new Suggestion(label, label,
                    (com.minion.core.skills.Skill.SOURCE_PROJECT.equals(s.source) ? "[项目] " : "[内置] ")
                            + s.description, Suggestion.Type.SKILL));
        }
        return out;
    }

    /** SLASH 模式全集：内置命令 + 技能条目 */
    public static List<Suggestion> all(List<Skill> skills) {
        List<Suggestion> out = builtins();
        out.addAll(skillEntries(skills));
        return out;
    }

    private SlashSuggester() { }
}
