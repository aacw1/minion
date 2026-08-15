package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;

import java.util.concurrent.CompletableFuture;

/** 向用户提问：execute 挂起等待回答（无超时），回答经 complete() 送达后作为工具结果返回。
 *  与 Claude Code 同名 AskUserQuestion；子 agent 禁用（SubAgentLoop 过滤 schema + 同名防御）。 */
public class AskUserQuestionTool implements Tool {

    private final AgentUi ui;
    /** 当前挂起的等待（单槽：同轮多次调用共享同一回答；null=未挂起） */
    private volatile CompletableFuture<String> pending;

    public AskUserQuestionTool(AgentUi ui) { this.ui = ui; }

    @Override
    public String name() { return "AskUserQuestion"; }

    @Override
    public String description() {
        return "向用户提问或请求选择。当缺少完成任务所需信息、需要用户确认方案或做出选择时调用。"
                + "参数 question 为必填问题文本；header 为可选简短标题；options 为可选答案列表"
                + "（2-4 个，每项含 label/description）；multiSelect 表示是否可多选。"
                + "调用后将挂起等待用户回答，回答会作为工具结果返回。";
    }

    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "向用户提问（缺少信息/需确认方案/需选择时）");
        JsonObject props = new JsonObject();
        JsonObject question = new JsonObject();
        question.addProperty("type", "string");
        question.addProperty("description", "要问用户的问题");
        JsonObject header = new JsonObject();
        header.addProperty("type", "string");
        header.addProperty("description", "简短标题（可选）");
        JsonObject options = new JsonObject();
        options.addProperty("type", "array");
        options.addProperty("description", "可选答案列表，2-4 个");
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        JsonObject label = new JsonObject();
        label.addProperty("type", "string");
        label.addProperty("description", "选项文本");
        JsonObject desc = new JsonObject();
        desc.addProperty("type", "string");
        desc.addProperty("description", "选项说明");
        itemProps.add("label", label);
        itemProps.add("description", desc);
        items.add("properties", itemProps);
        options.add("items", items);
        JsonObject multiSelect = new JsonObject();
        multiSelect.addProperty("type", "boolean");
        multiSelect.addProperty("description", "是否可多选（可选）");
        props.add("question", question);
        props.add("header", header);
        props.add("options", options);
        props.add("multiSelect", multiSelect);
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("question");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        String question = args.has("question") && !args.get("question").isJsonNull()
                ? args.get("question").getAsString()
                : "请提供完成任务所需的信息";
        CompletableFuture<String> fut;
        final boolean owner;
        synchronized (this) {
            fut = this.pending; // 同轮多次调用共享同一 future：重入链到已有挂起而非新建
            if (fut == null) {
                fut = new CompletableFuture<String>();
                this.pending = fut;
                owner = true;
            } else {
                owner = false;
            }
        }
        if (owner) ui.onAskUserStart(question);
        try {
            String answer = fut.get(); // 阻塞到 answerAskUser 或线程中断（终止）
            if (owner) ui.onAskUserDone(answer);
            return ToolResult.success(answer);
        } finally {
            // 仅清自己占据的槽位：跟随者先结束时不得误清新一轮的 pending
            if (this.pending == fut) this.pending = null;
        }
    }

    /** 用户回答入口（AgentLoop.answerAskUser 转发）；无挂起时忽略并返回 false */
    public boolean complete(String answer) {
        CompletableFuture<String> fut = pending;
        if (fut == null) return false;
        fut.complete(answer);
        return true;
    }
}
