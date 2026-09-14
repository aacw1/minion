package com.minion.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 贴底容差纯函数（MainWindow.followEps）：容差 = 倍数×视口高/可滚动行程；未超一屏返回 1.0 恒贴底 */
public class MainWindowFollowEpsTest {

    @Test
    public void threeScreensContent_viewportOne() {
        // 内容 3 屏、视口 1 屏：行程 2 屏 → eps = 2×1/2 = 1.0（容差覆盖满行程，任一位置都算贴底）
        assertEquals(1.0, MainWindow.followEps(600, 1800, 2.0), 1e-9);
    }

    @Test
    public void fiveScreensContent_viewportOne() {
        // 内容 5 屏、视口 1 屏：行程 4 屏 → eps = 2/4 = 0.5（距底 2 屏内仍跟随）
        assertEquals(0.5, MainWindow.followEps(600, 3000, 2.0), 1e-9);
    }

    @Test
    public void contentNotTallerThanViewport_alwaysPinned() {
        assertEquals(1.0, MainWindow.followEps(600, 400, 2.0), 1e-9);
        assertEquals(1.0, MainWindow.followEps(600, 600, 2.0), 1e-9);
    }

    @Test
    public void twoScreensVsOldHalfScreenTolerance() {
        // 长工具输出一次到位的增长为何中断跟随：同一 5 屏内容、vvalue=0.8（距底 0.2）——
        // 旧 0.5 屏容差 eps=0.125 判「离开底部」永久停跟；新 2 屏容差 eps=0.5 仍算贴底、继续滚到底
        assertEquals(0.125, MainWindow.followEps(600, 3000, 0.5), 1e-9);
        assertEquals(0.5, MainWindow.followEps(600, 3000, 2.0), 1e-9);
        assertEquals(2.0, MainWindow.FOLLOW_SCREENS, 1e-9);
    }
}
