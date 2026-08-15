package com.minion.gui.confirm;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.dialog.ConfirmSheet;
import javafx.application.Platform;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * GUI 确认交互：工具线程 ask → Platform.runLater 投递 ConfirmSheet 底部卡片 →
 * 工具线程 take() 阻塞等待结果（不阻塞 FX 线程，消除旧版 showAndWait 嵌套事件循环）。
 * 无 GUI 环境（测试/FX toolkit 未启动）时 JDK8 的 Platform.runLater 直接抛
 * IllegalStateException，捕获后安全默认 REJECT 不挂死。
 * 点击即送达、无限等待：工具线程一直等到用户点击（或会话关闭中断），
 * 杜绝「用户已点同意但超时被抢先判拒绝」的竞态。
 */
public class GuiConfirmUi implements ConfirmUi {

    @Override
    public Decision ask(String message) {
        final LinkedBlockingQueue<Decision> q = new LinkedBlockingQueue<Decision>(1);
        try {
            Platform.runLater(new Runnable() {
                @Override public void run() { ConfirmSheet.show(message, q::offer); }
            });
        } catch (IllegalStateException e) {
            return Decision.REJECT; // FX toolkit 未启动（无 GUI 环境），防御性拒绝
        }
        try {
            return q.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.REJECT;
        }
    }
}
