package com.minion.core.tools.db;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 数据源表单校验：标识名唯一性、URL 前缀、用户名/密码非空（新建时 originalName 传 null，修改时传原名） */
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
                "u", "p", list(ds("dev", "jdbc:mysql://h2:3306/db")), null));
    }

    @Test
    public void rejectsBlankName() {
        assertEquals("标识名不能为空", DataSourceValidator.validate("  ", "jdbc:x", "u", "p", new ArrayList<DataSourceConfig>(), null));
        assertEquals("标识名不能为空", DataSourceValidator.validate(null, "jdbc:x", "u", "p", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void rejectsDuplicateNameIgnoringCase() {
        assertEquals("标识名已存在：PROD",
                DataSourceValidator.validate("PROD", "jdbc:mysql://h/db", "u", "p", list(ds("prod", "jdbc:mysql://h/db")), null));
    }

    @Test
    public void allowsKeepingOwnNameOnEdit() {
        // 修改时改回自身原名不算重复
        assertNull(DataSourceValidator.validate("prod", "jdbc:mysql://newhost/db",
                "u", "p", list(ds("prod", "jdbc:mysql://oldhost/db")), "prod"));
    }

    @Test
    public void rejectsTakingAnotherEntrysNameOnEdit() {
        assertEquals("标识名已存在：dev",
                DataSourceValidator.validate("dev", "jdbc:mysql://h/db",
                        "u", "p", list(ds("prod", "jdbc:mysql://a/db"), ds("dev", "jdbc:mysql://b/db")), "prod"));
    }

    @Test
    public void rejectsBlankUrl() {
        assertEquals("URL 不能为空", DataSourceValidator.validate("prod", "  ", "u", "p", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void rejectsUrlWithoutJdbcPrefix() {
        assertEquals("URL 必须以 jdbc: 开头",
                DataSourceValidator.validate("prod", "mysql://h:3306/db", "u", "p", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void acceptsJdbcPrefixIgnoringCase() {
        assertNull(DataSourceValidator.validate("prod", "JDBC:oracle:thin:@h:1521:ORCL",
                "u", "p", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void rejectsOverlongName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 41; i++) sb.append('n');
        assertEquals("标识名过长（≤40 字符）",
                DataSourceValidator.validate(sb.toString(), "jdbc:x", "u", "p", new ArrayList<DataSourceConfig>(), null));
    }

    @Test
    public void nameIsTrimmedBeforeCompare() {
        assertNull(DataSourceValidator.validate("  prod  ", "jdbc:mysql://h/db",
                "u", "p", list(ds("dev", "jdbc:mysql://h/db")), null));
    }

    /** 用户名全空白/未填 → 拒绝（trim 判空，规则与界面提示一致） */
    @Test
    public void rejectsBlankUser() {
        assertEquals("用户名不能为空",
                DataSourceValidator.validate("prod", "jdbc:mysql://h/db", "  ", "p",
                        new ArrayList<DataSourceConfig>(), null));
        assertEquals("用户名不能为空",
                DataSourceValidator.validate("prod", "jdbc:mysql://h/db", null, "p",
                        new ArrayList<DataSourceConfig>(), null));
    }

    /** 密码未填/全空白 → 拒绝（trim 判空；保存仍保留原值首尾空格，见 DataSourceDialog） */
    @Test
    public void rejectsBlankPassword() {
        assertEquals("密码不能为空",
                DataSourceValidator.validate("prod", "jdbc:mysql://h/db", "u", "",
                        new ArrayList<DataSourceConfig>(), null));
        assertEquals("密码不能为空",
                DataSourceValidator.validate("prod", "jdbc:mysql://h/db", "u", "   ",
                        new ArrayList<DataSourceConfig>(), null));
    }
}
