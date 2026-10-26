package com.minion.core.tools.plugin;

import com.minion.core.tools.db.DataSourceConfig;
import com.minion.core.tools.ssh.SshConnection;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** tools.json：缺失生成默认（全不启用）/ 往返保存 / 损坏备份重建 / 未知 id */
public class ToolStoreTest {

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("tool-store-test");
        d.toFile().deleteOnExit();
        return d;
    }

    @Test
    public void loadMissingFileCreatesAllDisabledDefaults() throws Exception {
        Path d = dir();
        ToolStore s = ToolStore.load(d);
        Path f = d.resolve("tools.json");
        assertTrue("缺省文件应已生成", Files.exists(f));

        for (String id : new String[]{"mysql", "postgresql", "oracle"}) {
            DbConfig db = s.dbConfig(id);
            assertNotNull(id, db);
            assertFalse(id + " 默认应不启用", db.enabled);
            assertEquals("", db.current);
            assertTrue(db.dataSources.isEmpty());
            assertNull(db.currentDataSource());
        }
        assertFalse("ssh 默认应不启用", s.sshConfig().enabled);
        assertEquals("", s.sshConfig().current);
        assertTrue(s.sshConfig().connections.isEmpty());
        // 生成文件里各项 enabled 都是 false
        String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertFalse(json.contains("\"enabled\":true"));
    }

    @Test
    public void saveRoundtripPreservesEverything() throws Exception {
        Path d = dir();
        ToolStore s = ToolStore.load(d);

        DbConfig my = s.dbConfig("mysql");
        my.enabled = true;
        my.dataSources.add(new DataSourceConfig("prod", "jdbc:mysql://h:3306/db", "u1", "p@ss"));
        my.dataSources.add(new DataSourceConfig("dev", "jdbc:mysql://h2:3306/db", "u2", ""));
        my.current = "prod";
        s.sshConfig().enabled = true;
        s.sshConfig().add(new SshConnection("bastion", "10.0.0.5", 2222, "root", "pwd", "", ""));
        s.save();

        ToolStore s2 = ToolStore.load(d);
        DbConfig my2 = s2.dbConfig("mysql");
        assertTrue(my2.enabled);
        assertEquals(2, my2.dataSources.size());
        assertEquals("prod", my2.current);
        DataSourceConfig prod = my2.currentDataSource();
        assertNotNull(prod);
        assertEquals("jdbc:mysql://h:3306/db", prod.url);
        assertEquals("u1", prod.user);
        assertEquals("p@ss", prod.password);   // 密码明文往返
        assertFalse(s2.dbConfig("oracle").enabled);
        assertTrue(s2.dbConfig("oracle").dataSources.isEmpty());

        assertTrue(s2.sshConfig().enabled);
        assertEquals("bastion", s2.sshConfig().current);
        SshConnection c = s2.sshConfig().currentConnection();
        assertNotNull(c);
        assertEquals("10.0.0.5", c.host);
        assertEquals(2222, c.port);
        assertEquals("root", c.user);
        assertEquals("pwd", c.password);
    }

    @Test
    public void loadCorruptFileBacksUpAndRebuilds() throws Exception {
        Path d = dir();
        Path f = d.resolve("tools.json");
        Files.write(f, "{ 这不是合法 JSON".getBytes(StandardCharsets.UTF_8));

        ToolStore s = ToolStore.load(d);
        assertTrue("损坏文件应备份为 .bak", Files.exists(d.resolve("tools.json.bak")));
        assertFalse(s.sshConfig().enabled);
        assertTrue(s.sshConfig().connections.isEmpty());
        assertTrue(s.dbConfig("mysql").dataSources.isEmpty());
    }

    @Test
    public void loadNullCollectionsNormalized() throws Exception {
        Path d = dir();
        Files.write(d.resolve("tools.json"),
                ("{\"ssh\":{\"enabled\":true,\"current\":null,\"connections\":null},"
                        + "\"mysql\":{\"dataSources\":null,\"current\":null}}")
                        .getBytes(StandardCharsets.UTF_8));
        ToolStore s = ToolStore.load(d);
        assertNotNull(s.dbConfig("mysql").dataSources);
        assertTrue(s.dbConfig("mysql").dataSources.isEmpty());
        assertEquals("", s.dbConfig("mysql").current);
        assertNotNull(s.sshConfig().connections);
        assertTrue(s.sshConfig().connections.isEmpty());
        assertEquals("", s.sshConfig().current);
        assertTrue(s.sshConfig().enabled);   // 已有值不被默认覆盖
    }

    @Test
    public void dbConfigUnknownIdReturnsNull() throws Exception {
        ToolStore s = ToolStore.load(dir());
        assertNull(s.dbConfig("sqlserver"));
        assertNull(s.dbConfig(null));
    }
}
