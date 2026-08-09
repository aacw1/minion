package com.minion.core.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ToolResultTest {

    @Test
    public void preview_multiLineShowsFirstLineAndCount() {
        ToolResult r = ToolResult.success("line1\nline2\nline3");
        assertEquals("line1\n(3 lines)", r.preview());
        ToolResult single = ToolResult.success("only one");
        assertEquals("only one", single.preview());
    }
}
