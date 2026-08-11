package com.minion.core.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UsageTrackerTest {

    @Test
    public void accumulates() {
        UsageTracker t = new UsageTracker();
        Usage u1 = new Usage();
        u1.inputTokens = 100; u1.outputTokens = 50; u1.reasoningTokens = 20;
        Usage u2 = new Usage();
        u2.inputTokens = 200; u2.outputTokens = 30; u2.reasoningTokens = 0;
        t.record(u1);
        t.record(u2);
        assertEquals(300, t.sessionInput());
        assertEquals(80, t.sessionOutput());
        assertEquals(20, t.sessionThinking());
        assertEquals(380, t.sessionTotal());
        assertEquals(u2, t.last());
    }

    @Test
    public void reset_clearsCountersAndLast() {
        UsageTracker t = new UsageTracker();
        Usage u = new Usage();
        u.inputTokens = 100; u.outputTokens = 50; u.reasoningTokens = 20;
        t.record(u);
        t.reset();
        assertEquals(0, t.sessionInput());
        assertEquals(0, t.sessionOutput());
        assertEquals(0, t.sessionThinking());
        assertEquals(0, t.sessionTotal());
        assertEquals(null, t.last());
    }
}
