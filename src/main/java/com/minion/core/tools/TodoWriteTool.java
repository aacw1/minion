package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.TodoList;

/** 任务清单跟踪。参数: action=create|update|mark（可省略，默认 create）, items, index(mark时) */
public class TodoWriteTool implements Tool {

    private final TodoList list;

    public TodoWriteTool(TodoList list) { this.list = list; }

    @Override
    public String name() { return "TodoWrite"; }

    @Override
    public String description() { return "维护任务清单：create/update 整体替换任务列表，mark 勾选完成任务"; }

    @Override
    public JsonObject schema() {
        // 手写精确 schema：SchemaGenerator.objectSchema 全按 string 生成，
        // 曾把 items 声明为 string，误导模型传字符串数组导致 execute 抛 Not a JSON Object
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "维护任务清单：create/update 整体替换任务列表，mark 勾选完成任务");

        JsonObject props = new JsonObject();

        // action 可选、默认 create：与 Claude Code 语义一致（省略 action 即创建列表），
        // 曾设为 required 且枚举缺 create，模型按习惯省略 action 时收到"未知 action: "错误
        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "操作类型（可省略，默认 create）：create/update 替换任务列表，mark 勾选第 index 项");
        JsonArray actionEnum = new JsonArray();
        actionEnum.add("create");
        actionEnum.add("update");
        actionEnum.add("mark");
        action.add("enum", actionEnum);
        props.add("action", action);

        JsonObject items = new JsonObject();
        items.addProperty("type", "array");
        items.addProperty("description", "update 时的新任务列表，每项为 {text, done}（兼容纯字符串项）");
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        JsonObject itemProps = new JsonObject();
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "任务内容");
        itemProps.add("text", text);
        JsonObject done = new JsonObject();
        done.addProperty("type", "boolean");
        done.addProperty("description", "是否已完成，默认 false");
        itemProps.add("done", done);
        item.add("properties", itemProps);
        JsonArray itemRequired = new JsonArray();
        itemRequired.add("text");
        item.add("required", itemRequired);
        items.add("items", item);
        props.add("items", items);

        JsonObject index = new JsonObject();
        index.addProperty("type", "integer");
        index.addProperty("description", "mark 时的任务下标（从 0 开始）");
        props.add("index", index);

        schema.add("properties", props);
        // action 非必填：省略时默认 create
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String action = args.has("action") ? args.get("action").getAsString() : "";
        if (action.isEmpty() || action.equals("create") || action.equals("update")) {
            JsonArray arr = args.has("items") && args.get("items").isJsonArray()
                    ? args.getAsJsonArray("items") : new JsonArray();
            java.util.List<TodoList.TodoItem> items = new java.util.ArrayList<TodoList.TodoItem>();
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonElement el = arr.get(i);
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    // 容错：模型可能把 items 传成字符串数组（schema 修复前的历史行为）
                    items.add(new TodoList.TodoItem(el.getAsString(), false));
                } else {
                    JsonObject o = el.getAsJsonObject();
                    items.add(new TodoList.TodoItem(
                            o.has("text") ? o.get("text").getAsString() : "",
                            o.has("done") && o.get("done").getAsBoolean()));
                }
            }
            list.replace(items);
            return ToolResult.success("任务清单:\n" + list.render());
        }
        if (action.equals("mark")) {
            if (!args.has("index")) return ToolResult.error("缺少 index 参数");
            boolean ok = list.markDone(args.get("index").getAsInt());
            if (!ok) return ToolResult.error("index 超出范围");
            return ToolResult.success("任务清单:\n" + list.render());
        }
        return ToolResult.error("未知 action: " + action + "（应为 create、update 或 mark）");
    }
}
