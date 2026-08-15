package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.agent.TitleGenerator;
import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.context.ContextManager;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.ImagePart;
import com.minion.core.llm.LlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.EditTool;
import com.minion.core.tools.GlobTool;
import com.minion.core.tools.GrepTool;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.WebFetchTool;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.WriteTool;
import com.minion.core.tools.browser.BrowserDebugTool;
import com.minion.core.tools.browser.BrowserEvalTool;
import com.minion.core.tools.browser.BrowserScreenshotTool;
import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.BrowserTool;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.command.CommandDispatcher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话外壳：每会话一个 AgentLoop + 独占工作线程（真并行）；
 * 每工作空间一套上下文（Workspace/SessionStore/ConfirmGate 空间级共享，
 * ToolRegistry 每会话独立——AgentLoop 构造按名注册 TaskTool 绑定本会话 loop）；
 * 切换不打断后台运行，EventList 事件缓冲由 UI 重放。
 */
public class SessionManager {

    public interface Listener {
        void onSessionTitleChanged(SessionHandle h);
        void onSessionRunningChanged(SessionHandle h, boolean running);
        void onSessionActivated(SessionHandle h);
        void onWorkspaceChanged();
        void onError(String message);
        /** AskUserQuestion 挂起状态变化（asking=true 且 question 非空=开始挂起；asking=false=复位） */
        default void onSessionAskChanged(SessionHandle h, boolean asking, String question) { }
    }

    /** 删除工作空间时等待会话退出的总超时（秒）：AgentLoop 中断后走退出落盘路径，正常远快于此 */
    private static final long DELETE_TERMINATE_TIMEOUT_SECONDS = 5;

    private final ConfirmUi confirmUi;
    private final Config config;
    private final Path jarDir;
    private final WorkspaceManager workspaces;
    private final ModelManager models;
    private final List<Skill> allSkills;
    private final BrowserSession browserSession; // 可为 null（测试）
    private final CommandDispatcher dispatcher; // 斜杠命令本地分发（GUI 输入路径）
    private final List<Listener> listeners = new ArrayList<Listener>();

    private final Map<String, WorkspaceCtx> ctxByName = new HashMap<String, WorkspaceCtx>();
    private String currentWorkspaceName;
    private SessionHandle currentSession;

    /** 每工作空间上下文（空间级共享对象；工具注册与工作线程下沉到每会话）。
     *  name/store 可变：工作空间重命名时同步（store 目录已迁移，须重建指向新目录，防旧路径复活）。 */
    private static class WorkspaceCtx {
        String name;
        final Workspace workspace;
        SessionStore store;
        final ConfirmGate confirmGate;
        final String skillsDir;
        final List<SessionHandle> sessions = new ArrayList<SessionHandle>();

        WorkspaceCtx(String name, Workspace workspace, SessionStore store,
                     ConfirmGate confirmGate, String skillsDir) {
            this.name = name;
            this.workspace = workspace;
            this.store = store;
            this.confirmGate = confirmGate;
            this.skillsDir = skillsDir;
        }
    }

    public SessionManager(ConfirmUi confirmUi, Config config, Path jarDir,
                          WorkspaceManager workspaces, ModelManager models,
                          List<Skill> allSkills, BrowserSession browserSession) {
        this.confirmUi = confirmUi;
        this.config = config;
        this.jarDir = jarDir;
        this.workspaces = workspaces;
        this.models = models;
        this.allSkills = allSkills;
        this.browserSession = browserSession;
        this.dispatcher = new CommandDispatcher(allSkills);
        loadWorkspaceContexts();
        this.currentWorkspaceName = workspaces.currentName();
    }

    public WorkspaceManager workspaces() { return workspaces; }
    public ModelManager models() { return models; }

    public void addListener(Listener l) { listeners.add(l); }

    private void notifyTitleChanged(SessionHandle h) {
        for (Listener l : listeners) l.onSessionTitleChanged(h);
    }
    /** running 回调：会话空闲时顺带回收换模型遗留的旧客户端（防 okhttp 资源滞留） */
    private void notifyRunningChanged(SessionHandle h, boolean running) {
        if (!running) h.closeRetired();
        for (Listener l : listeners) l.onSessionRunningChanged(h, running);
    }
    private void notifyActivated(SessionHandle h) {
        for (Listener l : listeners) l.onSessionActivated(h);
    }
    private void notifyWorkspaceChanged() {
        for (Listener l : listeners) l.onWorkspaceChanged();
    }
    private void notifyError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }
    private void notifyAskChanged(SessionHandle h, boolean asking) {
        for (Listener l : listeners) l.onSessionAskChanged(h, asking, asking ? h.askQuestion : null);
    }

    /** 装配所有工作空间上下文（对照 Main 现有注册代码），并恢复历史会话 */
    private void loadWorkspaceContexts() {
        for (WorkspaceConfig w : workspaces.list()) {
            WorkspaceCtx ctx = buildCtx(w);
            ctxByName.put(w.workSpaceName, ctx);
            restoreSessions(ctx);
        }
    }

    /**
     * 恢复历史会话：store.list() 跳过损坏项（SessionStore 现有行为），逐 id 装载；
     * 标题取落盘 session.title，titlePending=false（恢复会话已有标题或旧格式无标题→显示占位）。
     */
    private void restoreSessions(WorkspaceCtx ctx) {
        List<SessionStore.SessionMeta> restored;
        try {
            restored = ctx.store.list();
        } catch (Exception e) {
            notifyError("恢复会话失败: " + e.getMessage());
            return;
        }
        for (SessionStore.SessionMeta meta : restored) {
            try {
                Session s = ctx.store.load(meta.id);
                ModelConfig mc = models.current();
                LlmClient llm = newLlm(mc);
                ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                        mc.keepRecentMessages, llm,
                        TokenCounter.estimate(new SystemPromptBuilder(projectMdPath(ctx.name))
                                .build(allSkills, new ArrayList<Skill>())));
                SessionController controller = new SessionController();
                controller.replayHistory(s.messages); // 历史消息灌入事件流：点击会话即可重放显示
                AgentLoop loop = new AgentLoop(llm, newRegistry(ctx),
                        new SystemPromptBuilder(projectMdPath(ctx.name)),
                        ctx.confirmGate, controller, cm, ctx.workspace, s);
                loop.setSessionStore(ctx.store); // 落盘接线：恢复后随每轮/退出兜底落盘
                loop.restoreSession(s); // 原地装载 + 半轮残留清洗 + cwd 恢复
                SessionHandle h = new SessionHandle(s.id, ctx.name, s, loop, controller,
                        s.title, false, llm);
                controller.setAskStateListener(new java.util.function.Consumer<String>() {
                    @Override public void accept(String question) {
                        h.askQuestion = question;
                        h.askPending = question != null;
                        notifyAskChanged(h, question != null);
                    }
                });
                ctx.sessions.add(h);
            } catch (Exception e) {
                notifyError("会话恢复失败（跳过）: " + e.getMessage());
            }
        }
    }

    private WorkspaceCtx buildCtx(WorkspaceConfig w) {
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        Workspace workspace = new Workspace(w.workDir);
        ConfirmGate gate = new ConfirmGate(config, confirmUi);
        return new WorkspaceCtx(w.workSpaceName, workspace,
                new SessionStore(WorkspaceManager.sessionDirFor(jarDir, w.workSpaceName)),
                gate, skillsDir);
    }

    /**
     * 每会话独立 ToolRegistry：AgentLoop 构造时按名注册 TaskTool(this)，若同空间共享
     * 单个 registry，task 工具会永远绑定最后构造的 loop（会话 A 的 task 调用事件流入会话 B）。
     * 工具对象本身无状态（构造参数 workspace/skillsDir/gate 为空间级共享对象），
     * 每次 new ToolRegistry 复制注册同样的工具即可；TaskTool 由 AgentLoop 自动注册、绑定本会话。
     */
    private ToolRegistry newRegistry(WorkspaceCtx ctx) {
        ToolRegistry registry = new ToolRegistry();
        String skillsDir = ctx.skillsDir;
        Workspace workspace = ctx.workspace;
        ConfirmGate gate = ctx.confirmGate;
        registry.register(new ReadTool(workspace, skillsDir, gate));
        registry.register(new WriteTool(workspace, skillsDir));
        registry.register(new EditTool(workspace, skillsDir));
        registry.register(new GlobTool(workspace, skillsDir, gate));
        registry.register(new GrepTool(workspace, skillsDir, gate));
        registry.register(new BashTool(workspace));
        registry.register(new WebFetchTool());
        if (browserSession != null) {
            registry.register(new BrowserTool(browserSession));
            registry.register(new BrowserEvalTool(browserSession));
            registry.register(new BrowserScreenshotTool(browserSession, workspace, skillsDir, gate));
            registry.register(new BrowserDebugTool(browserSession));
        }
        return registry;
    }

    /** 创建会话（恢复会话传 title；新建传 null → titlePending） */
    public SessionHandle createSession(String title) {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        if (ctx == null) return null; // 终审修复：deleteWorkspace 有运行中会话时 ctx 先移除、currentWorkspaceName 后台回退（≤5s 窗口），防 FX 线程 NPE
        ModelConfig mc = models.current();
        Session s = Session.create(ctx.workspace.workDir(), mc.modelName);
        s.title = title;
        LlmClient llm = newLlm(mc);
        ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                mc.keepRecentMessages, llm,
                TokenCounter.estimate(new SystemPromptBuilder(projectMdPath(currentWorkspaceName))
                        .build(allSkills, new ArrayList<Skill>())));
        SessionController controller = new SessionController();
        AgentLoop loop = new AgentLoop(llm, newRegistry(ctx),
                new SystemPromptBuilder(projectMdPath(currentWorkspaceName)),
                ctx.confirmGate, controller, cm, ctx.workspace, s);
        loop.setSessionStore(ctx.store); // 落盘接线：每轮/退出兜底落盘生效
        SessionHandle h = new SessionHandle(s.id, currentWorkspaceName, s, loop, controller,
                title, title == null, llm);
        controller.setAskStateListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String question) {
                h.askQuestion = question;
                h.askPending = question != null;
                notifyAskChanged(h, question != null);
            }
        });
        ctx.sessions.add(h);
        try {
            ctx.store.save(s); // 立即落盘（含空会话）
        } catch (Exception e) {
            notifyError("会话落盘失败: " + e.getMessage());
        }
        return h;
    }

    private String projectMdPath(String workspaceName) {
        WorkspaceConfig c = workspaces.get(workspaceName);
        if (c == null || c.projectMd == null || c.projectMd.trim().isEmpty()) {
            return "./project.md";
        }
        return c.projectMd;
    }

    /** 新建 LlmClient（模型配置工厂；GUI 弹窗切模型也用它） */
    public LlmClient newLlm(ModelConfig mc) {
        return new DeepSeekClient(mc.url, mc.apiKey, mc.modelName,
                mc.thinking, mc.reasoningEffort, mc.provider);
    }

    /**
     * 模型/参数变更 propagate：全部工作空间全部会话换新 LLM 客户端 + 压缩参数热更新。
     * 旧客户端登记待回收（close 会 cancel 运行中请求，不可立即关）；会话空闲时回收。
     */
    public void applyModelChanged() {
        ModelConfig mc = models.current();
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                LlmClient fresh = newLlm(mc);
                LlmClient old = h.llm;
                h.llm = fresh;
                h.retireLlm(old); // 换引用后登记旧客户端（guard old != llm 防误登记当前客户端）
                h.loop.setLlm(fresh); // 下轮请求生效
                ContextManager cm = h.loop.contextManager();
                if (cm != null) {
                    cm.setLlm(fresh);
                    cm.update(mc.maxContextTokens, mc.compressThreshold, mc.keepRecentMessages);
                }
            }
        }
    }

    public List<SessionHandle> sessions() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        return ctx == null ? new ArrayList<SessionHandle>() : new ArrayList<SessionHandle>(ctx.sessions);
    }

    public SessionHandle currentSession() { return currentSession; }

    public void renameSession(SessionHandle h, String newTitle) {
        h.title = newTitle;
        h.session.title = newTitle;
        persist(h);
        notifyTitleChanged(h);
    }

    public void deleteSession(SessionHandle h) {
        h.deleted = true; // 先置位：send 中据此中止，防已删除会话的文件/事件复活
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        if (h.running) stop(h);
        h.loop.shutdown();
        h.pool.shutdownNow();
        h.closeAll(); // 会话删除即释放其 LLM 客户端（当前 + 待回收，okhttp 资源）
        ctx.sessions.remove(h);
        try {
            ctx.store.delete(h.id);
        } catch (Exception e) {
            notifyError("删除会话文件失败: " + e.getMessage());
        }
        h.controller.eventList().setActive(false, null); // 移除被删会话的 active 残留
        if (currentSession == h) currentSession = null;
    }

    public void activateSession(SessionHandle h) {
        if (h.deleted) return; // 已删句柄不可再激活（防已删会话残留激活态/后续 send 落空）
        if (!currentWorkspaceName.equals(h.workspaceName)) return; // 非当前工作空间的句柄不激活
        if (currentSession == h) return; // 重复激活（页签选中/左侧点击重叠）幂等跳过，避免重放闪烁
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentSession = h;
        h.controller.eventList().setActive(true, null);
        notifyActivated(h);
    }

    /** 工作空间切换（UI 层负责换绑视图；此处切上下文与激活态） */
    public void switchWorkspace(String name) {
        if (ctxByName.get(name) == null) return;
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentWorkspaceName = name;
        currentSession = null;
        workspaces.setCurrent(name);
        notifyWorkspaceChanged();
    }

    /** 新建工作空间：配置落盘 + 建上下文（不自动切换，用户点击列表项切换）。false=名称非法或重名 */
    public boolean addWorkspace(String name, String workDir, String projectMd) {
        if (!workspaces.add(name, workDir, projectMd)) return false;
        ctxByName.put(name, buildCtx(workspaces.get(name)));
        return true;
    }

    /**
     * 重命名：配置迁移 + 会话目录迁移（WorkspaceManager.rename 内部完成）+ ctx 换键 + 当前名同步
     * + 全部会话 workspaceName 同步（否则 activateSession 守卫「非当前空间不激活」拒绝页签切换、
     * send/persist 按旧名查 ctx 落空）+ store 重建指向新目录。false=新名非法/重名
     */
    public boolean renameWorkspace(String oldName, String newName) {
        if (!workspaces.rename(oldName, newName)) return false;
        WorkspaceCtx ctx = ctxByName.remove(oldName);
        ctx.name = newName;
        ctxByName.put(newName, ctx);
        ctx.store = new SessionStore(WorkspaceManager.sessionDirFor(jarDir, newName)); // 目录已迁移，store 跟随
        for (SessionHandle h : ctx.sessions) h.workspaceName = newName;
        if (currentWorkspaceName.equals(oldName)) currentWorkspaceName = newName;
        notifyWorkspaceChanged();
        return true;
    }

    /**
     * 修改工作空间：配置落盘 + workDir 热更新（所有会话共享同一 workspace 实例，
     * setWorkDir 后下一轮工具调用即按新根守卫，无需重启）；projectMd 对新会话生效（运行中会话
     * 的 system prompt 在创建时构建，不热换）。
     */
    public void updateWorkspace(String name, String workDir, String projectMd) {
        workspaces.update(name, workDir, projectMd);
        WorkspaceCtx ctx = ctxByName.get(name);
        if (ctx != null) ctx.workspace.setWorkDir(workDir);
    }

    /**
     * 工作空间拖拽排序：转发 WorkspaceManager（不发通知——notifyWorkspaceChanged 会触发
     * MainWindow 的 clearChatPane 清空右侧聊天区，拖拽排序不应清内容；UI 侧 drop 后自行 refresh）。
     */
    public boolean moveWorkspace(String name, int newIndex) {
        return workspaces.move(name, newIndex);
    }

    /**
     * 删除工作空间：先终止该空间所有会话（置 deleted + 中断 + 关闭，等退出完成）
     * → 再删配置/目录（remove 内部递归删 session/<name>/）→ 当前名同步。
     * 顺序不可颠倒：AgentLoop 所有退出路径无条件 persistSession（createDirectories 复活目录），
     * 必须先等会话退出完再删目录。运行中会话的等待最长 DELETE_TERMINATE_TIMEOUT_SECONDS 秒；
     * 可能被 FX 线程调用（右键菜单 onAction），有运行中会话时整个终止+删除流程放后台
     * daemon 线程执行，FX 线程只发起。false=空间不存在或删最后一个被拒绝
     */
    public boolean deleteWorkspace(final String name) {
        WorkspaceCtx ctx = ctxByName.get(name);
        if (ctx == null) return false;
        if (workspaces.list().size() <= 1) return false; // 删最后一个被拒（与 remove 同判据），会话上下文不动
        boolean hasRunning = false;
        for (SessionHandle h : ctx.sessions) {
            h.deleted = true; // 先置位：send 中据此中止，防已删除会话的文件/事件复活
            if (h.running) { h.loop.interrupt(); hasRunning = true; } // 终止运行中循环（stop 语义）
            h.loop.shutdown();
            h.pool.shutdownNow();
            h.closeAll(); // 工作空间删除即释放其全部会话的 LLM 客户端（当前 + 待回收）
            h.controller.eventList().setActive(false, null); // 移除被删会话的 active 残留
        }
        ctxByName.remove(name);
        if (hasRunning) {
            // 运行中会话的 awaitTermination 可能阻塞（最长超时）：后台 daemon 线程执行，不阻塞 FX 线程
            Thread t = new Thread(new Runnable() {
                @Override public void run() { finishDeleteWorkspace(ctx, name); }
            }, "minion-ws-delete");
            t.setDaemon(true);
            t.start();
        } else {
            finishDeleteWorkspace(ctx, name); // 无运行中会话：awaitTermination 即时返回，可同步完成
        }
        return true;
    }

    /** 等待该空间所有会话退出（超时按继续，AgentLoop 有 stop 语义正常不会存活）→ 删配置/目录 → 当前名同步 */
    private void finishDeleteWorkspace(WorkspaceCtx ctx, String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DELETE_TERMINATE_TIMEOUT_SECONDS);
        for (SessionHandle h : ctx.sessions) {
            long remain = deadline - System.nanoTime();
            if (remain <= 0) break;
            try {
                h.pool.awaitTermination(remain, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!workspaces.remove(name)) return; // 理论不可达（前面已校验），防御
        if (currentWorkspaceName.equals(name)) {
            currentWorkspaceName = workspaces.currentName(); // remove 已回落 currentName
            currentSession = null;
            notifyWorkspaceChanged();
        }
    }

    /** 发送：新会话（titlePending）先本地置标题，再跑正式任务 */
    public void send(final SessionHandle h, final String text) { send(h, text, null); }

    /** 发送（带图）：图片随消息以 OpenAI 视觉 content 数组传给模型 */
    public void send(final SessionHandle h, final String text, final List<ImagePart> images) {
        if (h == null) return;
        final WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        h.pool.submit(new Runnable() {
            @Override public void run() {
                try {
                    if (h.deleted) return; // 队列积压期间被删除
                    if (h.titlePending) {
                        h.title = TitleGenerator.localTitle(text); // 本地截取，不再走 LLM 摘要
                        if (h.deleted) return; // 摘要期间被删除：不再落盘/通知
                        h.titlePending = false;
                        h.session.title = h.title;
                        persist(h);
                        notifyTitleChanged(h);
                    }
                    h.running = true;
                    notifyRunningChanged(h, true);
                    try {
                        h.loop.runUserTurn(text, images);
                    } finally {
                        if (h.deleted) {
                            // 运行中被删除：runUserTurn 退出路径已把文件写回（deleted 对 AgentLoop 不可见），
                            // 此处补删，防重启后 restore 复活
                            try { ctx.store.delete(h.id); }
                            catch (Exception e) { notifyError("删除会话文件失败: " + e.getMessage()); }
                        }
                        h.running = false;
                        if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                            h.askPending = false;
                            h.askQuestion = null;
                            notifyAskChanged(h, false);
                        }
                        notifyRunningChanged(h, false);
                    }
                } catch (Exception e) {
                    h.running = false;
                    if (h.askPending) { // 中断路径 onAskUserDone 不回调，此处兜底复位
                        h.askPending = false;
                        h.askQuestion = null;
                        notifyAskChanged(h, false);
                    }
                    notifyRunningChanged(h, false);
                    notifyError("任务执行异常: " + e.getMessage());
                }
            }
        });
    }

    public void stop(SessionHandle h) {
        if (h != null && h.running) {
            h.loop.interrupt(); // 只取消当前客户端；换模型后 in-flight 请求在旧（已退役）客户端上
            h.closeRetired();   // 一并取消旧客户端的流式请求，防「终止」失效等旧流自然结束（数分钟）
        }
    }

    /** 运行中补充：入 AgentLoop 挂起队列 + 发聊天标识事件（UI 事件仅在点击时发一次，注入不重发） */
    public void sendSupplement(final SessionHandle h, final String text) { sendSupplement(h, text, null); }

    /** 运行中补充（带图）：图片占位并入聊天标识事件（聊天区不渲染图片本体） */
    public void sendSupplement(final SessionHandle h, final String text, final List<ImagePart> images) {
        if (h == null || text == null || text.trim().isEmpty()) return;
        h.loop.offerSupplement(text, images);
        h.controller.onUserSupplement(ImagePart.displayText(images, text));
    }

    /** 回答 AskUserQuestion：完成挂起的等待（未挂起时忽略）；回答作为工具结果回传继续本轮 */
    public void sendAnswer(final SessionHandle h, final String text) {
        if (h == null || !h.running) return;
        h.loop.answerAskUser(text);
    }

    /** 全部技能（补全弹层/命令分发共用；启动时扫描） */
    public List<Skill> skills() { return allSkills; }

    /** 当前工作空间 workDir（文件补全遍历根；无当前空间返回 null） */
    public String currentWorkspaceDir() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        return ctx == null ? null : ctx.workspace.workDir();
    }

    /** 斜杠命令本地分发：命中 → 聊天区回显命令 + 系统行结果（不入 LLM 历史）；未命中 → 按普通消息发送 */
    public void dispatchCommand(SessionHandle h, String text) {
        String result = dispatcher.dispatch(h, text);
        if (result == null) { send(h, text); return; }
        h.controller.onUserMessage(text); // 仅展示回显，不注入 LLM 历史
        h.controller.onSystem(result);
    }

    private void persist(SessionHandle h) {
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        try {
            ctx.store.save(h.session);
        } catch (Exception e) {
            notifyError("会话落盘失败: " + e.getMessage());
        }
    }

    /** 是否有会话正在后台运行（窗口关闭确认用，跨工作空间） */
    public boolean hasRunning() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (h.running) return true;
            }
        }
        return false;
    }

    /** 关闭：终止所有运行中会话（窗口关闭时调用） */
    public void shutdown() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (h.running) h.loop.interrupt();
                h.loop.shutdown();
                h.pool.shutdownNow();
                h.closeAll(); // 关 okhttp 连接池/线程（当前 + 待回收），防 JVM 残留
            }
        }
    }
}
