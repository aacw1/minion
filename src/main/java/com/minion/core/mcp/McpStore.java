package com.minion.core.mcp;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** MCP 服务器配置：jarDir/mcp.json 单文件多服务器（仿 WorkspaceManager 模式） */
public class McpStore {

    public static final String FILE_NAME = "mcp.json";

    private final Path file;
    private final List<McpServer> servers = new ArrayList<McpServer>();

    McpStore(Path file) { this.file = file; }

    /** jar 同目录 mcp.json；缺失生成空列表；损坏备份 .bak 后重建 */
    public static McpStore load(Path jarDir) {
        McpStore s = new McpStore(jarDir.resolve(FILE_NAME));
        boolean loaded = false;
        if (Files.exists(s.file)) {
            try {
                String json = new String(Files.readAllBytes(s.file), StandardCharsets.UTF_8);
                Holder h = new Gson().fromJson(json, Holder.class);
                if (h != null && h.servers != null) {
                    s.servers.addAll(h.servers);
                    loaded = true;
                }
            } catch (Exception e) {
                backupCorrupt(s.file);
            }
        }
        if (!loaded) {
            s.save();
        }
        return s;
    }

    public List<McpServer> list() { return servers; }

    public void save() {
        // 原子写：先写 *.tmp 再 move 覆盖，避免半截文件；失败清理 tmp
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Holder h = new Holder();
            h.servers = servers;
            Files.createDirectories(file.getParent());
            Files.write(tmp, new Gson().toJson(h).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) { }
            System.err.println("[minion] 写入 mcp.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[minion] mcp.json 损坏备份失败: " + e.getMessage());
        }
    }

    private static class Holder {
        List<McpServer> servers;
    }
}
