package com.minion.gui.input;

import java.util.List;

/** 输入块（chip）：弹层确认的 /命令、@文件、粘贴长文本与上传图片在输入框上方的不可编辑块。
 *  纯模型 + 纯静态逻辑（compose/阈值/类型映射），可脱离 JavaFX 单测。 */
public final class InputChip {

    /** 块类型：COMMAND 斜杠命令 / SKILL 技能 / FILE 文件 / PASTE 粘贴长文本 / IMAGE 图片 */
    public enum Type { COMMAND, SKILL, FILE, PASTE, IMAGE }

    /** 粘贴变块阈值（字符数，含换行）：长度大于阈值才变块，否则走原生粘贴 */
    public static final int PASTE_CHIP_THRESHOLD = 1000;

    public final Type type;
    /** 发送用的完整文本（命令/路径/粘贴全文） */
    public final String content;
    /** 块上显示的文本（粘贴块为「粘贴内容，N 字符」，其余同 content） */
    public final String display;
    /** 粘贴块在输入文本中的行内占位符（粘贴时插入光标处，发送时原位展开为 content）；其余块为 null */
    public final String placeholder;

    private InputChip(Type type, String content, String display, String placeholder) {
        this.type = type;
        this.content = content;
        this.display = display;
        this.placeholder = placeholder;
    }

    /** 命令/技能/文件块：显示 = 内容 */
    public static InputChip textChip(Type type, String content) {
        return new InputChip(type, content, content, null);
    }

    /** 粘贴块：显示「粘贴内容，N 字符」；placeholder 为输入文本光标处的行内占位符 */
    public static InputChip pasteChip(String content, String placeholder) {
        return new InputChip(Type.PASTE, content, "粘贴内容，" + content.length() + " 字符", placeholder);
    }

    /** 粘贴块（无占位符）：compose 时与其他块同置文本之前 */
    public static InputChip pasteChip(String content) {
        return pasteChip(content, null);
    }

    /** 图片块：content = data URI（发送时解析拆 mime/base64），display = 图片：<文件名> */
    public static InputChip imageChip(String mime, String base64, String name) {
        return new InputChip(Type.IMAGE, "data:" + mime + ";base64," + base64, "图片：" + name, null);
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

    /** 粘贴是否变块：长度（含换行）大于阈值 */
    public static boolean shouldChipPaste(String text) {
        return text != null && text.length() > PASTE_CHIP_THRESHOLD;
    }

    /** 组装发送文本：带占位符的粘贴块在文本中占位符处原位展开（保持光标落位）；
     *  其余块按列表顺序单个空格连接置于文本之前；文本非空时补一个空格再追加。
     *  图片块跳过（走独立图片通道）；粘贴块占位符不在文本中则跳过（reconcile 已同步，防御重复） */
    public static String compose(List<InputChip> chips, String text) {
        String body = text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        for (InputChip c : chips) {
            if (c == null || c.type == Type.IMAGE || c.placeholder != null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.content);
        }
        for (InputChip c : chips) {
            if (c == null || c.placeholder == null) continue;
            body = body.replace(c.placeholder, c.content);
        }
        if (!body.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(body);
        }
        return sb.toString();
    }
}
