package com.minion.gui.input;

/** 补全条目：label 显示文本 / insertText 选中后插入文本 / desc 描述（右对齐灰字） */
public class Suggestion {

    /** 条目类型（样式/图标预留） */
    public enum Type { COMMAND, SKILL, FILE }

    public final String label;
    public final String insertText;
    public final String desc;
    public final Type type;

    public Suggestion(String label, String insertText, String desc, Type type) {
        this.label = label;
        this.insertText = insertText;
        this.desc = desc;
        this.type = type;
    }
}
