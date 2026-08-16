package com.minion.gui.command;

import com.minion.core.skills.Skill;
import com.minion.gui.session.SessionHandle;

import java.util.List;
import java.util.Locale;

/** 斜杠命令本地分发（恢复 CLI 语义）：返回 null = 非命令（按普通消息发送）；
 *  非 null = 已本地执行的命令展示文本。命令结果永不发给 LLM。 */
public class CommandDispatcher {

    private final List<Skill> skills;

    public CommandDispatcher(List<Skill> skills) { this.skills = skills; }

    public String dispatch(SessionHandle h, String input) {
        if (input == null || !input.trim().startsWith("/")) return null;
        String[] parts = input.trim().split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if ("/help".equals(cmd)) return helpText();
        if ("/skills".equals(cmd)) return skillsText();
        if ("/skill".equals(cmd)) return dispatchSkill(h, parts);
        if ("/tokens".equals(cmd)) return tokensText(h);
        if ("/compact".equals(cmd)) return dispatchCompact(h);
        return "未知命令 " + parts[0] + "（/help 查看）";
    }

    private String helpText() {
        return "可用命令：\n"
                + "/help        显示本帮助\n"
                + "/skills      列出可用技能\n"
                + "/skill <名>  加载技能到当前会话（下一轮请求生效）\n"
                + "/compact     立即压缩上下文\n"
                + "/tokens      显示 token 用量统计";
    }

    private String skillsText() {
        if (skills == null || skills.isEmpty()) {
            return "未发现可用技能。请检查 设置 → 基础设置 → 技能目录（skills.dir）";
        }
        StringBuilder sb = new StringBuilder("可用技能（").append(skills.size()).append(" 个）：");
        for (Skill s : skills) sb.append('\n').append("- ").append(s.hint());
        return sb.toString();
    }

    private String dispatchSkill(SessionHandle h, String[] parts) {
        if (parts.length < 2) return "用法: /skill <技能名>（/skills 查看列表）";
        for (Skill s : skills) {
            if (s.name.equalsIgnoreCase(parts[1])) {
                h.loop.offerSkillLoad(s);
                return "已加载技能: " + s.name + "（正文将注入，下一轮请求生效）";
            }
        }
        return "未找到技能: " + parts[1] + "（/skills 查看列表）";
    }

    private String tokensText(SessionHandle h) {
        com.minion.core.llm.UsageTracker t = h.loop.usage();
        return String.format(Locale.ROOT, "会话统计: in %d · out %d · thinking %d · 合计 %d",
                t.sessionInput(), t.sessionOutput(), t.sessionThinking(), t.sessionTotal());
    }

    /** /compact 含阻塞 LLM 调用：提交会话工作线程执行，绝不在 FX 线程跑；运行中时排队等回合结束 */
    private String dispatchCompact(SessionHandle h) {
        h.pool.submit(new Runnable() {
            @Override public void run() { h.loop.compactNow(); }
        });
        return "已请求压缩上下文（会话空闲后执行）";
    }
}
