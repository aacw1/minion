package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.RecordingUi;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SkillToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeLlmClient llm;
    private ToolRegistry registry;
    private AgentLoop loop;
    private final Skill SKILL = new Skill("brainstorming", "头脑风暴技能", "正文：先澄清需求再设计", "SKILL.md");

    @Before
    public void setup() {
        Config config = Config.load(tmp.getRoot().toPath());
        llm = new FakeLlmClient();
        registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.example.ExampleTool());
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, new RecordingUi(), null,
                new Workspace(tmp.getRoot().getPath()),
                Session.create(tmp.getRoot().getPath(), "test-model"));
        loop.setAllSkills(Collections.singletonList(SKILL));
    }

    private SkillTool newTool() { return new SkillTool(loop); }

    private ToolResult exec(SkillTool tool, String json) {
        try {
            return tool.execute(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return ToolResult.error("异常: " + e.getMessage());
        }
    }

    @Test
    public void agentLoop_autoRegistersSkillTool() {
        assertEquals("Skill", registry.get("skill").name());
    }

    @Test
    public void loadsSkill_returnsSuccessAndInjectsNextTurn() {
        ToolResult r = exec(newTool(), "{\"name\":\"brainstorming\"}");
        assertTrue(r.ok);
        assertTrue(r.output.contains("brainstorming"));
        // 触发注入（runUserTurn 开头 drain）：<skill> 消息 pinned 入历史
        llm.addTurn("好的");
        loop.runUserTurn("开始");
        List<Message> msgs = loop.messages();
        assertTrue(msgs.get(0).pinned);
        assertTrue(msgs.get(0).content.contains("<skill name=\"brainstorming\">"));
        assertTrue(msgs.get(0).content.contains("正文：先澄清需求再设计"));
    }

    @Test
    public void repeatLoad_reportsAlreadyInContext_noDuplicate() {
        llm.addTurn("好的");
        loop.offerSkillLoad(SKILL); // 前置入队：runUserTurn 开头 drain 注入 <skill> 消息
        loop.runUserTurn("开始");
        SkillTool tool = newTool();
        ToolResult r = exec(tool, "{\"name\":\"brainstorming\"}");
        assertTrue(r.ok);
        assertTrue(r.output.contains("已在上下文中"));
        // 不再重复注入
        llm.addTurn("继续");
        loop.runUserTurn("继续");
        long pinnedCount = loop.messages().stream()
                .filter(m -> m.pinned && m.content.contains("<skill name=\"brainstorming\">")).count();
        assertEquals(1, pinnedCount);
    }

    @Test
    public void unknownSkill_returnsError() {
        ToolResult r = exec(newTool(), "{\"name\":\"notexist\"}");
        assertFalse(r.ok);
        assertTrue(r.output.contains("未找到技能"));
    }

    @Test
    public void missingName_returnsError() {
        ToolResult r = exec(newTool(), "{}");
        assertFalse(r.ok);
        assertTrue(r.output.contains("name"));
    }

    @Test
    public void emptySkills_returnsError() {
        loop.setAllSkills(Collections.<Skill>emptyList());
        ToolResult r = exec(newTool(), "{\"name\":\"brainstorming\"}");
        assertFalse(r.ok);
        assertTrue(r.output.contains("技能目录"));
    }
}
