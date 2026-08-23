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

    private static final int SEGMENT_MIN = 30; // 段内消息数下限：以下不再递归

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

    /** 压缩：链为单位，摘要置前；保留最近 keepRecent 条原文。失败分段递归降级，全部失败原样返回。 */
    public List<Message> compress(List<Message> messages) {
        // 超长单链先按消息均分拆成 ≤ SEGMENT_MIN 的子链：否则单链段无法在链边界二分，
        // splitByTokens 会把 mid 钳回 from，产生同参数递归 → 无限递归栈溢出
        List<List<Message>> chains = splitOversizedChains(chunkChains(messages));
        int take = takeChains(chains, keepRecent, messages);
        if (take == 0) return messages; // 全部要保留，无需压缩

        SegResult res = compressChains(chains, 0, take, null);
        if (res == null) {
            System.err.println("[minion] 压缩失败，跳过本轮");
            return messages;
        }
        // 技能加载消息（pinned）常驻：不入链不参与摘要，原样保留在摘要后
        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        result.addAll(res.messages);
        for (Message m : messages) {
            if (m.pinned) result.add(m);
        }
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链；旧 summary 由新摘要取代
        }
        return result;
    }

    private static class SegResult {
        final String summary;         // 本段完全成功时的摘要文本；null = 未完全成功
        final List<Message> messages; // 本段输出（摘要置前+失败原文）；null = 段整体失败
        SegResult(String summary, List<Message> messages) {
            this.summary = summary;
            this.messages = messages;
        }
    }

    /** 压缩 chains[from,to)，prefix 为上游摘要文本（可为 null）。递归降级：
     *  整体成功 → 单摘要；失败且段 > SEGMENT_MIN → token 均衡二分递归；
     *  失败且已最小段 → prefix(如有)+原文。返回 null = 本段整体失败。 */
    private SegResult compressChains(List<List<Message>> chains, int from, int to, String prefix) {
        String s = callLlm(prefix, buildBatch(chains, from, to));
        if (s != null && !s.trim().isEmpty()) {
            String t = s.trim();
            return new SegResult(t, Collections.singletonList(summaryMsg(t)));
        }
        if (countMessages(chains, from, to) <= SEGMENT_MIN) {
            if (prefix == null) return null; // 无上游摘要且本段失败 → 整体失败
            List<Message> out = new ArrayList<Message>();
            out.add(summaryMsg(prefix));
            for (int i = from; i < to; i++) out.addAll(chains.get(i));
            return new SegResult(null, out);
        }
        int mid = splitByTokens(chains, from, to);
        SegResult left = compressChains(chains, from, mid, prefix);
        if (left == null) {
            // 左段全败：右段带原 prefix 压，左段原文保留在结果尾部
            SegResult right = compressChains(chains, mid, to, prefix);
            if (right == null) return null;
            List<Message> out = new ArrayList<Message>(right.messages);
            for (int i = from; i < mid; i++) out.addAll(chains.get(i));
            return new SegResult(right.summary, out);
        }
        if (left.summary != null) {
            // 左段完全成功：摘要A 作 prefix 压右段（合并），左段摘要由右段结果取代
            SegResult right = compressChains(chains, mid, to, left.summary);
            if (right == null) {
                // 右段整体失败：摘要A + 右段原文（原文不得因降级丢失）
                List<Message> out = new ArrayList<Message>(left.messages);
                for (int i = mid; i < to; i++) out.addAll(chains.get(i));
                return new SegResult(left.summary, out);
            }
            return right;
        }
        // 左段部分成功（含失败原文）：右段独立压，两侧结果摘要统一置前拼接
        SegResult right = compressChains(chains, mid, to, null);
        if (right == null) {
            // 右段整体失败：左段部分结果 + 右段原文（原文不得因降级丢失）
            List<Message> out = new ArrayList<Message>(left.messages);
            for (int i = mid; i < to; i++) out.addAll(chains.get(i));
            return new SegResult(left.summary, out);
        }
        List<Message> out = new ArrayList<Message>();
        for (Message m : left.messages) if (m.summary) out.add(m);
        for (Message m : right.messages) if (m.summary) out.add(m);
        for (Message m : left.messages) if (!m.summary) out.add(m);
        for (Message m : right.messages) if (!m.summary) out.add(m);
        return new SegResult(null, out);
    }

    /** 拼压缩批次文本；prefix 非空时置于批次前（上游摘要参与下游合并） */
    private String callLlm(String prefix, String batch) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) sb.append(prefix).append('\n');
        sb.append(batch);
        try {
            return llm.completeChat(
                    Collections.singletonList(Message.user(sb.toString())), COMPRESS_SYSTEM);
        } catch (Exception e) {
            return null; // 失败静默降级，顶层统一告警
        }
    }

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

    private int countMessages(List<List<Message>> chains, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) n += chains.get(i).size();
        return n;
    }

    /** 按 token 均衡二分（链为边界），mid 严格落在 (from, to) 内 */
    private int splitByTokens(List<List<Message>> chains, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) total += TokenCounter.estimateMessages(chains.get(i));
        int half = total / 2;
        int acc = 0;
        int mid = from + 1;
        for (int i = from; i < to - 1; i++) {
            acc += TokenCounter.estimateMessages(chains.get(i));
            if (acc >= half) {
                mid = i + 1;
                break;
            }
        }
        if (mid <= from) mid = from + 1;
        if (mid >= to) mid = to - 1;
        return mid;
    }

    /** 拆分超长单链（> SEGMENT_MIN 条消息）为 ≤ SEGMENT_MIN 的子链，保持消息顺序。
     *  保证递归中任何 countMessages > SEGMENT_MIN 的段至少含 2 条链，splitByTokens 的
     *  mid 必严格落在 (from, to) 内，杜绝单链段同参数无限递归。 */
    private static List<List<Message>> splitOversizedChains(List<List<Message>> chains) {
        List<List<Message>> out = new ArrayList<List<Message>>();
        for (List<Message> chain : chains) {
            splitChainByMessages(chain, out);
        }
        return out;
    }

    /** 单链按消息数均分递归二分，直至子链 ≤ SEGMENT_MIN 条（拷贝为独立列表，不持有原链视图） */
    private static void splitChainByMessages(List<Message> chain, List<List<Message>> out) {
        if (chain.size() <= SEGMENT_MIN) {
            out.add(new ArrayList<Message>(chain));
            return;
        }
        int mid = chain.size() / 2;
        splitChainByMessages(chain.subList(0, mid), out);
        splitChainByMessages(chain.subList(mid, chain.size()), out);
    }

    private static Message summaryMsg(String text) {
        Message m = Message.user("【历史对话摘要】\n" + text);
        m.summary = true;
        return m;
    }
}
