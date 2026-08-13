package com.minion.gui.input;

/** 补全触发解析：按光标位置提取当前「词」，判定 /命令、@文件 或普通文本。纯静态，可单测。
 *  词边界 = 空白（含换行）；前一词为 /skill 时当前词按技能名补全。 */
public final class CompletionParser {

    /** 补全模式：NONE 普通文本 / SLASH 斜杠命令 / SLASH_SKILL 技能名 / FILE 文件 */
    public enum Mode { NONE, SLASH, SLASH_SKILL, FILE }

    /** 解析结果：mode 模式 / query 过滤词（不含前缀）/ start,end 替换区间（含前缀） */
    public static final class Token {
        public final Mode mode;
        public final String query;
        public final int start;
        public final int end;

        public Token(Mode mode, String query, int start, int end) {
            this.mode = mode;
            this.query = query;
            this.start = start;
            this.end = end;
        }
    }

    public static Token parse(String text, int caret) {
        if (text == null || text.isEmpty()) return new Token(Mode.NONE, "", 0, 0);
        int c = Math.max(0, Math.min(caret, text.length()));
        // 当前词：空白分隔
        int s = c;
        while (s > 0 && !isSep(text.charAt(s - 1))) s--;
        int e = c;
        while (e < text.length() && !isSep(text.charAt(e))) e++;
        String word = text.substring(s, e);
        if (word.startsWith("/")) {
            return new Token(Mode.SLASH, word.substring(1), s, e);
        }
        if (word.startsWith("@")) {
            return new Token(Mode.FILE, word.substring(1), s, e);
        }
        // 普通词：前一词是 /skill → 技能名补全
        int ps = s;
        while (ps > 0 && isSep(text.charAt(ps - 1))) ps--;
        int pe = ps;
        while (pe > 0 && !isSep(text.charAt(pe - 1))) pe--;
        String prev = pe == ps ? "" : text.substring(pe, ps);
        if ("/skill".equalsIgnoreCase(prev)) {
            return new Token(Mode.SLASH_SKILL, word, s, e);
        }
        return new Token(Mode.NONE, "", s, e);
    }

    private static boolean isSep(char ch) {
        return Character.isWhitespace(ch);
    }

    private CompletionParser() { }
}
