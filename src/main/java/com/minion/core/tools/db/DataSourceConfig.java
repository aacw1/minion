package com.minion.core.tools.db;

/**
 * 数据源配置项（tools.json 里 dataSources 数组元素）。
 * 字段名即 JSON 键名，gson 直接序列化；密码按设计决策明文存（与 model.json 的 apiKey 一致）。
 */
public class DataSourceConfig {
    /** 标识名：同一数据库类型内唯一，可修改 */
    public String name = "";
    public String url = "";
    public String user = "";
    public String password = "";

    public DataSourceConfig() { }

    public DataSourceConfig(String name, String url, String user, String password) {
        this.name = name;
        this.url = url;
        this.user = user;
        this.password = password;
    }
}
