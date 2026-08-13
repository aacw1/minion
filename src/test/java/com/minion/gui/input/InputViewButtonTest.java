package com.minion.gui.input;

import org.junit.Test;

import static org.junit.Assert.*;

/** 按钮状态机纯函数测试：图标/透明度/背景类/动作的判定依据 */
public class InputViewButtonTest {

    @Test
    public void idle_withContent_send() {
        assertEquals(InputView.BtnMode.SEND, InputView.buttonMode(false, false, true));
    }

    @Test
    public void idle_empty_sendDim() {
        assertEquals(InputView.BtnMode.SEND_DIM, InputView.buttonMode(false, false, false));
    }

    @Test
    public void running_empty_stop() {
        assertEquals(InputView.BtnMode.STOP, InputView.buttonMode(true, false, false));
    }

    @Test
    public void running_withContent_supplement() {
        assertEquals(InputView.BtnMode.SUPPLEMENT, InputView.buttonMode(true, false, true));
    }

    @Test
    public void asking_empty_answerDim() {
        // 提问挂起 + 空输入：变淡回答箭头（模型在等回答而非忙碌，不显示终止方块；终止入口为 Esc）
        assertEquals(InputView.BtnMode.ANSWER_DIM, InputView.buttonMode(true, true, false));
    }

    @Test
    public void asking_withContent_answer() {
        assertEquals(InputView.BtnMode.ANSWER, InputView.buttonMode(true, true, true));
    }
}
