package com.minion.core.tools.db;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.plugin.DbConfig;

import java.nio.file.Path;

/**
 * 只读数据库工具（按 DbType 参数化，三处实例化：DbMysql / DbPostgres / DbOracle）。
 * description 每轮由 AgentLoop 重新取，故动态拼接「当前数据源」——设置页切下拉框后无需重建工具即生效。
 * 只用当前选中数据源，不向模型暴露 datasource 参数；查询不弹高危确认窗。
 */
public class DbTool implements Tool {

    /** PostgreSQL 的 schema/describe 固定提示：不建连接直接返回 */
    static final String PG_SCHEMA_HINT =
            "postgreSQL 数据源不支持列出表名（当前环境查询系统表会报错），"
                    + "请直接使用 query，例如 SELECT * FROM 表名 LIMIT 1 推断结构";
    static final String PG_DESCRIBE_HINT =
            "postgreSQL 数据源不支持表结构查询，"
                    + "请用 query 执行 SELECT * FROM 表名 LIMIT 1 自行推断字段";

    private final DbType type;
    private final DbConfig config;
    private final DbExecutor executor;

    public DbTool(DbType type, DbConfig config, Path tmpDir) {
        this.type = type;
        this.config = config;
        this.executor = new DbExecutor(tmpDir);
    }

    @Override
    public String name() { return type.toolName(); }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.displayName()).append(" 只读查询（");
        DataSourceConfig ds = config == null ? null : config.currentDataSource();
        if (ds == null) {
            sb.append("当前未选择数据源，调用会返回提示，请在 设置 → 工具 中选择数据源");
        } else {
            sb.append("当前数据源: ").append(ds.name).append("，").append(ds.url);
        }
        sb.append("）。action=query 执行只读 SQL 返回 Markdown 表格（最多 ")
          .append(DbExecutor.MAX_ROWS).append(" 行）");
        if (type.supports("schema")) {
            sb.append("；action=schema 列出表与视图；action=describe 查看表字段");
        } else {
            sb.append("。仅支持 action=query；schema/describe 在本类型下不可用，会返回提示，")
              .append("表结构请用 SELECT * FROM 表名 LIMIT 1 自行推断");
        }
        sb.append("。仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN，禁止多语句与写操作。");
        return sb.toString();
    }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema(type.displayName() + " 只读数据库操作",
                new String[]{"action", "sql", "table"}, new String[]{"action"});
    }

    /** 只读查询不打断心流：不弹确认窗 */
    @Override
    public boolean isHighRisk(JsonObject args) { return false; }

    @Override
    public ToolResult execute(JsonObject args) {
        if (args == null || !args.has("action")) return ToolResult.error("缺少 action 参数");
        String action = args.get("action").getAsString().trim();
        if (!"query".equals(action) && !"schema".equals(action) && !"describe".equals(action)) {
            return ToolResult.error("未知 action: " + action + "（支持 " + type.actionsText() + "）");
        }
        DataSourceConfig ds = config == null ? null : config.currentDataSource();
        if (ds == null) {
            return ToolResult.error(type.displayName() + " 插件未选择数据源，请在 设置 → 工具 → "
                    + type.displayName() + " 中选择");
        }
        if ("query".equals(action)) {
            if (!args.has("sql")) return ToolResult.error("query 需要 sql 参数");
            String sql = args.get("sql").getAsString();
            String why = SqlGuard.check(sql);       // 第一层防护
            if (why != null) return ToolResult.error(why);
            return executor.query(ds, type, sql);
        }
        if ("schema".equals(action)) {
            if (!type.supports("schema")) return ToolResult.error(PG_SCHEMA_HINT);
            return executor.listTables(ds, type);
        }
        if (!type.supports("describe")) return ToolResult.error(PG_DESCRIBE_HINT);
        if (!args.has("table")) return ToolResult.error("describe 需要 table 参数");
        String table = args.get("table").getAsString().trim();
        if (table.isEmpty()) return ToolResult.error("describe 需要 table 参数");
        return executor.describe(ds, type, table);
    }
}
