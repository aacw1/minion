package com.minion.core.tools.ssh;

import org.junit.Test;
import static org.junit.Assert.*;

public class SshAuthTest {

    private static SshConnection base() {
        SshConnection c = new SshConnection();
        c.host = "h";
        c.user = "u";
        c.password = "p";
        return c;
    }

    @Test
    public void keyPreferredWhenPathSet() {
        SshConnection c = base();
        c.privateKeyPath = " ~/.ssh/id_rsa ";
        assertEquals("key", SshAuth.mode(c));
    }

    @Test
    public void passwordWhenNoKeyPath() {
        assertEquals("password", SshAuth.mode(base()));
        SshConnection c = base();
        c.password = "  ";
        assertEquals("password", SshAuth.mode(c));
    }

    @Test
    public void emptyConnectionDefaultsToPassword() {
        assertEquals("password", SshAuth.mode(new SshConnection()));
    }
}
