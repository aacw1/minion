package com.minion.core.agent;

/** 新会话标题生成：本地截取（前 MAX_TITLE_LEN 字），不再调 LLM 摘要 */
public class TitleGenerator {

    public static final int MAX_TITLE_LEN = 20;

    /** 本地标题：去换行/首尾空白，截取前 20 字；空输入回退「新会话」 */
    public static String localTitle(String text) {
        // \r\n 先整体替换为单空格，否则 \r、\n 各自替换会出现双空格（测试 localTitle_normalizesNewlinesAndTrim 断言）
        String t = text == null ? "" : text.trim().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (t.length() > MAX_TITLE_LEN) t = t.substring(0, MAX_TITLE_LEN);
        return t.isEmpty() ? "新会话" : t;
    }
}
