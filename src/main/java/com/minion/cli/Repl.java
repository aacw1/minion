package com.minion.cli;

import com.minion.core.agent.AgentLoop;
import com.minion.core.config.Config;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.LlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;
import com.minion.core.util.ConsoleIo;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.List;

/** 交互式 REPL：JLine 行编辑、补全、Ctrl+C 中断、命令分发、统计行 */
public class Repl {

    private final Config config;
    private final LlmClient llm;
    private final AgentLoop loop;
    private final CommandDispatcher dispatcher;
    private final SkillManager skillManager;
    private final SessionStore store;
    private final ConfirmReader confirmReader;
    /** JLine 行编辑器：主循环、/resume、确认交互共用同一实例，避免双输入源争读控制台 */
    private LineReader reader;
    private volatile boolean exitRequested = false;

    public Repl(Config config, LlmClient llm, AgentLoop loop,
                CommandDispatcher dispatcher, SkillManager skillManager, SessionStore store,
                ConfirmReader confirmReader) {
        this.config = config;
        this.llm = llm;
        this.loop = loop;
        this.dispatcher = dispatcher;
        this.skillManager = skillManager;
        this.store = store;
        this.confirmReader = confirmReader;
    }

    public static boolean isCommand(String input) {
        if (input == null || input.isEmpty() || input.equals("/")) return false;
        return input.trim().startsWith("/");
    }

    public void start() throws Exception {
        Terminal terminal = ConsoleIo.buildTerminal();
        List<String> completions = new ArrayList<String>();
        completions.addAll(java.util.Arrays.asList(
                "/help", "/exit", "/quit", "/skills", "/skill", "/resume",
                "/compact", "/tokens", "/clear", "/model"));
        if (skillManager != null) {
            for (Skill s : skillManager.scan()) completions.add("/skill " + s.name);
        }
        Completer completer = new StringsCompleter(completions);
        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .build();
        // 全应用唯一 LineReader：确认交互与主循环共用，避免双终端后台线程竞争抢读 System.in
        // （按键会被随机错分，表现为确认无响应、按键堆积后突然刷屏）
        confirmReader.setLineReader(reader);

        terminal.handle(Terminal.Signal.INT, sig -> {
            if (exitRequested) {
                System.out.println("\n再见");
                System.exit(0);
            }
            exitRequested = true;
            loop.interrupt();
            System.out.println("\n(已请求中断，按 Ctrl+C 再次退出)");
        });

        renderer().setEchoUser(false); // JLine 已回显用户输入，避免二次打印
        System.out.println(renderer().wrapBanner(StartupBanner.format(config)));
        System.out.println(renderer().wrapBanner("minion — 代码开发助手  (输入 /help 查看命令)"));
        System.out.println(renderer().wrapBanner("输入 /skills 查看所有技能，/skill <技能名> 加载技能"));
        printResumeHint();

        while (!exitRequested) {
            String input;
            try {
                input = reader.readLine(Renderer.PROMPT);
            } catch (org.jline.reader.EndOfFileException e) {
                break;
            } catch (org.jline.reader.UserInterruptException e) {
                continue;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            if (isCommand(trimmed)) {
                handleCommand(trimmed);
            } else {
                long start = System.currentTimeMillis();
                loop.runUserTurn(trimmed);
                long elapsed = System.currentTimeMillis() - start;
                int currentCtx = loop.contextManager() != null
                        ? loop.contextManager().estimate(loop.messages())
                        : TokenCounter.estimateMessages(loop.messages());
                String stats = StatsLine.format(loop.usage(), elapsed, currentCtx, config.maxContextTokens());
                renderer().printlnStats(stats);
            }
        }
        try {
            store.save(loop.session());
        } catch (Exception e) {
            System.out.println("退出保存失败: " + e.getMessage());
        }
        System.out.println("再见");
    }

    private void handleCommand(String input) {
        Object r = dispatcher.dispatch(input);
        if (r instanceof CommandDispatcher.Command) {
            switch ((CommandDispatcher.Command) r) {
                case HELP:
                    System.out.println(helpText());
                    break;
                case EXIT:
                    exitRequested = true;
                    break;
                case SKILLS:
                    printSkills();
                    break;
                case RESUME:
                    resumeFlow();
                    break;
                case COMPACT:
                    break; // dispatch 内已执行
                case CLEAR:
                    loop.messages().clear();
                    System.out.println("已清空当前上下文");
                    break;
                default:
                    break;
            }
        } else if (r instanceof String) {
            System.out.println(r);
        } else {
            System.out.println("未知命令: " + input.trim() + "（/help 查看命令）");
        }
    }

    private void printSkills() {
        if (skillManager == null) {
            System.out.println("技能系统未启用");
            return;
        }
        List<Skill> skills = skillManager.scan();
        if (skills.isEmpty()) {
            System.out.println("没有发现技能（技能目录: " + config.skillsDir() + "）");
            return;
        }
        System.out.println("可用技能:");
        for (Skill s : skills) {
            String loaded = loop.loadedSkills().stream()
                    .anyMatch(x -> x.name.equals(s.name)) ? " [已加载]" : "";
            System.out.println("  /skill " + s.name + " — " + s.description + loaded);
        }
    }

    private void resumeFlow() {
        try {
            List<SessionStore.SessionMeta> metas = store.list();
            if (metas.isEmpty()) {
                System.out.println("没有历史会话");
                return;
            }
            System.out.println("历史会话:");
            for (int i = 0; i < metas.size(); i++) {
                System.out.println("  [" + (i + 1) + "] " + metas.get(i).createdAt
                        + " — " + metas.get(i).preview);
            }
            System.out.print("选择会话编号（回车取消）: ");
            String line;
            try {
                line = reader.readLine();
            } catch (org.jline.reader.UserInterruptException e) {
                return; // Ctrl+C 取消选择
            } catch (org.jline.reader.EndOfFileException e) {
                return; // EOF 视为取消
            }
            if (line == null || line.trim().isEmpty()) return;
            int idx = Integer.parseInt(line.trim()) - 1;
            if (idx < 0 || idx >= metas.size()) {
                System.out.println("无效编号");
                return;
            }
            resumeSession(metas.get(idx).id);
        } catch (Exception e) {
            System.out.println("恢复失败: " + e.getMessage());
        }
    }

    private void resumeSession(String id) throws Exception {
        com.minion.core.agent.Session s = store.load(id);
        loop.restoreSession(s);
        System.out.println("已恢复会话 " + s.createdAt + "（" + s.messages.size() + " 条消息）");
    }

    private void printResumeHint() {
        try {
            if (store != null && store.latest() != null) {
                System.out.println("(检测到上次会话，输入 /resume 恢复)");
            }
        } catch (Exception ignored) { }
    }

    static String helpText() {
        return "命令:\n"
                + "  /help         帮助\n"
                + "  /exit /quit   退出并保存会话\n"
                + "  /skills       列出技能\n"
                + "  /skill <名>   加载技能\n"
                + "  /resume       恢复历史会话\n"
                + "  /compact      立即压缩上下文\n"
                + "  /tokens       会话 token 统计\n"
                + "  /clear        清空当前上下文（会话文件保留）\n"
                + "  /model        模型配置概览\n"
                + "其他输入将作为消息发给模型。Ctrl+C 中断当前任务，再按退出。";
    }

    private Renderer renderer() { return (Renderer) loop.ui(); }
}
