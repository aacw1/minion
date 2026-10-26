package com.minion.gui.chat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ChatViewContentPaddingTest {

    @Test public void wideWindowCentersContent() {
        assertEquals(186.0, ChatView.contentPadding(1200), 0.001);
    }

    @Test public void narrowWindowUsesMinimumPadding() {
        assertEquals(20.0, ChatView.contentPadding(400), 0.001);
    }

    @Test public void exactlyMaxWidthUsesMinimumPadding() {
        assertEquals(20.0, ChatView.contentPadding(868), 0.001);
    }
}
