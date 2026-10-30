package com.minion.gui;

/**
 * 装饰性连续动画开关。
 *
 * <p>软件渲染（无 GPU / 远程桌面）下，任何 60fps 动画都是一次完整的光栅化 + 提交：
 * 实测仅一个 14px 齿轮旋转或 8px 状态点呼吸，在 2x 渲染倍率下就能把 60fps 拉到约 45fps，
 * 低端机更明显。这些动画只表达"正在运行"，不是功能语义，故默认在软件管线降级为静态。</p>
 *
 * <p>配置：{@code -Dminion.animations=auto|full|reduced} 或环境变量 {@code MINION_ANIMATIONS}（默认 auto）。
 * auto = 软件管线降级；full = 强制保留（CPU 富余用户）；reduced = 强制关闭（远程桌面可显式指定）。</p>
 */
public final class Motion {

    private static final String SETTING = "minion.animations";

    private Motion() { }

    /** 是否降级装饰性动画（系统属性优先，其次环境变量；双击启动也可用 MINION_ANIMATIONS 覆盖） */
    public static boolean reduced() {
        String setting = System.getProperty(SETTING);
        if (setting == null || setting.trim().isEmpty()) {
            setting = System.getenv("MINION_ANIMATIONS");
        }
        return reduced(setting, RenderPipeline.isSoftware());
    }

    /** 纯函数（可单测）：setting 为空/未知时按软件管线判定 */
    static boolean reduced(String setting, boolean softwarePipeline) {
        if (setting != null) {
            String s = setting.trim();
            if ("full".equalsIgnoreCase(s)) return false;
            if ("reduced".equalsIgnoreCase(s) || "reduced-motion".equalsIgnoreCase(s)) return true;
        }
        return softwarePipeline;
    }
}
