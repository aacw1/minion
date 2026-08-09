package com.minion.core.tools.confirm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class FakeConfirmUi implements ConfirmUi {
    private final Queue<Decision> queue = new ArrayDeque<Decision>();
    public final List<String> asked = new ArrayList<String>();

    public FakeConfirmUi(Decision... decisions) {
        for (Decision d : decisions) queue.add(d);
    }

    @Override
    public Decision ask(String message) {
        asked.add(message);
        Decision d = queue.poll();
        return d == null ? Decision.APPROVE : d;
    }
}
