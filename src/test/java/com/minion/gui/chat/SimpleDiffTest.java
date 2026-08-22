package com.minion.gui.chat;

import com.minion.gui.chat.SimpleDiff.Line;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** SimpleDiff 行级 diff 纯逻辑测试（LCS） */
public class SimpleDiffTest {

    private static String marks(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(l.mark);
        return sb.toString();
    }

    @Test
    public void identical_returnsAllCommon() {
        List<Line> out = SimpleDiff.diff("a\nb\nc", "a\nb\nc");
        assertEquals("   ", marks(out));
        assertEquals("b", out.get(1).text);
    }

    @Test
    public void pureInsert_marksAllNew() {
        List<Line> out = SimpleDiff.diff("a\nc", "a\nb\nc");
        assertEquals(" + ", marks(out));
        assertEquals("b", out.get(1).text);
    }

    @Test
    public void pureDelete_marksAllOld() {
        List<Line> out = SimpleDiff.diff("a\nb\nc", "a\nc");
        assertEquals(" - ", marks(out));
        assertEquals("b", out.get(1).text);
    }

    @Test
    public void mixedChange_marksOldThenNew() {
        // 替换行：旧行标记 - 在前、新行标记 + 在后（LCS 回溯序）
        List<Line> out = SimpleDiff.diff("a\nx\nd", "a\ny\nd");
        assertEquals(" -+ ", marks(out));
    }

    @Test
    public void emptyOld_allNew() {
        List<Line> out = SimpleDiff.diff("", "a\nb");
        assertEquals("++", marks(out));
    }

    @Test
    public void emptyNew_allOld() {
        List<Line> out = SimpleDiff.diff("a\nb", "");
        assertEquals("--", marks(out));
    }

    @Test
    public void bothEmpty_noLines() {
        assertTrue(SimpleDiff.diff("", "").isEmpty());
        assertTrue(SimpleDiff.diff(null, null).isEmpty());
    }

    @Test
    public void crlf_normalizedToLines() {
        List<Line> out = SimpleDiff.diff("a\r\nb", "a\r\nb\nc");
        assertEquals("  +", marks(out));
    }
}
