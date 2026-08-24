package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** 子 agent：独立消息数组 + 完整工具集（无 task），无轮数上限，返回最终文本 */
public class SubAgentLoop {

    private static final String SUB_SYSTEM_SUFFIX =
            "\n\n你是一个子 agent。只负责完成上述任务，完成后用最终文本总结结果（不要客套）。";

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    /** 429 限流长重试策略（与主循环一致；测试可覆写小参数） */
    public RetryPolicy retryPolicy429 = RetryPolicy.rateLimit();
    private final List<Message> messages = new ArrayList<Message>();

    public SubAgentLoop(String systemPrompt, String taskDescription, String workDir,
                        LlmClient llm, ToolRegistry registry, ConfirmGate confirmGate, AgentUi ui) {
        this.llm = llm;
        this.registry = registry;
        this.confirmGate = confirmGate;
        this.ui = ui;
        messages.add(Message.system(systemPrompt + SUB_SYSTEM_SUFFIX));
        messages.add(Message.user("任务: " + taskDescription));
    }

    public String run() {
        ui.onSubAgentStart(messages.get(1).content);
        int retries = 0;
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
                        thinking.append(delta);
                        ui.onThinking(delta);
                    }
                    @Override
                    public void onContent(String delta) {
                        content.append(delta);
                        ui.onSubAgentDelta(delta);
                    }
                    @Override
                    public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                        finish[0] = finishReason;
                        usage[0] = u;
                        toolCalls[0] = tcs;
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
                    if (e.type == LlmException.Type.RATE_LIMIT) {
                        // 429 长重试与主循环一致：2s 起步 +2s 递增，上限 10s，总时长 30 分钟；
                        // 进度经 onRetryProgress 进左下角指示器，成功轻提示恢复，超时一次性总结停止
                        int attempts = 0;
                        long waited = 0;
                        boolean exhausted = false; // 超时总结标志：break 后统一复位指示器再返回
                        String failure = null; // 重试中遇非 429 错误的文案：break 后统一复位指示器再返回
                        while (true) {
                            attempts++;
                            long delay = retryPolicy429.delayMs(attempts);
                            if (!sleepWithInterruptCheck(delay)) break; // 中断
                            waited += delay;
                            if (retryPolicy429.isExhausted(waited)) {
                                ui.onError("子 agent 429 重试了 " + attempts + " 次，持续 "
                                        + (waited / 60000) + " 分钟仍失败，已停止重试");
                                exhausted = true;
                                break;
                            }
                            ui.onRetryProgress(attempts); // 指示器显示"正在重试中…第 N 次"
                            try {
                                llm.streamChat(messages, subAgentTools(), handler);
                                // 重试成功但流中断（onError 回调已提示）：不再报"已恢复"
                                if (!"error".equals(finish[0])) {
                                    ui.onWarning("子 agent 已恢复，继续执行");
                                }
                                break; // 成功：finish/toolCalls 已回调，走正常路径
                            } catch (LlmException re) {
                                if (Thread.currentThread().isInterrupted()) break;
                                if (re.type != LlmException.Type.RATE_LIMIT) {
                                    // 重试中遇非 429 错误（网络/超时/5xx）：与主循环一致，
                                    // break 退出重试态、统一复位指示器，不再继续退避
                                    ui.onError("子 agent 请求失败: " + re.getMessage());
                                    failure = re.getMessage();
                                    break;
                                }
                                // 仍 429：继续退避
                            }
                        }
                        ui.onRetryProgress(0); // 退出重试态（成功/超时/中断/非 429 失败统一复位）
                        if (Thread.currentThread().isInterrupted()) {
                            ui.onWarning("子 agent 已中断");
                            return "子 agent 已中断";
                        }
                        if (exhausted) {
                            return "子 agent 失败: 429 持续 " + (waited / 60000) + " 分钟"; // 已 onError
                        }
                        if (failure != null) {
                            return "子 agent 失败: " + failure; // 已 onError
                        }
                        if (finish[0] == null && usage[0] == null) {
                            // 防御兜底：正常退出必有 finish/usage 回调（成功 break 后）或
                            // exhausted/failure 标志，理论不可达；保留旧文案以防回归误判
                            return "子 agent 失败: 429 重试超时"; // 已 onError
                        }
                        // 重试成功：落入下方正常处理
                    } else if (e.retryable && retries < 1) {
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
                    ui.onSubAgentDone(content.toString());
                    return content.toString();
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
                    messages.add(Message.toolResult(call.id, call.name, result.output));
                    ui.onToolResult(call.name, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断标志，不吞掉中断
            ui.onRetryProgress(0); // 429 重试等待中被真实中断（future.cancel(true)）：复位指示器
            ui.onWarning("子 agent 已中断");
            return "子 agent 已中断";
        } catch (Exception e) {
            ui.onError("子 agent 异常: " + e.getMessage());
            return "子 agent 异常: " + e.getMessage();
        }
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
            if (tool == null) return ToolResult.error("未知工具: " + call.name);
            JsonObject args;
            try {
                args = JsonParser.parseString(call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
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
