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
          + "相关文件路径、代码约定、用户偏好。只输出摘要正文，不要客套，500 字以内。";

    private final int maxContextTokens;
    private final double threshold;
    private final int keepRecent;
    private final LlmClient llm;
    private final int systemTokens;

    public ContextManager(int maxContextTokens, double threshold, int keepRecent,
                          LlmClient llm, int systemTokens) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
        this.llm = llm;
        this.systemTokens = systemTokens;
    }

    public int estimate(List<Message> messages) {
        return systemTokens + TokenCounter.estimateMessages(messages);
    }

    public boolean shouldCompress(List<Message> messages) {
        return estimate(messages) >= maxContextTokens * threshold;
    }

    /** 按完整回合链切块。summary 消息跳过（已压缩过，不再参与）；system 消息跳过（系统提示词
     *  不并入链、不进入压缩批次，由 compress 原样保留）。 */
    public static List<List<Message>> chunkChains(List<Message> messages) {
        List<List<Message>> chains = new ArrayList<List<Message>>();
        List<Message> cur = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.summary || m.role == Message.Role.SYSTEM) {
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

    /** 压缩：链为单位，摘要置前；保留最近 keepRecent 条原文。失败原样返回。 */
    public List<Message> compress(List<Message> messages) {
        List<List<Message>> chains = chunkChains(messages);
        int keep = 0;
        int take = 0;
        for (int i = chains.size() - 1; i >= 0; i--) {
            int size = chains.get(i).size();
            if (keep + size <= keepRecent) {
                keep += size;
            } else {
                take = i + 1;
                break;
            }
        }
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

        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        Message summaryMsg = Message.user("【历史对话摘要】\n" + summary.trim());
        summaryMsg.summary = true;
        result.add(summaryMsg);
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链；旧 summary 由新摘要取代
        }
        return result;
    }
}
