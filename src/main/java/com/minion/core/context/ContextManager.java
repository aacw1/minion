package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 上下文管理：token 估算、阈值判断、单次压缩（保留区外的早期原子组 → 摘要置前）。
 *  阈值 0.65 / 保留区预算 0.13×max（＝阈值 × KEEP_BUDGET_RATIO）/ 保底保留最新 1 组 / 摘要上限 5000 字
 *  为硬编码常量（不进模型配置，只保留 maxContextTokens）。
 *  压缩失败不再降级：直接抛 LlmException，由 AgentLoop 按重试策略处理。
 *  压缩指令可定制（默认主代理版；子代理用 SUB_AGENT_COMPRESS_SYSTEM 强调任务目标/进度/落盘路径）。 */
public class ContextManager {

    /** 摘要输出上限（字符）：写入压缩器 system 提示词 */
    static final int SUMMARY_MAX_CHARS = 5000;
    /** 触发阈值（estimate >= maxContextTokens × THRESHOLD） */
    static final double THRESHOLD = 0.65;
    /** 保留区预算比例：保留区 token 预算 = maxContextTokens × THRESHOLD × 本值 = 0.13×max
     *  （触发量的 20% 留给保留区，其余 80% 为压缩目标） */
    static final double KEEP_BUDGET_RATIO = 0.2;
    /** 保底保留的最近原子组数：最新 1 组无条件保留（哪怕自身超预算）——当前任务上下文不可丢 */
    static final int KEEP_MIN_GROUPS = 1;
    /** 事前收益门槛：可压量 ≥ maxContextTokens × 本值 才值得压缩
     *  （摘要输出本身 ~2-3.5k token，压少了净收益为负） */
    static final double MIN_COMPRESSIBLE_RATIO = 0.05;
    /** 危险区豁免：estimate ≥ maxContextTokens × 本值（贴近窗口）时无视门槛强制压缩保命 */
    static final double FORCE_COMPRESS_RATIO = 0.85;

    private static final String COMPRESS_SYSTEM =
            "你是 minion 的上下文压缩器。把用户提供的对话历史压缩成一段中文摘要，保留："
          + "未完成的任务与目标、已做出的关键决策及原因、使用过的工具与结果要点、"
          + "相关文件路径、代码约定、用户偏好。只输出摘要正文，不要客套，"
          + SUMMARY_MAX_CHARS + " 字以内。";

    /** 子代理压缩指令：与主代理版（用户偏好/代码约定导向）不同——子代理一次只做一个任务，
     *  压缩后必须还能继续干活/汇报，故强调任务目标与验收标准、已完成步骤与结论、
     *  已落盘文件完整路径（后续汇报要引用）、错误与未完成项 */
    public static final String SUB_AGENT_COMPRESS_SYSTEM =
            "你是 minion 子代理的上下文压缩器。把对话历史压缩成一段中文摘要，保留："
          + "当前任务目标与验收标准、已完成的步骤与结论、已落盘文件的完整路径、"
          + "遇到的错误与未完成项。只输出摘要正文，不要客套，"
          + SUMMARY_MAX_CHARS + " 字以内。";

    // FX 线程 update/setLlm 写入、会话工作线程 shouldCompress/compress 读取——volatile 防 JMM 数据竞争
    private volatile int maxContextTokens;
    private volatile LlmClient llm;
    private final int systemTokens;    // system 提示词 token 估算在构造时固定，保持不变
    /** 压缩指令（主代理默认版 COMPRESS_SYSTEM / 子代理定制版 SUB_AGENT_COMPRESS_SYSTEM） */
    private final String compressSystem;

    public ContextManager(int maxContextTokens, LlmClient llm, int systemTokens) {
        this(maxContextTokens, llm, systemTokens, COMPRESS_SYSTEM);
    }

    /** 定制压缩指令（子代理用；默认构造 = 主代理版，行为零变化） */
    public ContextManager(int maxContextTokens, LlmClient llm, int systemTokens, String compressSystemPrompt) {
        this.maxContextTokens = maxContextTokens;
        this.llm = llm;
        this.systemTokens = systemTokens;
        this.compressSystem = compressSystemPrompt;
    }

    /** 当前压缩指令（诊断/测试断言用：子代理实例应为 SUB_AGENT_COMPRESS_SYSTEM） */
    public String compressSystem() { return compressSystem; }

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

    /** 按原子组切块：原子组是可安全切割的最小单位——有工具调用的 assistant 与其后的 tool 结果
     *  捆为一组（保证 tool_call↔tool 配对不被切断，否则接口 400）；普通 user、assistant（无工具调用）
     *  各自一组。summary 消息跳过（已压缩过，不再参与）；pinned 消息跳过（技能正文常驻，压缩豁免，
     *  由 compress 原样保留）；system 消息跳过（系统提示词不并入组、不进入压缩批次，由 compress 原样保留）。 */
    public static List<List<Message>> chunkGroups(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<List<Message>>();
        List<Message> cur = null; // 当前工具组（assistant(tool_calls) + 其后 tool）
        for (Message m : messages) {
            if (m.summary || m.pinned || m.role == Message.Role.SYSTEM) {
                flush(groups, cur);
                cur = null;
                continue;
            }
            if (cur != null) {
                if (m.role == Message.Role.TOOL) {
                    cur.add(m); // 工具结果并入所属工具组，配对不外泄
                    continue;
                }
                flush(groups, cur);
                cur = null;
            }
            if (m.role == Message.Role.ASSISTANT && m.toolCalls != null && !m.toolCalls.isEmpty()) {
                cur = new ArrayList<Message>();
                cur.add(m);
            } else {
                List<Message> g = new ArrayList<Message>();
                g.add(m); // 普通 user / assistant / 孤立 tool：各自一组
                groups.add(g);
            }
        }
        flush(groups, cur);
        return groups;
    }

    private static void flush(List<List<Message>> groups, List<Message> cur) {
        if (cur != null && !cur.isEmpty()) {
            groups.add(cur);
        }
    }

    /** 压缩：单次 LLM 调用，无递归、无降级。
     *  - 返回入参同一实例（引用相等）＝ 暂无可压缩（无原子组，或全部组都在保留区预算 0.13×max 内）；
     *  - 成功：返回「system 原样 + 新摘要置前 + pinned 原样 + 未压缩原子组」——保留区 = 预算内最近 K 组、保底最新 1 组；
     *  - 失败（请求异常/空摘要）：抛 LlmException，由调用方按重试策略处理。 */
    public List<Message> compress(List<Message> messages) throws LlmException {
        List<List<Message>> groups = chunkGroups(messages);
        int take = groups.size() - keepCount(groups);
        if (take <= 0) return messages; // 暂无可压缩：不调 LLM、不改变历史
        String summary = callLlm(existingSummaryText(messages), buildBatch(groups, 0, take));
        List<Message> result = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.role == Message.Role.SYSTEM) result.add(m); // system 原样保留，置于最前
        }
        result.add(summaryMsg(summary));
        for (Message m : messages) {
            if (m.pinned) result.add(m); // 技能加载消息（pinned）常驻：不入组不参与摘要
        }
        for (int i = take; i < groups.size(); i++) {
            result.addAll(groups.get(i)); // 保留未被压缩的原子组；旧 summary 由新摘要取代
        }
        return result;
    }

    /** 保留的原子组数：从最新组往前累加 token，累计 ≤ 预算的最大组数（下限 KEEP_MIN_GROUPS=1，无上限）。
     *  组内是协议不可拆单位（assistant(tool_calls)+tool 配对），只能整组保留/整组压缩。 */
    private int keepCount(List<List<Message>> groups) {
        if (groups.isEmpty()) return 0; // 空组：keep=0（take=0，compress 同引用返回），不留 take=-1 的隐晦中间值
        long budget = (long) (maxContextTokens * THRESHOLD * KEEP_BUDGET_RATIO);
        long acc = 0;
        int keep = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            long t = TokenCounter.estimateMessages(groups.get(i));
            if (keep >= KEEP_MIN_GROUPS && acc + t > budget) break;
            acc += t;
            keep++;
        }
        return Math.max(keep, KEEP_MIN_GROUPS);
    }

    /** 本次压缩能压掉的 token 量（＝将被并入摘要的组合计；take = 0 时为 0）：事前收益门槛的输入 */
    public long compressibleTokens(List<Message> messages) {
        List<List<Message>> groups = chunkGroups(messages);
        int take = groups.size() - keepCount(groups);
        long acc = 0;
        for (int i = 0; i < take; i++) acc += TokenCounter.estimateMessages(groups.get(i));
        return acc;
    }

    /** 本次是否值得压缩（调用方须先确认 shouldCompress 为 true）：
     *  危险区（estimate ≥ 85%×max）无条件压；否则须可压量 ≥ 5%×max（防零收益空转、防压出净增）。
     *  只跳过"本次"——任务推进后旧组滑出保留区、可压量增长，随时可再次压缩。 */
    public boolean worthCompressing(List<Message> messages) {
        if (estimate(messages) >= maxContextTokens * FORCE_COMPRESS_RATIO) return true;
        return compressibleTokens(messages) >= maxContextTokens * MIN_COMPRESSIBLE_RATIO;
    }

    /** 既有摘要文本（二次压缩并入输入，避免旧摘要内容丢失）：拼接全部 summary 消息
     *  （兼容历史遗留多条摘要的会话），无则 null */
    private static String existingSummaryText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m.summary && m.content != null && !m.content.trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(m.content);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 单次压缩请求：prefix（旧摘要）非空时置前参与合并；空摘要视为失败抛 EMPTY_RESPONSE */
    private String callLlm(String prefix, String batch) throws LlmException {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) sb.append(prefix).append('\n');
        sb.append(batch);
        String s = llm.completeChat(
                Collections.singletonList(Message.user(sb.toString())), compressSystem);
        if (s == null || s.trim().isEmpty()) {
            throw new LlmException(LlmException.Type.EMPTY_RESPONSE, "压缩模型返回空摘要", true);
        }
        return s.trim();
    }

    /** 拼压缩批次文本（按原子组区间 [from, to)） */
    private String buildBatch(List<List<Message>> groups, int from, int to) {
        StringBuilder batch = new StringBuilder();
        for (int i = from; i < to; i++) {
            for (Message m : groups.get(i)) {
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
