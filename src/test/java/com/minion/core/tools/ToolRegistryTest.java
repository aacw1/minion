package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.tools.example.ExampleTool;
import org.junit.Test;

import static org.junit.Assert.*;

public class ToolRegistryTest {

    @Test
    public void registerAndGet() {
        ToolRegistry reg = new ToolRegistry();
        Tool tool = new ExampleTool();
        reg.register(tool);
        assertEquals(tool, reg.get("example"));
        assertNull(reg.get("nope"));
        assertEquals(1, reg.all().size());
        assertEquals(1, reg.schemas().size());
        JsonObject schema = reg.schemas().get(0);
        assertEquals("function", schema.get("type").getAsString());
        JsonObject fn = schema.getAsJsonObject("function");
        assertEquals("example", fn.get("name").getAsString());
        assertEquals("object", fn.getAsJsonObject("parameters").get("type").getAsString());
    }
}
