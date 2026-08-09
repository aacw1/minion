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
}
