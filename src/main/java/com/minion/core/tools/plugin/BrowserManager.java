package com.minion.core.tools.plugin;

import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.CdpClient;
import com.minion.core.tools.browser.ChromeLauncher;

import java.nio.file.Paths;

/**
 * 浏览器运行期句柄：持有当前 BrowserSession（懒建，构造对象不拉 Chrome 进程，
 * 真正启动发生在首次 CDP 调用 ChromeLauncher.pageEndpoint()）。
 * 工具对象持有本类引用而非 BrowserSession —— 配置改动 reconfigure 重建后，
 * 各会话 registry 里的浏览器工具无需替换，下一次调用自动用新 session。
 */
public class BrowserManager {

    /** 未就绪（未配置 path）时 4 个浏览器工具共用的指引文案 */
    public static final String NOT_READY =
            "浏览器未就绪：请在 设置 → 工具 → 浏览器操作 → 配置 中填写浏览器路径";

    private BrowserConfig config;
    private ChromeLauncher launcher;
    private BrowserSession session;
    /** ofSession 构造的固定注入模式（测试用）：reconfigure/shutdown 无操作 */
    private final boolean fixed;

    public BrowserManager(BrowserConfig config) {
        this.config = config == null ? new BrowserConfig() : config;
        this.fixed = false;
    }

    private BrowserManager(BrowserSession fixedSession) {
        this.config = new BrowserConfig();
        this.session = fixedSession;
        this.fixed = true;
    }

    /** 固定 session 注入（测试用）：session() 恒返回它 */
    public static BrowserManager ofSession(BrowserSession s) { return new BrowserManager(s); }

    /** 当前配置（GUI 弹窗预填、SessionManager 判断是否装配用） */
    public BrowserConfig config() { return config; }

    /** 当前会话；path 为空（未配置）返回 null，工具据此回 NOT_READY */
    public synchronized BrowserSession session() {
        if (fixed) return session;
        String path = config.path == null ? "" : config.path.trim();
        if (path.isEmpty()) return null;
        if (session == null) {
            launcher = new ChromeLauncher(path, config.port, Paths.get(config.userDataDir),
                    config.headless, config.timeoutMs);
            session = new BrowserSession(launcher, new CdpClient(10000, config.timeoutMs));
        }
        return session;
    }

    /**
     * 配置变更：关掉 minion 自启的 Chrome（当前已打开页面会丢失），下次 session() 按新配置懒建。
     * 复用外部 Chrome 实例时 launcher.stop() 是空操作（process 为 null）。
     */
    public synchronized void reconfigure(BrowserConfig next) {
        if (fixed) return;
        if (next != null) config.copyFrom(next);
        stopLauncher();
        session = null;
    }

    /** 退出钩子调用 */
    public synchronized void shutdown() {
        if (fixed) return;
        stopLauncher();
        session = null;
    }

    private void stopLauncher() {
        if (launcher != null) {
            launcher.stop();
            launcher = null;
        }
    }
}
