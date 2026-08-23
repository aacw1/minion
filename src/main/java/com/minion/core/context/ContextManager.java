package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 上下文管理：token 估算、阈值判断、链式压缩（完整回合链为单位，摘要置前） */
public class ContextManager {

    private static final String COMPRESS_SYSTEM =
            "你是 minion 的上下文压缩器。把用户提供的对话历史压缩成一段中文摘要，保留："
          + "未完成的任务与目标、已做出的关键决策及原因、使用过的工具与结果要点、"
          + "相关文件路径、代码约定、用户偏好。只输出摘要正文，不要客套，3000 字以内。";

    private static final int KEEP_MIN = 12;          // 保留区下限（条数）
    private static final double KEEP_RATIO = 0.5;    // 保留区占比目标

    // FX 线程 update/setLlm 写入、会话工作线程 shouldCompress/compress 读取——volatile 防 JMM 数据竞争
    private volatile int maxContextTokens;      // final 移除
    private volatile double threshold;
    private volatile int keepRecent;
    private volatile LlmClient llm;
    private final int systemTokens;    // system 提示词 token 估算在构造时固定，保持不变

    public ContextManager(int maxContextTokens, double threshold, int keepRecent,
                          LlmClient llm, int systemTokens) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
        this.llm = llm;
        this.systemTokens = systemTokens;
    }

    /** 模型参数热更新（设置窗修改后调用；运行时生效于下一轮压缩判断） */
    public void update(int maxContextTokens, double threshold, int keepRecent) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    /** 换 LLM 客户端（模型切换后调用；压缩请求走新客户端） */
    public void setLlm(LlmClient llm) { this.llm = llm; }

    /** 上下文窗口上限（AgentLoop 压缩百分比计算用） */
    public int maxTokens() { return maxContextTokens; }

    public int estimate(List<Message> messages) {
        return systemTokens + TokenCounter.estimateMessages(messages);
    }

    public boolean shouldCompress(List<Message> messages) {
        return estimate(messages) >= maxContextTokens * threshold;
    }

    /** 按完整回合链切块。summary 消息跳过（已压缩过，不再参与）；pinned 消息跳过（技能
     *  正文常驻，压缩豁免，由 compress 原样保留）；system 消息跳过（系统提示词不并入链、
     *  不进入压缩批次，由 compress 原样保留）。 */
    public static List<List<Message>> chunkChains(List<Message> messages) {
        List<List<Message>> chains = new ArrayList<List<Message>>();
        List<Message> cur = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.summary || m.pinned || m.role == Message.Role.SYSTEM) {
                flush(chains, cur);
                continue;
            }
            cur.add(m);
            if (m.role == Message.Role.ASSISTANT
                    && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                flush(chains, cur); // 无工具调用的 assistant 结束一条链
            }
        }
        flush(chains, cur);
        return chains;
    }

    private static void flush(List<List<Message>> chains, List<Message> cur) {
        if (!cur.isEmpty()) {
            chains.add(new ArrayList<Message>(cur));
            cur.clear();
        }
    }

    /** 保留区动态缩小：keep 条数起步，保留区 token 占比 > KEEP_RATIO 且条数 > KEEP_MIN
     *  时减半重算。返回要压缩的链数（前 take 个链）。 */
    private int takeChains(List<List<Message>> chains, int limit, List<Message> messages) {
        int take = computeTake(chains, limit);
        int total = TokenCounter.estimateMessages(messages);
        while (take > 0 && limit > KEEP_MIN) {
            int keptTokens = 0;
            for (int i = take; i < chains.size(); i++) {
                keptTokens += TokenCounter.estimateMessages(chains.get(i));
            }
            if ((double) keptTokens / total <= KEEP_RATIO) break;
            limit = Math.max(KEEP_MIN, limit / 2); // 减半不跌破下限（如 13 → 12，而非 6）
            take = computeTake(chains, limit);
        }
        return take;
    }

    /** 从后往前按链累计：保留的完整链条数总和 ≤ limit。返回首个保留链的下标（= 要压缩的链数）。 */
    private int computeTake(List<List<Message>> chains, int limit) {
        int kept = 0;
        int take = chains.size();
        for (int i = chains.size() - 1; i >= 0; i--) {
            int size = chains.get(i).size();
            if (kept + size > limit) break;
            kept += size;
            take = i;
        }
        return take;
    }

    /** 压缩：链为单位，摘要置前；保留最近 keepRecent 条原文。失败原样返回。 */
    public List<Message> compress(List<Message> messages) {
        List<List<Message>> chains = chunkChains(messages);
        int take = takeChains(chains, keepRecent, messages);
        if (take == 0) return messages; // 全部要保留，无需压缩

        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < take; i++) {
            for (Message m : chains.get(i)) {
                batch.append('[').append(m.role).append(']');
                if (m.content != null) batch.append(' ').append(m.content);
                if (m.reasoningContent != null) batch.append(" (思考: ").append(m.reasoningContent).append(')');
                if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                    batch.append(" [工具: ");
                    for (com.minion.core.llm.ToolCall tc : m.toolCalls) {
                        batch.append(tc.name).append(' ');
                    }
                    batch.append(']');
                }
                batch.append('\n');
            }
        }
        String summary;
        try {
            summary = llm.completeChat(
                    Collections.singletonList(Message.user(batch.toString())), COMPRESS_SYSTEM);
        } catch (Exception e) {
            System.err.println("[minion] 压缩失败，跳过本轮: " + e.getMessage());
            return messages;
        }
        if (summary == null || summary.trim().isEmpty()) {
            System.err.println("[minion] 压缩返回空摘要，跳过本轮");
            return messages;
        }

        // 技能加载消息（pinned）常驻：不入链不参与摘要，原样保留在摘要后
        List<Message> pinnedMsgs = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.pinned) pinnedMsgs.add(m);
        }
        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        Message summaryMsg = Message.user("【历史对话摘要】\n" + summary.trim());
        summaryMsg.summary = true;
        result.add(summaryMsg);
        result.addAll(pinnedMsgs);
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链；旧 summary 由新摘要取代
        }
        return result;
    }
}
