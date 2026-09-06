package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.browser.BrowserDebugTool;
import com.minion.core.tools.browser.BrowserEvalTool;
import com.minion.core.tools.browser.BrowserScreenshotTool;
import com.minion.core.tools.browser.BrowserTool;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器插件：4 个 CDP 工具（Browser / BrowserEval / BrowserScreenshot / BrowserDebug）。
 * 配置对象与 ToolStore 里的是同一实例，GUI 改完调 onConfigChanged() 即重建 Chrome。
 */
public class BrowserPlugin implements ToolPlugin {

    private final BrowserConfig config;
    private final BrowserManager manager;
    /** 落盘回调（ToolPluginManager 注入 store::save）；测试可传 null */
    private final Runnable saver;

    public BrowserPlugin(BrowserConfig config, BrowserManager manager, Runnable saver) {
        this.config = config == null ? new BrowserConfig() : config;
        this.manager = manager;
        this.saver = saver;
    }

    @Override public String id() { return "browser"; }

    @Override public String displayName() { return "浏览器操作"; }

    @Override
    public String statusText() {
        String path = config.path == null ? "" : config.path.trim();
        // 工具页只露端口号即可（文件名/有头无头这些细节进「配置」弹窗看）
        if (path.isEmpty()) return "未配置";
        return "端口 " + config.port;
    }

    @Override public boolean enabled() { return config.enabled; }

    /** 未配置浏览器路径时不允许启用（勾选框置灰；避免模型拿到工具却打不开 Chrome） */
    @Override
    public boolean canEnable() {
        String path = config.path == null ? "" : config.path.trim();
        return !path.isEmpty();
    }

    @Override
    public void setEnabled(boolean on) {
        config.enabled = on;
        save();
    }

    @Override
    public List<Tool> createTools(ToolContext ctx) {
        List<Tool> list = new ArrayList<Tool>();
        list.add(new BrowserTool(manager));
        list.add(new BrowserEvalTool(manager));
        list.add(new BrowserScreenshotTool(manager,
                ctx == null ? null : ctx.workspace,
                ctx == null ? null : ctx.skillsDir,
                ctx == null ? null : ctx.tmpDir,
                ctx == null ? null : ctx.confirmGate));
        list.add(new BrowserDebugTool(manager));
        return list;
    }

    /** 配置变更：关掉旧 Chrome 并按新配置重建（已打开的页面会丢失） */
    @Override
    public void onConfigChanged() {
        if (manager != null) manager.reconfigure(config);
    }

    public BrowserConfig config() { return config; }

    public BrowserManager manager() { return manager; }

    private void save() {
        if (saver != null) saver.run();
    }
}
