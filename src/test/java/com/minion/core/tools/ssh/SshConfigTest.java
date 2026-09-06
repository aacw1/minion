package com.minion.core.tools.ssh;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** 连接配置：current 解析/回退规则，镜像 DbConfigTest 的覆盖点 */
public class SshConfigTest {

    private static SshConnection conn(String name) {
        SshConnection c = new SshConnection();
        c.name = name;
        c.host = "h";
        c.user = "u";
        return c;
    }

    @Test
    public void currentConnectionMatchesTrimmedAndIgnoreCase() {
        SshConfig cfg = new SshConfig();
        cfg.connections.add(conn("prod"));
        cfg.connections.add(conn("Dev "));
        cfg.current = " dev ";
        assertEquals("Dev ", cfg.currentConnection().name);
    }

    @Test
    public void currentConnectionNullWhenBlankOrMissing() {
        SshConfig cfg = new SshConfig();
        assertNull(cfg.currentConnection());
        cfg.connections.add(conn("a"));
        cfg.current = "nope";
        assertNull(cfg.currentConnection());
    }

    @Test
    public void addFirstBecomesCurrent() {
        SshConfig cfg = new SshConfig();
        assertTrue(cfg.add(conn("a")));
        assertEquals("a", cfg.current);
        assertFalse("重名应拒绝", cfg.add(conn("A")));
    }

    @Test
    public void removeCurrentFallsBackToFirst() {
        SshConfig cfg = new SshConfig();
        cfg.add(conn("a"));
        cfg.add(conn("b"));
        cfg.setCurrent("b");
        assertTrue(cfg.remove("b"));
        assertEquals("a", cfg.current);
    }

    @Test
    public void removeLastEntryClearsCurrent() {
        SshConfig cfg = new SshConfig();
        cfg.add(conn("a"));
        assertTrue(cfg.remove("a"));
        assertEquals("", cfg.current);
        assertNull(cfg.currentConnection());
    }

    @Test
    public void renameCurrentSyncsCurrentField() {
        SshConfig cfg = new SshConfig();
        cfg.add(conn("a"));
        SshConnection n = conn("renamed");
        assertTrue(cfg.replace("a", n));
        assertEquals("renamed", cfg.current);
        assertSame(n, cfg.currentConnection());
    }

    @Test
    public void setCurrentOnlyAcceptsExistingName() {
        SshConfig cfg = new SshConfig();
        cfg.add(conn("a"));
        cfg.setCurrent("ghost");
        assertEquals("a", cfg.current);
    }

    @Test
    public void namesReturnsTrimmedInOrder() {
        SshConfig cfg = new SshConfig();
        cfg.add(conn("b"));
        cfg.add(conn(" A "));
        List<String> names = cfg.names();
        assertEquals(2, names.size());
        assertEquals("b", names.get(0));
        assertEquals("A", names.get(1));
    }
}
