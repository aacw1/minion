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
}
