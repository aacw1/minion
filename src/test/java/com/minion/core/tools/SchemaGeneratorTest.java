package com.minion.core.tools;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SchemaGeneratorTest {

    @Test
    public void objectSchema_shape() {
        JsonObject s = SchemaGenerator.objectSchema("示例工具", new String[]{"a", "b"}, new String[]{"a"});
        assertEquals("object", s.get("type").getAsString());
        assertEquals("示例工具", s.get("description").getAsString());
        JsonObject props = s.getAsJsonObject("properties");
        assertTrue(props.has("a"));
        assertTrue(props.has("b"));
        assertEquals("string", props.get("a").getAsJsonObject().get("type").getAsString());
        assertEquals(1, s.getAsJsonArray("required").size());
        assertEquals("a", s.getAsJsonArray("required").get(0).getAsString());
    }

    /** 带属性描述的重载：{名称, 描述} 二元组；描述为空时不输出该字段 */
    @Test
    public void objectSchema_withPropertyDescriptions() {
        JsonObject s = SchemaGenerator.objectSchema("带描述", new String[][]{
                {"a", "描述A"}, {"b", null}}, new String[]{"a"});
        JsonObject props = s.getAsJsonObject("properties");
        assertEquals("string", props.getAsJsonObject("a").get("type").getAsString());
        assertEquals("描述A", props.getAsJsonObject("a").get("description").getAsString());
        assertFalse(props.getAsJsonObject("b").has("description"));
        assertEquals("a", s.getAsJsonArray("required").get(0).getAsString());
    }
}
