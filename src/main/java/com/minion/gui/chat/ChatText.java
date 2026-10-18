package com.minion.gui.chat;

/** 消息区文本清洗（纯逻辑、无 JavaFX 依赖，可单测）。
 *
 *  U+FFFD 是编码解码失败时产生的替换字符（如 Windows 控制台 GBK 输出被按 UTF-8 解码）。
 *  JavaFX 8 文本引擎在换行计算（PrismTextLayout/TextRun.getWrapIndex）遇到它时可能越界，
 *  而该异常发生在 Scene 的布局 pass 中：一个节点抛异常会中断整轮布局，表现为整页错乱
 *  （消息区/侧栏不显示、输入框按钮重叠、光标位置错位）。该字符本身只是"无法解码"占位、
 *  无信息价值，显示为 '?' 视觉等价且不触发该缺陷（探针实证：同文本替换后布局正常）。
 */
final class ChatText {

    private ChatText() { }

    /** 不可解码替换字符 → '?'，其余原样；null 归一为空串（TextArea 不接受 null） */
    static String sanitize(String text) {
        if (text == null) return "";
        if (text.indexOf('\uFFFD') < 0) return text;
        return text.replace('\uFFFD', '?');
    }
}
