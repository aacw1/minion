package com.minion.gui.confirm;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.dialog.ConfirmSheet;
import javafx.application.Platform;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * GUI 确认交互：工具线程 ask → Platform.runLater 投递 ConfirmSheet 底部卡片 →
 * 工具线程 poll 阻塞等待结果（不阻塞 FX 线程，消除旧版 showAndWait 嵌套事件循环）。
 * 无 GUI 环境（测试/FX toolkit 未启动）时 JDK8 的 Platform.runLater 直接抛
 * IllegalStateException，捕获后安全默认 REJECT 不挂死；3 秒未响应同样兜底
 * REJECT——拒绝比错误批准安全，用户重发请求即可（超时后卡片仍在展示，
 * 用户点击结果被丢弃，与旧 Alert 语义一致）。
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
            Decision d = q.poll(3, TimeUnit.SECONDS);
            return d != null ? d : Decision.REJECT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.REJECT;
        }
    }
}
