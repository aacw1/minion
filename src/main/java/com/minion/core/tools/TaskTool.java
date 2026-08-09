package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;

/** 派发子 agent。由 AgentLoop 提供执行器。 */
public class TaskTool implements Tool {

    private final AgentLoop loop;

    public TaskTool(AgentLoop loop) { this.loop = loop; }

    @Override
    public String name() { return "task"; }

    @Override
    public String description() { return "派发一个子 agent 完成独立子任务（完整工具集，可并行）。参数 description 说明任务，prompt 可选指定返回格式"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("派发子 agent 任务",
                new String[]{"description", "prompt"}, new String[]{"description"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("description")) return ToolResult.error("缺少 description 参数");
        String result = loop.runSubAgent(args);
        return ToolResult.success(result);
    }
}
