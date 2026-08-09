package com.minion.core.agent;

import java.util.ArrayList;
import java.util.List;

/** 任务清单（会话内状态） */
public class TodoList {

    public static class TodoItem {
        public String text;
        public boolean done;

        public TodoItem() { }

        public TodoItem(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    public final List<TodoItem> items = new ArrayList<TodoItem>();

    public void replace(List<TodoItem> newItems) {
        items.clear();
        items.addAll(newItems);
    }

    public boolean markDone(int index) {
        if (index < 0 || index >= items.size()) return false;
        items.get(index).done = true;
        return true;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            sb.append("- ").append(item.done ? "[x]" : "[ ]")
              .append(' ').append(item.text).append('\n');
        }
        return sb.toString();
    }
}
