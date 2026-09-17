package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class SchemaGenerator {

    /** 全 string 参数的对象 schema */
    public static JsonObject objectSchema(String description, String[] properties, String[] required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", description);
        JsonObject props = new JsonObject();
        for (String p : properties) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            props.add(p, prop);
        }
        schema.add("properties", props);
        if (required.length > 0) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            schema.add("required", req);
        }
        return schema;
    }

    /** 带属性描述的对象 schema：properties 为 {名称, 描述} 二元组，描述为 null/空则不输出该字段。
     *  供需要向模型说明参数语义的工具使用（如 Glob 的 pattern 基准），旧签名行为不变。 */
    public static JsonObject objectSchema(String description, String[][] properties, String[] required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", description);
        JsonObject props = new JsonObject();
        for (String[] p : properties) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            if (p.length > 1 && p[1] != null && !p[1].isEmpty()) prop.addProperty("description", p[1]);
            props.add(p[0], prop);
        }
        schema.add("properties", props);
        if (required.length > 0) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            schema.add("required", req);
        }
        return schema;
    }
}
