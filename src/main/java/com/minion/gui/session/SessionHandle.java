package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;

/** 会话句柄：GUI 层持有的会话视图状态（后台运行实体为 AgentLoop） */
public class SessionHandle {

    public final String id;
    public final String workspaceName;
    public final Session session;
    public final AgentLoop loop;
    public final SessionController controller;

    /** 展示标题（新建会话由 LLM 摘要生成；恢复会话来自落盘） */
    public volatile String title;
    /** 新建会话尚未生成标题（发送时先摘要 → 再跑任务） */
    public volatile boolean titlePending;
    /** 是否正在后台运行（UI 徽标/终止按钮依据） */
    public volatile boolean running;

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
    }
}
