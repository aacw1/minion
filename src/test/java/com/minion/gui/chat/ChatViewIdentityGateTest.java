package com.minion.gui.chat;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatViewIdentityGateTest {

    @Test public void showsOnlyOncePerRound() {
        ChatView.IdentityGate gate = new ChatView.IdentityGate();
        gate.onUserMessage();
        assertTrue(gate.consume(0, true));
        assertFalse(gate.consume(0, true));
    }

    @Test public void ignoresSubAgentAndUserSegments() {
        ChatView.IdentityGate gate = new ChatView.IdentityGate();
        gate.onUserMessage();
        assertFalse(gate.consume(2, true));
        assertFalse(gate.consume(0, false));
        assertTrue(gate.consume(0, true));
    }

    @Test public void newUserMessageRearms() {
        ChatView.IdentityGate gate = new ChatView.IdentityGate();
        gate.onUserMessage();
        assertTrue(gate.consume(0, true));
        gate.onUserMessage();
        assertTrue(gate.consume(0, true));
    }

    /** 斜杠命令结果走 SYSTEM 事件（log-sys）：不是 AI 输出，不得消费身份名额
     *  （否则 /help、/tokens 等本地结果上方会冒出「m minion」） */
    @Test public void systemSegmentsDoNotConsumeIdentity() {
        assertFalse(ChatView.consumesIdentity("log-sys"));
    }

    /** AI 正文段（log-reply）消费身份名额；用户回显、本地系统行与本地警告/错误同样不消费 */
    @Test public void replySegmentsConsumeIdentity() {
        assertTrue(ChatView.consumesIdentity("log-reply"));
        assertFalse(ChatView.consumesIdentity("log-input"));
        assertFalse(ChatView.consumesIdentity("log-warn"));
        assertFalse(ChatView.consumesIdentity("log-error"));
        assertFalse(ChatView.consumesIdentity(null));
    }
}
