package com.minion.gui.icon;

import javafx.scene.shape.SVGPath;

/**
 * 图标工厂：集中管理全部界面矢量图标（Material Symbols Outlined 24×24 viewport，Apache-2.0，
 * 来源 https://github.com/google/material-design-icons ，注释标注图标名）。
 * 颜色一律由 CSS 样式类控制（-fx-fill），本类不设颜色；
 * 显示尺寸用 size() 按 24 viewport 缩放（默认 scale=1.0 即 24px）。
 * 动机：Win7 字体回退链缺这些 Unicode 字形（⚙✕✅❌❓▶✓⛭▾▸⏱❐□ 等显示方块），SVGPath 不依赖字体。
 */
public final class IconFactory {

    private IconFactory() { }

    // ---- 24×24 path 常量（Material Symbols / Material Icons，图标名见注释） ----

    /** settings（齿轮；自 RunningIndicator 迁移） */
    public static final String SETTINGS_PATH = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94"
            + "l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22"
            + "l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41"
            + "h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33"
            + "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58"
            + "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61"
            + "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54"
            + "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54"
            + "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32"
            + "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6"
            + "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z";
    /** remove（最小化：短横线） */
    public static final String REMOVE_PATH = "M19 13H5v-2h14v2z";
    /** crop_square（最大化：方框） */
    public static final String CROP_SQUARE_PATH = "M18 4H6c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H6V6h12v12z";
    /** filter_none（还原：双框） */
    public static final String FILTER_NONE_PATH = "M3 5H1v16c0 1.1.9 2 2 2h16v-2H3V5zm18-4H7c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zm0 16H7V3h14v14z";
    /** close（叉） */
    public static final String CLOSE_PATH = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
    /** edit（铅笔） */
    public static final String EDIT_PATH = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";
    /** delete（垃圾桶） */
    public static final String DELETE_PATH = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    /** lens（实心圆点） */
    public static final String DOT_PATH = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z";
    /** help_outline（问号圈） */
    public static final String HELP_PATH = "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z";
    /** check_circle（成功圈勾） */
    public static final String SUCCESS_PATH = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z";
    /** error（失败圈叹号） */
    public static final String ERROR_PATH = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z";
    /** play_arrow（子任务开始） */
    public static final String PLAY_PATH = "M8 5v14l11-7z";
    /** check（子任务完成） */
    public static final String CHECK_PATH = "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    /** build（扳手：工具摘要） */
    public static final String BUILD_PATH = "M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z";
    /** expand_more（收起） */
    public static final String CHEVRON_DOWN_PATH = "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z";
    /** chevron_right（展开） */
    public static final String CHEVRON_RIGHT_PATH = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z";
    /** timer（统计行耗时） */
    public static final String TIMER_PATH = "M15 1H9v2h6V1zm-4 13h2V8h-2v6zm8.03-6.61l1.42-1.42c-.43-.51-.9-.99-1.41-1.41l-1.42 1.42C16.07 4.74 14.12 4 12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9 9-4.03 9-9c0-2.12-.74-4.07-1.97-5.61zM12 20c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z";
    /** attach_file（回形针：上传，outlined 填充版） */
    public static final String ATTACH_FILE_PATH = "M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5c0-1.38 1.12-2.5 2.5-2.5s2.5 1.12 2.5 2.5v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z";
    /** 发送上箭头（自绘 path，InputView 迁移） */
    public static final String SEND_PATH = "M12 4 L20 13 L15 13 L15 21 L9 21 L9 13 L4 13 Z";
    /** 停止方块（自绘 path，InputView 迁移） */
    public static final String STOP_PATH = "M7 7 L17 7 L17 17 L7 17 Z";

    // ---- 工厂方法（样式类在 theme.css 定义颜色） ----

    private static SVGPath create(String path, String styleClass) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add(styleClass);
        return icon;
    }

    public static SVGPath settings() { return create(SETTINGS_PATH, "icon-settings"); }
    public static SVGPath gear() { return create(SETTINGS_PATH, "running-indicator-gear"); }
    public static SVGPath remove() { return create(REMOVE_PATH, "icon-min"); }
    public static SVGPath cropSquare() { return create(CROP_SQUARE_PATH, "icon-max"); }
    public static SVGPath filterNone() { return create(FILTER_NONE_PATH, "icon-restore"); }
    public static SVGPath close() { return create(CLOSE_PATH, "icon-close"); }
    public static SVGPath edit() { return create(EDIT_PATH, "icon-edit"); }
    public static SVGPath delete() { return create(DELETE_PATH, "icon-delete"); }
    public static SVGPath dot() { return create(DOT_PATH, "icon-dot"); }
    public static SVGPath help() { return create(HELP_PATH, "icon-help"); }
    public static SVGPath success() { return create(SUCCESS_PATH, "icon-success"); }
    public static SVGPath error() { return create(ERROR_PATH, "icon-error"); }
    public static SVGPath play() { return create(PLAY_PATH, "icon-play"); }
    public static SVGPath check() { return create(CHECK_PATH, "icon-check"); }
    public static SVGPath build() { return create(BUILD_PATH, "icon-build"); }
    public static SVGPath chevronDown() { return create(CHEVRON_DOWN_PATH, "icon-chevron"); }
    public static SVGPath chevronRight() { return create(CHEVRON_RIGHT_PATH, "icon-chevron"); }
    public static SVGPath timer() { return create(TIMER_PATH, "icon-timer"); }
    public static SVGPath send() { return create(SEND_PATH, "icon-send"); }
    public static SVGPath stop() { return create(STOP_PATH, "icon-stop"); }
    public static SVGPath attachFile() { return create(ATTACH_FILE_PATH, "icon-upload"); }

    /** 按 24×24 viewport 缩放到目标像素（scale = px/24） */
    public static void size(SVGPath icon, double px) {
        double s = px / 24.0;
        icon.setScaleX(s);
        icon.setScaleY(s);
    }
}
