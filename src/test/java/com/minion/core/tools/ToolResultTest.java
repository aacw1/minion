package com.minion.core.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ToolResultTest {

    @Test
    public void preview_multiLineShowsFirstLineAndCount() {
        ToolResult r = ToolResult.success("line1\nline2\nline3");
        assertEquals("line1\n(3 lines)", r.preview());
        ToolResult single = ToolResult.success("only one");
        assertEquals("only one", single.preview());
    }

    /** 空输出占位：开启+空输出→占位；开启+非空→原样；关闭→原样；null 安全 */
    @Test
    public void outputForApi_placeholderRules() {
        // 开启：空/空白输出 → 占位
        assertEquals("输出内容为空", ToolResult.outputForApi("", true));
        assertEquals("输出内容为空", ToolResult.outputForApi("   \n ", true));
        assertEquals("输出内容为空", ToolResult.outputForApi(null, true));
        // 开启：非空输出 → 原样
        assertEquals("mvn success", ToolResult.outputForApi("mvn success", true));
        // 关闭：空输出 → 原样（行为与现状一致；null 原样返回 null）
        assertEquals("", ToolResult.outputForApi("", false));
        assertNull(ToolResult.outputForApi(null, false));
        assertEquals("mvn success", ToolResult.outputForApi("mvn success", false));
    }
}
