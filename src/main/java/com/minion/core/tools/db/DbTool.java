package com.minion.core.tools.db;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

    /** query 的 full 参数容错解析：布尔原值 / 字符串 "true"（大小写、首尾空白容忍），缺省或畸形一律 false */
    static boolean fullOf(JsonObject args) {
        if (args == null || !args.has("full")) return false;
        try {
            JsonElement e = args.get("full");
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            return Boolean.parseBoolean(e.getAsString().trim());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

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
        sb.append("。大字段(CLOB/TEXT/LONGTEXT/JSON 等)默认截断 ")
          .append(MarkdownTable.CELL_MAX).append(" 字符并标注「…[完整 N 字符]」；")
          .append("当结果里出现该标注、或你已知该字段很长而需要其完整内容时，")
          .append("必须重发同一查询并设 full=true，并用 WHERE/LIMIT 把 SQL 限定到需要的行：")
          .append("full=true 时单元格上限 ").append(DbExecutor.FULL_CELL_MAX)
          .append(" 字符，单值查询可 inline 全文，超出自动落盘并附文件路径；")
          .append("结果超过 ").append(MarkdownTable.CHAR_BUDGET)
          .append(" 字符会落盘并提示拆分——请避免一次拉取大量大字段（如整表 JSON 列），")
          .append("按需 WHERE/LIMIT 拆分查询");
        if (type.supports("schema")) {
            sb.append("；action=schema 列出表与视图；action=describe 查看表字段");
        } else {
            sb.append("。仅支持 action=query；schema/describe 在本类型下不可用，会返回提示，")
              .append("表结构请用 SELECT * FROM 表名 LIMIT 1 自行推断");
        }
        sb.append("。仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN，禁止多语句与写操作。");
        return sb.toString();
    }

    /**
     * 手写精确 schema（不用 SchemaGenerator：它把所有属性生成为无描述的 string，
     * 曾使 full 在模型眼里是隐形参数、几乎不被调用）。
     * action 枚举按 DbType 动态生成（PG 无 schema/describe）；full 声明为布尔开关。
     */
    @Override
    public JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", type.displayName() + " 只读数据库操作");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "操作类型：query 执行只读 SQL；schema 列出表与视图；describe 查看表字段");
        JsonArray actionEnum = new JsonArray();
        for (String a : type.actions()) actionEnum.add(a);
        action.add("enum", actionEnum);
        props.add("action", action);

        JsonObject sql = new JsonObject();
        sql.addProperty("type", "string");
        sql.addProperty("description", "action=query 时必填：单条只读 SQL（SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN）");
        props.add("sql", sql);

        JsonObject table = new JsonObject();
        table.addProperty("type", "string");
        table.addProperty("description", "action=describe 时必填：表名（支持 % / _ 模式匹配多表）");
        props.add("table", table);

        JsonObject full = new JsonObject();
        full.addProperty("type", "boolean");
        full.addProperty("description", "仅在需要大字段全文时设 true：单元格截断线从 "
                + MarkdownTable.CELL_MAX + " 放宽到 " + DbExecutor.FULL_CELL_MAX + " 字符。"
                + "结果里出现「…[完整 N 字符]」且需要该内容时，必须设 full=true 重发同一查询，"
                + "并用 WHERE/LIMIT 限定到需要的行");
        props.add("full", full);

        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("action");
        schema.add("required", required);
        return schema;
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
            return executor.query(ds, type, sql, fullOf(args));
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
