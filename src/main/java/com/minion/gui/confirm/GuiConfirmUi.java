package com.minion.gui.confirm;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.dialog.ConfirmDialog;
import javafx.application.Platform;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GUI 确认交互：工具线程 ask → FutureTask 投递 FX 线程弹窗 → 阻塞等待结果。
 * 无 GUI 环境（测试/FX toolkit 未启动）时，JDK8 的 Platform.runLater 直接抛
 * IllegalStateException，捕获后安全默认 REJECT 不挂死；弹窗超过 3 秒未响应
 * （用户长时间思考）同样兜底 REJECT——拒绝比错误批准安全，用户重发请求即可。
 * FX 线程正常时弹窗关闭即返回，不受超时影响。
 */
public class GuiConfirmUi implements ConfirmUi {

    @Override
    public Decision ask(String message) {
        final FutureTask<Decision> task = new FutureTask<Decision>(() -> ConfirmDialog.show(message));
        try {
            Platform.runLater(task);
        } catch (IllegalStateException e) {
            return Decision.REJECT; // FX toolkit 未启动（无 GUI 环境），防御性拒绝
        }
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.REJECT;
        } catch (ExecutionException e) {
            return Decision.REJECT;
        } catch (TimeoutException e) {
            return Decision.REJECT;
        }
    }
}
