package com.minion.core.tools.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.minion.core.tools.db.DataSourceConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 可插拔工具配置：jarDir/tools.json 单文件（仿 McpStore）。
 * 文件不存在 → 生成默认（四个工具全部不启用）；损坏 → 备份 .bak 后重建；写入用 .tmp + move 原子覆盖。
 */
public class ToolStore {

    public static final String FILE_NAME = "tools.json";

    /** gson 映射根对象：字段名即 JSON 顶层键名 */
    private static class Root {
        BrowserConfig browser = new BrowserConfig();
        DbConfig mysql = new DbConfig();
        DbConfig postgresql = new DbConfig();
        DbConfig oracle = new DbConfig();
    }

    private final Path file;
    private final Root root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private ToolStore(Path file, Root root) {
        this.file = file;
        this.root = root;
    }

    /** jar 同目录 tools.json；缺失生成默认；损坏备份 .bak 后重建 */
    public static ToolStore load(Path jarDir) {
        Path file = jarDir.resolve(FILE_NAME);
        Root loaded = null;
        if (Files.exists(file)) {
            try {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                loaded = new Gson().fromJson(json, Root.class);
            } catch (Exception e) {
                System.err.println("[minion] tools.json 解析失败: " + e.getMessage());
            }
            if (loaded == null) backupCorrupt(file);
        }
        Root root = loaded == null ? new Root() : loaded;
        ToolStore store = new ToolStore(file, root);
        store.normalize();
        if (loaded == null) store.save();   // 缺失/损坏：立即落一份默认值
        return store;
    }

    public BrowserConfig browserConfig() { return root.browser; }

    /** mysql / postgresql / oracle；未知 id 返回 null */
    public DbConfig dbConfig(String id) {
        if ("mysql".equals(id)) return root.mysql;
        if ("postgresql".equals(id)) return root.postgresql;
        if ("oracle".equals(id)) return root.oracle;
        return null;
    }

    /** gson 可能把显式 null 写进字段（手改文件/旧版本），统一归一化为可用默认值 */
    private void normalize() {
        if (root.browser == null) root.browser = new BrowserConfig();
        BrowserConfig b = root.browser;
        if (b.path == null) b.path = "";
        if (b.userDataDir == null) b.userDataDir = "./.minion/browser-profile";
        if (b.port <= 0) b.port = 9222;
        if (b.timeoutMs <= 0) b.timeoutMs = 30000;
        for (String id : new String[]{"mysql", "postgresql", "oracle"}) {
            DbConfig db = dbConfig(id);
            if (db == null) continue;
            if (db.dataSources == null) db.dataSources = new ArrayList<DataSourceConfig>();
            if (db.current == null) db.current = "";
            // current 指向已不存在的数据源（手改文件）→ 回退到第一个，与删除回退规则一致
            if (!db.current.trim().isEmpty() && db.currentDataSource() == null) {
                List<DataSourceConfig> list = db.dataSources;
                db.current = list.isEmpty() ? ""
                        : (list.get(0).name == null ? "" : list.get(0).name.trim());
            }
        }
    }

    public void save() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(tmp, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) { }
            System.err.println("[minion] 写入 tools.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[minion] tools.json 损坏备份失败: " + e.getMessage());
        }
    }
}
