package com.minion.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 工具参数宽松解析：尾部杂讯容忍 + lenient 语法 + 真畸形报错（线上实证回归） */
public class ToolArgumentsTest {

    private static final String PAD = pad();

    private static String pad() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 42; i++) sb.append("0123456789"); // 420 字符，贴近线上 line 1 column 435
        return sb.toString();
    }

    @Test public void normalJson() {
        JsonObject o = ToolArguments.parse("{\"question\": \"你好\", \"header\": \"h\"}");
        assertEquals("你好", o.get("question").getAsString());
    }

    @Test public void trailingExtraBrace() {
        // 模型多吐一个右花括号：整段 JSON 合法值之后有杂讯，旧实现报 Use JsonReader.setLenient
        JsonObject o = ToolArguments.parse("{\"question\": \"" + PAD + "\"}}");
        assertEquals(PAD, o.get("question").getAsString());
    }

    @Test public void trailingChinesePunctuation() {
        JsonObject o = ToolArguments.parse("{\"question\": \"" + PAD + "\"}。");
        assertEquals(PAD, o.get("question").getAsString());
    }

    @Test public void trailingSecondJson() {
        JsonObject o = ToolArguments.parse("{\"a\": 1}{\"b\": 2}");
        assertEquals(1, o.get("a").getAsInt());
    }

    @Test public void trailingMarkdownFence() {
        JsonObject o = ToolArguments.parse("{\"a\": 1}```");
        assertEquals(1, o.get("a").getAsInt());
    }

    @Test public void unescapedNewlineInValue() {
        // 模型把多行文本直接写进字符串（未转义换行）：lenient 解析
        JsonObject o = ToolArguments.parse("{\"question\": \"第一行\n第二行\"}");
        assertEquals("第一行\n第二行", o.get("question").getAsString());
    }

    @Test public void singleQuotedAndBareKey() {
        JsonObject o = ToolArguments.parse("{question: 'abc'}");
        assertEquals("abc", o.get("question").getAsString());
    }

    @Test public void trailingWhitespaceOnly() {
        JsonObject o = ToolArguments.parse("{\"a\": 1}  \n\t ");
        assertEquals(1, o.get("a").getAsInt());
    }

    @Test public void nullAndBlank() {
        assertTrue(ToolArguments.parse(null).entrySet().isEmpty());
        assertTrue(ToolArguments.parse("   ").entrySet().isEmpty());
    }

    @Test public void truncatedValueThrows() {
        try {
            ToolArguments.parse("{\"question\": \"abc");
            fail("截断的参数必须抛异常（交给模型重发）");
        } catch (JsonSyntaxException expected) {
            // 预期：值内部畸形仍报错
        }
    }

    @Test public void nonObjectThrows() {
        try {
            ToolArguments.parse("[1, 2, 3]");
            fail("非对象参数必须抛异常");
        } catch (JsonSyntaxException expected) {
            assertTrue(expected.getMessage().contains("不是 JSON 对象"));
        }
    }
}
