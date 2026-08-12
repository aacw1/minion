package com.minion.gui.dialog;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.theme.Theme;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

/** 高危/越界操作确认弹窗；必须在 FX 线程调用（GuiConfirmUi 经 Platform.runLater 保证） */
public class ConfirmDialog {

    public static ConfirmUi.Decision show(String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("确认操作");
        a.setHeaderText(message);
        Theme.style(a); // 弹窗深色
        ButtonType approve = new ButtonType("✅ 批准", ButtonBar.ButtonData.YES);
        ButtonType reject = new ButtonType("❌ 拒绝", ButtonBar.ButtonData.NO);
        ButtonType session = new ButtonType("本次会话全部批准", ButtonBar.ButtonData.OK_DONE);
        ButtonType whitelist = new ButtonType("批准并记住", ButtonBar.ButtonData.OTHER);
        a.getButtonTypes().setAll(approve, reject, session, whitelist);
        a.showAndWait();
        ButtonType r = a.getResult();
        if (r == approve) return ConfirmUi.Decision.APPROVE;
        if (r == session) return ConfirmUi.Decision.APPROVE_SESSION;
        if (r == whitelist) return ConfirmUi.Decision.APPROVE_WHITELIST;
        return ConfirmUi.Decision.REJECT;
    }
}
