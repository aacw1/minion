package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolArguments;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.OutputDump;
import com.minion.core.tools.Tool;
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
        messages.add(Message.user("任务: " + taskDescription));
    }

    /** 消息数组（压缩判断/测试断言用） */
    public List<Message> messages() { return messages; }

    public String run() {
        ui.onSubAgentStart(messages.get(1).content);
        int retries = 0;
        final boolean[] inRetry = new boolean[1]; // 瞬时错误重试循环进行中（首个流式增量到达即复位指示器）；方法级：外层 InterruptedException 需访问
        try {
            while (true) {
                // 中断路径：主循环 interrupt() 取消 in-flight 工具 future → 本线程中断 → 立即中止
                if (Thread.currentThread().isInterrupted()) {
                    ui.onWarning("子 agent 已中断");
                    return "子 agent 已中断";
                }
                final List<ToolCall>[] toolCalls = new List[1];
                final String[] finish = new String[1];
                final Usage[] usage = new Usage[1];
                final StringBuilder content = new StringBuilder();
                final StringBuilder thinking = new StringBuilder();
                final com.minion.core.llm.StreamHandler handler = new com.minion.core.llm.StreamHandler() {
                    @Override
                    public void onThinking(String delta) {
                        resetRetryOnFirstDelta();
                        thinking.append(delta);
                        ui.onThinking(delta);
                    }
                    @Override
                    public void onContent(String delta) {
                        resetRetryOnFirstDelta();
                        content.append(delta);
                        ui.onSubAgentDelta(delta);
                    }
                    @Override
                    public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                        resetRetryOnFirstDelta(); // 零增量成功（如纯 tool_calls 回复）兜底复位
                        finish[0] = finishReason;
                        usage[0] = u;
                        toolCalls[0] = tcs;
                    }
                    /** 重试成功后的首个流式回调：立即复位指示器（"重试中"文案消失） */
                    void resetRetryOnFirstDelta() {
                        if (inRetry[0]) {
                            inRetry[0] = false;
                            ui.onRetryProgress(RetryProgress.none());
                        }
                    }
                    @Override
                    public void onError(LlmException e) { finish[0] = "error"; ui.onError(e.getMessage()); }
                };
                try {
                    llm.streamChat(messages, subAgentTools(), handler);
                } catch (LlmException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        // 已被主循环中断（cancel 引发的 Canceled 错误）：不重试
                        ui.onWarning("子 agent 已中断");
                        return "子 agent 已中断";
                    }
                    if (RetryPolicy.isTransient(e) && noOutputYet(content, thinking)) {
                        // 瞬时错误长重试与主循环一致：按最近一次错误类别等待（429/超时/网络 5s、500 类/空响应 30s），
                        // 墙钟总时长 12 分钟；覆盖 429/超时/可恢复网络错误/500 类（含 503/504）/空响应；
                        // 零增量闸门防重复输出
                        int attempts = 0;
                        long retryStart = System.currentTimeMillis(); // 墙钟基准：含每次请求自身耗时
                        long elapsed = 0;                             // 耗尽时的真实耗时（返回值文案用）
                        boolean exhausted = false; // 超时总结标志：break 后统一复位指示器再返回
                        String failure = null;     // 重试中遇非瞬时错误的文案：break 后统一复位指示器再返回
                        LlmException last = e;
                        inRetry[0] = true;
                        while (true) {
                            attempts++;
                            long delay = retryPolicy.delayMs(RetryPolicy.kindOf(last));
                            ui.onRetryProgress(RetryProgress.from(attempts, last, delay)); // 尝试前更新指示器（含等待时长）
                            if (!sleepWithInterruptCheck(delay)) break; // 中断
                            elapsed = System.currentTimeMillis() - retryStart;
                            if (retryPolicy.isExhausted(elapsed)) {
                                ui.onError("子 agent " + RetryProgress.tag(last) + " 重试了 " + attempts
                                        + " 次，持续 " + (elapsed / 60000) + " 分钟仍失败，已停止重试");
                                exhausted = true;
                                break;
                            }
                            try {
                                llm.streamChat(messages, subAgentTools(), handler);
                                // 成功后静默恢复（不打扰正文）：首个流式增量/onFinish 已复位指示器，
                                // 若流中断（onError 回调已提示）则落下方正常路径处理
                                break;
                            } catch (LlmException re) {
                                if (Thread.currentThread().isInterrupted()) break;
                                if (!RetryPolicy.isTransient(re) || !noOutputYet(content, thinking)) {
                                    // 永久性/非瞬时错误（DNS 配错、其他 5xx、已吐字断流）：退出重试，
                                    // 统一复位指示器，不再继续退避
                                    ui.onError("子 agent 请求失败: " + re.getMessage());
                                    failure = re.getMessage();
                                    break;
                                }
                                last = re; // 仍可重试：指示器标签/错误体随最近一次失败更新
                            }
                        }
                        if (inRetry[0]) { inRetry[0] = false; ui.onRetryProgress(RetryProgress.none()); } // 退出重试态统一复位（幂等）
                        if (Thread.currentThread().isInterrupted()) {
                            ui.onWarning("子 agent 已中断");
                            return "子 agent 已中断";
                        }
                        if (exhausted) {
                            return "子 agent 失败: " + RetryProgress.tag(last) + " 持续 " + (elapsed / 60000) + " 分钟"; // 已 onError
                        }
                        if (failure != null) {
                            return "子 agent 失败: " + failure; // 已 onError
                        }
                        if (finish[0] == null && usage[0] == null) {
                            // 防御兜底：正常退出必有 finish/usage 回调（成功 break 后）或
                            // exhausted/failure 标志，理论不可达；保留旧文案以防回归误判
                            return "子 agent 失败: " + RetryProgress.tag(last) + " 重试超时"; // 已 onError
                        }
                        // 重试成功：落入下方正常处理
                    } else if (e.retryable && retries < 1 && noOutputYet(content, thinking)) {
                        // 兜底：可重试但未归类错误（现主流错误均已被长重试覆盖，此分支实际不可达）
                        retries++;
                        ui.onWarning("子 agent 请求失败（" + e.getMessage() + "），自动重试 1 次");
                        // 退避与主循环一致：429 限流 2s，其余（网络/超时）0.5s
                        Thread.sleep(e.type == LlmException.Type.RATE_LIMIT ? 2000 : 500);
                        continue; // 消息未变，直接重发本轮
                    } else {
                        ui.onError("子 agent 请求失败: " + e.getMessage());
                        return "子 agent 失败: " + e.getMessage();
                    }
                }
                if (toolCalls[0] == null || toolCalls[0].isEmpty()
                        || !"tool_calls".equals(finish[0])) {
                    String report = content.toString();
                    String ret = persistReport(report);
                    ui.onSubAgentDone(ret);
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
                            ToolResult.outputForApi(result.output, emptyOutputPlaceholder)));
                    ui.onToolResult(call.name, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志，不吞掉中断
            if (inRetry[0]) { inRetry[0] = false; ui.onRetryProgress(RetryProgress.none()); } // 瞬时错误重试等待中被真实中断（future.cancel(true)）：复位指示器
            ui.onWarning("子 agent 已中断");
            return "子 agent 已中断";
        } catch (Exception e) {
            ui.onError("子 agent 异常: " + e.getMessage());
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
        String head = report.length() <= REPORT_MAX_CHARS
                ? report : report.substring(0, REPORT_MAX_CHARS);
        String note = report.length() <= REPORT_MAX_CHARS
                ? "完整报告已落盘："
                : "完整报告 " + report.length() + " 字符已落盘：";
        return head + "\n\n（" + note + dumped.toAbsolutePath() + "，可用 Read 查看）";
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
            ui.onToolCall(call.name, args);
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
