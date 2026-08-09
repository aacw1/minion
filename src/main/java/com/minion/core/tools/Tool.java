package com.minion.core.tools;

import com.google.gson.JsonObject;

public interface Tool {
    String name();
    String description();
    JsonObject schema();
    ToolResult execute(JsonObject args) throws Exception;
    default boolean isHighRisk(JsonObject args) { return false; }
}
