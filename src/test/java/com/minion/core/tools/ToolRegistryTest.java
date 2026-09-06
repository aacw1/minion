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

    /** 只记录启用状态的假 gate */
    private static class FakeGate implements ToolRegistry.PluginGate {
        final java.util.Map<String, Boolean> state = new java.util.HashMap<String, Boolean>();
        @Override public boolean enabled(String pluginId) {
            Boolean b = state.get(pluginId);
            return b == null || b;   // 未登记的插件默认放行
        }
    }

    /** 指定名字的极简工具（测试用） */
    private static Tool named(final String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " 描述"; }
            @Override public JsonObject schema() {
                return SchemaGenerator.objectSchema(name, new String[]{"a"}, new String[0]);
            }
            @Override public ToolResult execute(JsonObject args) {
                return ToolResult.success(name);
            }
        };
    }

    private static boolean schemasContain(ToolRegistry reg, String toolName) {
        for (JsonObject o : reg.schemas()) {
            if (toolName.equals(o.getAsJsonObject("function").get("name").getAsString())) return true;
        }
        return false;
    }

    @Test
    public void gateHidesDisabledPluginTools() {
        ToolRegistry reg = new ToolRegistry();
        FakeGate gate = new FakeGate();
        reg.setGate(gate);
        reg.register("browser", named("Browser"));
        reg.register("mysql", named("DbMysql"));
        gate.state.put("browser", false);

        assertNull("禁用插件的工具 get 返回 null", reg.get("Browser"));
        assertFalse(schemasContain(reg, "Browser"));
        assertEquals(1, reg.all().size());
        assertEquals(1, reg.schemas().size());
        // 另一个插件不受影响
        assertNotNull(reg.get("DbMysql"));
        assertTrue(schemasContain(reg, "DbMysql"));
    }

    @Test
    public void gateReenablingRestoresTools() {
        ToolRegistry reg = new ToolRegistry();
        FakeGate gate = new FakeGate();
        reg.setGate(gate);
        reg.register("browser", named("Browser"));
        gate.state.put("browser", false);
        assertNull(reg.get("Browser"));
        gate.state.put("browser", true);
        assertNotNull("重新启用后同一 registry 立即可见", reg.get("Browser"));
        assertTrue(schemasContain(reg, "Browser"));
    }

    @Test
    public void builtinToolsWithoutPluginIdNeverFiltered() {
        ToolRegistry reg = new ToolRegistry();
        FakeGate gate = new FakeGate();
        reg.setGate(gate);
        reg.register(named("Read"));            // 无 pluginId
        gate.state.put("browser", false);
        gate.state.put("Read", false);          // 即使有同名插件 id 也不影响内置工具
        assertNotNull(reg.get("Read"));
        assertTrue(schemasContain(reg, "Read"));
        assertEquals(1, reg.schemas().size());
    }

    @Test
    public void nullGateBehavesAsBefore() {
        ToolRegistry reg = new ToolRegistry();
        reg.register("browser", named("Browser"));   // 打了标签但没有 gate
        assertNotNull(reg.get("Browser"));
        assertEquals(1, reg.schemas().size());
        assertEquals(1, reg.all().size());
    }

    @Test
    public void sameNameOverrideUpdatesPluginOwner() {
        ToolRegistry reg = new ToolRegistry();
        FakeGate gate = new FakeGate();
        reg.setGate(gate);
        reg.register("browser", named("Dup"));
        reg.register("mysql", named("Dup"));     // 同名覆盖，归属改为 mysql
        gate.state.put("browser", false);
        assertNotNull("归属已改为 mysql，browser 的开关不该影响它", reg.get("Dup"));
        gate.state.put("mysql", false);
        assertNull(reg.get("Dup"));
        assertEquals(0, reg.all().size());       // 两个插件都禁用后 all() 为空
    }
}
