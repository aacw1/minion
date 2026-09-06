package com.minion.core.tools.plugin;

import com.minion.core.tools.Tool;
import com.minion.core.tools.browser.BrowserDebugTool;
import com.minion.core.tools.browser.BrowserEvalTool;
import com.minion.core.tools.browser.BrowserScreenshotTool;
import com.minion.core.tools.browser.BrowserTool;

import java.io.File;
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
        if (path.isEmpty()) return "未配置浏览器路径";
        String fileName = new File(path).getName();
        if (fileName.isEmpty()) fileName = path;
        return fileName + " · 端口 " + config.port + " · " + (config.headless ? "无头" : "有头");
    }

    @Override public boolean enabled() { return config.enabled; }

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
