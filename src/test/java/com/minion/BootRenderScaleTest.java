package com.minion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 启动器渲染倍率策略（纯函数）：远程桌面/软件管线降为 1x，显式覆盖优先。 */
public class BootRenderScaleTest {

    @Test
    public void remoteSessionNameDetection() {
        assertTrue(Boot.isRemoteSessionName("RDP-Tcp#0"));
        assertTrue(Boot.isRemoteSessionName(" rdp-tcp#12 "));
        assertFalse(Boot.isRemoteSessionName("Console"));
        assertFalse(Boot.isRemoteSessionName(null));
        assertFalse(Boot.isRemoteSessionName("RDP")); // 只有前缀 RDP- 才算
    }

    @Test
    public void remoteDesktopCapsRenderScale() {
        assertEquals("remote-desktop",
                Boot.renderScaleCapReason("RDP-Tcp#0", "es2,sw", null));
    }

    @Test
    public void softwarePipelineCapsRenderScale() {
        assertEquals("software-pipeline",
                Boot.renderScaleCapReason("Console", "es2,sw", "sw"));
        assertEquals("software-pipeline",
                Boot.renderScaleCapReason("Console", "sw", null));
        assertEquals("software-pipeline",
                Boot.renderScaleCapReason("Console", " sw , es2 ", null));
    }

    @Test
    public void hardwarePipelineKeepsDefaultRenderScale() {
        assertNull(Boot.renderScaleCapReason("Console", "es2,sw", null));
        assertNull(Boot.renderScaleCapReason("Console", "es2", "es2"));
        assertNull(Boot.renderScaleCapReason("Console", null, null));
    }
}
