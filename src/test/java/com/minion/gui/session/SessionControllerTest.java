package com.minion.gui.session;

import com.minion.core.llm.ImagePart;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ToolResult;
import com.minion.gui.session.EventList.Ev;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** 历史消息重演：USER→USER_MESSAGE、ASSISTANT content→CONTENT，工具过程跳过 */
public class SessionControllerTest {

    /** 恢复历史：带图 user 消息事件文本拼图片占位（聊天区不渲染图片本体） */
    @Test
    public void replayHistory_userWithImages_emitsPlaceholder() {
        SessionController c = new SessionController();
        ImagePart img = new ImagePart();
        img.mime = "image/png"; img.base64 = "QUJD"; img.name = "截图.png";
        Message m = Message.userWithImages("看这张图", Collections.singletonList(img));
        c.replayHistory(Collections.singletonList(m));
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.USER_MESSAGE, evs.get(0).kind);
        assertEquals("图片：截图.png 看这张图", evs.get(0).text);
    }

    @Test
    public void replayHistory_convertsUserAndAssistant() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("你好"));
        msgs.add(Message.assistant("你好，我是助手"));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.USER_MESSAGE, evs.get(0).kind);
        assertEquals("你好", evs.get(0).text);
        assertEquals(EventList.Kind.CONTENT, evs.get(1).kind);
        assertEquals("你好，我是助手", evs.get(1).text);
    }

    /** 历史回放：assistant 带 reasoningContent → 先 THINKING 事件（完整文本）再 CONTENT。
     *  回归保护：重启恢复会话后【思考】段不丢失（此前 replayHistory 只重演 content） */
    @Test
    public void replayHistory_assistantWithReasoning_emitsThinkingThenContent() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        Message m = Message.assistant("正文回复");
        m.reasoningContent = "思考过程";
        msgs.add(m);
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.THINKING, evs.get(0).kind);
        assertEquals("思考过程", evs.get(0).text);
        assertEquals(EventList.Kind.CONTENT, evs.get(1).kind);
        assertEquals("正文回复", evs.get(1).text);
    }

    /** 历史回放：TOOL 结果消息 → TOOL_RESULT 事件；系统消息/空 content 跳过 */
    @Test
    public void replayHistory_skipsSystemAndEmpty() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("system prompt"));
        msgs.add(Message.toolResult("tc1", "ReadTool", "file content"));
        Message withCall = Message.assistant(null);
        withCall.toolCalls = new ArrayList<ToolCall>(); // 仅工具调用无 content
        msgs.add(withCall);
        msgs.add(Message.assistant(""));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.TOOL_RESULT, evs.get(0).kind);
        assertEquals("ReadTool", evs.get(0).text);
    }

    /** 历史回放：assistant 工具调用 → 逐个 TOOL_CALL 事件（name+参数），
     *  TOOL 结果消息 → TOOL_RESULT 成功态（历史无成败标记，统一按成功显示）。
     *  回归保护：重启恢复会话后工具调用过程不丢失 */
    @Test
    public void replayHistory_toolCallsAndResults_emitsCallThenResult() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        Message withCall = Message.assistant(null);
        ToolCall tc = new ToolCall();
        tc.name = "ReadTool";
        tc.arguments = "{\"path\":\"a.txt\"}";
        withCall.toolCalls = new ArrayList<ToolCall>();
        withCall.toolCalls.add(tc);
        msgs.add(withCall);
        msgs.add(Message.toolResult("tc1", "ReadTool", "file content"));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.TOOL_CALL, evs.get(0).kind);
        assertEquals("ReadTool", evs.get(0).text);
        assertEquals("{\"path\":\"a.txt\"}", evs.get(0).data);
        assertEquals(EventList.Kind.TOOL_RESULT, evs.get(1).kind);
        assertEquals("ReadTool", evs.get(1).text);
        assertEquals("ok\nfile content", evs.get(1).data);
    }

    @Test
    public void onUserSupplement_emitsSupplementEvent() {
        SessionController c = new SessionController();
        c.onUserSupplement("补充内容");
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("补充内容", evs.get(0).text);
    }

    /** 历史回放：supplement=true 的 USER 消息 → USER_SUPPLEMENT 事件 */
    @Test
    public void replayHistory_userSupplement_emitsSupplementEvent() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.userSupplement("历史补充"));
        msgs.add(Message.user("普通消息"));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("历史补充", evs.get(0).text);
        assertEquals(EventList.Kind.USER_MESSAGE, evs.get(1).kind);
    }

    /** AskUserQuestion 回答显示（设计 2026-08-16）：回答经 USER_SUPPLEMENT 事件入流（【输入】段），
     *  消息区提问与回答成对显示 */
    @Test
    public void onAskUserDone_answer_emitsSupplementEvent() {
        SessionController c = new SessionController();
        c.onAskUserDone("方案B");
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("方案B", evs.get(0).text);
    }

    /** 空回答不投递（与「输入为空不发」一致），仅复位状态 */
    @Test
    public void onAskUserDone_emptyAnswer_emitsNothing() {
        SessionController c = new SessionController();
        c.onAskUserDone("");
        c.onAskUserDone(null);
        assertEquals(0, c.eventList().snapshot().size());
    }

    /** 历史回放：AskUserQuestion 的 TOOL 消息（回答在 output）→ 先 USER_SUPPLEMENT 回答行再 TOOL_RESULT ✅，
     *  恢复会话后提问与回答成对显示，顺序与运行时一致 */
    @Test
    public void replayHistory_askUserToolResult_emitsAnswerThenResult() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.toolResult("tc1", "AskUserQuestion", "方案B"));
        c.replayHistory(msgs);
        List<Ev> evs = c.eventList().snapshot();
        assertEquals(2, evs.size());
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("方案B", evs.get(0).text);
        assertEquals(EventList.Kind.TOOL_RESULT, evs.get(1).kind);
        assertEquals("AskUserQuestion", evs.get(1).text);
    }

    /** AskUserQuestion 状态转发：开始（带问题）→ 完成（null） */
    @Test
    public void askStateListener_startAndDone() {
        SessionController c = new SessionController();
        final List<String> states = new ArrayList<String>();
        c.setAskStateListener(new java.util.function.Consumer<String>() {
            @Override public void accept(String question) { states.add(question); }
        });
        c.onAskUserStart("选哪个？");
        c.onAskUserDone("方案B");
        assertEquals(2, states.size());
        assertEquals("选哪个？", states.get(0));
        assertNull(states.get(1));
    }

    // ===== 完整输出携带与重演（设计 2026-08-22 工具详情完整展示，Task 3）=====

    private static List<Ev> replay(Message... messages) {
        SessionController c = new SessionController();
        c.replayHistory(java.util.Arrays.asList(messages));
        return c.eventList().snapshot();
    }

    /** 运行时成功结果：TOOL_RESULT data 携带完整输出（"ok\n"+output），不再只有 "ok" 标记 */
    @Test
    public void onToolResult_success_carriesFullOutput() {
        SessionController c = new SessionController();
        c.onToolResult("Bash", ToolResult.success("line1\nline2\nline3"));
        Ev ev = c.eventList().snapshot().get(0);
        assertEquals(EventList.Kind.TOOL_RESULT, ev.kind);
        assertEquals("Bash", ev.text);
        assertEquals("ok\nline1\nline2\nline3", ev.data);
    }

    /** 运行时失败结果：data 为 "error:"+完整原因（多行不截断） */
    @Test
    public void onToolResult_error_carriesFullReason() {
        SessionController c = new SessionController();
        c.onToolResult("Edit", ToolResult.error("第一行原因\n第二行原因"));
        Ev ev = c.eventList().snapshot().get(0);
        assertEquals("error:第一行原因\n第二行原因", ev.data);
    }

    /** null 结果（异常路径）降级为成功态 "ok"，不抛 NPE */
    @Test
    public void onToolResult_nullResult_okOnly() {
        SessionController c = new SessionController();
        c.onToolResult("Bash", null);
        Ev ev = c.eventList().snapshot().get(0);
        assertEquals("ok", ev.data);
    }

    /** 成功但输出为空串：保留 "ok\n" 前缀（换行后空串），渲染层按成功态解析 */
    @Test
    public void onToolResult_success_emptyOutput_okPrefix() {
        SessionController c = new SessionController();
        c.onToolResult("Bash", ToolResult.success(""));
        Ev ev = c.eventList().snapshot().get(0);
        assertEquals("ok\n", ev.data);
    }

    /** 历史 TOOL 消息重演：输出携带存储的完整内容（"ok\n"+content） */
    @Test
    public void replayHistory_toolMessage_carriesStoredOutput() {
        List<Ev> evs = replay(Message.toolResult("id1", "Bash", "pwd 输出内容"));
        assertEquals(1, evs.size());
        assertEquals(EventList.Kind.TOOL_RESULT, evs.get(0).kind);
        assertEquals("Bash", evs.get(0).text);
        assertEquals("ok\npwd 输出内容", evs.get(0).data);
    }

    /** 历史 AskUserQuestion 回答：先重演回答行（USER_SUPPLEMENT）再 ✅ 行，✅ 行携带完整输出 */
    @Test
    public void replayHistory_askUserQuestion_answerThenOkLine() {
        List<Ev> evs = replay(Message.toolResult("id2", "AskUserQuestion", "用户回答文本"));
        assertEquals(2, evs.size()); // 回答行（USER_SUPPLEMENT）+ ✅ 行
        assertEquals(EventList.Kind.USER_SUPPLEMENT, evs.get(0).kind);
        assertEquals("用户回答文本", evs.get(0).text);
        assertEquals(EventList.Kind.TOOL_RESULT, evs.get(1).kind);
        assertEquals("ok\n用户回答文本", evs.get(1).data);
    }

    /** 历史 AskUserQuestion 失败输出（空参数快速失败）不得被当成用户回答重演成【输入】行：
     *  历史 TOOL 消息无成败标记，只能靠约定的失败前缀识别 */
    @Test
    public void replayHistory_askUserQuestion_invalidCallFailure_notReplayedAsAnswer() {
        String reason = com.minion.core.tools.AskUserQuestionTool.INVALID_PREFIX
                + "必须提供非空 question，请重新发起提问";
        List<Ev> evs = replay(Message.toolResult("id5", "AskUserQuestion", reason));
        assertEquals("失败原因只应有一行工具结果，不得多出回答行", 1, evs.size());
        assertEquals(EventList.Kind.TOOL_RESULT, evs.get(0).kind);
        assertTrue("失败原因仍要完整可见", ((String) evs.get(0).data).contains("必须提供非空 question"));
    }

    /** 历史 TOOL 消息内容为空：统一成功态且不产生多余换行（data="ok"） */
    @Test
    public void replayHistory_toolMessage_emptyContent_okOnly() {
        List<Ev> evs = replay(Message.toolResult("id3", "Bash", ""));
        assertEquals(1, evs.size());
        assertEquals("ok", evs.get(0).data);
    }

    /** 压缩状态回调：onCompressingChanged 转发到 compressingStateListener */
    @Test
    public void onCompressingChanged_forwardsToListener() {
        SessionController c = new SessionController();
        final java.util.List<Boolean> got = new java.util.ArrayList<Boolean>();
        c.setCompressingStateListener(new java.util.function.Consumer<Boolean>() {
            @Override public void accept(Boolean b) { got.add(b); }
        });
        c.onCompressingChanged(true);
        c.onCompressingChanged(false);
        assertEquals(java.util.Arrays.asList(true, false), got);
    }

    /** 上下文统计：onContextStats 转发到注入的监听器（环形进度圈数据链） */
    @Test
    public void contextStats_forwardsToListener() {
        SessionController c = new SessionController();
        final int[] got = new int[2];
        c.setContextStatsListener(new java.util.function.Consumer<SessionController.ContextStat>() {
            @Override public void accept(SessionController.ContextStat stat) {
                got[0] = stat.used;
                got[1] = stat.max;
            }
        });
        c.onContextStats(98000, 900000);
        assertEquals(98000, got[0]);
        assertEquals(900000, got[1]);
    }
}
