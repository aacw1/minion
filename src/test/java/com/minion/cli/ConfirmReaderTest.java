package com.minion.cli;

import com.minion.core.tools.confirm.ConfirmUi;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** ConfirmReader 决策映射测试：用 dumb 终端 + 显式输入流注入，不触碰 System.in */
public class ConfirmReaderTest {

    private ConfirmUi.Decision ask(String input) throws Exception {
        Terminal terminal = new DumbTerminal(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        try {
            return new ConfirmReader(reader).ask("测试提示");
        } finally {
            terminal.close();
        }
    }

    @Test
    public void emptyLine_approves() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE, ask("\n"));
    }

    @Test
    public void y_approves() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE, ask("y\n"));
    }

    @Test
    public void uppercaseY_approves() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE, ask("Y\n"));
    }

    @Test
    public void uppercaseN_rejects() throws Exception {
        assertEquals(ConfirmUi.Decision.REJECT, ask("N\n"));
    }

    @Test
    public void uppercaseW_approvesAndWhitelists() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE_WHITELIST, ask("W\n"));
    }

    @Test
    public void uppercaseA_approvesForSession() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE_SESSION, ask("A\n"));
    }

    @Test
    public void n_rejects() throws Exception {
        assertEquals(ConfirmUi.Decision.REJECT, ask("n\n"));
    }

    @Test
    public void w_approvesAndWhitelists() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE_WHITELIST, ask("w\n"));
    }

    @Test
    public void a_approvesForSession() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE_SESSION, ask("a\n"));
    }

    @Test
    public void invalidInput_retriesUntilValid() throws Exception {
        assertEquals(ConfirmUi.Decision.REJECT, ask("x\nn\n"));
    }

    /** 运行时注入 reader（REPL 启动后共享同一 LineReader，避免双终端竞争读 System.in） */
    @Test
    public void setLineReader_thenAsk_usesInjectedReader() throws Exception {
        Terminal terminal = new DumbTerminal(
                new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        ConfirmReader cr = new ConfirmReader();
        cr.setLineReader(reader);
        try {
            assertEquals(ConfirmUi.Decision.APPROVE, cr.ask("测试提示"));
        } finally {
            terminal.close();
        }
    }


    @Test
    public void longForms_mapToDecisions() throws Exception {
        assertEquals(ConfirmUi.Decision.APPROVE, ask("yes\n"));
        assertEquals(ConfirmUi.Decision.REJECT, ask("no\n"));
        assertEquals(ConfirmUi.Decision.APPROVE_WHITELIST, ask("whitelist\n"));
        assertEquals(ConfirmUi.Decision.APPROVE_SESSION, ask("all\n"));
    }

    // null（EOF）分支无法经 jline LineReader 模拟：dumb 终端下 readLine 遇 EOF 抛
    // EndOfFileException（实测 jline 3.25.1），不会返回 null；生产路径中该异常向上传播，
    // 由 REPL 主循环（交互输入）或工具执行的防御捕获兜底。故该分支不做自动化断言。
}
