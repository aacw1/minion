package com.minion.core.tools.plugin;

/**
 * 浏览器插件配置（tools.json 的 browser 段）。
 * 字段名即 JSON 键名；默认值就是 tools.json 首次生成时的内容。
 * 与 config.properties 完全解耦——不读也不写 browser.* 键。
 */
public class BrowserConfig {
    public boolean enabled = false;
    /** Chrome 可执行文件路径；空 = 未配置（工具调用时返回指引文案） */
    public String path = "";
    public int port = 9222;
    public String userDataDir = "./.minion/browser-profile";
    public boolean headless = false;
    public int timeoutMs = 30000;

    /** 复制一份（GUI 弹窗取消时不污染内存配置） */
    public BrowserConfig copy() {
        BrowserConfig c = new BrowserConfig();
        c.enabled = enabled;
        c.path = path;
        c.port = port;
        c.userDataDir = userDataDir;
        c.headless = headless;
        c.timeoutMs = timeoutMs;
        return c;
    }

    /** 把 other 的字段覆盖到自己（保持对象引用不变，插件/管理器持有的仍是同一个实例） */
    public void copyFrom(BrowserConfig other) {
        if (other == null) return;
        this.enabled = other.enabled;
        this.path = other.path;
        this.port = other.port;
        this.userDataDir = other.userDataDir;
        this.headless = other.headless;
        this.timeoutMs = other.timeoutMs;
    }
}
