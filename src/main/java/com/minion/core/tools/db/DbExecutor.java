package com.minion.core.tools.db;

import com.minion.core.tools.ToolResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 只读 SQL 执行器：**每次调用新建 JDBC 连接、用完即关（禁用连接池）**。
 * 三层只读防护的第 2、3 层在这里：Connection.setReadOnly(true) + 只用 executeQuery
 * （第 1 层 SqlGuard 由 DbTool 在调用前执行）。另加 setMaxRows 限流与 setQueryTimeout 防卡死。
 * 所有失败都转成 ToolResult.error（文案口径见设计文档第 5 节），不向调用方抛异常。
 */
public class DbExecutor {

    /** 返回给模型的最大行数；setMaxRows 用 MAX_ROWS+1 探测是否被截断 */
    public static final int MAX_ROWS = 100;
    public static final int QUERY_TIMEOUT_SECONDS = 300;
    public static final int LOGIN_TIMEOUT_SECONDS = 10;
    /** schema 动作（表清单）上限 */
    public static final int SCHEMA_MAX_ROWS = 500;

    private final Path tmpDir;

    public DbExecutor(Path tmpDir) { this.tmpDir = tmpDir; }

    public DbExecutor(String tmpDir) { this(tmpDir == null ? null : Paths.get(tmpDir)); }

    /** 连接结果：conn 非 null 即成功，否则 error 是可直接返回的失败结果 */
    private static final class Opened {
        final Connection conn;
        final ToolResult error;
        Opened(Connection conn, ToolResult error) { this.conn = conn; this.error = error; }
    }

    /** 显式 Class.forName（不依赖 shade 后的服务发现）+ 每次新建连接 */
    private Opened open(DataSourceConfig ds, DbType type) {
        try {
            Class.forName(type.driverClass());
        } catch (Throwable e) {
            return new Opened(null, ToolResult.error(type.displayName()
                    + " 驱动加载失败（驱动应已内置，请检查 jar 完整性）: " + e));
        }
        try {
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            Connection c = DriverManager.getConnection(ds.url, ds.user, ds.password);
            c.setAutoCommit(true);
            try {
                c.setReadOnly(true);
            } catch (Throwable ignored) {
                // 部分驱动/数据库不强制 readOnly；写操作已由 SqlGuard 拦在前面
            }
            return new Opened(c, null);
        } catch (SQLException e) {
            return new Opened(null, ToolResult.error("连接数据源「" + ds.name + "」失败: "
                    + firstLine(e.getMessage()) + "（请检查 URL/账号/网络/白名单）"));
        }
    }

    /** 执行只读 SQL，结果渲染成 Markdown 表格（超 30000 字符落盘） */
    public ToolResult query(DataSourceConfig ds, DbType type, String sql) {
        Opened opened = open(ds, type);
        if (opened.conn == null) return opened.error;
        long t0 = System.currentTimeMillis();
        Statement st = null;
        ResultSet rs = null;
        try {
            st = opened.conn.createStatement();
            st.setMaxRows(MAX_ROWS + 1);
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            rs = st.executeQuery(sql);
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            List<String> names = new ArrayList<String>();
            for (int i = 1; i <= cols; i++) {
                String label = md.getColumnLabel(i);
                names.add(label == null || label.isEmpty() ? md.getColumnName(i) : label);
            }
            List<List<String>> rows = new ArrayList<List<String>>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() == MAX_ROWS) { truncated = true; break; }
                List<String> row = new ArrayList<String>();
                for (int i = 1; i <= cols; i++) row.add(MarkdownTable.cell(rs.getObject(i)));
                rows.add(row);
            }
            long elapsed = System.currentTimeMillis() - t0;
            String head = MarkdownTable.header(ds.name, elapsed, rows.size(), truncated, MAX_ROWS);
            if (rows.isEmpty()) return ToolResult.success(head + "\n\n查询成功，0 行结果");
            return ToolResult.success(MarkdownTable.fit(
                    head + "\n\n" + MarkdownTable.render(names, rows), tmpDir));
        } catch (SQLException e) {
            return ToolResult.error(sqlError(e));
        } finally {
            close(rs);
            close(st);
            close(opened.conn);
        }
    }

    /** 列出表与视图（走 DatabaseMetaData，不拼 SQL） */
    public ToolResult listTables(DataSourceConfig ds, DbType type) {
        Opened opened = open(ds, type);
        if (opened.conn == null) return opened.error;
        long t0 = System.currentTimeMillis();
        ResultSet rs = null;
        try {
            DatabaseMetaData meta = opened.conn.getMetaData();
            rs = meta.getTables(catalogOf(opened.conn, type), schemaOf(meta, type), "%",
                    new String[]{"TABLE", "VIEW"});
            List<List<String>> rows = new ArrayList<List<String>>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() == SCHEMA_MAX_ROWS) { truncated = true; break; }
                rows.add(Arrays.asList(
                        MarkdownTable.cell(rs.getString("TABLE_NAME")),
                        MarkdownTable.cell(rs.getString("TABLE_TYPE")),
                        MarkdownTable.cell(remark(rs))));
            }
            long elapsed = System.currentTimeMillis() - t0;
            String head = MarkdownTable.header(ds.name, elapsed, rows.size(), truncated, SCHEMA_MAX_ROWS);
            if (rows.isEmpty()) return ToolResult.success(head + "\n\n未发现任何表或视图");
            StringBuilder sb = new StringBuilder(head).append("\n\n").append(MarkdownTable.render(
                    Arrays.asList("表名", "类型", "注释"), rows));
            if (truncated) {
                sb.append("\n\n…（已达 ").append(SCHEMA_MAX_ROWS).append(" 行上限，可用 query 精确检索）");
            }
            return ToolResult.success(MarkdownTable.fit(sb.toString(), tmpDir));
        } catch (SQLException e) {
            return ToolResult.error(sqlError(e));
        } finally {
            close(rs);
            close(opened.conn);
        }
    }

    /** 查看表字段（走 DatabaseMetaData；tablePattern 含 % / _ 时按模式匹配多表） */
    public ToolResult describe(DataSourceConfig ds, DbType type, String tablePattern) {
        Opened opened = open(ds, type);
        if (opened.conn == null) return opened.error;
        long t0 = System.currentTimeMillis();
        ResultSet rs = null;
        try {
            DatabaseMetaData meta = opened.conn.getMetaData();
            rs = meta.getColumns(catalogOf(opened.conn, type), schemaOf(meta, type), tablePattern, "%");
            List<List<String>> rows = new ArrayList<List<String>>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() == MAX_ROWS) { truncated = true; break; }
                rows.add(Arrays.asList(
                        MarkdownTable.cell(rs.getString("TABLE_NAME")),
                        MarkdownTable.cell(rs.getString("COLUMN_NAME")),
                        MarkdownTable.cell(rs.getString("TYPE_NAME")),
                        MarkdownTable.cell(sizeText(rs)),
                        MarkdownTable.cell(nullableText(rs.getInt("NULLABLE"))),
                        MarkdownTable.cell(defaultText(rs)),
                        MarkdownTable.cell(remark(rs))));
            }
            long elapsed = System.currentTimeMillis() - t0;
            if (rows.isEmpty()) return ToolResult.error("未找到表: " + tablePattern);
            String head = MarkdownTable.header(ds.name, elapsed, rows.size(), truncated, MAX_ROWS);
            StringBuilder sb = new StringBuilder(head).append("\n\n").append(MarkdownTable.render(
                    Arrays.asList("表名", "列名", "类型", "长度", "可空", "默认值", "注释"), rows));
            if (truncated) {
                sb.append("\n\n…（已达 ").append(MAX_ROWS).append(" 行上限，可用更精确的表名）");
            }
            return ToolResult.success(MarkdownTable.fit(sb.toString(), tmpDir));
        } catch (SQLException e) {
            return ToolResult.error(sqlError(e));
        } finally {
            close(rs);
            close(opened.conn);
        }
    }

    /** MySQL 用当前库作 catalog；其余不限定（Oracle 靠 schema 限定） */
    private static String catalogOf(Connection c, DbType type) throws SQLException {
        return type == DbType.MYSQL ? c.getCatalog() : null;
    }

    /** Oracle 不限定 schema 会拉出 SYS/SYSTEM 海量对象，用当前登录用户限定 */
    private static String schemaOf(DatabaseMetaData meta, DbType type) throws SQLException {
        return type == DbType.ORACLE ? meta.getUserName() : null;
    }

    /** REMARKS 在部分驱动/未开 useInformationSchema 时取不到，按空处理 */
    private static String remark(ResultSet rs) {
        try {
            String r = rs.getString("REMARKS");
            return r == null ? "" : r;
        } catch (SQLException e) {
            return "";
        }
    }

    private static String defaultText(ResultSet rs) {
        try {
            String d = rs.getString("COLUMN_DEF");
            return d == null ? "" : d;
        } catch (SQLException e) {
            return "";
        }
    }

    /** 长度列：COLUMN_SIZE；小数位有效时附精度（如 10,2） */
    private static String sizeText(ResultSet rs) throws SQLException {
        int size = rs.getInt("COLUMN_SIZE");
        int digits = rs.getInt("DECIMAL_DIGITS");
        if (rs.wasNull()) return String.valueOf(size);
        return digits > 0 ? size + "," + digits : String.valueOf(size);
    }

    private static String nullableText(int nullable) {
        if (nullable == DatabaseMetaData.columnNoNulls) return "否";
        if (nullable == DatabaseMetaData.columnNullable) return "是";
        return "未知";
    }

    private static String sqlError(SQLException e) {
        if (isTimeout(e)) {
            return "查询超时（>" + QUERY_TIMEOUT_SECONDS + "s），已中断；请加 LIMIT 或缩小查询范围";
        }
        return "查询失败 [SQLState " + e.getSQLState() + " / 错误码 " + e.getErrorCode() + "]: "
                + firstLine(e.getMessage());
    }

    /** 超时判定：PG 57014 / Oracle 01013 / MySQL 70100，兜底看 message 里的 timeout 字样 */
    static boolean isTimeout(SQLException e) {
        String state = e.getSQLState();
        if ("57014".equals(state) || "01013".equals(state) || "70100".equals(state)) return true;
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("query execution was interrupted");
    }

    /** 驱动异常首行（多行堆栈式消息只取首行），超 200 字符截断 */
    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String first = (i < 0 ? s : s.substring(0, i)).trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    private static void close(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) { }
    }
}
