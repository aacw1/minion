package com.minion.gui.theme;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** 字体族选择：JavaFX 8 的 CSS 字体列表不会逐项回退，必须由 UiFonts 按已安装族显式挑选 */
public class UiFontsTest {

    /** Win10+：UI 族存在时优先，保持原界面观感 */
    @Test
    public void prefersYaHeiUi() {
        assertEquals("Microsoft YaHei UI", UiFonts.resolve(Arrays.asList(
                "Segoe UI", "微软雅黑", "Microsoft YaHei UI", "SimSun")));
    }

    /** Win7 现场：没有 UI 族，中文环境 JavaFX 暴露的普通雅黑族名是“微软雅黑” */
    @Test
    public void win7WithoutUiFallsBackToLocalizedYaHei() {
        assertEquals("微软雅黑", UiFonts.resolve(Arrays.asList(
                "Segoe UI", "微软雅黑", "SimSun")));
    }

    /** 英文环境：普通雅黑以英文族名出现时也能选中 */
    @Test
    public void englishYaHeiFamilyWhenLocalizedMissing() {
        assertEquals("Microsoft YaHei", UiFonts.resolve(Arrays.asList(
                "Segoe UI", "Microsoft YaHei", "SimSun")));
    }

    /** 无雅黑的中文环境：黑体/宋体优先于拉丁字体（至少覆盖 CJK/箭头） */
    @Test
    public void fallsBackToChineseFontBeforeLatin() {
        assertEquals("SimSun", UiFonts.resolve(Arrays.asList(
                "Segoe UI", "SimSun", "Arial")));
    }

    /** 一个候选都没有：System 兜底，不返回 null */
    @Test
    public void fallsBackToSystem() {
        assertEquals("System", UiFonts.resolve(Arrays.asList("Arial", "Tahoma")));
        assertEquals("System", UiFonts.resolve(Collections.<String>emptyList()));
        assertEquals("System", UiFonts.resolve(null));
    }
}
