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
        // 提问挂起 + 空输入：变淡回答箭头（模型在等回答而非忙碌，不显示终止方块；终止入口为发送行停止按钮）
        assertEquals(InputView.BtnMode.ANSWER_DIM, InputView.buttonMode(true, true, false));
    }

    @Test
    public void asking_withContent_answer() {
        assertEquals(InputView.BtnMode.ANSWER, InputView.buttonMode(true, true, true));
    }

    /** 确认插入文本：@ 文件补全须补回 @ 前缀（FileSuggester 的 insertText 为纯路径） */
    @Test
    public void insertionText_fileMode_prependsAt() {
        assertEquals("@src/a.txt", InputView.insertionText(CompletionParser.Mode.FILE, "src/a.txt"));
    }

    @Test
    public void insertionText_fileMode_keepsExistingAt() {
        assertEquals("@x.txt", InputView.insertionText(CompletionParser.Mode.FILE, "@x.txt"));
    }

    @Test
    public void insertionText_slashMode_unchanged() {
        assertEquals("/help", InputView.insertionText(CompletionParser.Mode.SLASH, "/help"));
    }

    /** 按钮三态色：空内容/运行中 #f48771（btn-send-empty），有内容非运行 #ff947c（btn-send-full） */
    @Test
    public void buttonStyleClass_threeStateColors() {
        assertEquals("btn-send-full", InputView.buttonStyleClass(InputView.BtnMode.SEND));
        assertEquals("btn-send-empty", InputView.buttonStyleClass(InputView.BtnMode.SEND_DIM));
        assertEquals("btn-send-empty", InputView.buttonStyleClass(InputView.BtnMode.SUPPLEMENT));
        assertEquals("btn-send-full", InputView.buttonStyleClass(InputView.BtnMode.ANSWER));
        assertEquals("btn-send-empty", InputView.buttonStyleClass(InputView.BtnMode.ANSWER_DIM));
        assertEquals("btn-send-empty", InputView.buttonStyleClass(InputView.BtnMode.STOP));
    }

    // ===== 防连按误终止（线上实证：回答/发送后输入框已清空，第二次 Enter 命中 STOP 把流程掐掉） =====

    @Test
    public void stopWithinGuardWindow_isIgnored() {
        assertTrue("距上次发送 120ms 的 STOP 应判为重复按键",
                InputView.shouldIgnoreTrigger(InputView.BtnMode.STOP, 1000, 880, InputView.STOP_GUARD_MS));
    }

    @Test
    public void stopAfterGuardWindow_runs() {
        assertFalse("超过防抖窗口的 STOP 应执行终止",
                InputView.shouldIgnoreTrigger(InputView.BtnMode.STOP, 1500, 880, InputView.STOP_GUARD_MS));
    }

    @Test
    public void stopBoundaryIsStrictlyLessThan() {
        assertTrue(InputView.shouldIgnoreTrigger(InputView.BtnMode.STOP, 1499, 1000, 500));
        assertFalse(InputView.shouldIgnoreTrigger(InputView.BtnMode.STOP, 1500, 1000, 500));
    }

    @Test
    public void stopWithoutRecentSend_runs() {
        // 从未有过发送动作（lastSendActionMs=0）：会话刚启动就要终止，不能被防抖挡下
        assertFalse(InputView.shouldIgnoreTrigger(InputView.BtnMode.STOP, 1500, 0, 500));
    }

    @Test
    public void sendModesNeverBlocked() {
        // 防抖只挡 STOP：连发消息 / 连续回答 / 连续补充都不受影响
        assertFalse(InputView.shouldIgnoreTrigger(InputView.BtnMode.SEND, 1000, 999, 500));
        assertFalse(InputView.shouldIgnoreTrigger(InputView.BtnMode.ANSWER, 1000, 999, 500));
        assertFalse(InputView.shouldIgnoreTrigger(InputView.BtnMode.SUPPLEMENT, 1000, 999, 500));
    }
}
