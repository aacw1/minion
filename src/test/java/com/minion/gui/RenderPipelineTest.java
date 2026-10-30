package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 渲染管线判定（纯函数）：软件管线识别与 prism.order 回退。 */
public class RenderPipelineTest {

    @Test
    public void softwarePipelineName_detected() {
        assertTrue(RenderPipeline.isSoftwarePipelineName("com.sun.prism.sw.SWPipeline"));
        assertFalse(RenderPipeline.isSoftwarePipelineName("com.sun.prism.es2.ES2Pipeline"));
        assertFalse(RenderPipeline.isSoftwarePipelineName(null));
    }

    @Test
    public void prismOrder_firstTokenIsSw() {
        assertTrue(RenderPipeline.firstOrderIsSw("sw"));
        assertTrue(RenderPipeline.firstOrderIsSw("sw,es2"));
        assertTrue(RenderPipeline.firstOrderIsSw(" SW , es2 "));
        assertFalse(RenderPipeline.firstOrderIsSw("es2,sw")); // 首项 es2：真实回退由管线实例决定
        assertFalse(RenderPipeline.firstOrderIsSw("d3d"));
        assertFalse(RenderPipeline.firstOrderIsSw(""));
        assertFalse(RenderPipeline.firstOrderIsSw(null));
    }

    @Test
    public void detect_doesNotThrowEvenWithoutToolkit() {
        // 测试环境无 JavaFX toolkit：反射失败/未初始化必须安全回退，不得让 UI 构建抛错
        RenderPipeline.resetForTest();
        boolean v = RenderPipeline.isSoftware();
        assertTrue(v || !v); // 只断言不抛异常；值取决于本机与 prism.order
    }
}
