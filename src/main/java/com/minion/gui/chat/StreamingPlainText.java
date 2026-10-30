package com.minion.gui.chat;

/**
 * 流式正文增量展平（P1-4，纯逻辑、无 JavaFX 依赖，可单测）。
 *
 * 背景：旧实现每次合并窗口 flush 都对**累计全文**调用 {@link MarkdownRenderer#toPlainText}，
 * 即一条 10 万字符的回复在流式期间被 flexmark 全文解析上百次（总成本 O(n²)）。
 * 本类按**行**增量处理：已完成的行只展平一次并把结果缓存在 {@code out} 里，每帧只处理新增行
 * （以及未完成的最后一行），把「每帧全量解析」降为「每帧 O(新增字符)」。
 *
 * 语义：按行近似 {@code MarkdownRenderer.toPlainText} 的块规则——段落内软换行直接拼接（flexmark
 * 的 Text 节点不含换行）、块之间空行分隔、围栏代码块内容原样保留、列表项 "• " 前缀、
 * 表格单元格 " | "、标题/引用去标记、行内强调/代码/链接/删除线去标记。
 * 流式期间允许与精确解析存在边缘差异（嵌套列表、松散列表、setext 标题等），
 * **段定稿时由 ChatView 用一次精确 {@code toPlainText(全文)} 覆盖**，最终显示逐字一致。
 */
final class StreamingPlainText {

    /** 块类型（决定块间空行与行间分隔规则） */
    private enum Block { NONE, PARA, LIST, TABLE, CODE }

    /** 已完成行的展平结果（只追加，永不回改） */
    private final StringBuilder out = new StringBuilder();
    /** 已消费到的源码位置（恒为整行边界） */
    private int consumed = 0;
    /** 围栏代码块状态 */
    private boolean inFence = false;
    /** 当前块类型 */
    private Block block = Block.NONE;

    /** 重置（轮次边界/内容被替换：缓存与状态全部作废） */
    void reset() {
        out.setLength(0);
        consumed = 0;
        inFence = false;
        block = Block.NONE;
    }

    /** 已消费的源码长度（诊断/测试用） */
    int consumed() { return consumed; }

    /**
     * 展平累计 markdown：返回「已完成行缓存 + 未完成行近似」的完整文本。
     * 内容只会追加（StreamBuffer 语义），若检测到变短则视为被替换并整体重置。
     */
    String render(CharSequence md) {
        if (md == null) return "";
        int len = md.length();
        if (len < consumed) reset();
        int lineStart = consumed;
        for (int i = consumed; i < len; i++) {
            if (md.charAt(i) == '\n') {
                line(md.subSequence(lineStart, i).toString());
                lineStart = i + 1;
            }
        }
        consumed = lineStart;
        if (lineStart >= len) return out.toString();
        String tail = md.subSequence(lineStart, len).toString();
        return out.toString() + partial(tail);
    }

    // ---- 整行处理（会推进行状态） ----

    private void line(String raw) {
        String t = raw.trim();
        if (inFence) {
            if (isFenceClose(t)) {
                inFence = false;
                block = Block.NONE; // 代码块结束：下一个块自带空行分隔
                return;
            }
            appendCode(raw);
            return;
        }
        if (t.isEmpty()) {
            block = Block.NONE;
            return;
        }
        if (isFenceOpen(t)) {
            inFence = true;
            block = Block.NONE; // 内容行到达时才与上文空行分隔（空代码块不产出）
            return;
        }
        if (isThematicBreak(t)) {
            block = Block.NONE;
            return;
        }
        String heading = headingText(t);
        if (heading != null) {
            block = Block.NONE;
            appendPara(heading);
            block = Block.NONE; // 标题独占一块
            return;
        }
        if (t.startsWith(">")) {
            appendPara(stripQuote(t));
            return;
        }
        if (isListMarker(t)) {
            appendListItem(listItemText(t));
            return;
        }
        if (isTableRow(t)) {
            if (isTableSeparator(t)) return;
            appendTableRow(t);
            return;
        }
        appendPara(flattenInline(t));
    }

    /** 代码块内容行：原样保留（含缩进），块内换行分隔 */
    private void appendCode(String raw) {
        if (block != Block.CODE) {
            if (out.length() > 0) out.append("\n\n");
            block = Block.CODE;
        } else {
            out.append('\n');
        }
        out.append(raw);
    }

    /** 段落行：块内软换行直接拼接（与 flexmark collectText 一致），块间空行分隔 */
    private void appendPara(String text) {
        if (block != Block.PARA) {
            if (out.length() > 0) out.append("\n\n");
            block = Block.PARA;
        }
        out.append(text);
    }

    /** 列表项："• " 前缀，同一列表内换行分隔、新列表空行分隔 */
    private void appendListItem(String text) {
        if (block != Block.LIST) {
            if (out.length() > 0) out.append("\n\n");
            block = Block.LIST;
        } else {
            out.append('\n');
        }
        out.append("• ").append(text);
    }

    /** 表格行：单元格 " | " 连接，同一表格内换行分隔 */
    private void appendTableRow(String t) {
        if (block != Block.TABLE) {
            if (out.length() > 0) out.append("\n\n");
            block = Block.TABLE;
        } else {
            out.append('\n');
        }
        out.append(tableCells(t));
    }

    // ---- 未完成行（不推进状态，仅按当前块推演前缀） ----

    private String partial(String tail) {
        String t = tail.trim();
        if (inFence) {
            if (isFenceClose(t)) return "";
            if (t.isEmpty()) return "";
            return (block == Block.CODE ? "\n" : (out.length() > 0 ? "\n\n" : "")) + tail;
        }
        if (t.isEmpty()) return "";
        if (isFenceOpen(t) || isThematicBreak(t)) return "";
        String heading = headingText(t);
        String text;
        if (heading != null) {
            text = heading;
        } else if (t.startsWith(">")) {
            text = stripQuote(t);
        } else if (isListMarker(t)) {
            text = "• " + listItemText(t);
        } else if (isTableRow(t)) {
            if (isTableSeparator(t)) return "";
            text = tableCells(t);
        } else {
            text = flattenInline(t);
        }
        switch (block) {
            case NONE:
            case PARA:
                return (block == Block.NONE && out.length() > 0 ? "\n\n" : "") + text;
            default:
                return "\n" + text; // LIST/TABLE/CODE：行间换行
        }
    }

    // ---- 行内/行级识别 ----

    /** 围栏起始：``` 或 ~~~（≥3 个，允许前导空白与 info string） */
    static boolean isFenceOpen(String t) {
        return t.startsWith("```") || t.startsWith("~~~");
    }

    /** 围栏结束：整行只有 ≥3 个相同围栏字符（忽略尾部空白） */
    static boolean isFenceClose(String t) {
        String s = t;
        if (s.startsWith("```")) s = s.substring(3);
        else if (s.startsWith("~~~")) s = s.substring(3);
        else return false;
        return s.trim().isEmpty() || s.chars().allMatch(c -> c == '`' || c == '~');
    }

    /** 分隔线：≥3 个 * / - / _（可夹空白） */
    static boolean isThematicBreak(String t) {
        String s = t.replace(" ", "");
        if (s.length() < 3) return false;
        char c = s.charAt(0);
        if (c != '*' && c != '-' && c != '_') return false;
        for (int i = 1; i < s.length(); i++) if (s.charAt(i) != c) return false;
        return true;
    }

    /** ATX 标题：# 开头（1~6 个）后接空白；返回去标记后的标题文本，非标题返回 null */
    static String headingText(String t) {
        int i = 0;
        while (i < t.length() && i < 6 && t.charAt(i) == '#') i++;
        if (i == 0) return null;
        if (i < t.length() && t.charAt(i) != ' ') return null;
        return t.substring(Math.min(i + 1, t.length())).trim();
    }

    /** 去引用标记：逐层去掉前导 ">" 与空白 */
    static String stripQuote(String t) {
        String s = t;
        while (s.startsWith(">")) {
            s = s.substring(1);
            if (s.startsWith(" ")) s = s.substring(1);
            s = s.trim();
        }
        return s;
    }

    /** 列表项标记：短横/星号/加号 或「数字 + . 或 )」，标记后必须跟空白 */
    static boolean isListMarker(String t) {
        if (t.length() < 2) return false;
        char c = t.charAt(0);
        if (c == '-' || c == '*' || c == '+') return t.charAt(1) == ' ';
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i == 0 || i >= t.length()) return false;
        return (t.charAt(i) == '.' || t.charAt(i) == ')')
                && i + 1 < t.length() && t.charAt(i + 1) == ' ';
    }

    /** 列表项正文：去掉标记与后续空白 */
    static String listItemText(String t) {
        char c = t.charAt(0);
        if (c == '-' || c == '*' || c == '+') return t.substring(2).trim();
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        return t.substring(i + 2).trim();
    }

    /** 表格行：以 | 开头（Markdown 表格行的典型形态） */
    static boolean isTableRow(String t) {
        return t.startsWith("|");
    }

    /** 表格分隔行：|---|:--:| 这类（只有 | - : 空白且含 -） */
    static boolean isTableSeparator(String t) {
        if (t.indexOf('-') < 0) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return true;
    }

    /** 表格单元格文本：去首尾 |，按 | 切分后逐个去空白并以 " | " 连接（与 toPlainText 一致） */
    static String tableCells(String t) {
        String s = t.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        StringBuilder sb = new StringBuilder();
        String[] cells = s.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(flattenInline(cells[i].trim()));
        }
        return sb.toString();
    }

    /** 行内标记展平（近似）：图片/链接取可见文本，代码/强调/删除线去定界符，反斜杠转义去转义符 */
    static String flattenInline(String s) {
        if (s.isEmpty()) return s;
        if (s.indexOf('*') < 0 && s.indexOf('_') < 0 && s.indexOf('`') < 0
                && s.indexOf('[') < 0 && s.indexOf('~') < 0 && s.indexOf('\\') < 0) {
            return s;
        }
        String r = s;
        r = r.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");      // 图片 → alt
        r = r.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");       // 链接 → 文本
        r = r.replaceAll("`+([^`]*)`+", "$1");                       // 行内代码
        r = r.replaceAll("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*", "$1");  // 粗体
        r = r.replaceAll("__(?=\\S)(.+?)(?<=\\S)__", "$1");
        r = r.replaceAll("~~(?=\\S)(.+?)(?<=\\S)~~", "$1");          // 删除线
        r = r.replaceAll("(?<![*\\w])\\*(?=\\S)(.+?)(?<=\\S)\\*(?![*\\w])", "$1"); // 斜体 *
        r = r.replaceAll("(?<![\\w_])_(?=\\S)(.+?)(?<=\\S)_(?![\\w_])", "$1");     // 斜体 _
        r = r.replace("\\", "");                                     // 转义符
        return r;
    }
}
