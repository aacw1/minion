package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;

/** 按钮模式判定为纯静态状态机，脱离 JavaFX 单测（BtnMode/buttonMode 包内可见） */
public class InputViewTest {

    @Test public void idleEmpty_sendDim() {
        assertEquals(InputView.BtnMode.SEND_DIM, InputView.buttonMode(false, false, false));
    }

    @Test public void idleWithContent_send() {
        assertEquals(InputView.BtnMode.SEND, InputView.buttonMode(false, false, true));
    }

    @Test public void runningEmpty_stop() {
        assertEquals(InputView.BtnMode.STOP, InputView.buttonMode(true, false, false));
    }

    @Test public void runningWithContent_supplement() {
        assertEquals(InputView.BtnMode.SUPPLEMENT, InputView.buttonMode(true, false, true));
    }

    /** 提问挂起 + 有内容：回答箭头 */
    @Test public void askPendingWithContent_answer() {
        assertEquals(InputView.BtnMode.ANSWER, InputView.buttonMode(true, true, true));
    }

    /** 提问挂起 + 空输入：变淡回答箭头（模型在等回答而非忙碌，不显示终止方块） */
    @Test public void askPendingEmpty_answerDim() {
        assertEquals(InputView.BtnMode.ANSWER_DIM, InputView.buttonMode(true, true, false));
    }
}
