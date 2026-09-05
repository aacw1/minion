package com.minion.core.tools.db;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 校验（纯函数，第一层防护）。
 * 三大驱动的 executeQuery 都是「先执行再判断有没有 ResultSet」，单靠它挡不住写操作，
 * 故在执行前用首关键词白名单 + 多语句检测 + 危险子串检测拦一道。
 * check 返回 null 表示放行，否则返回可直接展示给模型的中文拒绝原因。
 * 已知取舍：字符串字面量里出现 FOR UPDATE 之类会被误拒（宁可误拒不可误放）；
 * 最终兜底应由数据源侧的只读账号完成。
 */
public final class SqlGuard {

    /** 拒绝原因统一前缀（DbTool 原样透传给模型） */
    private static final String REJECT = "只读工具拒绝执行：";

    /** 放行的首关键词（Oracle 上 DESC/DESCRIBE 是 SQL*Plus 命令、JDBC 会报错，保留无害） */
    private static final Set<String> ALLOWED = new HashSet<String>(Arrays.asList(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN"));

    /** 出现即拒的子串（整词边界匹配、忽略大小写），文案与 FORBIDDEN_PATTERNS 一一对应 */
    private static final String[] FORBIDDEN = {
            "INTO OUTFILE", "INTO DUMPFILE", "FOR UPDATE", "LOCK IN SHARE MODE"};

    /** 前导注释/空白：-- 行注释、# 行注释（MySQL）、块注释、空白 */
    private static final Pattern LEADING = Pattern.compile(
            "^(?:\\s|--[^\\n]*(\\n|$)|#[^\\n]*(\\n|$)|/\\*[\\s\\S]*?\\*/)+");

    /** 任意连续空白：危险子串匹配前压成单个空格 */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 整词匹配：前后不能是字母/数字/下划线（防 information 里的 for、for_update_at 列名误伤） */
    private static final Pattern[] FORBIDDEN_PATTERNS = new Pattern[FORBIDDEN.length];

    static {
        for (int i = 0; i < FORBIDDEN.length; i++) {
            FORBIDDEN_PATTERNS[i] = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(FORBIDDEN[i]) + "(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE);
        }
    }

    private SqlGuard() { }

    /**
     * @param sql 模型提交的语句
     * @return null 表示放行；非 null 为拒绝原因（可直接作为 ToolResult 错误文案）
     */
    public static String check(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "SQL 不能为空";
        String body = stripLeadingComments(sql);
        if (body.isEmpty()) return "SQL 不能为空";

        String head = firstKeyword(body);
        if (head.isEmpty()) return REJECT + "无法识别语句首关键词";
        if (!ALLOWED.contains(head)) {
            return REJECT + "语句以 " + head + " 开头"
                    + "（仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN）";
        }
        // 多语句：body 已去首尾空白，最多允许末尾一个终止分号，去掉后仍含分号即为多条
        // （"SELECT 1;;" 这种尾部空语句也算多语句，不能整段剥分号）
        String single = body;
        if (single.endsWith(";")) single = single.substring(0, single.length() - 1);
        if (single.indexOf(';') >= 0) return REJECT + "不允许多条语句";

        // 危险子串在「空白压平」后的文本上匹配：关键字间允许任意空白/换行，否则 INTO\nOUTFILE 漏判
        String flat = SPACES.matcher(body).replaceAll(" ");
        for (int i = 0; i < FORBIDDEN_PATTERNS.length; i++) {
            if (FORBIDDEN_PATTERNS[i].matcher(flat).find()) {
                return REJECT + "语句含 " + FORBIDDEN[i];
            }
        }
        return null;
    }

    /** 循环剥前导注释与空白（防块注释多层包裹，如两个块注释连续紧贴再接写语句） */
    static String stripLeadingComments(String sql) {
        String s = sql;
        while (true) {
            String cur = s.trim();
            Matcher m = LEADING.matcher(cur);
            if (!m.find()) return cur;
            String next = cur.substring(m.end()).trim();
            if (next.equals(cur)) return next;   // 无进展，防死循环
            s = next;
        }
    }

    /** 首关键词：截到第一个空白、括号、分号、逗号、星号、斜杠（词中块注释/优化器提示）为止，转大写 */
    static String firstKeyword(String body) {
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == ';' || c == ',' || c == '*' || c == '/') break;
            i++;
        }
        return body.substring(0, i).toUpperCase();
    }
}
