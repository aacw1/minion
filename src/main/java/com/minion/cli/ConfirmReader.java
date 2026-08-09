package com.minion.cli;

import com.minion.core.tools.confirm.ConfirmUi;
import org.jline.reader.LineReader;

/** JLine 确认交互实现 */
public class ConfirmReader implements ConfirmUi {

    /** 可变：REPL 启动后注入唯一 LineReader（全应用单 reader，避免双终端竞争读 System.in） */
    private volatile LineReader reader;

    /** 默认构造：reader 由 REPL 启动后 setLineReader 注入 */
    public ConfirmReader() { }

    /** 测试用：直接绑定 reader */
    public ConfirmReader(LineReader reader) { this.reader = reader; }

    /** REPL 启动后注入共享 reader（与主循环同一实例，杜绝双输入源争读控制台） */
    public void setLineReader(LineReader reader) { this.reader = reader; }

    @Override
    public Decision ask(String message) {
        while (true) {
            System.out.println();
            System.out.println(message);
            LineReader r = reader;
            if (r == null) throw new IllegalStateException("LineReader 未注入（REPL 未启动）");
            String line = r.readLine("[回车/Y]确认 [N]拒绝 [W]确认+加入白名单 [A]本会话放行: ");
            if (line == null) return Decision.REJECT;
            String t = line.trim().toLowerCase();
            if (t.isEmpty() || t.equals("y") || t.equals("yes")) return Decision.APPROVE;
            if (t.equals("n") || t.equals("no")) return Decision.REJECT;
            if (t.equals("w") || t.equals("whitelist")) return Decision.APPROVE_WHITELIST;
            if (t.equals("a") || t.equals("all")) return Decision.APPROVE_SESSION;
            System.out.println("无效输入，请重试");
        }
    }
}
