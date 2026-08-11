package com.minion.core.llm;

public class UsageTracker {
    private int sessionInput;
    private int sessionOutput;
    private int sessionThinking;
    private Usage last;

    public synchronized void record(Usage usage) {
        if (usage == null) return;
        last = usage;
        sessionInput += usage.inputTokens;
        sessionOutput += usage.outputTokens;
        sessionThinking += usage.reasoningTokens;
    }

    public synchronized int sessionInput() { return sessionInput; }
    public synchronized int sessionOutput() { return sessionOutput; }
    public synchronized int sessionThinking() { return sessionThinking; }
    public synchronized int sessionTotal() { return sessionInput + sessionOutput; }
    public synchronized Usage last() { return last; }

    /** 清零本轮统计（/new 复用实例，不清引用） */
    public synchronized void reset() {
        sessionInput = 0;
        sessionOutput = 0;
        sessionThinking = 0;
        last = null;
    }

    /** 从另一 tracker 复制统计（/resume 原地恢复：Main/CommandDispatcher 捕获的是会话
     *  usage 实例引用，换实例会让后续统计与 /tokens 展示落到已废弃实例上） */
    public synchronized void restore(UsageTracker from) {
        if (from == null) return;
        sessionInput = from.sessionInput();
        sessionOutput = from.sessionOutput();
        sessionThinking = from.sessionThinking();
        last = from.last();
    }
}
