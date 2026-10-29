package com.minion.gui.theme;

import javafx.scene.control.Dialog;

/** 主题工具：样式表路径常量 + 弹窗深色挂载（Dialog 不继承 Scene 样式表，须显式添加） */
public final class Theme {

    public static final String STYLESHEET = "/theme/theme.css";

    private Theme() { }

    /** 给弹窗挂深色样式表（Alert/TextInputDialog/Dialog 均为 Dialog 子类，getDialogPane() 可用）；
     *  同时写入当前环境解析出的字体族（JavaFX 8 的 CSS 字体列表不会逐项回退，见 UiFonts） */
    public static void style(Dialog<?> d) {
        if (d != null && d.getDialogPane() != null) {
            d.getDialogPane().getStylesheets().add(STYLESHEET);
            UiFonts.apply(d.getDialogPane());
        }
    }
}
