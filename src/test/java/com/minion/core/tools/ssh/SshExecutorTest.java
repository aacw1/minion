package com.minion.core.tools.ssh;

import org.junit.Test;

import static org.junit.Assert.*;

/** SshExecutor：本测试不依赖真实 sshd。用 127.0.0.1:1（必然拒绝）验证
 *  「不会死等」：connect 失败应秒回且文案可读（若实现真的去等 10s 会暴露）。 */
public class SshExecutorTest {

    private static SshConnection unreachable(String mode) {
        SshConnection c = new SshConnection();
        c.name = "bad";
        c.host = "127.0.0.1";
        c.port = 1;              // 端口 1：本机无监听，connect 立即 ECONNREFUSED
        c.user = "u";
        if ("password".equals(mode)) {
            c.password = "p";
        } else {
            c.privateKeyPath = "/nonexistent/key";
        }
        return c;
    }

    @Test
    public void connectRefusedFailsFastWithReadableMessage() throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            new SshExecutor().exec(unreachable("password"), "ls", 5, null);
            fail("应抛 SshOpException");
        } catch (SshOpException e) {
            assertFalse("文案不能是裸异常堆栈", e.getMessage().contains("com.jcraft"));
            assertTrue("应含失败原因", e.getMessage().length() > 0);
        }
        long cost = System.currentTimeMillis() - t0;
        assertTrue("拒绝连接应在 3 秒内返回（实测 5s 超时上限）", cost < 3000);
    }

    @Test
    public void testConnectionReturnsFailureNotThrow() {
        SshExecutor.TestResult r = new SshExecutor().test(unreachable("key"));
        assertFalse(r.ok);
        assertFalse(r.message.isEmpty());
    }
}
