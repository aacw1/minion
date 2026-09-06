package com.minion.core.tools;

import org.junit.Test;
import static org.junit.Assert.*;

/** 远端高危名单：复用本地集合 + 远端扩展集；本地判定行为不受影响 */
public class DangerousCommandsRemoteTest {

    @Test
    public void remoteMatchesLocalAndExtra() {
        assertTrue(DangerousCommands.isDangerousRemote("rm -rf /tmp/x"));
        assertTrue(DangerousCommands.isDangerousRemote("sudo dd if=/dev/zero of=/dev/sda"));
        assertTrue(DangerousCommands.isDangerousRemote("systemctl stop nginx"));
        assertTrue(DangerousCommands.isDangerousRemote("/sbin/reboot"));
        assertTrue(DangerousCommands.isDangerousRemote("apt-get remove vim"));
        assertTrue(DangerousCommands.isDangerousRemote("'yum' erase httpd"));
    }

    @Test
    public void remoteSafeCommandsPass() {
        assertFalse(DangerousCommands.isDangerousRemote("ls -la /var/log"));
        assertFalse(DangerousCommands.isDangerousRemote("tail -f /var/log/nginx/access.log"));
        assertFalse(DangerousCommands.isDangerousRemote("cat /etc/os-release"));
        assertFalse(DangerousCommands.isDangerousRemote("ps aux | grep java"));
    }

    @Test
    public void localListUnchanged() {
        assertTrue("本地集合应有 rm", DangerousCommands.isDangerous("rm x"));
        assertFalse("systemctl 不应进入本地集合（本地判定行为不变）",
                DangerousCommands.isDangerous("systemctl stop x"));
    }
}
