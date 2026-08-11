package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;

/** 浏览器导航:open/back/refresh/status(首次调用启动 Chrome) */
public class BrowserTool implements Tool {

    private final BrowserSession session;

    public BrowserTool(BrowserSession session) { this.session = session; }

    @Override
    public String name() { return "Browser"; }

    @Override
    public String description() { return "浏览器导航:open(url) 打开页面 / back 后退 / refresh 刷新 / status 当前页面状态(首次调用自动启动 Chrome)"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("浏览器导航",
                new String[]{"action", "url"}, new String[]{"action"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("action")) return ToolResult.error("缺少 action 参数");
        String action = args.get("action").getAsString();
        try {
            if ("open".equals(action)) {
                if (!args.has("url")) return ToolResult.error("open 需要 url 参数");
                return ToolResult.success(session.open(args.get("url").getAsString()));
            }
            if ("back".equals(action)) return ToolResult.success(session.back());
            if ("refresh".equals(action)) return ToolResult.success(session.refresh());
            if ("status".equals(action)) return ToolResult.success(session.pageInfo());
            return ToolResult.error("未知 action: " + action + "(支持 open/back/refresh/status)");
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
