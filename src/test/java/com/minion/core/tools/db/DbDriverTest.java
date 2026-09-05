package com.minion.core.tools.db;

import org.junit.Test;

import java.sql.Driver;
import java.sql.DriverManager;

import static org.junit.Assert.*;

/** 三个 JDBC 驱动可加载且已注册到 DriverManager（shade 后服务文件合并的兜底验证） */
public class DbDriverTest {

    @Test
    public void mysqlDriverLoadable() throws Exception {
        Class<?> c = Class.forName("com.mysql.cj.jdbc.Driver");
        assertTrue(Driver.class.isAssignableFrom(c));
    }

    @Test
    public void postgresDriverLoadable() throws Exception {
        Class<?> c = Class.forName("org.postgresql.Driver");
        assertTrue(Driver.class.isAssignableFrom(c));
    }

    @Test
    public void oracleDriverLoadable() throws Exception {
        Class<?> c = Class.forName("oracle.jdbc.OracleDriver");
        assertTrue(Driver.class.isAssignableFrom(c));
    }

    @Test
    public void driversRegisteredAfterExplicitLoad() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        Class.forName("org.postgresql.Driver");
        Class.forName("oracle.jdbc.OracleDriver");
        assertTrue(accepts("jdbc:mysql://localhost:3306/db"));
        assertTrue(accepts("jdbc:postgresql://localhost:5432/db"));
        assertTrue(accepts("jdbc:oracle:thin:@localhost:1521:ORCL"));
    }

    /** DriverManager 是否有驱动认领该 url（不实际建连） */
    private static boolean accepts(String url) {
        java.util.Enumeration<Driver> e = DriverManager.getDrivers();
        while (e.hasMoreElements()) {
            try {
                if (e.nextElement().acceptsURL(url)) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }
}
