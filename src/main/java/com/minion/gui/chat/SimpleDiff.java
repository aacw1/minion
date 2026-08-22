package com.minion.gui.chat;

import java.util.ArrayList;
import java.util.List;

/** 行级 diff（LCS 动态规划）：输入两段文本，输出逐行前缀标记。
 *  纯逻辑无 JavaFX 依赖，可单测。适用规模：工具调用参数几百行内（O(n×m) int DP 表，
 *  数千行整文件替换场景内存可达数十 MB，勿用于超大输入），不引入 Myers。 */
public class SimpleDiff {

    /** 仅存在于旧文本 */
    public static final char ONLY_OLD = '-';
    /** 仅存在于新文本 */
    public static final char ONLY_NEW = '+';
    /** 两文本公共行 */
    public static final char COMMON = ' ';

    public static class Line {
        public final char mark;
        public final String text;
        Line(char mark, String text) {
            this.mark = mark;
            this.text = text;
        }
    }

    /** 行级 diff；null/空串按零行处理 */
    public static List<Line> diff(String oldText, String newText) {
        String[] oldLines = split(oldText);
        String[] newLines = split(newText);
        int n = oldLines.length, m = newLines.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = oldLines[i].equals(newLines[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<Line>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (oldLines[i].equals(newLines[j])) {
                out.add(new Line(COMMON, oldLines[i]));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new Line(ONLY_OLD, oldLines[i]));
                i++;
            } else {
                out.add(new Line(ONLY_NEW, newLines[j]));
                j++;
            }
        }
        while (i < n) out.add(new Line(ONLY_OLD, oldLines[i++]));
        while (j < m) out.add(new Line(ONLY_NEW, newLines[j++]));
        return out;
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\\r?\\n", -1); // -1 保留尾部空行语义
    }
}
