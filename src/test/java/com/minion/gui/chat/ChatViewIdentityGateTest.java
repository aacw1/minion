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
}
