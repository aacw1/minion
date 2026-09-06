package com.minion.core.tools.db;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 数据源标识名唯一性与 URL 前缀校验（新建时 originalName 传 null，修改时传原名） */
public class DataSourceValidatorTest {

    private static DataSourceConfig ds(String name, String url) {
        DataSourceConfig d = new DataSourceConfig();
        d.name = name;
        d.url = url;
        return d;
    }

    private static List<DataSourceConfig> list(DataSourceConfig... items) {
        List<DataSourceConfig> l = new ArrayList<DataSourceConfig>();
        for (DataSourceConfig d : items) l.add(d);
        return l;
    }

    @Test
    public void acceptsValidNewEntry() {
        assertNull(DataSourceValidator.validate("prod", "jdbc:mysql://h:3306/db",
                list(ds("dev", "jdbc:mysql://h2:3306/db")), null));
    }

    @Test
    public void rejectsBlankName() {
        assertEquals("标识名不能为空", DataSourceValidator.validate("  ", "jdbc:x", new ArrayList<DataSourceConfig>(), null));
        assertEquals("标识名不能为空", DataSourceValidator.validate(null, "jdbc:x", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void rejectsDuplicateNameIgnoringCase() {
        assertEquals("标识名已存在：PROD",
                DataSourceValidator.validate("PROD", "jdbc:mysql://h/db", list(ds("prod", "jdbc:mysql://h/db")), null));
    }

    @Test
    public void allowsKeepingOwnNameOnEdit() {
        // 修改时改回自身原名不算重复
        assertNull(DataSourceValidator.validate("prod", "jdbc:mysql://newhost/db",
                list(ds("prod", "jdbc:mysql://oldhost/db")), "prod"));
    }

    @Test
    public void rejectsTakingAnotherEntrysNameOnEdit() {
        assertEquals("标识名已存在：dev",
                DataSourceValidator.validate("dev", "jdbc:mysql://h/db",
                        list(ds("prod", "jdbc:mysql://a/db"), ds("dev", "jdbc:mysql://b/db")), "prod"));
    }

    @Test
    public void rejectsBlankUrl() {
        assertEquals("URL 不能为空", DataSourceValidator.validate("prod", "  ", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void rejectsUrlWithoutJdbcPrefix() {
        assertEquals("URL 必须以 jdbc: 开头",
                DataSourceValidator.validate("prod", "mysql://h:3306/db", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void acceptsJdbcPrefixIgnoringCase() {
        assertNull(DataSourceValidator.validate("prod", "JDBC:oracle:thin:@h:1521:ORCL",
                new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void rejectsOverlongName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 41; i++) sb.append('n');
        assertEquals("标识名过长（≤40 字符）",
                DataSourceValidator.validate(sb.toString(), "jdbc:x", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void nameIsTrimmedBeforeCompare() {
        assertNull(DataSourceValidator.validate("  prod  ", "jdbc:mysql://h/db",
                list(ds("dev", "jdbc:mysql://h/db")), null));
    }
}
