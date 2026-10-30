package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 上下文管理：token 估算、阈值判断、单次压缩（保留区外的早期原子组 → 摘要置前）。
 *  阈值 0.65 / 保留区预算 0.13×max（＝阈值 × KEEP_BUDGET_RATIO）/ 保留区常态保底最近 4 组
 *  （危险区 estimate ≥ 85%×max 时降为 1 组）/ 摘要上限 5000 字
 *  为硬编码常量（不进模型配置，只保留 maxContextTokens）。
 *  压缩失败不再降级：直接抛 LlmException，由 AgentLoop 按重试策略处理。
 *  压缩指令可定制（默认主代理版；子代理用 SUB_AGENT_COMPRESS_SYSTEM 强调任务目标/进度/落盘路径）。
 *
 *  P1-3（性能与准确性）：
 *  — {@link #evaluate(List)} 一次扫描给出全部判定（used/可压量/保留组/是否该压/是否值得压/硬闸），
 *    主循环每轮只调一次，替代旧实现 shouldCompress + worthCompressing + compressibleTokens + estimate
 *    的 6~9 次全量扫描；兼容接口内部委托它，语义不变。
 *  — {@link #calibrate(int, int)} 用真实 usage.inputTokens 回灌修正系数（clamp 0.8~1.5，EMA 平滑），
 *    估算不再「只记账不校准」。
 *  — {@link #HARD_LIMIT_RATIO}（0.95）发送前硬闸：估算贴近窗口且无法靠压缩自救时拒绝发送，
 *    避免必然 400 后该会话此后每次重发仍超窗。
 */
public class ContextManager {

    /** 摘要输出上限（字符）：写入压缩器 system 提示词 */
    static final int SUMMARY_MAX_CHARS = 5000;
    /** 触发阈值（estimate >= maxContextTokens × THRESHOLD） */
    static final double THRESHOLD = 0.65;
    /** 保留区预算比例：保留区 token 预算 = maxContextTokens × THRESHOLD × 本值 = 0.13×max
     *  （触发量的 20% 留给保留区，其余 80% 为压缩目标） */
    static final double KEEP_BUDGET_RATIO = 0.2;
    /** 常态保底保留的最近原子组数：最近 4 组无条件保留（哪怕合计超保留区预算）——正在进行的任务链不可丢 */
    static final int KEEP_MIN_GROUPS = 4;
    /** 危险区保底：estimate ≥ 85%×max 时降为最新 1 组（保命压缩优先于保真） */
    static final int KEEP_CRITICAL_GROUPS = 1;
    /** 事前收益门槛：可压量 ≥ maxContextTokens × 本值 才值得压缩
     *  （摘要输出本身 ~2-3.5k token，压少了净收益为负） */
    static final double MIN_COMPRESSIBLE_RATIO = 0.05;
    /** 危险区豁免：estimate ≥ maxContextTokens × 本值（贴近窗口）时无视门槛强制压缩保命 */
    static final double FORCE_COMPRESS_RATIO = 0.85;
    /** 发送前硬闸（P1-3）：estimate ≥ maxContextTokens × 本值 视为「贴近窗口」，
     *  本轮又无法通过压缩自救时拒绝发送（服务端 400 之后每次重发仍超窗，用户只能新建会话） */
    static final double HARD_LIMIT_RATIO = 0.95;

    /** 校准系数下限/上限：估算器与真实分词器差异再大也不让估算失控（P1-3） */
    static final double CALIBRATION_MIN = 0.8;
    static final double CALIBRATION_MAX = 1.5;
    /** 参与校准的最小真实输入 token：过小的请求（或测试桩的固定 usage）噪声大，忽略 */
    static final int CALIBRATION_MIN_TOKENS = 200;
    /** 可接受的真实/估算比值区间：明显越界说明 usage 不可信（缺失、被网关改写、估算口径完全不同） */
    static final double CALIBRATION_MIN_RATIO = 0.5;
    static final double CALIBRATION_MAX_RATIO = 3.0;

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
    /** usage 校准系数（1.0 = 不校准；P1-3 由 AgentLoop 收到真实 usage 后回灌） */
    private volatile double calibration = 1.0;

    /**
     * 单次扫描的上下文评估结果（P1-3，纯数据）：主循环每轮只算一次，拿全部判定。
     * 用于替代旧实现里 shouldCompress/worthCompressing/compressibleTokens/estimate 的多次全量扫描。
     */
    public static final class Evaluation {
        /** 校准后的估算总量（含 system 提示词） */
        public final int used;
        /** 上下文窗口上限 */
        public final int max;
        /** 原子组数（summary/pinned/system 不入组） */
        public final int groups;
        /** 本次保留的原子组数（预算内尽量多留，保底 4/危险区 1） */
        public final int keep;
        /** 本次将被压缩的原子组数（0 = 暂无可压缩） */
        public final int take;
        /** 可压掉的 token 量（未校准原始估算；与旧 compressibleTokens 同口径） */
        public final long compressible;
        /** 超过触发阈值 */
        public final boolean shouldCompress;
        /** 值得压缩（危险区强制，否则要求可压量 ≥ 5%×max） */
        public final boolean worthCompressing;
        /** 危险区（≥85%×max） */
        public final boolean forceZone;
        /** 贴近窗口（≥95%×max）：无法压缩自救时发送前硬闸拦下 */
        public final boolean hardLimit;

        Evaluation(int used, int max, int groups, int keep, int take, long compressible,
                   boolean shouldCompress, boolean worthCompressing,
                   boolean forceZone, boolean hardLimit) {
            this.used = used;
            this.max = max;
            this.groups = groups;
            this.keep = keep;
            this.take = take;
            this.compressible = compressible;
            this.shouldCompress = shouldCompress;
            this.worthCompressing = worthCompressing;
            this.forceZone = forceZone;
            this.hardLimit = hardLimit;
        }

        /** 占窗口百分比（四舍五入；max ≤ 0 时 0） */
        public int percent() {
            return max <= 0 ? 0 : (int) Math.round(used * 100.0 / max);
        }

        /** 是否存在可压缩内容（take > 0） */
        public boolean canCompress() { return take > 0; }
    }

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

    // ---------------- P1-3：估算校准 ----------------

    /** 当前估算校准系数（1.0 = 未校准；诊断/测试用） */
    public double calibration() { return calibration; }

    /**
     * 用真实 usage 校准估算（P1-3）：请求前的估算值 calibratedEstimate 与该请求的服务端
     * input_tokens 比较，得到「真实/原始估算」比值，clamp 到 0.8~1.5 后以 0.5 权重 EMA 回灌。
     *
     * 忽略两类不可信观测：真实值过小（< 200 token，噪声/测试桩）、比值越界（0.5~3.0 之外，
     * 典型是网关没回 usage 或 usage 被改写）。返回是否采纳本次观测。
     */
    public boolean calibrate(int actualInputTokens, int calibratedEstimate) {
        if (actualInputTokens < CALIBRATION_MIN_TOKENS || calibratedEstimate <= 0) return false;
        double f = calibration;
        double rawEstimate = calibratedEstimate / f;   // 还原「未校准」估算
        if (rawEstimate <= 0) return false;
        double ratio = actualInputTokens / rawEstimate; // 真实 / 原始估算
        if (ratio < CALIBRATION_MIN_RATIO || ratio > CALIBRATION_MAX_RATIO) return false;
        double target = clamp(ratio, CALIBRATION_MIN, CALIBRATION_MAX);
        calibration = clamp(0.5 * f + 0.5 * target, CALIBRATION_MIN, CALIBRATION_MAX);
        return true;
    }

    /** 重置校准（新建会话/切模型后的诊断入口；测试用） */
    public void resetCalibration() { calibration = 1.0; }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 校准缩放（系数 1.0 时结果与原始估算完全一致，既有断言口径不变） */
    private int scale(long raw) {
        double f = calibration;
        long scaled = f == 1.0 ? raw : (long) Math.ceil(raw * f);
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    // ---------------- P1-3：单次扫描评估 ----------------

    /**
     * 一次扫描给出全部上下文判定（线程安全：只读 messages 与 volatile 配置）。
     * 原子组 token 在切组时一次累加，不再「chunkGroups 后再逐组 estimateMessages」。
     */
    public Evaluation evaluate(List<Message> messages) {
        List<Long> sums = new ArrayList<Long>();
        long excluded = 0;   // summary/pinned/system：计入总量但不成组（压缩豁免）
        int cur = -1;        // 当前工具组下标（assistant(tool_calls) + 其后 tool）
        int n = messages == null ? 0 : messages.size();
        for (int i = 0; i < n; i++) {
            Message m = messages.get(i);
            long t = TokenCounter.estimateMessage(m);
            if (m.summary || m.pinned || m.role == Message.Role.SYSTEM) {
                excluded += t;
                cur = -1;
                continue;
            }
            if (cur >= 0) {
                if (m.role == Message.Role.TOOL) {
                    sums.set(cur, sums.get(cur) + t); // 工具结果并入所属工具组，配对不外泄
                    continue;
                }
                cur = -1;
            }
            if (m.role == Message.Role.ASSISTANT && m.toolCalls != null && !m.toolCalls.isEmpty()) {
                cur = sums.size();
                sums.add(t);
            } else {
                sums.add(t); // 普通 user / assistant / 孤立 tool：各自一组
            }
        }
        long raw = excluded + systemTokens;
        for (Long s : sums) raw += s;
        int used = scale(raw);
        int max = maxContextTokens;
        boolean forceZone = max > 0 && used >= max * FORCE_COMPRESS_RATIO;
        int minKeep = forceZone ? KEEP_CRITICAL_GROUPS : KEEP_MIN_GROUPS;
        int keep = keepCountForSums(sums, minKeep);
        int take = sums.size() - keep;
        long compressible = 0;
        for (int i = 0; i < take; i++) compressible += sums.get(i);
        boolean should = max > 0 && used >= max * THRESHOLD;
        boolean worth = forceZone || compressible >= max * MIN_COMPRESSIBLE_RATIO;
        boolean hard = max > 0 && used >= max * HARD_LIMIT_RATIO;
        return new Evaluation(used, max, sums.size(), keep, take, compressible, should, worth, forceZone, hard);
    }

    /** 估算当前上下文 token（校准后；单次扫描，不含分组分配） */
    public int estimate(List<Message> messages) {
        long raw = systemTokens;
        if (messages != null) {
            for (Message m : messages) raw += TokenCounter.estimateMessage(m);
        }
        return scale(raw);
    }

    public boolean shouldCompress(List<Message> messages) {
        return evaluate(messages).shouldCompress;
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
     *  - 返回入参同一实例（引用相等）＝ 暂无可压缩（无原子组、组数 ≤ 保底组数，或全部组都在保留区预算 0.13×max 内）；
     *  - 成功：返回「system 原样 + 新摘要置前 + pinned 原样 + 未压缩原子组」——保留区 = 预算内最近 K 组、
     *    常态保底最近 4 组（危险区 estimate ≥ 85%×max 降 1 组）；
     *  - 失败（请求异常/空摘要）：抛 LlmException，由调用方按重试策略处理。 */
    public List<Message> compress(List<Message> messages) throws LlmException {
        List<List<Message>> groups = chunkGroups(messages);
        int take = groups.size() - keepCount(groups, minKeepGroups(messages));
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

    /** 本次压缩的保底保留组数：危险区（estimate ≥ 85%×max）降为最新 1 组，常态保底最近 4 组 */
    private int minKeepGroups(List<Message> messages) {
        return estimate(messages) >= maxContextTokens * FORCE_COMPRESS_RATIO
                ? KEEP_CRITICAL_GROUPS : KEEP_MIN_GROUPS;
    }

    /** 保留的原子组数（按消息组）：转换为组 token 后复用同一算法 */
    private int keepCount(List<List<Message>> groups, int minKeep) {
        long[] sums = new long[groups.size()];
        for (int i = 0; i < groups.size(); i++) sums[i] = TokenCounter.estimateMessages(groups.get(i));
        return keepCountForSums(sums, minKeep);
    }

    /** 保留的原子组数（按组 token 数组）：从最新组往前累加 token，累计 ≤ 预算的最大组数；
     *  下限 minKeep（常态 4 组保底 / 危险区 1 组），无上限（预算内尽量多留）；
     *  组数 ≤ minKeep 时返回组数本身（take = 0 → 不压缩）。
     *  组内是协议不可拆单位（assistant(tool_calls)+tool 配对），只能整组保留/整组压缩。 */
    private int keepCountForSums(List<Long> sums, int minKeep) {
        int n = sums.size();
        if (n == 0) return 0; // 空组：keep=0（take=0，compress 同引用返回）
        long budget = (long) (maxContextTokens * THRESHOLD * KEEP_BUDGET_RATIO);
        long acc = 0;
        int keep = 0;
        for (int i = n - 1; i >= 0; i--) {
            long t = sums.get(i);
            if (keep >= minKeep && acc + t > budget) break;
            acc += t;
            keep++;
        }
        return keep; // 循环语义已保证：不 break 直到 keep ≥ minKeep 或组耗尽（返回值 ≤ 组数、take ≥ 0）
    }

    private int keepCountForSums(long[] sums, int minKeep) {
        int n = sums.length;
        if (n == 0) return 0;
        long budget = (long) (maxContextTokens * THRESHOLD * KEEP_BUDGET_RATIO);
        long acc = 0;
        int keep = 0;
        for (int i = n - 1; i >= 0; i--) {
            long t = sums[i];
            if (keep >= minKeep && acc + t > budget) break;
            acc += t;
            keep++;
        }
        return keep;
    }

    /** 本次压缩能压掉的 token 量（＝将被并入摘要的组合计；take = 0 时为 0）：事前收益门槛的输入 */
    public long compressibleTokens(List<Message> messages) {
        return evaluate(messages).compressible;
    }

    /** 本次是否值得压缩（调用方须先确认 shouldCompress 为 true）：
     *  危险区（estimate ≥ 85%×max）无条件压；否则须可压量 ≥ 5%×max（防零收益空转、防压出净增）。
     *  只跳过"本次"——任务推进后旧组滑出保留区、可压量增长，随时可再次压缩。 */
    public boolean worthCompressing(List<Message> messages) {
        return evaluate(messages).worthCompressing;
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
