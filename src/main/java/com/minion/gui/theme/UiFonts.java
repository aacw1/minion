package com.minion.gui.theme;

import javafx.scene.Node;
import javafx.scene.text.Font;

import java.util.List;

/**
 * 界面字体选择兜底（Win7 正文方块修复）。
 *
 * <p>JavaFX 8 的 {@code -fx-font-family} 不支持 CSS 字体列表逐项回退：只取第一个族，
 * 该族不存在时直接落到 {@code System}，后面的族不会尝试（openjfx-dev 2022-04 邮件与
 * JavaFX 8 探针均实证）。Win7 只有 Microsoft YaHei，没有 Windows 8+ 的
 * {@code Microsoft YaHei UI}；中文环境下 JavaFX 暴露的普通雅黑族名是 {@code 微软雅黑}，
 * 不是 {@code Microsoft YaHei}。若 CSS 首族写 UI，Win7 会整体落到 System，而 JavaFX 8
 * 的复合字体回退对箭头/符号覆盖不全，正文出现方块；剪贴板内容本身正确（Notepad++ 用
 * 系统回退能正常显示），不是编码问题。</p>
 *
 * <p>本类从已安装族中按序挑第一个可用者，并以行内样式写到根节点，绕开 CSS 列表缺陷。</p>
 */
public final class UiFonts {

    /** Win8+ 优先 UI 版；Win7 回退到中文环境实际族名“微软雅黑”；再退黑体/宋体/拉丁字体 */
    private static final String[] PREFERRED = {
            "Microsoft YaHei UI",
            "微软雅黑",
            "Microsoft YaHei",
            "SimHei",
            "SimSun",
            "宋体",
            "Segoe UI",
            "System"
    };

    /** 首次解析结果缓存（FX 线程启动后字体列表稳定） */
    private static volatile String cached;

    private UiFonts() { }

    /** 当前运行环境选定的界面字体族（首次调用后缓存） */
    public static String family() {
        String f = cached;
        if (f == null) {
            cached = f = resolve(Font.getFamilies());
        }
        return f;
    }

    /**
     * 纯函数（可单测）：按优先级从 installed 中选第一个存在的族；全缺回退 System。
     * 不做大小写/模糊匹配——JavaFX 族名是精确值，模糊匹配可能选到非预期字体。
     */
    static String resolve(List<String> installed) {
        if (installed != null) {
            for (String want : PREFERRED) {
                if (installed.contains(want)) return want;
            }
        }
        return "System";
    }

    /** 把选定字体以行内样式追加到节点（保留节点既有行内样式）；null 安全 */
    public static void apply(Node node) {
        if (node == null) return;
        String family = family().replace("\"", "");
        String rule = "-fx-font-family: \"" + family + "\";";
        String old = node.getStyle();
        if (old == null || old.trim().isEmpty()) {
            node.setStyle(rule);
        } else {
            node.setStyle(old + (old.trim().endsWith(";") ? " " : "; ") + rule);
        }
    }
}
