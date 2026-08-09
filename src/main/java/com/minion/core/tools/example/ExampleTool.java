package com.minion.core.tools.example;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** 示例工具：回显参数。仅用于测试与工具编写模板。 */
public class ExampleTool implements Tool {
    @Override
    public String name() { return "example"; }

    @Override
    public String description() { return "回显 text 参数（示例工具）"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("回显文本", new String[]{"text"}, new String[]{"text"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String text = args.has("text") ? args.get("text").getAsString() : "";
        return ToolResult.success("echo: " + text);
    }
}
