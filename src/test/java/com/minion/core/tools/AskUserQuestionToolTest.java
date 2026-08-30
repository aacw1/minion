package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.RecordingUi;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** AskUserQuestion 工具：挂起等待回答；无挂起时 complete 忽略；缺 question 回退默认文案 */
public class AskUserQuestionToolTest {

    @Test
    public void complete_withoutPending_returnsFalse() {
        AskUserQuestionTool tool = new AskUserQuestionTool(new RecordingUi());
        assertFalse(tool.complete("无人等待"));
    }

    @Test
    public void execute_blocksUntilAnswered() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
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

    /** 行为变更（2026-08-30）：空参数提不出任何内容 → 快速失败回传模型自重发，
     *  不再挂起等用户回答（旧实现兜底常量「请提供完成任务所需的信息」照样挂起，
     *  用户面对空白提问无从作答）。规约 3：工具错误返回失败 ToolResult 给模型自调 */
    @Test
    public void execute_emptyArgs_failsFastWithoutSuspending() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        ToolResult r = tool.execute(new JsonObject());
        assertFalse("提不出内容的提问应返回失败", r.ok);
        assertTrue("失败原因应指导模型补 question: " + r.output, r.output.contains("question"));
        assertTrue("不得进入挂起等待", ui.asksStarted.isEmpty());
        assertFalse("未挂起则 complete 无效", tool.complete("随便答"));
    }

    /** 线上实证形态：question 键丢失但内容可从 options 字符串救回 → 仍挂起，
     *  且 onAskUserStart 携带的是提取出的真实问题（输入框占位与消息区同源） */
    @Test
    public void execute_swallowedQuestion_asksWithExtractedText() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        final JsonObject args = new JsonObject();
        JsonArray opts = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("label", "方案甲");
        opts.add(a);
        JsonObject b = new JsonObject();
        b.addProperty("label", "方案乙");
        opts.add(b);
        args.addProperty("options", opts.toString()); // options 退化为字符串，无 question 键
        final ToolResult[] result = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    result[0] = tool.execute(args);
                } catch (Exception e) {
                    result[0] = ToolResult.error("异常: " + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("可提取内容的提问应挂起", 1, ui.asksStarted.size());
        assertEquals("占位提示应用首个选项 label 兜底", "方案甲", ui.asksStarted.get(0));
        assertTrue(tool.complete("方案乙"));
        t.join(5000);
        assertNotNull(result[0]);
        assertTrue(result[0].ok);
        assertEquals("方案乙", result[0].output);
    }

    /** 计数刹车（第 3 次修复轮新增）：AgentLoop 主循环无轮次上限，纯快速失败会「失败→重发→失败」
     *  无限循环卡死会话。规则：第 1 次无效回传失败让模型自纠（线上实证第二次即发对），
     *  连续第 2 次改挂起软兜底把决策权交回用户 */
    @Test
    public void secondConsecutiveEmptyArgs_suspendsInsteadOfFailing() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        ToolResult first = tool.execute(new JsonObject());
        assertFalse("第 1 次仍回传失败给模型自纠", first.ok);

        final ToolResult[] second = new ToolResult[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    second[0] = tool.execute(new JsonObject());
                } catch (Exception e) {
                    second[0] = ToolResult.error("异常: " + e.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("连续第 2 次无效应挂起而非继续失败", 1, ui.asksStarted.size());
        assertTrue("挂起文案应说明是参数格式异常: " + ui.asksStarted.get(0),
                ui.asksStarted.get(0).contains("格式"));
        assertTrue(tool.complete("你要问的是什么"));
        t.join(5000);
        assertFalse(t.isAlive());
        assertNotNull(second[0]);
        assertTrue(second[0].ok);
        assertEquals("你要问的是什么", second[0].output);
    }

    /** 成功提问归零计数：一次正常提问后再畸形，仍应先给模型一次自纠机会 */
    @Test
    public void validAskResetsStrikeCounter() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        assertFalse(tool.execute(new JsonObject()).ok); // strike 1
        JsonObject good = JsonParser.parseString("{\"question\":\"选哪个？\"}").getAsJsonObject();
        final Object[] holder = new Object[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    holder[0] = tool.execute(good);
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        tool.complete("方案A");
        t.join(5000);
        assertEquals("正常提问应挂起等待", 1, ui.asksStarted.size());
        // 计数归零：下一次畸形仍是失败（不是挂起）
        ToolResult again = tool.execute(new JsonObject());
        assertFalse("成功提问后计数应归零，再来一次仍是首次失败", again.ok);
        assertEquals("挂起次数不应增加", 1, ui.asksStarted.size());
    }

    /** 占位/软兜底文案不得被恢复路径当成失败前缀（否则历史里重演错位） */
    @Test
    public void fallbackPlaceholder_notConfusedWithInvalidPrefix() {
        AskUserQuestionTool.Ask empty = AskUserQuestionTool.normalize(new JsonObject());
        assertFalse(empty.placeholder().startsWith(AskUserQuestionTool.INVALID_PREFIX));
    }

    /** 中断可解除挂起（原 execute_missingQuestion_usesFallbackText 顺带覆盖，行为变更后单列保留） */
    @Test
    public void execute_interrupted_unblocksThread() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    tool.execute(JsonParser.parseString("{\"question\":\"选哪个？\"}").getAsJsonObject());
                } catch (Exception ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (ui.asksStarted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(1, ui.asksStarted.size());
        t.interrupt();
        t.join(5000);
        assertFalse("中断后挂起线程必须退出", t.isAlive());
    }

    /** 同轮两次并行 execute 共享同一回答：一次 complete 唤醒两者（回归：旧实现第二把
     *  execute 覆盖 pending 使第一把成为孤儿永远挂起，AgentLoop 按序等待只能终止回合） */
    @Test
    public void twoParallelExecutes_shareOneAnswer() throws Exception {
        RecordingUi ui = new RecordingUi();
        final AskUserQuestionTool tool = new AskUserQuestionTool(ui);
        final ToolResult[] results = new ToolResult[2];
        final CountDownLatch entered = new CountDownLatch(2);
        Runnable task = new Runnable() {
            @Override public void run() {
                ToolResult r;
                try {
                    JsonObject args = JsonParser.parseString(
                            "{\"question\":\"选哪个？\"}").getAsJsonObject();
                    r = tool.execute(args);
                } catch (Exception e) {
                    r = ToolResult.error("异常: " + e.getMessage());
                }
                synchronized (results) {
                    if (results[0] == null) results[0] = r; else results[1] = r;
                }
                entered.countDown();
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.setDaemon(true); t2.setDaemon(true); // 挂起线程不得阻碍测试 JVM 退出（孤儿线程仍阻塞）
        t1.start(); t2.start();
        // 等待两个 execute 都进入挂起：WAITING = 已链到槽位 future 并阻塞在 fut.get()
        long deadline = System.currentTimeMillis() + 5000;
        while ((t1.getState() != Thread.State.WAITING || t2.getState() != Thread.State.WAITING)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("两个 execute 都应进入挂起等待",
                t1.getState() == Thread.State.WAITING && t2.getState() == Thread.State.WAITING);
        assertTrue(tool.complete("答案"));
        assertTrue("一次 complete 应唤醒两个并行 execute", entered.await(10, TimeUnit.SECONDS));
        assertEquals("答案", results[0].output);
        assertEquals("答案", results[1].output);
        // 共享同一回答：仅首个 execute（owner）发起提问与结束回调
        assertEquals(1, ui.asksStarted.size());
        assertEquals(1, ui.asksDone.size());
    }

    /** schema 契约：question 必填；options 数组；multiSelect 布尔 */
    @Test
    public void schema_hasQuestionRequiredAndOptionsArray() {
        JsonObject schema = new AskUserQuestionTool(new RecordingUi()).schema();
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
