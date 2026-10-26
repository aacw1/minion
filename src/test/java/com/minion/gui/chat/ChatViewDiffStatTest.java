package com.minion.gui.chat;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ChatViewDiffStatTest {

    @Test public void countsPlusAndMinusLines() {
        assertEquals("+2 −1", ChatView.diffStat("+ a\n- b\n+ c"));
    }

    @Test public void returnsNullWhenNoChanges() {
        assertNull(ChatView.diffStat(""));
        assertNull(ChatView.diffStat("no diff here"));
    }
}
