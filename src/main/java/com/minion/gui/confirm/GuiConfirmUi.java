package com.minion.gui.confirm;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.dialog.ConfirmSheet;
import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI 确认交互：工具线程 ask → Platform.runLater 投递 ConfirmSheet 底部卡片 →
 * 工具线程 take() 阻塞等待结果（不阻塞 FX 线程，消除旧版 showAndWait 嵌套事件循环）。
 * 无 GUI 环境（测试/FX toolkit 未启动）时 JDK8 的 Platform.runLater 直接抛
 * IllegalStateException，捕获后安全默认 REJECT 不挂死。
 * 点击即送达、无限等待：工具线程一直等到用户点击（或会话关闭中断），
 * 杜绝「用户已点同意但超时被抢先判拒绝」的竞态。
 * 中断（停止/关会话）时撤销自己的确认句柄：显示中的立即淡出关闭，排队中的直接出队。
 */
public class GuiConfirmUi implements ConfirmUi {

    @Override
    public Decision ask(String message) {
        final LinkedBlockingQueue<Decision> q = new LinkedBlockingQueue<Decision>(1);
        final AtomicReference<ConfirmSheet.Handle> ref = new AtomicReference<ConfirmSheet.Handle>();
        final CountDownLatch posted = new CountDownLatch(1);
        try {
            Platform.runLater(new Runnable() {
                @Override public void run() {
                    ref.set(ConfirmSheet.show(message, q::offer));
                    posted.countDown();
                }
            });
        } catch (IllegalStateException e) {
            return Decision.REJECT; // FX toolkit 未启动（无 GUI 环境），防御性拒绝
        }
        try {
            return q.take();
        } catch (InterruptedException e) {
            // InterruptedException 已清除中断状态：先等投递任务跑完拿到句柄，再恢复中断位；
            // 若先恢复中断位，await 会立刻抛 InterruptedException，200ms 投递窗口恒为 0。
            try {
                try { posted.await(200, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) {
                    // 等待期间再次被中断：吞掉并继续，最后统一恢复中断位
                }
                final ConfirmSheet.Handle h = ref.get();
                if (h != null) Platform.runLater(h::cancel); // 中断即关闭自己的卡片
            } finally {
                Thread.currentThread().interrupt(); // 恢复中断位，交上层循环识别停止
            }
            return Decision.REJECT;
        }
    }
}
