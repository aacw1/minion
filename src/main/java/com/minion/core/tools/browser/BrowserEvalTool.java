package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;

/**
 * 页面内执行 JS 并返回结果。输入/点击/取数据都用它,例如:
 *   document.querySelector('#user').value = 'admin'(受控组件用 __minion_set_value 辅助)
 *   [...document.querySelectorAll('table tr')].map(r => r.innerText).join('\n')
 */
public class BrowserEvalTool implements Tool {

    private final BrowserSession session;

    public BrowserEvalTool(BrowserSession session) { this.session = session; }

    @Override
    public String name() { return "BrowserEval"; }

    @Override
    public String description() { return "在浏览器当前页面执行 JS 并返回结果(输入、点击、提取数据都用它)"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("在页面执行 JS",
                new String[]{"expression", "awaitPage"},
                new String[]{"expression"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("expression")) return ToolResult.error("缺少 expression 参数");
        boolean await = !args.has("awaitPage") || args.get("awaitPage").getAsBoolean();
        try {
            if (await) session.waitForPage(10000); // 未连接时直接返回,由 evaluate 兜底
            return ToolResult.success(session.evaluate(args.get("expression").getAsString()));
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
