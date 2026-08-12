package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 会话句柄：GUI 层持有的会话视图状态（后台运行实体为 AgentLoop） */
public class SessionHandle {

    public final String id;
    public final String workspaceName;
    public final Session session;
    public final AgentLoop loop;
    public final SessionController controller;
    /** 会话独占工作线程（真并行：每会话一个单线程池，互不阻塞） */
    public final ExecutorService pool;

    /** 展示标题（新建会话由 LLM 摘要生成；恢复会话来自落盘） */
    public volatile String title;
    /** 新建会话尚未生成标题（发送时先摘要 → 再跑任务） */
    public volatile boolean titlePending;
    /** 是否正在后台运行（UI 徽标/终止按钮依据） */
    public volatile boolean running;
    /** 会话已删除（send 中据此中止，防已删除会话的文件/事件复活） */
    public volatile boolean deleted;

    public SessionHandle(String id, String workspaceName, Session session,
                         AgentLoop loop, SessionController controller, String title,
                         boolean titlePending) {
        this.id = id;
        this.workspaceName = workspaceName;
        this.session = session;
        this.loop = loop;
        this.controller = controller;
        this.title = title;
        this.titlePending = titlePending;
        String idPrefix = id.length() > 8 ? id.substring(0, 8) : id;
        this.pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "minion-session-" + workspaceName + "-" + idPrefix);
            t.setDaemon(true);
            return t;
        });
    }
}
