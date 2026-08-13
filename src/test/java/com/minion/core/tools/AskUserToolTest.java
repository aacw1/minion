package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.RecordingUi;
import org.junit.Test;

import static org.junit.Assert.*;

/** ask_user 工具：挂起等待回答；无挂起时 complete 忽略；缺 question 回退默认文案 */
public class AskUserToolTest {

    @Test
    public void complete_withoutPending_returnsFalse() {
        AskUserTool tool = new AskUserTool(new RecordingUi());
        assertFalse(tool.complete("无人等待"));
    }

    @Test
    public void execute_blocksUntilAnswered() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserTool tool = new AskUserTool(ui);
        final ToolResult[] result = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JsonObject args = JsonParser.parseString(
                            "{\"question\":\"选哪个？\"}").getAsJsonObject();
                    result[0] = tool.execute(args);
                } catch (Exception e) {
                    result[0] = ToolResult.error("异常: " + e.getMessage());
                }
            }
        });
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue("未进入挂起（onAskUserStart 未回调）", ui.asksStarted.size() == 1);
        assertEquals("选哪个？", ui.asksStarted.get(0));
        assertTrue(tool.complete("方案B"));
        t.join(5000);
        assertFalse(t.isAlive());
        assertNotNull(result[0]);
        assertTrue(result[0].ok);
        assertEquals("方案B", result[0].output);
        assertEquals(1, ui.asksDone.size());
        assertEquals("方案B", ui.asksDone.get(0));
        // 完成后 pending 清空：再次 complete 无效
        assertFalse(tool.complete("再来一次"));
    }

    @Test
    public void execute_missingQuestion_usesFallbackText() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserTool tool = new AskUserTool(ui);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { tool.execute(new JsonObject()); } catch (Exception ignored) { }
            }
        });
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        t.interrupt(); // 不回答，直接打断（验证中断可解除阻塞）
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(1, ui.asksStarted.size());
        assertFalse("缺少 question 应回退默认文案", ui.asksStarted.get(0).isEmpty());
    }

    /** schema 契约：question 必填；options 数组；multiSelect 布尔 */
    @Test
    public void schema_hasQuestionRequiredAndOptionsArray() {
        JsonObject schema = new AskUserTool(new RecordingUi()).schema();
        assertEquals("object", schema.get("type").getAsString());
        assertEquals(1, schema.getAsJsonArray("required").size());
        assertEquals("question", schema.getAsJsonArray("required").get(0).getAsString());
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("question"));
        assertTrue(props.has("header"));
        assertTrue(props.has("options"));
        assertEquals("array", props.getAsJsonObject("options").get("type").getAsString());
        assertEquals("boolean", props.getAsJsonObject("multiSelect").get("type").getAsString());
    }
}
