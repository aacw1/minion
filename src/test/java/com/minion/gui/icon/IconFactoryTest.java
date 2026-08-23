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

    @Test
    public void chevronPaths_differ() {
        assertNotEquals(IconFactory.CHEVRON_DOWN_PATH, IconFactory.CHEVRON_RIGHT_PATH);
    }
}
