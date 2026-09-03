package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;

/** isInlineQuietState：@文件确认内联插入后，插入的 @路径 词在文本中保持安静
 *  （光标处解析出的 FILE 词仍等于刚插入的全文时不重开补全弹层；词被改动/光标移走后恢复）。 */
public class InputViewInlineTest {

    @Test public void justInserted_quiet() {
        String inline = "@src/a.txt";
        assertTrue(InputView.isInlineQuietState(inline, inline.length(), inline));
    }

    @Test public void caretInsideJustInsertedWord_stillQuiet() {
        String inline = "@src/a.txt";
        assertTrue(InputView.isInlineQuietState(inline, 5, inline));
    }

    @Test public void wordExtended_noLongerQuiet() {
        String inline = "@src/a.txt";
        assertFalse(InputView.isInlineQuietState("@src/a.txtx", 12, inline));
    }

    @Test public void wordTrimmed_noLongerQuiet() {
        String inline = "@src/a.txt";
        assertFalse(InputView.isInlineQuietState("@src/a", 7, inline));
    }

    @Test public void caretInLaterWord_notQuiet() {
        String inline = "@src/a.txt";
        String text = inline + " 修复编译错误";
        assertFalse(InputView.isInlineQuietState(text, text.length(), inline));
    }

    @Test public void nullInline_notQuiet() {
        assertFalse(InputView.isInlineQuietState("@src/a.txt", 11, null));
        assertFalse(InputView.isInlineQuietState("@src/a.txt", 11, ""));
    }

    @Test public void emptyText_notQuiet() {
        assertFalse(InputView.isInlineQuietState("", 0, "@src/a.txt"));
    }

    @Test public void slashWord_notQuiet() {
        // 非 FILE 词不受抑制（/命令弹层照常）
        assertFalse(InputView.isInlineQuietState("/help", 5, "/help"));
    }

    @Test public void sameInlineUsedAgain_quiet() {
        // 用户重新输入一模一样的 @路径 停在词尾：同样安静（改词或移走即恢复）
        String inline = "@src/a.txt";
        assertTrue(InputView.isInlineQuietState(inline, inline.length(), inline));
    }
}
