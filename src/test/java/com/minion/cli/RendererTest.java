package com.minion.cli;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RendererTest {

    private final PrintStream original = System.out;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    private Renderer renderer() {
        try {
            // PrintStream 默认按平台编码（GBK）编码输出，必须显式 UTF-8 才能和 buf.toString("UTF-8") 对齐
            System.setOut(new PrintStream(buf, false, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        return new Renderer(false);
    }

    /** 平台默认编码是 GBK，而打印内容是 UTF-8 字节，必须显式解码 */
    private String out() {
        try {
            return buf.toString("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @After
    public void restoreOut() { System.setOut(original); }

    /** 思考分块流式到达时，【思考】标签只在块开头打一次 */
    @Test
    public void thinking_labelPrintedOnlyOncePerBlock() {
        Renderer r = renderer();
        r.onThinking("用户");
        r.onThinking("问");
        r.onThinking("C盘");
        assertEquals("【思考】用户问C盘", out());
    }

    /** 思考结束后正文开始，下一块思考后正文重新打【回复】 */
    @Test
    public void thinking_resetsAfterContent() {
        Renderer r = renderer();
        r.onThinking("思考一");
        r.onContent("正文");
        r.onThinking("思考二");
        r.onContent("正文二");
        assertEquals(2, out().split("【思考】", -1).length - 1);
        assertEquals(2, out().split("【回复】", -1).length - 1);
    }

    /** 工具调用开始后思考块结束，下一个思考块重新打标签 */
    @Test
    public void thinking_resetsAfterToolCall() {
        Renderer r = renderer();
        r.onThinking("思考");
        r.onToolCall("Bash", null);
        r.onThinking("新思考");
        assertEquals(2, out().split("【思考】", -1).length - 1);
    }

    /** REPL 下 JLine 已回显，onUserMessage 不重复打印 */
    @Test
    public void onUserMessage_echoOff_isSilent() {
        Renderer r = renderer();
        r.setEchoUser(false);
        r.onUserMessage("你好");
        assertEquals("", out());
    }

    /** 思考结束后正文开始：换行 + 分隔线 + 【回复】标签 */
    @Test
    public void content_afterThinking_printsSeparatorAndLabel() {
        Renderer r = renderer();
        r.onThinking("思考一");
        r.onContent("正文");
        String[] lines = out().split("\\r?\\n");
        assertEquals("【思考】思考一", lines[0]);
        assertTrue("第 2 行应为分隔线，实际: " + lines[1], lines[1].startsWith("─"));
        assertEquals("【回复】正文", lines[2]);
    }

    /** 没有思考直接出正文：只打【回复】标签，无分隔线 */
    @Test
    public void content_withoutThinking_printsLabelOnly() {
        Renderer r = renderer();
        r.onContent("正文");
        assertEquals("【回复】正文", out());
    }

    /** 正文流式分块时【回复】标签只打一次 */
    @Test
    public void content_labelPrintedOnlyOncePerBlock() {
        Renderer r = renderer();
        r.onContent("正");
        r.onContent("文");
        assertEquals("【回复】正文", out());
    }

    /** 正文没以换行收尾时，printlnStats 先补换行，统计行独占一行 */
    @Test
    public void printlnStats_addsNewlineWhenStreamDoesNotEndWithNewline() {
        Renderer r = renderer();
        r.onContent("正文");
        r.printlnStats("⏱ 1.5s");
        assertEquals("【回复】正文" + System.lineSeparator() + "⏱ 1.5s" + System.lineSeparator(), out());
    }

    /** 正文已以换行收尾时，printlnStats 不再加空行 */
    @Test
    public void printlnStats_noExtraBlankWhenStreamEndsWithNewline() {
        Renderer r = renderer();
        r.onContent("正文" + System.lineSeparator());
        r.printlnStats("⏱ 1.5s");
        assertEquals("【回复】正文" + System.lineSeparator() + "⏱ 1.5s" + System.lineSeparator(), out());
    }

    /** 提示符：❯ 在 mintty 默认字体链中渲染为 ?，统一用普通 > */
    @Test
    public void prompt_constant_isPlainArrow() {
        assertEquals("> ", Renderer.PROMPT);
    }

    /** -c 模式回显用户消息时带提示符（先补空行与统计行隔开） */
    @Test
    public void onUserMessage_echoOn_printsPromptAndText() {
        Renderer r = renderer();
        r.onUserMessage("你好");
        assertEquals(System.lineSeparator() + "> 你好" + System.lineSeparator(), out());
        SafeGlyphs.assertSafe(out());
    }

    /** 工具调用行：🔧 等符号字体链不可渲染，用 [工具] 标签 */
    @Test
    public void onToolCall_printsToolLabel() {
        Renderer r = renderer();
        JsonObject args = new JsonObject();
        args.addProperty("command", "dir");
        r.onToolCall("Bash", args);
        assertTrue(out().contains("[工具] Bash → "));
        SafeGlyphs.assertSafe(out());
    }

    /** 工具结果失败标记：✗ 改为 Latin-1 的 ×（Consolas/GBK 均可渲染） */
    @Test
    public void onToolResult_error_usesCross() {
        Renderer r = renderer();
        r.onToolResult("Bash", ToolResult.error("失败"));
        assertEquals("· × Bash: 失败" + System.lineSeparator(), out());
    }

    /** 错误行用 ×，警告行用 ! */
    @Test
    public void onErrorAndWarning_useRenderableMarkers() {
        Renderer r = renderer();
        r.onError("出错了");
        r.onWarning("小心");
        assertEquals("× 出错了" + System.lineSeparator()
                + "! 小心" + System.lineSeparator(), out());
        SafeGlyphs.assertSafe(out());
    }

    /** 子 agent 行：⌁ 改用 ~ */
    @Test
    public void onSubAgent_usesTildeMarkers() {
        Renderer r = renderer();
        r.onSubAgentStart("调研");
        r.onSubAgentDone("摘要");
        assertTrue(out().contains("~ 子agent: 调研"));
        assertTrue(out().contains("~ 子agent完成"));
        SafeGlyphs.assertSafe(out());
    }
}
