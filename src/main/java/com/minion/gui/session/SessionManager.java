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
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final List<Listener> listeners = new ArrayList<Listener>();

    private final Map<String, WorkspaceCtx> ctxByName = new HashMap<String, WorkspaceCtx>();
    private String currentWorkspaceName;
    private SessionHandle currentSession;
    private final ExecutorService titlePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minion-title");
        t.setDaemon(true);
        return t;
    });

    /** 每工作空间上下文（空间级共享对象；工具注册与工作线程下沉到每会话） */
    private static class WorkspaceCtx {
        final String name;
        final Workspace workspace;
        final SessionStore store;
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
        loadWorkspaceContexts();
        this.currentWorkspaceName = workspaces.currentName();
    }

    public WorkspaceManager workspaces() { return workspaces; }
    public ModelManager models() { return models; }

    public void addListener(Listener l) { listeners.add(l); }

    private void notifyTitleChanged(SessionHandle h) {
        for (Listener l : listeners) l.onSessionTitleChanged(h);
    }
    private void notifyRunningChanged(SessionHandle h, boolean running) {
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
                AgentLoop loop = new AgentLoop(llm, newRegistry(ctx),
                        new SystemPromptBuilder(projectMdPath(ctx.name)),
                        ctx.confirmGate, controller, cm, ctx.workspace, s);
                loop.setSessionStore(ctx.store); // 落盘接线：恢复后随每轮/退出兜底落盘
                loop.restoreSession(s); // 原地装载 + 半轮残留清洗 + cwd 恢复
                ctx.sessions.add(new SessionHandle(s.id, ctx.name, s, loop, controller,
                        s.title, false));
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
            registry.register(new BrowserScreenshotTool(browserSession, workspace, skillsDir));
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
                title, title == null);
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

    /** 重命名：配置迁移 + 会话目录迁移（WorkspaceManager.rename 内部完成）+ ctx 换键 + 当前名同步。false=新名非法/重名 */
    public boolean renameWorkspace(String oldName, String newName) {
        if (!workspaces.rename(oldName, newName)) return false;
        WorkspaceCtx ctx = ctxByName.remove(oldName);
        ctxByName.put(newName, ctx);
        if (currentWorkspaceName.equals(oldName)) currentWorkspaceName = newName;
        notifyWorkspaceChanged();
        return true;
    }

    /**
     * 修改工作空间：仅更新配置落盘。运行中的会话持有旧 workspace 引用，
     * 热更新会连锁重建整套上下文（registry/store/loop 引用），YAGNI 不做——
     * 修改在重启后对新会话生效。
     */
    public void updateWorkspace(String name, String workDir, String projectMd) {
        workspaces.update(name, workDir, projectMd);
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

    /** 发送：新会话（titlePending）先摘要生成标题，再跑正式任务 */
    public void send(final SessionHandle h, final String text) {
        if (h == null) return;
        final WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        h.pool.submit(new Runnable() {
            @Override public void run() {
                try {
                    if (h.deleted) return; // 队列积压期间被删除
                    if (h.titlePending) {
                        h.title = generateTitle(text);
                        if (h.deleted) return; // 摘要期间被删除：不再落盘/通知
                        h.titlePending = false;
                        h.session.title = h.title;
                        persist(h);
                        notifyTitleChanged(h);
                    }
                    h.running = true;
                    notifyRunningChanged(h, true);
                    try {
                        h.loop.runUserTurn(text);
                    } finally {
                        if (h.deleted) {
                            // 运行中被删除：runUserTurn 退出路径已把文件写回（deleted 对 AgentLoop 不可见），
                            // 此处补删，防重启后 restore 复活
                            try { ctx.store.delete(h.id); }
                            catch (Exception e) { notifyError("删除会话文件失败: " + e.getMessage()); }
                        }
                        h.running = false;
                        notifyRunningChanged(h, false);
                    }
                } catch (Exception e) {
                    h.running = false;
                    notifyRunningChanged(h, false);
                    notifyError("任务执行异常: " + e.getMessage());
                }
            }
        });
    }

    /** 摘要标题：当前模型 completeChat + 10s 超时；失败回退 */
    private String generateTitle(String text) {
        ModelConfig mc = models.current();
        final LlmClient llm = newLlm(mc);
        Future<String> f = titlePool.submit(() -> {
            try {
                List<Message> msgs = new ArrayList<Message>();
                msgs.add(Message.user(text));
                return llm.completeChat(msgs, TitleGenerator.buildPrompt(text));
            } catch (Exception e) {
                return null;
            }
        });
        try {
            String raw = f.get(10, TimeUnit.SECONDS);
            // Callable 内吞 LLM 异常 → null：按失败回退（clean(null) 会退成「新会话」，失真）
            if (raw == null) return TitleGenerator.fallbackTitle(text);
            return TitleGenerator.clean(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TitleGenerator.fallbackTitle(text);
        } catch (ExecutionException e) {
            return TitleGenerator.fallbackTitle(text);
        } catch (TimeoutException e) {
            f.cancel(true);
            return TitleGenerator.fallbackTitle(text);
        }
    }

    public void stop(SessionHandle h) {
        if (h != null && h.running) h.loop.interrupt();
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
            }
        }
        titlePool.shutdownNow();
    }
}
