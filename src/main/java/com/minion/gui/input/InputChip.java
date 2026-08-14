package com.minion.gui.input;

import java.util.List;

/** 输入块（chip）：弹层确认的 /命令、@文件 与粘贴长文本在输入框上方的不可编辑块。
 *  纯模型 + 纯静态逻辑（compose/阈值/类型映射），可脱离 JavaFX 单测。 */
public final class InputChip {

    /** 块类型：COMMAND 斜杠命令 / SKILL 技能 / FILE 文件 / PASTE 粘贴长文本 */
    public enum Type { COMMAND, SKILL, FILE, PASTE }

    /** 粘贴变块阈值（字符数，含换行） */
    public static final int PASTE_CHIP_THRESHOLD = 100;

    public final Type type;
    /** 发送用的完整文本（命令/路径/粘贴全文） */
    public final String content;
    /** 块上显示的文本（粘贴块为「粘贴内容，N 字符」，其余同 content） */
    public final String display;

    private InputChip(Type type, String content, String display) {
        this.type = type;
        this.content = content;
        this.display = display;
    }

    /** 命令/技能/文件块：显示 = 内容 */
    public static InputChip textChip(Type type, String content) {
        return new InputChip(type, content, content);
    }

    /** 粘贴块：显示「粘贴内容，N 字符」 */
    public static InputChip pasteChip(String content) {
        return new InputChip(Type.PASTE, content, "粘贴内容，" + content.length() + " 字符");
    }

    /** 弹层模式 → 块类型（NONE 不进确认路径，兜底 COMMAND） */
    public static Type modeToType(CompletionParser.Mode mode) {
        switch (mode) {
            case SLASH: return Type.COMMAND;
            case SLASH_SKILL: return Type.SKILL;
            case FILE: return Type.FILE;
            default: return Type.COMMAND;
        }
    }

    /** 粘贴是否变块：长度（含换行）≥ 阈值 */
    public static boolean shouldChipPaste(String text) {
        return text != null && text.length() >= PASTE_CHIP_THRESHOLD;
    }

    /** 组装发送文本：块按列表顺序单个空格连接；文本非空时补一个空格再原样追加 */
    public static String compose(List<InputChip> chips, String text) {
        StringBuilder sb = new StringBuilder();
        for (InputChip c : chips) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.content);
        }
        if (text != null && !text.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(text);
        }
        return sb.toString();
    }
}
