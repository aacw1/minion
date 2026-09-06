package com.minion.core.tools.ssh;

/** ssh 操作失败（连接/认证/命令/传输），message 为可直接展示的首行文案（工具层转 ToolResult.error） */
public class SshOpException extends Exception {
    public SshOpException(String message) { super(message); }
    public SshOpException(String message, Throwable cause) { super(message, cause); }
}
