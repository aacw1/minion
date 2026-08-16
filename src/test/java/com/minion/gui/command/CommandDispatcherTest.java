package com.minion.gui.command;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import com.minion.gui.session.EventList;
import com.minion.gui.session.SessionController;
import com.minion.gui.session.SessionHandle;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 斜杠命令本地分发（恢复 CLI 语义；结果永不发给 LLM） */
public class CommandDispatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private CommandDispatcher dispatcher;
    private SessionHandle h;
    private SessionController controller;

    @Before
    public void setUp() {
        List<Skill> skills = Arrays.asList(
                new Skill("brainstorming", "需求头脑风暴", "指令正文", "/skills/brainstorming/SKILL.md"),
                new Skill("writing-plans", "编写实施计划", "指令正文", "/skills/writing-plans/SKILL.md"));
        dispatcher = new CommandDispatcher(skills);
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        controller = new SessionController();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        Session s = Session.create(tmp.getRoot().getPath(), "test-model");
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, controller, null, new Workspace(tmp.getRoot().getPath()), s);
        h = new SessionHandle("sid123456789", "ws", s, loop, controller, "标题", false, llm);
    }

    @After
    public void tearDown() {
        h.pool.shutdownNow();
        h.loop.shutdown();
    }

    @Test public void plainText_returnsNull() {
        assertNull(dispatcher.dispatch(h, "帮我修个 bug"));
    }

    @Test public void help_listsCommands() {
        String r = dispatcher.dispatch(h, "/help");
        assertNotNull(r);
        assertTrue(r.contains("/skills"));
        assertTrue(r.contains("/skill"));
        assertTrue(r.contains("/compact"));
        assertTrue(r.contains("/tokens"));
    }

    @Test public void skills_listsSkillNames() {
        String r = dispatcher.dispatch(h, "/skills");
        assertTrue(r.contains("brainstorming"));
        assertTrue(r.contains("writing-plans"));
    }

    @Test public void skill_loadsIntoLoop() {
        String r = dispatcher.dispatch(h, "/skill brainstorming");
        assertTrue(r.contains("已加载技能: brainstorming"));
        // 入队待注入：系统提示词不含正文（正文以 user 消息注入，下轮请求生效）
        assertFalse(h.loop.buildSystemPrompt().contains("指令正文"));
    }

    @Test public void skill_caseInsensitive() {
        String r = dispatcher.dispatch(h, "/SKILL BRAINSTORMING");
        assertTrue(r.contains("已加载技能: brainstorming"));
    }

    @Test public void skill_missingArgShowsUsage() {
        String r = dispatcher.dispatch(h, "/skill");
        assertTrue(r.contains("用法"));
    }

    @Test public void skill_unknownName() {
        String r = dispatcher.dispatch(h, "/skill notexist");
        assertTrue(r.contains("未找到技能"));
    }

    @Test public void tokens_showsUsageStats() {
        String r = dispatcher.dispatch(h, "/tokens");
        assertTrue(r.startsWith("会话统计"));
    }

    /** /compact 在会话工作线程执行（阻塞 LLM 调用不进 FX 线程）；无压缩管理器时走 onWarning */
    @Test
    public void compact_dispatchesToPoolAndWarnsWithoutContextManager() throws Exception {
        String r = dispatcher.dispatch(h, "/compact");
        assertEquals("已请求压缩上下文（会话空闲后执行）", r);
        h.pool.shutdown();
        assertTrue(h.pool.awaitTermination(3, TimeUnit.SECONDS));
        boolean warned = false;
        for (EventList.Ev e : controller.eventList().snapshot()) {
            if (e.kind == EventList.Kind.WARNING && e.text.contains("未启用上下文压缩")) warned = true;
        }
        assertTrue("compactNow 应经 AgentUi 发出未启用提示", warned);
    }

    @Test public void unknownCommand_returnsErrorText() {
        String r = dispatcher.dispatch(h, "/nosuchcmd");
        assertTrue(r.contains("未知命令"));
    }
}
