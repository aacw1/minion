package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** 网页调试:网络请求 / console 日志 / 页面信息 */
public class BrowserDebugTool implements Tool {

    private final BrowserSession session;

    public BrowserDebugTool(BrowserSession session) { this.session = session; }

    @Override
    public String name() { return "BrowserDebug"; }

    @Override
    public String description() { return "调试信息:network 网络请求列表 / console 控制台日志 / page 当前页面状态"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("浏览器调试信息",
                new String[]{"action", "limit"}, new String[]{"action"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("action")) return ToolResult.error("缺少 action 参数");
        String action = args.get("action").getAsString();
        int limit = 50;
        if (args.has("limit")) {
            try {
                limit = args.get("limit").getAsInt();
            } catch (NumberFormatException e) {
                return ToolResult.error("参数 limit 格式错误: " + e.getMessage());
            }
        }
        if ("network".equals(action)) return ToolResult.success(session.debugNetwork(limit));
        if ("console".equals(action)) return ToolResult.success(session.debugConsole(limit));
        if ("page".equals(action)) return ToolResult.success(session.pageInfo());
        return ToolResult.error("未知 action: " + action + "(支持 network/console/page)");
    }
}
