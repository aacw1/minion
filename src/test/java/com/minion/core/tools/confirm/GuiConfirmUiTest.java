package com.minion.core.tools.confirm;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * GuiConfirmUi 线程语义测试（不启动 JavaFX Application Thread——
 * JDK8 实测无 toolkit 时 Platform.runLater 抛 IllegalStateException，
 * ask() 捕获后防御性 REJECT 立即返回，不会因无限等待而挂死）。
 */
public class GuiConfirmUiTest {

    @Test
    public void ask_withoutFxThread_returnsReject() throws Exception {
        final ConfirmUi ui = new com.minion.gui.confirm.GuiConfirmUi();
        final CountDownLatch done = new CountDownLatch(1);
        final ConfirmUi.Decision[] result = new ConfirmUi.Decision[1];
        Thread t = new Thread(() -> {
            result[0] = ui.ask("! 高危操作 Bash → rm -rf");
            done.countDown();
        });
        t.start();
        assertTrue("ask 不应挂死", done.await(5, TimeUnit.SECONDS));
        assertEquals(ConfirmUi.Decision.REJECT, result[0]);
    }

    @Test
    public void implementsConfirmUi() {
        ConfirmUi ui = new com.minion.gui.confirm.GuiConfirmUi();
        assertTrue(ui instanceof ConfirmUi);
    }
}
