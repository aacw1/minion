package com.minion.core.tools.plugin;

import com.minion.core.tools.db.DataSourceConfig;
import org.junit.Test;

import static org.junit.Assert.*;

/** DbConfig：当前数据源解析、增删改与 current 回退规则 */
public class DbConfigTest {

    private static DbConfig with(String... names) {
        DbConfig c = new DbConfig();
        for (String n : names) c.dataSources.add(new DataSourceConfig(n, "jdbc:x:" + n, "", ""));
        return c;
    }

    @Test
    public void currentDataSourceMatchesTrimmedAndIgnoreCase() {
        DbConfig c = with("prod", "dev");
        c.current = " PROD ";
        assertNotNull(c.currentDataSource());
        assertEquals("jdbc:x:prod", c.currentDataSource().url);
    }

    @Test
    public void currentDataSourceNullWhenBlankOrMissing() {
        DbConfig c = with("prod");
        c.current = "";
        assertNull(c.currentDataSource());
        c.current = "ghost";
        assertNull(c.currentDataSource());
    }

    @Test
    public void findReturnsNullForUnknown() {
        DbConfig c = with("prod");
        assertNotNull(c.find("prod"));
        assertNull(c.find("nope"));
        assertNull(c.find(null));
    }

    @Test
    public void removeCurrentFallsBackToFirst() {
        DbConfig c = with("a", "b", "c");
        c.current = "b";
        c.remove("b");
        assertEquals("a", c.current);
        assertEquals(2, c.dataSources.size());
    }

    @Test
    public void removeLastEntryClearsCurrent() {
        DbConfig c = with("only");
        c.current = "only";
        c.remove("only");
        assertEquals("", c.current);
        assertTrue(c.dataSources.isEmpty());
    }

    @Test
    public void removeNonCurrentKeepsCurrent() {
        DbConfig c = with("a", "b");
        c.current = "a";
        c.remove("b");
        assertEquals("a", c.current);
    }

    @Test
    public void renameCurrentSyncsCurrentField() {
        DbConfig c = with("old", "other");
        c.current = "old";
        DataSourceConfig edited = new DataSourceConfig("new", "jdbc:y", "u", "p");
        c.replace("old", edited);
        assertEquals("new", c.current);
        assertEquals("jdbc:y", c.currentDataSource().url);
        assertEquals(2, c.dataSources.size());
    }

    @Test
    public void renameNonCurrentKeepsCurrent() {
        DbConfig c = with("a", "b");
        c.current = "a";
        c.replace("b", new DataSourceConfig("b2", "jdbc:z", "", ""));
        assertEquals("a", c.current);
        assertNotNull(c.find("b2"));
        assertNull(c.find("b"));
    }

    @Test
    public void setCurrentOnlyAcceptsExistingName() {
        DbConfig c = with("a");
        c.setCurrent("a");
        assertEquals("a", c.current);
        c.setCurrent("ghost");
        assertEquals("a", c.current);   // 不存在的标识名忽略，避免下拉框显示空值
    }
}
