package com.minion.gui.dialog;

import com.minion.core.mcp.McpServer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Set;

/** MCP 表单字段与传输方式的联动口径（纯逻辑，单测三行矩阵；GUI 只消费不实现规则） */
public final class McpFormPolicy {

    public enum Field { COMMAND, ARGS, ENV, URL, HEADERS }

    private McpFormPolicy() { }

    /** 某传输可见字段：stdio→命令组；sse→URL（旧版 SSE 库不支持自定义头）；streamable→URL+请求头 */
    public static Set<Field> fieldsOf(String transport) {
        String t = McpServer.normalizedTransport(transport);
        if (McpServer.STREAMABLE.equals(t)) return EnumSet.of(Field.URL, Field.HEADERS);
        if (McpServer.SSE.equals(t)) return EnumSet.of(Field.URL);
        return EnumSet.of(Field.COMMAND, Field.ARGS, Field.ENV);
    }

    /** 显示名（列表 meta 与表单标题） */
    public static String labelOf(String transport) {
        String t = McpServer.normalizedTransport(transport);
        if (McpServer.STREAMABLE.equals(t)) return "Streamable";
        if (McpServer.SSE.equals(t)) return "SSE";
        return "stdio";
    }

    /** 保存裁剪：只保留本传输相关字段，其余清空（防止切传输后残留脏配置） */
    public static void trim(McpServer s) {
        Set<Field> keep = fieldsOf(s.transport);
        if (!keep.contains(Field.COMMAND)) s.command = "";
        if (!keep.contains(Field.ARGS)) s.args = new ArrayList<String>();
        if (!keep.contains(Field.ENV)) s.env = new HashMap<String, String>();
        if (!keep.contains(Field.URL)) s.url = "";
        if (!keep.contains(Field.HEADERS)) s.headers = new HashMap<String, String>();
        s.transport = McpServer.normalizedTransport(s.transport);
    }
}
