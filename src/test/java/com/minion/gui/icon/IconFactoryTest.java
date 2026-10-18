package com.minion.gui.icon;

import javafx.scene.shape.SVGPath;
import org.junit.Test;

import static org.junit.Assert.*;

/** 图标工厂：全部方法返回非空可构造的 SVGPath 且带正确样式类；size() 缩放换算 */
public class IconFactoryTest {

    private void assertIcon(SVGPath icon, String styleClass) {
        assertNotNull(icon);
        assertNotNull(icon.getContent());
        assertFalse("path 非空: " + styleClass, icon.getContent().trim().isEmpty());
        assertTrue("样式类 " + styleClass, icon.getStyleClass().contains(styleClass));
    }

    @Test
    public void allIcons_validAndStyled() {
        assertIcon(IconFactory.settings(), "icon-settings");
        assertIcon(IconFactory.gear(), "running-indicator-gear");
        assertIcon(IconFactory.remove(), "icon-min");
        assertIcon(IconFactory.cropSquare(), "icon-max");
        assertIcon(IconFactory.filterNone(), "icon-restore");
        assertIcon(IconFactory.close(), "icon-close");
        assertIcon(IconFactory.edit(), "icon-edit");
        assertIcon(IconFactory.delete(), "icon-delete");
        assertIcon(IconFactory.dot(), "icon-dot");
        assertIcon(IconFactory.help(), "icon-help");
        assertIcon(IconFactory.success(), "icon-success");
        assertIcon(IconFactory.error(), "icon-error");
        assertIcon(IconFactory.play(), "icon-play");
        assertIcon(IconFactory.check(), "icon-check");
        assertIcon(IconFactory.build(), "icon-build");
        assertIcon(IconFactory.chevronDown(), "icon-chevron");
        assertIcon(IconFactory.chevronRight(), "icon-chevron");
        assertIcon(IconFactory.timer(), "icon-timer");
        assertIcon(IconFactory.send(), "icon-send");
        assertIcon(IconFactory.stop(), "icon-stop");
        assertIcon(IconFactory.attachFile(), "icon-upload");
    }

    @Test
    public void size_scalesToTargetPx() {
        SVGPath icon = IconFactory.success();
        IconFactory.size(icon, 12);
        assertEquals(12.0 / 24.0, icon.getScaleX(), 0.001);
        assertEquals(12.0 / 24.0, icon.getScaleY(), 0.001);
    }

    /** 描边图标：带 .icon-stroke 与专属样式类，且 path 能被 SVGPath 解析（布局宽度非 0，解析失败会抛异常） */
    @Test
    public void strokeIcons_parseAndStyled() {
        assertStroke(IconFactory.strokeClose(), "icon-close");
        assertStroke(IconFactory.strokeMinus(), "icon-min");
        assertStroke(IconFactory.strokeMaximize(), "icon-max");
        assertStroke(IconFactory.strokeRestore(), "icon-restore");
        assertStroke(IconFactory.strokeSend(), "icon-send");
        assertStroke(IconFactory.strokePlus(), "icon-plus");
        assertStroke(IconFactory.strokeSettings(), "icon-settings");
    }

    private void assertStroke(SVGPath icon, String styleClass) {
        assertIcon(icon, styleClass);
        assertTrue("icon-stroke 样式类: " + styleClass, icon.getStyleClass().contains("icon-stroke"));
        // 横线（最小化）高度为 0，故只校验宽度；解析错误会在 bounds 计算时直接抛出
        assertTrue("path 可解析: " + styleClass, icon.getLayoutBounds().getWidth() > 0);
    }

    /** sizeVisual 按图形实际 bounds 归一化：14px 目标下 bounds×scale 应接近 14（容差 2px），
     *  修正 size() 按 24 viewport 缩放导致描边图标实显只有目标一半的问题 */
    @Test
    public void sizeVisual_normalizesToTargetBounds() {
        SVGPath icon = IconFactory.strokeClose();
        javafx.geometry.Bounds b = icon.getBoundsInLocal();
        double max = Math.max(b.getWidth(), b.getHeight());
        assertTrue("bounds 非零（可解析且未入场景）: " + max, max > 0);
        IconFactory.sizeVisual(icon, 14);
        assertEquals(icon.getScaleX(), icon.getScaleY(), 0.0001);
        assertEquals(14, max * icon.getScaleX(), 2);
        // 齿轮 path bounds 更大（约 22），同样归一化到 14
        SVGPath gear = IconFactory.strokeSettings();
        double gearMax = Math.max(gear.getBoundsInLocal().getWidth(), gear.getBoundsInLocal().getHeight());
        IconFactory.sizeVisual(gear, 14);
        assertEquals(14, gearMax * gear.getScaleX(), 2);
    }

    @Test
    public void chevronPaths_differ() {
        assertNotEquals(IconFactory.CHEVRON_DOWN_PATH, IconFactory.CHEVRON_RIGHT_PATH);
    }
}
