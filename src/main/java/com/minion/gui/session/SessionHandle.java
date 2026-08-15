package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 会话句柄：GUI 层持有的会话视图状态（后台运行实体为 AgentLoop） */
public class SessionHandle {

    public final String id;
    /** 所属工作空间名：工作空间重命名时同步更新（非 final，否则 activate/send/persist 按旧名查 ctx 全部失效） */
    public String workspaceName;
    public final Session session;
    public final AgentLoop loop;
    public final SessionController controller;
    /** 会话独占工作线程（真并行：每会话一个单线程池，互不阻塞） */
    public final ExecutorService pool;
    /** 会话独享的 LLM 客户端（换模型时换新实例；删除/退出时 close 释放 okhttp 资源） */
    public volatile LlmClient llm; // 由 final 改 volatile：模型热更新允许换实例

    /** 已退役（换模型替换下来）的客户端：close 会 cancel 运行中请求，运行中不能立即关，登记待回收 */
    private final List<LlmClient> retiredLlms = new ArrayList<LlmClient>();

    /** 登记待回收客户端 */
    public synchronized void retireLlm(LlmClient old) {
        if (old != null && old != llm) retiredLlms.add(old);
    }

    /** 关闭全部客户端（当前 + 待回收）：会话删除/工作空间删除/应用退出时调用 */
    public synchronized void closeAll() {
        llm.close();
        for (LlmClient c : retiredLlms) c.close();
        retiredLlms.clear();
    }

    /** 会话空闲（running→false）时回收换模型遗留的旧客户端 */
    public synchronized void closeRetired() {
        for (LlmClient c : retiredLlms) c.close();
        retiredLlms.clear();
    }

    /** 展示标题（新建会话由 LLM 摘要生成；恢复会话来自落盘） */
    public volatile String title;
    /** 新建会话尚未生成标题（发送时先摘要 → 再跑任务） */
    public volatile boolean titlePending;
    /** 是否正在后台运行（UI 徽标/终止按钮依据） */
    public volatile boolean running;
    /** 会话已删除（send 中据此中止，防已删除会话的文件/事件复活） */
    public volatile boolean deleted;
    /** AskUserQuestion 挂起中（输入框进入回答模式；回答/中断/回合结束复位） */
    public volatile boolean askPending;
    /** AskUserQuestion 问题文本（回答模式占位提示用） */
    public volatile String askQuestion;

    public SessionHandle(String id, String workspaceName, Session session,
                         AgentLoop loop, SessionController controller, String title,
                         boolean titlePending, LlmClient llm) {
        this.id = id;
        this.workspaceName = workspaceName;
        this.session = session;
        this.loop = loop;
        this.controller = controller;
        this.title = title;
        this.titlePending = titlePending;
        this.llm = llm;
        String idPrefix = id.length() > 8 ? id.substring(0, 8) : id;
        this.pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "minion-session-" + workspaceName + "-" + idPrefix);
            t.setDaemon(true);
            return t;
        });
    }
}
