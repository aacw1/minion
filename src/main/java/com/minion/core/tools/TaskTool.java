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
    public String description() { return "派发一个子 agent 完成独立子任务（完整工具集，可并行）。参数 description 说明任务。"
            + "子 agent 报告已自动落盘，返回内容含完整报告路径——不要重复落盘，需要细节时用 Read 读取该路径"; }

    @Override
    public JsonObject schema() {
        // 只声明 description：旧 prompt 参数全仓无消费点（runner 只读 description），
        // 出现在 schema/描述里会误导模型传无用参数（终审 P3 清理）
        return SchemaGenerator.objectSchema("派发子 agent 任务",
                new String[]{"description"}, new String[]{"description"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("description")) return ToolResult.error("缺少 description 参数");
        String result = loop.runSubAgent(args);
        return ToolResult.success(result);
    }
}
