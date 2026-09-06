package com.minion.core.tools.db;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 支持的数据库类型：插件 id、页面显示名、工具名、驱动类、URL 模板（新建数据源预填）、动作集。
 * PostgreSQL 只支持 query —— 当前环境下查系统表会报错，故 schema/describe 不建连接、直接返回提示。
 * MySQL 的 URL 模板带 useInformationSchema=true：否则 DatabaseMetaData 的 REMARKS（表/列注释）恒为空。
 */
public enum DbType {

    MYSQL("mysql", "mysql", "DbMysql", "com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://localhost:3306/dbname?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&useInformationSchema=true",
            Arrays.asList("query", "schema", "describe")),

    POSTGRES("postgresql", "postgreSQL", "DbPostgres", "org.postgresql.Driver",
            "jdbc:postgresql://localhost:5432/dbname",
            Collections.singletonList("query")),

    ORACLE("oracle", "oracle", "DbOracle", "oracle.jdbc.OracleDriver",
            "jdbc:oracle:thin:@localhost:1521:ORCL",
            Arrays.asList("query", "schema", "describe"));

    private final String id;
    private final String displayName;
    private final String toolName;
    private final String driverClass;
    private final String urlTemplate;
    private final List<String> actions;

    DbType(String id, String displayName, String toolName, String driverClass,
           String urlTemplate, List<String> actions) {
        this.id = id;
        this.displayName = displayName;
        this.toolName = toolName;
        this.driverClass = driverClass;
        this.urlTemplate = urlTemplate;
        this.actions = actions;
    }

    /** tools.json 的键名，也是 ToolRegistry 的插件标签 */
    public String id() { return id; }

    /** 设置页显示名 */
    public String displayName() { return displayName; }

    /** 暴露给模型的工具名 */
    public String toolName() { return toolName; }

    public String driverClass() { return driverClass; }

    public String urlTemplate() { return urlTemplate; }

    public List<String> actions() { return actions; }

    /** "query/schema/describe"，用于「未知 action」提示 */
    public String actionsText() {
        StringBuilder sb = new StringBuilder();
        for (String a : actions) {
            if (sb.length() > 0) sb.append('/');
            sb.append(a);
        }
        return sb.toString();
    }

    public boolean supports(String action) { return action != null && actions.contains(action); }

    /** 按 tools.json 键名反查；未知返回 null */
    public static DbType ofId(String id) {
        if (id == null) return null;
        for (DbType t : values()) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
