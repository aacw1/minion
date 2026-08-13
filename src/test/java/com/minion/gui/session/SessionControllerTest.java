package com.minion.gui.session;

import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.gui.session.EventList.Ev;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 历史消息重演：USER→USER_MESSAGE、ASSISTANT content→CONTENT，工具过程跳过 */
public class SessionControllerTest {

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

    /** 工具消息/系统消息/空 content/assistant 工具调用 全部跳过 */
    @Test
    public void replayHistory_skipsToolAndSystemAndEmpty() {
        SessionController c = new SessionController();
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.system("system prompt"));
        msgs.add(Message.toolResult("tc1", "ReadTool", "file content"));
        Message withCall = Message.assistant(null);
        withCall.toolCalls = new ArrayList<ToolCall>(); // 仅工具调用无 content
        msgs.add(withCall);
        msgs.add(Message.assistant(""));
        c.replayHistory(msgs);
        assertEquals(0, c.eventList().size());
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

    /** ask_user 状态转发：开始（带问题）→ 完成（null） */
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
}
