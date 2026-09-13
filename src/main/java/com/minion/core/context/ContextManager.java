package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 上下文管理：token 估算、阈值判断、单次压缩（最早链 → 摘要置前）。
 *  阈值 0.65 / 压缩比例 0.8 / 摘要上限 5000 字为硬编码常量（不进模型配置，只保留 maxContextTokens）。
 *  压缩失败不再降级：直接抛 LlmException，由 AgentLoop 按重试策略处理。 */
public class ContextManager {

    /** 摘要输出上限（字符）：写入压缩器 system 提示词 */
    static final int SUMMARY_MAX_CHARS = 5000;
    /** 触发阈值（estimate >= maxContextTokens × THRESHOLD） */
    static final double THRESHOLD = 0.65;
    /** 触发量中要压缩的比例（其余为保留区） */
    static final double COMPRESS_RATIO = 0.8;

    private static final String COMPRESS_SYSTEM =
            "你是 minion 的上下文压缩器。把用户提供的对话历史压缩成一段中文摘要，保留："
          + "未完成的任务与目标、已做出的关键决策及原因、使用过的工具与结果要点、"
          + "相关文件路径、代码约定、用户偏好。只输出摘要正文，不要客套，"
          + SUMMARY_MAX_CHARS + " 字以内。";

    // FX 线程 update/setLlm 写入、会话工作线程 shouldCompress/compress 读取——volatile 防 JMM 数据竞争
    private volatile int maxContextTokens;
    private volatile LlmClient llm;
    private final int systemTokens;    // system 提示词 token 估算在构造时固定，保持不变

    public ContextManager(int maxContextTokens, LlmClient llm, int systemTokens) {
        this.maxContextTokens = maxContextTokens;
        this.llm = llm;
        this.systemTokens = systemTokens;
    }

    /** 模型参数热更新（设置窗修改后调用；运行时生效于下一轮压缩判断） */
    public void update(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    /** 换 LLM 客户端（模型切换后调用；压缩请求走新客户端） */
    public void setLlm(LlmClient llm) { this.llm = llm; }

    /** 上下文窗口上限（AgentLoop 压缩百分比计算用） */
    public int maxTokens() { return maxContextTokens; }

    /** 自动压缩阈值（0~1，恒 0.65；GUI 悬停"剩余x%自动压缩"计算用） */
    public double threshold() { return THRESHOLD; }

    public int estimate(List<Message> messages) {
        return systemTokens + TokenCounter.estimateMessages(messages);
    }

    public boolean shouldCompress(List<Message> messages) {
        return estimate(messages) >= maxContextTokens * THRESHOLD;
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

    /** 压缩：单次 LLM 调用，无递归、无降级。
     *  - 返回入参同一实例（引用相等）＝ 暂无可压缩（无链）；
     *  - 成功：返回「system 原样 + 新摘要置前 + pinned 原样 + 未压缩链」；
     *  - 失败（请求异常/空摘要）：抛 LlmException，由调用方按重试策略处理。 */
    public List<Message> compress(List<Message> messages) throws LlmException {
        List<List<Message>> chains = chunkChains(messages);
        if (chains.isEmpty()) return messages;
        int take = takeCount(chains);
        String summary = callLlm(existingSummaryText(messages), buildBatch(chains, 0, take));
        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        result.add(summaryMsg(summary));
        for (Message m : messages) {
            if (m.pinned) result.add(m); // 技能加载消息（pinned）常驻：不入链不参与摘要
        }
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链；旧 summary 由新摘要取代
        }
        return result;
    }

    /** 要压缩的链数：从最早链逐链累加 token，累计首次 ≥ compressTokens 时的链数（含该链，
     *  保证压缩量 ≥ COMPRESS_RATIO）；全部链合计仍不足时全压。有链时恒 ≥ 1。 */
    private int takeCount(List<List<Message>> chains) {
        long compressTokens = (long) (maxContextTokens * THRESHOLD * COMPRESS_RATIO);
        long acc = 0;
        int take = 0;
        for (List<Message> chain : chains) {
            acc += TokenCounter.estimateMessages(chain);
            take++;
            if (acc >= compressTokens) return take;
        }
        return take;
    }

    /** 既有摘要文本（二次压缩并入输入，避免旧摘要内容丢失）；无则 null */
    private static String existingSummaryText(List<Message> messages) {
        for (Message m : messages) {
            if (m.summary && m.content != null) return m.content;
        }
        return null;
    }

    /** 单次压缩请求：prefix（旧摘要）非空时置前参与合并；空摘要视为失败抛 EMPTY_RESPONSE */
    private String callLlm(String prefix, String batch) throws LlmException {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) sb.append(prefix).append('\n');
        sb.append(batch);
        String s = llm.completeChat(
                Collections.singletonList(Message.user(sb.toString())), COMPRESS_SYSTEM);
        if (s == null || s.trim().isEmpty()) {
            throw new LlmException(LlmException.Type.EMPTY_RESPONSE, "压缩模型返回空摘要", true);
        }
        return s.trim();
    }

    /** 拼压缩批次文本 */
    private String buildBatch(List<List<Message>> chains, int from, int to) {
        StringBuilder batch = new StringBuilder();
        for (int i = from; i < to; i++) {
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
        return batch.toString();
    }

    private static Message summaryMsg(String text) {
        Message m = Message.user("【历史对话摘要】\n" + text);
        m.summary = true;
        return m;
    }
}
