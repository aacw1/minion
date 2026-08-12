package com.minion.core.tools.confirm;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * GuiConfirmUi 线程语义测试（不启动 JavaFX Application Thread——
 * Platform.runLater 在未启动时直接排队不执行，因此本测试仅验证
 * ask() 在无 FX 线程时返回 REJECT 不挂死）。
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
