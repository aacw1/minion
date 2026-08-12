package com.minion.gui;

import javafx.scene.Cursor;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * 无边框窗口边缘缩放：根布局上覆盖 8 个透明区域（四边厚 5px、四角 14px 见方），
 * 按下拖动按方向调整窗口几何。最小尺寸受 stage.setMinWidth/MinHeight 约束（clamp）。
 */
public final class ResizeHelper {

    private static final double EDGE = 5;
    private static final double CORNER = 14;
    /** 方向位掩码：N=上 S=下 W=左 E=右 */
    private static final int N = 1, S = 2, W = 4, E = 8;

    private ResizeHelper() { }

    /** 挂到根容器：frame 必须是 AnchorPane（region 用锚定定位），内部再放实际内容 */
    public static void attach(final Stage stage, final Pane frame) {
        final double[] sx = new double[1]; // 按下点屏幕坐标
        final double[] sy = new double[1];
        final double[] wx = new double[1]; // 按下时窗口几何
        final double[] wy = new double[1];
        final double[] ww = new double[1];
        final double[] wh = new double[1];

        Region top = region(frame, N, Cursor.V_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setTopAnchor(top, 0.0);
        AnchorPane.setLeftAnchor(top, CORNER);
        AnchorPane.setRightAnchor(top, CORNER);
        top.setPrefHeight(EDGE);

        Region bottom = region(frame, S, Cursor.V_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setBottomAnchor(bottom, 0.0);
        AnchorPane.setLeftAnchor(bottom, CORNER);
        AnchorPane.setRightAnchor(bottom, CORNER);
        bottom.setPrefHeight(EDGE);

        Region left = region(frame, W, Cursor.H_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setLeftAnchor(left, 0.0);
        AnchorPane.setTopAnchor(left, CORNER);
        AnchorPane.setBottomAnchor(left, CORNER);
        left.setPrefWidth(EDGE);

        Region right = region(frame, E, Cursor.H_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setRightAnchor(right, 0.0);
        AnchorPane.setTopAnchor(right, CORNER);
        AnchorPane.setBottomAnchor(right, CORNER);
        right.setPrefWidth(EDGE);

        Region nw = region(frame, N | W, Cursor.NW_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setTopAnchor(nw, 0.0);
        AnchorPane.setLeftAnchor(nw, 0.0);
        nw.setPrefSize(CORNER, CORNER);

        Region ne = region(frame, N | E, Cursor.NE_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setTopAnchor(ne, 0.0);
        AnchorPane.setRightAnchor(ne, 0.0);
        ne.setPrefSize(CORNER, CORNER);

        Region sw = region(frame, S | W, Cursor.SW_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setBottomAnchor(sw, 0.0);
        AnchorPane.setLeftAnchor(sw, 0.0);
        sw.setPrefSize(CORNER, CORNER);

        Region se = region(frame, S | E, Cursor.SE_RESIZE, stage, sx, sy, wx, wy, ww, wh);
        AnchorPane.setBottomAnchor(se, 0.0);
        AnchorPane.setRightAnchor(se, 0.0);
        se.setPrefSize(CORNER, CORNER);
    }

    private static Region region(final Pane frame, final int dir, final Cursor cursor,
                                 final Stage stage, final double[] sx, final double[] sy,
                                 final double[] wx, final double[] wy,
                                 final double[] ww, final double[] wh) {
        Region r = new Region();
        r.getStyleClass().add("resize-edge");
        r.setCursor(cursor);
        r.setOnMousePressed(e -> {
            sx[0] = e.getScreenX();
            sy[0] = e.getScreenY();
            wx[0] = stage.getX();
            wy[0] = stage.getY();
            ww[0] = stage.getWidth();
            wh[0] = stage.getHeight();
        });
        r.setOnMouseDragged(e -> {
            double dx = e.getScreenX() - sx[0];
            double dy = e.getScreenY() - sy[0];
            double x = wx[0], y = wy[0], w = ww[0], h = wh[0];
            if ((dir & E) != 0) w = Math.max(stage.getMinWidth(), ww[0] + dx);
            if ((dir & S) != 0) h = Math.max(stage.getMinHeight(), wh[0] + dy);
            if ((dir & W) != 0) {
                w = Math.max(stage.getMinWidth(), ww[0] - dx);
                x = wx[0] + (ww[0] - w);
            }
            if ((dir & N) != 0) {
                h = Math.max(stage.getMinHeight(), wh[0] - dy);
                y = wy[0] + (wh[0] - h);
            }
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
        });
        frame.getChildren().add(r);
        return r;
    }
}
