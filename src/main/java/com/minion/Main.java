package com.minion;

import com.minion.cli.ConfirmReader;
import com.minion.cli.CommandDispatcher;
import com.minion.cli.Renderer;
import com.minion.cli.Repl;
import com.minion.cli.StatsLine;
import com.minion.core.util.ConsoleIo;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.EditTool;
import com.minion.core.tools.GlobTool;
import com.minion.core.tools.GrepTool;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.TodoWriteTool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.WebFetchTool;
import com.minion.core.tools.WriteTool;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        ConsoleIo.install(); // 先于任何输出：System.out/err 强制 UTF-8，Windows 控制台切代码页 65001
        Config config = Config.load();
        if (config.modelKey().isEmpty() || config.modelKey().equals("sk-your-key")) {
            System.err.println("[minion] 请先编辑 jar 同目录的 config.properties，配置 model.key");
            if (args.length == 0) return; // 交互模式必须配置 key
        }

        LlmClient llm = new DeepSeekClient(config.modelUrl(), config.modelKey(),
                config.modelName(), config.thinkingEnabled(), config.reasoningEffort());
        // 确认交互不在此处建 Terminal/LineReader：REPL 启动时创建全应用唯一 reader 后注入，
        // 否则两个 DumbTerminal 的后台读取线程会竞争抢读 System.in（按键随机错分）
        ConfirmReader confirmReader = new ConfirmReader();
        ConfirmUi confirmUi = confirmReader;
        Renderer renderer = new Renderer(config.uiColor());

        ToolRegistry registry = new ToolRegistry();
        String workDir = config.workDir();
        registry.register(new ReadTool(workDir));
        registry.register(new WriteTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new GlobTool(workDir));
        registry.register(new GrepTool(workDir));
        registry.register(new BashTool(workDir));
        registry.register(new WebFetchTool());

        SkillManager skillManager = new SkillManager(config.skillsDir());
        List<Skill> skills = skillManager.scan();

        SessionStore store = new SessionStore(Paths.get(config.sessionDir()));

        // 交互模式下确认用 ConfirmReader；-c 模式全部放行（脚本化）
        ConfirmGate confirm = new ConfirmGate(config,
                args.length >= 2 && "-c".equals(args[0])
                        ? ui -> ConfirmUi.Decision.APPROVE : confirmUi);

        // systemTokens：按当前技能+项目提示实际拼出的系统提示长度估算（比 buildSystemPreview 更准）
        ContextManager ctx = new ContextManager(config.maxContextTokens(),
                config.compressThreshold(), config.keepRecentMessages(),
                llm, TokenCounter.estimate(new SystemPromptBuilder(config)
                        .build(skills, Collections.<Skill>emptyList())));

        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, renderer, ctx);
        // TodoWriteTool 接线会话内任务清单（loop 创建后注册）：todos 随会话落盘/恢复，而非独立空清单
        registry.register(new TodoWriteTool(loop.session().todos));
        loop.setAllSkills(skills);
        loop.setSessionStore(store);

        if (args.length >= 2 && "-c".equals(args[0])) {
            long start = System.currentTimeMillis();
            loop.runUserTurn(args[1]);
            long elapsed = System.currentTimeMillis() - start;
            renderer.printlnStats(StatsLine.format(loop.usage(), elapsed,
                    ctx.estimate(loop.messages()), config.maxContextTokens()));
            return;
        }

        if (args.length >= 1 && "-r".equals(args[0])) {
            try {
                com.minion.core.agent.Session latest = store.latest();
                if (latest != null) {
                    loop.restoreSession(latest);
                    System.out.println("已恢复会话 " + latest.createdAt
                            + "（" + latest.messages.size() + " 条消息）");
                } else {
                    System.out.println("没有历史会话，开始新会话");
                }
            } catch (Exception e) {
                System.out.println("恢复失败: " + e.getMessage());
            }
        }

        Repl repl = new Repl(config, llm, loop,
                new CommandDispatcher(loop, config, store, skillManager, renderer),
                skillManager, store, confirmReader);
        repl.start();
    }
}
