package com.minion.gui.input;

/** 补全条目：label 显示文本 / insertText 选中后插入文本 / desc 描述（右对齐灰字）。
 *  {@link #lower} 是构造期缓存的 label 小写：过滤/排序在每次敲键时对全量条目做比较，
 *  十万级文件列表下逐次 toLowerCase() 的字符串分配本身就是可感知卡顿（P0-6）。 */
public class Suggestion {

    /** 条目类型（样式/图标预留） */
    public enum Type { COMMAND, SKILL, FILE }

    public final String label;
    public final String insertText;
    public final String desc;
    public final Type type;
    /** label 的小写缓存（过滤/排序用；label 为 null 时为空串） */
    public final String lower;

    public Suggestion(String label, String insertText, String desc, Type type) {
        this.label = label;
        this.insertText = insertText;
        this.desc = desc;
        this.type = type;
        this.lower = label == null ? "" : label.toLowerCase();
    }
}
