package com.minion.gui;

/**
 * 运行时渲染管线探测（软件光栅化 = 无可用 GPU，低端机 / 远程桌面的常见状态）。
 *
 * <p>用途：按实际管线做差异化取舍——软件管线没有 GPU 纹理上传成本，但填充率极低：
 * 视口缓存、动画降级、渲染倍率上限都只在软件管线上启用，GPU 正常机器行为保持不变。</p>
 *
 * <p>探测优先反射 {@code com.sun.prism.GraphicsPipeline#getPipeline()}（JDK 8 内部 API，
 * 反射调用避免编译期内部依赖；JavaFX 9+ 改模块后也会安全回退）。
 * 拿不到实例（尚未初始化）时回退到 {@code prism.order} 首项是否为 {@code sw}。</p>
 */
public final class RenderPipeline {

    /** 探测结果缓存：管线在进程内不会变；探测失败（null）不缓存，等初始化后再试 */
    private static volatile Boolean cached;

    private RenderPipeline() { }

    /** 当前是否软件渲染管线 */
    public static boolean isSoftware() {
        Boolean c = cached;
        if (c != null) return c;
        Boolean detected = detectByPipeline();
        if (detected != null) {
            cached = detected;
            return detected;
        }
        return firstOrderIsSw(System.getProperty("prism.order", ""));
    }

    /** 反射取 Prism 管线实例：null = 尚未初始化/不可用（调用方回退） */
    private static Boolean detectByPipeline() {
        try {
            Class<?> pipeline = Class.forName("com.sun.prism.GraphicsPipeline");
            Object instance = pipeline.getMethod("getPipeline").invoke(null);
            if (instance == null) return null;
            return isSoftwarePipelineName(instance.getClass().getName());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 管线类名判定（纯函数可单测）：软件管线固定为 com.sun.prism.sw.SWPipeline */
    static boolean isSoftwarePipelineName(String className) {
        return className != null && className.endsWith(".SWPipeline");
    }

    /** prism.order 首项判定（纯函数可单测）："sw"/"sw,es2" → true；"es2,sw" → false（实际回退由管线实例决定） */
    static boolean firstOrderIsSw(String order) {
        if (order == null) return false;
        for (String part : order.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) return "sw".equalsIgnoreCase(p);
        }
        return false;
    }

    /** 单测复位（生产不调用） */
    static void resetForTest() {
        cached = null;
    }
}
