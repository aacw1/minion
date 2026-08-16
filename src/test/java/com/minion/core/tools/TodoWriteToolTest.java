package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.TodoList;
import org.junit.Test;

import static org.junit.Assert.*;

public class TodoWriteToolTest {

    private final TodoList list = new TodoList();
    private final TodoWriteTool tool = new TodoWriteTool(list);

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    public void update_replacesList() {
        ToolResult r = tool.execute(args("{\"action\":\"update\",\"items\":[{\"text\":\"任务A\",\"done\":false},{\"text\":\"任务B\",\"done\":true}]}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("- [ ] 任务A"));
        assertTrue(r.output.contains("- [x] 任务B"));
        assertEquals(2, list.items.size());
    }

    @Test
    public void mark_checksIndex() {
        tool.execute(args("{\"action\":\"update\",\"items\":[{\"text\":\"A\",\"done\":false},{\"text\":\"B\",\"done\":false}]}"));
        ToolResult r = tool.execute(args("{\"action\":\"mark\",\"index\":0}"));
        assertTrue(r.ok);
        assertTrue(list.items.get(0).done);
        assertFalse(list.items.get(1).done);
    }

    @Test
    public void mark_outOfRange_error() {
        tool.execute(args("{\"action\":\"update\",\"items\":[{\"text\":\"A\",\"done\":false}]}"));
        ToolResult r = tool.execute(args("{\"action\":\"mark\",\"index\":9}"));
        assertFalse(r.ok);
    }

    // 回归：schema 曾把所有参数声明为 string，误导模型把 items 传成字符串数组，
    // execute 对字符串元素 getAsJsonObject() 抛 Not a JSON Object
    @Test
    public void schema_declaresRealTypes() {
        com.google.gson.JsonObject s = tool.schema();
        com.google.gson.JsonObject props = s.getAsJsonObject("properties");
        // items 必须是数组（元素为 {text, done}），而非 string
        com.google.gson.JsonObject items = props.getAsJsonObject("items");
        assertEquals("array", items.get("type").getAsString());
        com.google.gson.JsonObject itemSchema = items.getAsJsonObject("items");
        assertEquals("object", itemSchema.get("type").getAsString());
        assertTrue(itemSchema.getAsJsonObject("properties").has("text"));
        assertTrue(itemSchema.getAsJsonObject("properties").has("done"));
        // index 必须是整数
        assertEquals("integer", props.getAsJsonObject("index").get("type").getAsString());
        // action 应给出枚举（含 create），且非必填（省略即创建）
        com.google.gson.JsonArray actionEnum = props.getAsJsonObject("action").getAsJsonArray("enum");
        assertEquals(3, actionEnum.size());
        assertEquals("create", actionEnum.get(0).getAsString());
        assertEquals("update", actionEnum.get(1).getAsString());
        assertEquals("mark", actionEnum.get(2).getAsString());
        assertFalse(s.has("required"));
    }

    // 回归：模型按 Claude Code 习惯省略 action（默认创建），曾收到"未知 action: "错误
    @Test
    public void noAction_defaultsToCreate() {
        ToolResult r = tool.execute(args("{\"items\":[{\"text\":\"任务A\",\"done\":false}]}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("- [ ] 任务A"));
        assertEquals(1, list.items.size());
    }

    @Test
    public void create_replacesList() {
        tool.execute(args("{\"items\":[{\"text\":\"A\",\"done\":true}]}"));
        ToolResult r = tool.execute(args("{\"action\":\"create\",\"items\":[{\"text\":\"B\",\"done\":false}]}"));
        assertTrue(r.ok);
        assertEquals(1, list.items.size());
        assertEquals("B", list.items.get(0).text);
        assertFalse(list.items.get(0).done);
    }

    // 容错：模型仍可能把 items 传成字符串数组，此时应转为任务项而非抛异常
    @Test
    public void update_itemsAsStringList_tolerated() {
        ToolResult r = tool.execute(args("{\"action\":\"update\",\"items\":[\"任务A\",\"任务B\"]}"));
        assertTrue(r.ok);
        assertEquals(2, list.items.size());
        assertEquals("任务A", list.items.get(0).text);
        assertEquals("任务B", list.items.get(1).text);
        assertFalse(list.items.get(0).done);
    }
}
