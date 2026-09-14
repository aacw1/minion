package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.context.ContextCompressor;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolArguments;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.OutputDump;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolOutputGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** 子 agent：独立消息数组 + 完整工具集（无 task），无轮数上限，返回最终文本 */
public class SubAgentLoop {

    private static final String SUB_SYSTEM_SUFFIX =
            "\n\n你是一个子 agent。只负责完成上述任务，完成后用最终文本总结结果（不要客套）。";

    /** 报告返回上限（字符）：超长报告只把头部返回主代理，完整内容落盘（硬编码常量，不进配置） */
    static final int REPORT_MAX_CHARS = 8000;

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    /** 报告落盘目录（会话 tmp：jarDir/.session/tmp/<会话id>；null=不落盘，返回原文） */
    private final String reportDir;
    /** 子代理编号（会话内递增；报告文件名与消息标识用） */
    private final int no;
    /** 瞬时错误长重试策略（与主循环一致：429/超时/网络 5s、500 类 30s，墙钟 12 分钟；测试可覆写小参数） */
    public RetryPolicy retryPolicy = RetryPolicy.transientErrors();
    /** 工具空输出占位（AgentLoop 创建时注入；开启时成功空输出发「输出内容为空」占位） */
    public boolean emptyOutputPlaceholder = false;
    /** 上下文压缩器（AgentLoop 派发时注入：与主代理同参数同策略；null=不压缩。
     *  systemTokens=0——子代理 system 提示词在 messages 内，由 TokenCounter 统一估算） */
    public ContextManager contextManager;
    /** 压缩无效防抖（子代理全程）：压缩后仍超阈值（保留区被大输出占满）→ 不再重复压缩（防每轮空转），任务继续 */
    private boolean compressIneffective = false;
    private final List<Message> messages = new ArrayList<Message>();

    /** 旧签名（不落盘，供不关心落盘的测试/调用方）：委托新构造 */
    public SubAgentLoop(String systemPrompt, String taskDescription, String workDir,
                        LlmClient llm, ToolRegistry registry, ConfirmGate confirmGate, AgentUi ui) {
        this(systemPrompt, taskDescription, workDir, llm, registry, confirmGate, ui, null, 0);
    }

    public SubAgentLoop(String systemPrompt, String taskDescription, String workDir,
                        LlmClient llm, ToolRegistry registry, ConfirmGate confirmGate, AgentUi ui,
                        String reportDir, int no) {
        this.llm = llm;
        this.registry = registry;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.reportDir = reportDir;
        this.no = no;
        messages.add(Message.system(systemPrompt + SUB_SYSTEM_SUFFIX));
        Message task = Message.user("任务: " + taskDescription);
        task.pinned = true; // 任务提示词压缩豁免（设计要求：任务不能被压掉）
        messages.add(task);
    }

    /** 消息数组（压缩判断/测试断言用） */
    public List<Message> messages() { return messages; }

    /** 报告落盘目录 Path（入历史闸门的大输出落盘位置；null=未接线不落盘，闸门降级纯截断） */
    private java.nio.file.Path reportDirPath() {
        String d = reportDir;
        return d == null ? null : java.nio.file.Paths.get(d);
    }

    public String run() {
        // START 事件只由 AgentLoop 派发时发送一次（Fix Round 1：此处曾重复发「任务: <desc>」，
        // 与派发点的 desc 叠成两行开始行；直构本类的测试/调用方不再收到 START）
        int retries = 0;
        try {
            while (true) {
                // 中断路径：主循环 interrupt() 取消 in-flight 工具 future → 本线程中断 → 立即中止
                if (Thread.currentThread().isInterrupted()) {
                    ui.onSubAgentNotice(no, "已中断");
                    return "子 agent 已中断";
                }
                // 上下文压缩检查点（与主代理同策略）：超阈值 → 压缩；失败中止并返回失败文本；
                // 压缩后仍超阈值 → 防抖置位，后续轮次不再重复压缩（防每轮白付一次压缩调用）
                if (contextManager != null && !compressIneffective
                        && contextManager.shouldCompress(messages)) {
                    String fail = compressSubContext();
                    if (fail != null) {
                        if (Thread.currentThread().isInterrupted()) {
                            // 压缩等待期间被停止：与其他中断路径同文案（spec 4.2「中断 → 子代理已中断」）
                            ui.onSubAgentNotice(no, "已中断");
                            return "子 agent 已中断";
                        }
                        ui.onSubAgentNotice(no, "上下文压缩失败：" + fail);
                        return "子代理失败: 上下文压缩失败（" + fail + "）";
                    }
                }
                final List<ToolCall>[] toolCalls = new List[1];
                final String[] finish = new String[1];
                final Usage[] usage = new Usage[1];
                final StringBuilder content = new StringBuilder();
                final StringBuilder thinking = new StringBuilder();
                final com.minion.core.llm.StreamHandler handler = new com.minion.core.llm.StreamHandler() {
                    @Override
                    public void onThinking(String delta) {
                        thinking.append(delta);
                        ui.onSubAgentThinking(no, delta);
                    }
                    @Override
                    public void onContent(String delta) {
                        content.append(delta);
                        ui.onSubAgentDelta(no, delta);
                    }
                    @Override
                    public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                        finish[0] = finishReason;
                        usage[0] = u;
                        toolCalls[0] = tcs;
                    }
                    @Override
                    public void onError(LlmException e) { finish[0] = "error"; ui.onSubAgentNotice(no, e.getMessage()); }
                };
                try {
                    llm.streamChat(messages, subAgentTools(), handler);
                } catch (LlmException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 已被主循环中断（cancel 引发的 Canceled 错误）：不重试
                        ui.onSubAgentNotice(no, "已中断");
                        return "子 agent 已中断";
                    }
                    if (RetryPolicy.isTransient(e) && noOutputYet(content, thinking)) {
                        // 瞬时错误长重试与主循环一致：按最近一次错误类别等待（429/超时/网络 5s、500 类/空响应 30s），
                        // 墙钟总时长 12 分钟；覆盖 429/超时/可恢复网络错误/500 类（含 503/504）/空响应；
                        // 零增量闸门防重复输出
                        int attempts = 0;
                        long retryStart = System.currentTimeMillis(); // 墙钟基准：含每次请求自身耗时
                        long elapsed = 0;                             // 耗尽时的真实耗时（返回值文案用）
                        boolean exhausted = false; // 超时总结标志：break 后统一返回（子代理无重试指示器，无需复位）
                        String failure = null;     // 重试中遇非瞬时错误的文案：break 后统一返回
                        LlmException last = e;
                        while (true) {
                            attempts++;
                            long delay = retryPolicy.delayMs(RetryPolicy.kindOf(last));
                            if (attempts == 1) notifyRetryEnter(last); // 只在进入重试时提示一条（长重试逐次提示会刷屏）
                            if (!sleepWithInterruptCheck(delay)) break; // 中断
                            elapsed = System.currentTimeMillis() - retryStart;
                            if (retryPolicy.isExhausted(elapsed)) {
                                ui.onSubAgentNotice(no, RetryProgress.tag(last) + " 重试了 " + attempts
                                        + " 次，持续 " + (elapsed / 60000) + " 分钟仍失败，已停止重试");
                                exhausted = true;
                                break;
                            }
                            try {
                                llm.streamChat(messages, subAgentTools(), handler);
                                // 成功后静默恢复（不打扰正文）：子代理无重试指示器可复位；
                                // 若流中断（onError 回调已提示）则落下方正常路径处理
                                break;
                            } catch (LlmException re) {
                                if (Thread.currentThread().isInterrupted()) break;
                                if (!RetryPolicy.isTransient(re) || !noOutputYet(content, thinking)) {
                                    // 永久性/非瞬时错误（DNS 配错、其他 5xx、已吐字断流）：退出重试，不再继续退避
                                    ui.onSubAgentNotice(no, "请求失败：" + re.getMessage());
                                    failure = re.getMessage();
                                    break;
                                }
                                last = re; // 仍可重试：下次退避间隔与失败标签随最近一次失败更新
                            }
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            ui.onSubAgentNotice(no, "已中断");
                            return "子 agent 已中断";
                        }
                        if (exhausted) {
                            return "子 agent 失败: " + RetryProgress.tag(last) + " 持续 " + (elapsed / 60000) + " 分钟"; // 已发子代理提示
                        }
                        if (failure != null) {
                            return "子 agent 失败: " + failure; // 已发子代理提示
                        }
                        if (finish[0] == null && usage[0] == null) {
                            // 防御兜底：正常退出必有 finish/usage 回调（成功 break 后）或
                            // exhausted/failure 标志，理论不可达；保留旧文案以防回归误判
                            return "子 agent 失败: " + RetryProgress.tag(last) + " 重试超时"; // 已发子代理提示
                        }
                        // 重试成功：落入下方正常处理
                    } else if (e.retryable && retries < 1 && noOutputYet(content, thinking)) {
                        // 兜底：可重试但未归类错误（现主流错误均已被长重试覆盖，此分支实际不可达）
                        retries++;
                        ui.onSubAgentNotice(no, "请求失败（" + e.getMessage() + "），自动重试 1 次");
                        // 退避与主循环一致：429 限流 2s，其余（网络/超时）0.5s
                        Thread.sleep(e.type == LlmException.Type.RATE_LIMIT ? 2000 : 500);
                        continue; // 消息未变，直接重发本轮
                    } else {
                        ui.onSubAgentNotice(no, "请求失败：" + e.getMessage());
                        return "子 agent 失败: " + e.getMessage();
                    }
                }
                if (toolCalls[0] == null || toolCalls[0].isEmpty()
                        || !"tool_calls".equals(finish[0])) {
                    String report = content.toString();
                    String ret = persistReport(report);
                    ui.onSubAgentDone(no, ret);
                    return ret;
                }
                // assistant 工具调用消息先入历史——tool 消息必须紧跟含对应 tool_call_id 的
                // assistant tool_calls 消息（DeepSeek/OpenAI 兼容 API 契约，否则 400）；
                // reasoningContent 原样回传同样是硬性要求（思考模式 + 工具调用，缺失下轮 400）
                Message assistantMsg = Message.assistant(
                        content.length() == 0 ? null : content.toString());
                assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
                assistantMsg.toolCalls = toolCalls[0];
                messages.add(assistantMsg);
                for (ToolCall call : toolCalls[0]) {
                    ToolResult result = runOneTool(call);
                    messages.add(Message.toolResult(call.id, call.name,
                            ToolOutputGate.apply(call.name,
                                    ToolResult.outputForApi(result.output, emptyOutputPlaceholder),
                                    reportDirPath())));
                    ui.onSubAgentToolResult(no, call.name, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志，不吞掉中断
            ui.onSubAgentNotice(no, "已中断");
            return "子 agent 已中断";
        } catch (Exception e) {
            ui.onSubAgentNotice(no, "运行异常：" + e.getMessage());
            return "子 agent 异常: " + e.getMessage();
        }
    }

    /** 报告落盘并返回给主代理的文本：报告一律先落盘（主代理按路径 Read 细节，避免重复落盘浪费一轮上下文）；
     *  短报告全文 + 路径；超长报告截断到 REPORT_MAX_CHARS + 「完整报告 N 字符已落盘」；
     *  落盘失败降级返回全文 + 失败说明（成果不丢）。空报告不落盘原样返回；reportDir=null（未接线）
     *  视为旧语义原样返回——既有测试与降级路径不受影响。 */
    String persistReport(String report) {
        if (report == null || report.trim().isEmpty()) return report == null ? "" : report;
        if (reportDir == null) return report; // 未接线：原样返回（不算失败）
        java.nio.file.Path dumped = OutputDump.write(java.nio.file.Paths.get(reportDir),
                "subagent-report-" + no, report);
        if (dumped == null) {
            return report + "\n\n（报告落盘失败，以上为完整内容）";
        }
        String head;
        if (report.length() <= REPORT_MAX_CHARS) {
            head = report;
        } else {
            head = report.substring(0, REPORT_MAX_CHARS);
            // 截点可能落在代理对（emoji）中间：丢弃末尾孤立高代理，避免返回文本出现畸形字符
            // （与 OutputDump.tail 的代理对处理对齐；主代理按落盘路径取全文，损失可忽略）
            if (Character.isHighSurrogate(head.charAt(head.length() - 1))) {
                head = head.substring(0, head.length() - 1);
            }
        }
        String note = report.length() <= REPORT_MAX_CHARS
                ? "完整报告已落盘："
                : "完整报告 " + report.length() + " 字符已落盘：";
        return head + "\n\n（" + note + dumped.toAbsolutePath() + "，可用 Read 查看）";
    }

    /** 进入重试提示：长重试逐次发事件会长时间刷屏（429 每 5s 一次），只在首次进入时提示一条；
     *  恢复不另行提示（子代理无指示器，用户从后续内容行自然看出已恢复） */
    private void notifyRetryEnter(LlmException last) {
        ui.onSubAgentNotice(no, RetryProgress.tag(last) + "，正在按重试策略自动重试…");
    }

    /** 子代理上下文压缩：ContextCompressor 承载重试算法（与主代理同一套）；
     *  成功/无可压缩返回 null（继续任务），失败返回原因（调用方中止并返回失败文本），中断返回"已中断"。
     *  成功时原地替换 messages 内容：messages 是 final 的稳定引用（压缩结果本身是新列表实例） */
    private String compressSubContext() {
        final boolean[] notified = new boolean[1];
        ContextCompressor.Result r = new ContextCompressor(retryPolicy).run(contextManager, messages,
                new ContextCompressor.Sink() {
                    @Override public void onRetryProgress(RetryProgress p) {
                        if (notified[0]) return; // 长重试逐次提示会刷屏：只在进入重试时提示一次
                        notified[0] = true;
                        String label = p.label != null ? p.label : String.valueOf(p.httpCode);
                        ui.onSubAgentNotice(no, "上下文压缩失败（" + label + "），正在按重试策略自动重试…");
                    }
                    @Override public boolean interrupted() { return Thread.currentThread().isInterrupted(); }
                });
        if (r.outcome == ContextCompressor.Outcome.OK) {
            messages.clear();
            messages.addAll(r.messages); // 摘要置前 + pinned 任务提示词常驻（压缩结果由 ContextManager 保证）
            int pct = (int) (contextManager.estimate(messages) * 100 / contextManager.maxTokens());
            if (contextManager.shouldCompress(messages)) {
                // 压缩后仍超阈值：保留区（最近 6 组）被大输出占满，再压是空转——本子代理不再重复压缩
                compressIneffective = true;
                ui.onSubAgentNotice(no, "压缩后上下文仍占 " + pct
                        + "%（大输出占满保留区），后续不再重复压缩");
            } else {
                // spec 4.2：成功提示带压缩后百分比（算法与主代理自动压缩一致）
                ui.onSubAgentNotice(no, "已压缩上下文（降低至 " + pct + "%）");
            }
            return null;
        }
        if (r.outcome == ContextCompressor.Outcome.NOTHING) return null; // 阈值触发但暂无可压缩：继续
        if (r.outcome == ContextCompressor.Outcome.INTERRUPTED) return "已中断";
        return r.failReason;
    }

    /** 零增量闸门：已吐过正文/思考即不可长重试（与主循环一致，防重复输出） */
    private boolean noOutputYet(StringBuilder content, StringBuilder thinking) {
        return content.length() == 0 && thinking.length() == 0;
    }

    /** 可中断等待：100ms 小片轮询中断标志（与主循环 sleepWithInterruptCheck 一致；
     *  返回 false 表示已中断） */
    private boolean sleepWithInterruptCheck(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) return false;
            Thread.sleep(Math.min(100, end - System.currentTimeMillis()));
        }
        return !Thread.currentThread().isInterrupted();
    }

    /** 子 agent 工具集 = registry 全部工具 schema，剔除 task（防无限递归） */
    private List<JsonObject> subAgentTools() {
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (JsonObject s : registry.schemas()) {
            if ("task".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("AskUserQuestion".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            if ("Skill".equals(s.getAsJsonObject("function").get("name").getAsString())) continue;
            list.add(s);
        }
        return list;
    }

    /** 单工具执行：任何异常均转为错误 ToolResult，单个工具失败不终止整个子 agent */
    private ToolResult runOneTool(ToolCall call) {
        try {
            if ("task".equals(call.name)) {
                // 防御：即使模型违规调用，也不得再派发子 agent（防无限递归）
                return ToolResult.error("子 agent 不可再派发子 agent（task 工具已禁用）");
            }
            if ("AskUserQuestion".equals(call.name)) {
                // 防御：子 agent 不得挂起询问用户（AskUserQuestion 已从 schema 剔除；防模型幻觉调用）
                return ToolResult.error("子 agent 不可询问用户（AskUserQuestion 工具已禁用）");
            }
            if ("Skill".equals(call.name)) {
                // 防御：子 agent 不得加载技能（正文会注入主会话队列——污染编排者上下文）
                return ToolResult.error("子 agent 不可加载技能（Skill 工具已禁用）");
            }
            Tool tool = registry.get(call.name);
            if (tool == null) return ToolResult.error("工具不存在或已停用: " + call.name);
            JsonObject args;
            try {
                // 宽松解析：同主循环（尾部杂讯/未转义换行容忍，见 ToolArguments）
                args = ToolArguments.parse(call.arguments);
            } catch (Exception e) {
                return ToolResult.error("工具参数 JSON 解析失败: " + e.getMessage());
            }
            if (!confirmGate.check(tool, args)) {
                return ToolResult.error("用户拒绝了该操作（" + call.name + "）");
            }
            ui.onSubAgentToolCall(no, call.name, args);
            try {
                return tool.execute(args);
            } catch (Exception e) {
                return ToolResult.error("工具执行异常: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // 防御：参数类型非法等 unchecked 异常不得穿透，转错误结果继续循环
            return ToolResult.error("工具执行异常: " + e.getMessage());
        }
    }
}
