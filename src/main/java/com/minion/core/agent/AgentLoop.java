package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.context.ContextManager;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.llm.UsageTracker;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.confirm.ConfirmGate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 主 agent 循环：请求 → 工具执行 → 回传，直到模型不再调用工具。 */
public class AgentLoop {

    public static final int DEFAULT_ROUND_LIMIT = 1000;

    private volatile LlmClient llm;
    private final ToolRegistry registry;
    private final SystemPromptBuilder promptBuilder;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    private ContextManager contextManager; // null = 不启用压缩；final 移除
    private final Workspace workspace;
    private final Session session;

    /** 可选：会话自动落盘（Task 18）。null = 不保存 */
    private SessionStore store;

    private volatile boolean interrupted = false;
    private List<Skill> allSkills = new ArrayList<Skill>();
    private List<Skill> loadedSkills = new ArrayList<Skill>();
    private java.util.function.Function<JsonObject, String> subAgentRunner; // Task 15 注入

    public int roundLimit = DEFAULT_ROUND_LIMIT;
    /** 连续工具失败止损阈值：达到后注入提醒让模型停止尝试并请求用户补充信息 */
    private static final int STUCK_THRESHOLD = 30;
    /** 连续失败工具计数（成功即清零；注入提醒后重置） */
    private int consecutiveToolErrors = 0;
    public int threads = 4;
    private final ExecutorService pool;
    /** ask_user 工具实例（构造注册；answerAskUser 经其送达回答） */
    private final com.minion.core.tools.AskUserTool askUserTool;
    /** 进行中的工具 future（供 interrupt() 取消） */
    private final List<Future<ToolResult>> inFlight = new ArrayList<Future<ToolResult>>();

    public AgentLoop(LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui,
                     ContextManager contextManager, Workspace workspace, Session session) {
        this.llm = llm;
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.contextManager = contextManager;
        this.workspace = workspace;
        this.session = session;
        // daemon 线程：main() 返回后 JVM 可正常退出（T21 REPL）
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "minion-tools");
            t.setDaemon(true);
            return t;
        });
        // T15：构造末尾自动注册 task 工具并注入默认子 agent 执行器
        registry.register(new com.minion.core.tools.TaskTool(this));
        // 终审修复：TodoWriteTool 按会话自动注册（构造捕获 session.todos 实例引用，
        // restoreSession/startNewSession 原地装载保证引用持续有效——与旧 Main 接线语义一致；
        // 每会话独立 registry 下模型可见 todo 工具，此前仅 TaskTool 自动注册导致 TodoWrite 静默丢失）
        registry.register(new com.minion.core.tools.TodoWriteTool(session.todos));
        this.askUserTool = new com.minion.core.tools.AskUserTool(ui);
        registry.register(askUserTool);
        setSubAgentRunner(args -> {
            String desc = args.has("description") ? args.get("description").getAsString() : "无描述";
            ui.onSubAgentStart(desc);
            return new SubAgentLoop(buildSystemPrompt(), desc, workspace.workDir(),
                    llm, registry, confirmGate, ui).run();
        });
    }

    /** 运行时切换模型（GUI 弹窗切换模型时调用；下轮请求生效） */
    public void setLlm(LlmClient llm) { this.llm = llm; }

    /** 替换上下文管理器（模型参数热更新时用于换新实例；现有实例变更参数用 contextManager().update） */
    public void setContextManager(ContextManager cm) { this.contextManager = cm; }

    public Session session() { return session; }
    public List<Message> messages() { return session.messages; }
    public UsageTracker usage() { return session.usage; }
    public List<Skill> allSkills() { return allSkills; }
    public void setAllSkills(List<Skill> skills) { this.allSkills = skills; }
    public List<Skill> loadedSkills() { return loadedSkills; }
    public void loadSkill(Skill skill) {
        // 按 name 判重：重复加载同一技能会重复注入系统提示词（token 浪费 + 指令歧义）
        for (Skill loaded : loadedSkills) {
            if (loaded.name.equals(skill.name)) return;
        }
        loadedSkills.add(skill);
    }

    /** 启用会话自动落盘（Task 18） */
    public void setSessionStore(SessionStore store) { this.store = store; }

    /** 会话落盘（失败不阻断主流程，仅告警） */
    private void saveSession() {
        if (store != null) {
            try { store.save(session); }
            catch (Exception e) { ui.onWarning("会话落盘失败: " + e.getMessage()); }
        }
    }

    /**
     * 落盘当前会话:先快照当前工作区 cwd 到会话再保存。
     * 此前会话文件 cwd 恒为 null,/resume 后 cd 跨会话持久化静默失效;
     * 所有保存入口(自动落盘/退出保存//new 预保存)统一走本方法。
     */
    public void persistSession() {
        session.cwd = workspace.cwd().toString();
        saveSession();
    }

    /** 当前系统提示（含已加载技能），子 agent 复用 */
    public String buildSystemPrompt() {
        return promptBuilder.build(allSkills, loadedSkills);
    }

    public void setSubAgentRunner(java.util.function.Function<JsonObject, String> runner) {
        this.subAgentRunner = runner;
    }

    /** 回答 ask_user（SessionManager.sendAnswer 转发）；无挂起时忽略 */
    public boolean answerAskUser(String answer) {
        return askUserTool.complete(answer);
    }

    public void interrupt() {
        interrupted = true;
        llm.cancel(); // 中断进行中的流式请求
        List<Future<ToolResult>> cancelThese;
        synchronized (inFlight) {
            cancelThese = new ArrayList<Future<ToolResult>>(inFlight);
        }
        for (Future<ToolResult> f : cancelThese) {
            f.cancel(true); // 中断执行中的工具，避免等待全部 in-flight 完成
        }
    }

    /** 运行中补充：入挂起队列（随会话落盘），检查点或下次发送时入历史 */
    public void offerSupplement(String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (session.pendingSupplements) {
            session.pendingSupplements.add(text);
        }
    }

    /** 挂起补充全部入历史并清空队列（UI 事件在点击时已发，此处不再发） */
    private void drainSupplements() {
        List<String> drain;
        synchronized (session.pendingSupplements) {
            if (session.pendingSupplements.isEmpty()) return;
            drain = new ArrayList<String>(session.pendingSupplements);
            session.pendingSupplements.clear();
        }
        for (String s : drain) session.messages.add(Message.userSupplement(s));
    }

    /** 关闭工具执行池（会话删除/应用退出时调用；daemon 线程，shutdownNow 不等任务完成） */
    public void shutdown() {
        pool.shutdownNow();
    }

    public void compactNow() {
        if (contextManager == null) {
            ui.onWarning("未启用上下文压缩");
            return;
        }
        int before = session.messages.size();
        session.messages = contextManager.compress(session.messages);
        if (session.messages.size() < before) {
            ui.onWarning("已压缩上下文（历史摘要已置前）");
        } else {
            ui.onWarning("暂无可压缩内容");
        }
    }

    /** REPL 统计用：上下文估算 */
    public ContextManager contextManager() { return contextManager; }

    /** REPL 渲染用 */
    public AgentUi ui() { return ui; }

    /** 恢复历史会话（Task 21 /resume、-r）：消息引用直接复用；
     *  todo/usage 必须原地装载而非换新实例：Main 注册 TodoWriteTool 时捕获的是
     *  session.todos 实例引用，换实例会让工具继续写已废弃清单（任务状态丢失，同 /new 修复）。 */
    public void restoreSession(Session s) {
        session.messages = s.messages;
        session.id = s.id;
        session.createdAt = s.createdAt;
        session.workDir = s.workDir;
        session.modelName = s.modelName;
        session.title = s.title;
        scrubHalfTurn(); // 恢复历史同样清洗半轮残留（外部/旧格式文件可能含残缺 toolCalls）
        if (s.todos != null) session.todos.replace(s.todos.items); // 原地装载（replace 内部 clear+addAll）
        if (s.usage != null) session.usage.restore(s.usage);
        workspace.restore(s.cwd);
        // 挂起补充随会话恢复（旧文件缺字段时 Gson 初始化器已兜底，此处再防御一次）
        session.pendingSupplements = s.pendingSupplements != null
                ? s.pendingSupplements : new ArrayList<String>();
    }

    /** /new:清空当前会话内容并回到工作区根。
     *  todo/usage 必须原地清空而非换新实例：Main 注册 TodoWriteTool 时捕获的是 session.todos
     *  的实例引用，换新实例会让工具继续写已废弃的空清单（任务状态丢失）。
     *  id/createdAt 必须重新生成：旧 id 会话已随 /new 落盘，沿用旧 id 会让新会话的
     *  自动落盘覆盖上一个会话文件。 */
    public void startNewSession() {
        session.messages.clear();
        session.pendingSupplements.clear();
        session.todos.clear();
        session.usage.reset();
        session.regenerateId();
        workspace.resetCwd();
    }

    /**
     * 半轮残留清洗：最近一条 assistant 消息带 toolCalls 但后续 TOOL 结果不完整（工具阶段中断/损坏历史），
     * 剥离其 toolCalls；残缺的 tool 结果一并移除；纯工具调用空壳消息整条移除。
     * 否则下轮请求发出「assistant 含 tool_calls 无对应 tool 结果」→ API 400 且非重试。
     * 空壳判定只看 content：仅思考无正文的 assistant 同样必须移除（DeepSeek 思考模式硬性要求
     * assistant 消息带 content 或 tool_calls，仅 reasoning_content 回传会 400）。
     */
    private void scrubHalfTurn() {
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Message m = session.messages.get(i);
            if (m.role != Message.Role.ASSISTANT) continue;
            int need = m.toolCalls == null ? 0 : m.toolCalls.size();
            int have = 0;
            for (int j = i + 1; j < session.messages.size(); j++) {
                if (session.messages.get(j).role == Message.Role.TOOL) have++;
            }
            if (need > 0 && have < need) {
                m.toolCalls = null;
                // 其后不完整的 tool 结果与剥离后的消息不对应，一并移除
                while (session.messages.size() > i + 1) {
                    session.messages.remove(session.messages.size() - 1);
                }
            }
            // 空壳 assistant 整条移除（无正文且无工具调用——含仅思考消息与旧格式残留），避免空消息进请求
            if (m.content == null && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                session.messages.remove(i);
            }
            break; // 只需检查最近一条 assistant
        }
    }

    public void runUserTurn(String input) {
        interrupted = false;
        long start = System.currentTimeMillis(); // 统计行：轮次耗时
        // 上次回合遗留的挂起补充先入历史（模型提问自然收尾/中断遗留），与本次输入拼接发送
        drainSupplements();
        ui.onUserMessage(input);
        session.messages.add(Message.user(input));
        int rounds = 0;
        int retries = 0;
        try {
            while (!interrupted) {
                if (rounds >= roundLimit) {
                    ui.onWarning("达到工具轮数上限(" + roundLimit + ")，已停止本轮");
                    break;
                }
                if (contextManager != null && contextManager.shouldCompress(session.messages)) {
                    int before = session.messages.size();
                    session.messages = contextManager.compress(session.messages);
                    if (session.messages.size() < before) {
                        int pct = (int) (contextManager.estimate(session.messages) * 100
                                / contextManager.maxTokens());
                        ui.onWarning("上下文已达 " + pct + "%，已自动压缩历史（技能不受影响）");
                    }
                }
                String system = promptBuilder.build(allSkills, loadedSkills);
                List<Message> request = new ArrayList<Message>();
                request.add(Message.system(system));
                request.addAll(session.messages);

                final List<ToolCall>[] toolCalls = new List[1];
                final Usage[] usage = new Usage[1];
                final String[] finish = new String[1];
                final StringBuilder content = new StringBuilder();
                final StringBuilder thinking = new StringBuilder();
                try {
                    llm.streamChat(request, registry.schemas(), new com.minion.core.llm.StreamHandler() {
                        @Override
                        public void onThinking(String delta) {
                            thinking.append(delta);
                            ui.onThinking(delta);
                        }
                        @Override
                        public void onContent(String delta) {
                            content.append(delta);
                            ui.onContent(delta);
                        }
                        @Override
                        public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                            finish[0] = finishReason;
                            usage[0] = u;
                            toolCalls[0] = tcs;
                        }
                        @Override
                        public void onError(LlmException e) {
                            finish[0] = "error";
                            ui.onError(e.getMessage());
                        }
                    });
                } catch (LlmException e) {
                    if (interrupted) {
                        // 用户主动中断（如 DeepSeekClient.cancel → Canceled）：不重试不打警告，
                        // 已收到的流式内容补入历史（不含 toolCalls——切断的 tool_calls 流不可信，回传会 400）
                        appendPartialAssistant(content, thinking);
                        break;
                    }
                    if (e.retryable && retries < 1) {
                        retries++;
                        ui.onWarning("请求失败（" + e.getMessage() + "），自动重试 1 次");
                        // 退避：429 限流 2s，其余（网络/超时）0.5s；立即重试 429 几乎必然再 429
                        Thread.sleep(e.type == LlmException.Type.RATE_LIMIT ? 2000 : 500);
                        continue; // 消息未变，直接重发本轮
                    }
                    ui.onError(e.getMessage());
                    break;
                }

                if (usage[0] != null) session.usage.record(usage[0]);
                if ("error".equals(finish[0])) break;

                // assistant 回复（含思考与工具调用）入会话历史——reasoningContent 回传硬性要求；
                // 无正文且无工具调用的空回复不入历史（仅思考消息回传会 400）
                if (content.length() > 0
                        || (toolCalls[0] != null && !toolCalls[0].isEmpty())) {
                    Message assistantMsg = Message.assistant(
                            content.length() == 0 ? null : content.toString());
                    assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
                    assistantMsg.toolCalls = toolCalls[0];
                    session.messages.add(assistantMsg);
                }

                if (interrupted) break;

                if (toolCalls[0] == null || toolCalls[0].isEmpty()
                        || !"tool_calls".equals(finish[0])) {
                    break;
                }
                rounds++;

                List<ToolCall> calls = toolCalls[0];
                List<Future<ToolResult>> futures = new ArrayList<Future<ToolResult>>();
                for (ToolCall call : calls) {
                    if (interrupted) break; // 提交间隙的中断兜底（T14 IM-2 残留 race）
                    futures.add(pool.submit(() -> runOneTool(call)));
                }
                synchronized (inFlight) {
                    inFlight.addAll(futures);
                }
                try {
                    // 循环上界用 futures.size()：提交阶段被打断时未提交的调用没有对应 future
                    for (int i = 0; i < futures.size(); i++) {
                        ToolResult result;
                        try {
                            result = futures.get(i).get();
                        } catch (ExecutionException e) {
                            result = ToolResult.error("工具执行异常: " + e.getMessage());
                        } catch (CancellationException e) {
                            break; // 已被 interrupt() 取消，本轮剩余工具结果丢弃
                        }
                        if (result == null) result = ToolResult.error("工具执行失败");
                        if (result.ok) {
                            consecutiveToolErrors = 0;
                        } else {
                            consecutiveToolErrors++;
                        }
                        session.messages.add(Message.toolResult(
                                calls.get(i).id, calls.get(i).name, result.output));
                        ui.onToolResult(calls.get(i).name, result);
                    }
                } finally {
                    synchronized (inFlight) {
                        inFlight.clear();
                    }
                }
                // 运行中补充注入检查点：工具结果全部入历史后、下一轮请求前；
                // ask_user 挂起时补充等回答的 TOOL 消息入历史后同请求发出；
                // interrupted 不注入——半轮 tool_call 未配对时插入 user 消息会破坏契约（400）
                if (!interrupted) drainSupplements();
                // 卡住止损：连续失败达阈值时注入系统提醒（user 消息而非 system——
                // OpenAI 兼容 API 只接受首条 system，插在对话中间会 400），
                // 模型下轮应输出提问文本而非再调工具；注入后计数重置，roundLimit 为最外层兜底
                if (consecutiveToolErrors >= STUCK_THRESHOLD) {
                    String hint = "[系统提醒] 你已连续 " + consecutiveToolErrors
                            + " 次工具调用失败。请停止调用工具，向用户说明已尝试的方案、失败原因，"
                            + "并列出完成任务还需要用户补充的信息或需要用户选择的方案。";
                    session.messages.add(Message.user(hint));
                    ui.onWarning("工具连续失败 " + consecutiveToolErrors
                            + " 次，已提醒模型停止尝试并请求用户补充信息");
                    consecutiveToolErrors = 0;
                }
                // 工具结果已入历史，每轮落盘一次（含中断取消提前退出的情况）
                persistSession();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ui.onWarning("已中断");
        } catch (Exception e) {
            ui.onError("异常: " + e.getMessage());
        }
        if (interrupted) {
            ui.onWarning("本轮已被中断");
            scrubHalfTurn(); // 兜底：任何中断退出路径（含工具阶段）的 toolCalls 半轮残留清洗
        }
        // 所有退出路径的兜底落盘：正常结束 / 轮数上限 / 错误 / 中断 / 异常
        persistSession();
        // 每轮结束统计行（置于 scrubHalfTurn/persistSession 之后：中断路径的 ctx 估算是清洗半轮后的准确值）
        long elapsed = System.currentTimeMillis() - start;
        int currentCtx = contextManager != null
                ? contextManager.estimate(session.messages)
                : TokenCounter.estimateMessages(session.messages);
        int maxCtx = contextManager != null ? contextManager.maxTokens() : 0;
        ui.onStatsLine(StatsLine.format(session.usage, elapsed, currentCtx, maxCtx));
    }

    /** 中断时把已收到的流式内容补进历史；不含 toolCalls（切断的 tool_calls 流不可信）。
     *  正文未到达时（仅思考）不入历史：仅 reasoning_content 无 content/tool_calls 的
     *  assistant 消息回传会 400（DeepSeek 思考模式硬性要求）。 */
    private void appendPartialAssistant(StringBuilder content, StringBuilder thinking) {
        if (content.length() == 0) return;
        Message assistantMsg = Message.assistant(content.toString());
        assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
        session.messages.add(assistantMsg);
    }

    private ToolResult runOneTool(ToolCall call) throws Exception {
        try {
            Tool tool = registry.get(call.name);
            if (tool == null) {
                return ToolResult.error("未知工具: " + call.name);
            }
            JsonObject args;
            try {
                args = JsonParser.parseString(call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
            } catch (Exception e) {
                return ToolResult.error("工具参数 JSON 解析失败: " + e.getMessage()
                        + "，请检查 arguments 格式");
            }
            if (!confirmGate.check(tool, args)) {
                String detail = args.has("command") ? args.get("command").getAsString()
                        : (args.has("path") ? args.get("path").getAsString() : args.toString());
                return ToolResult.error("用户拒绝了该操作（" + call.name + " → " + detail + "），请调整方案");
            }
            ui.onToolCall(call.name, args);
            try {
                return tool.execute(args);
            } catch (Exception e) {
                return ToolResult.error("工具执行异常: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // 防御：参数类型非法等 unchecked 异常不得穿透执行线程
            return ToolResult.error("工具执行异常: " + e.getMessage());
        }
    }

    /** 派发子 agent（Task 15 由 TaskTool 调用） */
    public String runSubAgent(JsonObject args) {
        if (subAgentRunner == null) {
            return "子 agent 不可用";
        }
        return subAgentRunner.apply(args);
    }
}
