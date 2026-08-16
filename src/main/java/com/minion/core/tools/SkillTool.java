package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;

import java.util.List;

/** 技能加载工具：按名加载技能到当前会话，正文以 <skill> 用户消息注入（渐进式披露）。
 *  与 Claude Code 同名 Skill；子 agent 禁用（SubAgentLoop 剔除 schema + 同名防御）。
 *  不做强制幂等：扫描历史报告加载状态，是否重复加载由模型判断。 */
public class SkillTool implements Tool {

    private final AgentLoop loop;

    public SkillTool(AgentLoop loop) { this.loop = loop; }

    @Override
    public String name() { return "Skill"; }

    @Override
    public String description() {
        return "按名称加载技能到当前会话，技能正文将作为一条用户消息注入（立即生效）。"
                + "当任务与可用技能描述匹配时调用；同一技能不要重复加载。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "按名称加载技能");
        JsonObject props = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "技能名（与技能列表中的 name 一致）");
        props.add("name", name);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("name");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        List<Skill> all = loop.allSkills();
        if (all == null || all.isEmpty()) {
            return ToolResult.error("当前无可用的技能（未配置技能目录 skills.dir 或无技能文件）");
        }
        String name = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString() : "";
        if (name.trim().isEmpty()) {
            return ToolResult.error("缺少参数 name（技能名），请参考可用技能列表");
        }
        Skill skill = null;
        for (Skill s : all) {
            if (s.name.equalsIgnoreCase(name)) { skill = s; break; }
        }
        if (skill == null) {
            return ToolResult.error("未找到技能: " + name + "（/skills 查看可用列表）");
        }
        // 历史标记扫描：报告加载状态（不强制幂等，由模型判断是否重复加载）
        // synchronized：与主循环检查点 add 并发（ArrayList 非线程安全）
        boolean already = false;
        synchronized (loop.messages()) {
            for (Message m : loop.messages()) {
                if (m.role == Message.Role.USER && m.content != null
                        && m.content.contains("<skill name=\"" + skill.name + "\">")) {
                    already = true;
                    break;
                }
            }
        }
        if (already) {
            return ToolResult.success("技能 " + skill.name + " 的正文已在上下文中（此前已注入），"
                    + "无需重复加载；如你判断正文已失效可再次调用");
        }
        loop.offerSkillLoad(skill);
        return ToolResult.success("已加载技能 " + skill.name + "（" + skill.description + "），正文将注入当前会话");
    }
}
