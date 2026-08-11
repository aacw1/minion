package com.minion.cli;

import com.google.gson.Gson;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.RecordingUi;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class CommandDispatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AgentLoop loop;
    private CommandDispatcher dispatcher;

    @org.junit.Before
    public void setup() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        loop = new AgentLoop(config, llm, registry,
                new com.minion.core.agent.SystemPromptBuilder(config), confirm, ui);
        dispatcher = new CommandDispatcher(loop, config,
                new SessionStore(tmp.getRoot().toPath().resolve("sessions")), null, ui);
    }

    @Test
    public void dispatch_knownCommands() {
        assertEquals(CommandDispatcher.Command.HELP, dispatcher.dispatch("/help"));
        assertEquals(CommandDispatcher.Command.EXIT, dispatcher.dispatch("/quit"));
        assertEquals(CommandDispatcher.Command.EXIT, dispatcher.dispatch("/exit"));
        assertEquals(CommandDispatcher.Command.SKILLS, dispatcher.dispatch("/skills"));
        assertEquals(CommandDispatcher.Command.COMPACT, dispatcher.dispatch("/compact"));
        // 简报规格：/tokens、/model 返回展示字符串（非枚举），tokens_returnsFormattedStats 亦验证
        assertTrue(dispatcher.dispatch("/tokens") instanceof String);
        assertEquals(CommandDispatcher.Command.CLEAR, dispatcher.dispatch("/clear"));
        assertTrue(dispatcher.dispatch("/model") instanceof String);
        assertEquals(CommandDispatcher.Command.RESUME, dispatcher.dispatch("/resume"));
    }

    @Test
    public void dispatchNewAndDelete() {
        assertEquals(CommandDispatcher.Command.NEW, dispatcher.dispatch("/new"));
        assertEquals(CommandDispatcher.Command.DELETE, dispatcher.dispatch("/delete"));
    }

    @Test
    public void dispatch_unknownAndPlainText() {
        assertNull(dispatcher.dispatch("/nope"));
        assertNull(dispatcher.dispatch("普通消息"));
    }

    @Test
    public void skill_loadAddsToLoop() {
        Skill skill = new Skill("review", "审查技能", "审查指令", "SKILL.md");
        dispatcher.dispatchSkill(skill);
        assertEquals(1, loop.loadedSkills().size());
        assertEquals("review", loop.loadedSkills().get(0).name);
    }

    @Test
    public void tokens_returnsFormattedStats() {
        Object r = dispatcher.dispatch("/tokens");
        assertTrue(r instanceof String);
        assertTrue(((String) r).contains("in"));
    }
}
