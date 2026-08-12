package com.minion.core.config;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 工作空间配置：jarDir/workspace.json 单文件多工作空间；会话目录按工作空间名派生 */
public class WorkspaceManager {

    public static final String FILE_NAME = "workspace.json";
    private static final String DEFAULT_NAME = "default";
    private static final String ILLEGAL_CHARS = "[\\\\/:*?\"<>|]";

    private final Path file;
    private final List<WorkspaceConfig> workspaces = new ArrayList<WorkspaceConfig>();
    private String currentName = DEFAULT_NAME;

    private WorkspaceManager(Path file) { this.file = file; }

    /** jar 同目录 workspace.json；缺失生成默认，损坏备份 .bak 后重建；空列表是合法状态不重建 */
    public static WorkspaceManager load(Path jarDir) {
        WorkspaceManager m = new WorkspaceManager(jarDir.resolve(FILE_NAME));
        boolean loaded = false;
        if (Files.exists(m.file)) {
            try {
                String json = new String(Files.readAllBytes(m.file), StandardCharsets.UTF_8);
                Holder h = new Gson().fromJson(json, Holder.class);
                if (h != null && h.workspaces != null) { // workspaces 键存在（含空数组）即采用
                    m.workspaces.addAll(h.workspaces);
                    if (h.currentWorkspaceName != null) m.currentName = h.currentWorkspaceName;
                    loaded = true;
                }
            } catch (Exception e) {
                backupCorrupt(m.file);
            }
        }
        if (!loaded) {
            m.workspaces.add(new WorkspaceConfig(DEFAULT_NAME, ".", "./project.md"));
            m.currentName = DEFAULT_NAME;
            m.save();
        }
        if (m.get(m.currentName) == null && !m.workspaces.isEmpty()) {
            m.currentName = m.workspaces.get(0).workSpaceName;
        }
        return m;
    }

    /** 会话存储目录：jarDir/session/<workSpaceName>/ */
    public static Path sessionDirFor(Path jarDir, String workspaceName) {
        return jarDir.resolve("session").resolve(workspaceName);
    }

    /** 名称合法性：非空、无非法字符、不重名（大小写不敏感，Windows 目录安全） */
    public static boolean isValidName(String name, List<String> existing) {
        if (name == null || name.trim().isEmpty()) return false;
        if (name.matches(".*" + ILLEGAL_CHARS + ".*")) return false;
        for (String e : existing) {
            if (e != null && e.equalsIgnoreCase(name)) return false;
        }
        return true;
    }

    public List<WorkspaceConfig> list() { return new ArrayList<WorkspaceConfig>(workspaces); }

    public WorkspaceConfig get(String name) {
        for (WorkspaceConfig w : workspaces) {
            if (w.workSpaceName.equals(name)) return w;
        }
        return null;
    }

    public WorkspaceConfig current() { return get(currentName); }

    public String currentName() { return currentName; }

    public boolean add(String name, String workDir, String projectMd) {
        if (name == null) return false;
        name = name.trim(); // 先 trim 再校验/存储
        if (!isValidName(name, names())) return false;
        workspaces.add(new WorkspaceConfig(name, workDir, projectMd));
        save();
        return true;
    }

    public boolean rename(String oldName, String newName) {
        if (get(oldName) == null) return false;
        if (newName == null) return false;
        newName = newName.trim(); // 先 trim 再校验
        if (!isValidName(newName, namesExcept(oldName))) return false;
        WorkspaceConfig w = get(oldName);
        w.workSpaceName = newName;
        // 会话目录随工作空间名迁移（目录不存在则跳过）
        Path oldDir = sessionDirFor(file.getParent(), oldName);
        Path newDir = sessionDirFor(file.getParent(), newName);
        if (Files.isDirectory(oldDir)) {
            try {
                Files.move(oldDir, newDir);
            } catch (IOException e) {
                w.workSpaceName = oldName; // 迁移失败回滚
                return false;
            }
        }
        if (currentName.equals(oldName)) currentName = newName;
        save();
        return true;
    }

    public void update(String name, String workDir, String projectMd) {
        WorkspaceConfig w = get(name);
        if (w == null) return;
        w.workDir = workDir;
        w.projectMd = projectMd;
        save();
    }

    public boolean remove(String name) {
        if (get(name) == null || workspaces.size() <= 1) return false;
        workspaces.remove(get(name));
        if (currentName.equals(name)) currentName = workspaces.get(0).workSpaceName;
        try {
            deleteRecursively(sessionDirFor(file.getParent(), name));
        } catch (IOException ignored) {
            // 目录删除失败不阻断配置删除
        }
        save();
        return true;
    }

    public void setCurrent(String name) {
        if (get(name) == null) return;
        currentName = name;
        save();
    }

    private List<String> names() {
        List<String> n = new ArrayList<String>();
        for (WorkspaceConfig w : workspaces) n.add(w.workSpaceName);
        return n;
    }

    private List<String> namesExcept(String name) {
        List<String> n = names();
        n.remove(name);
        return n;
    }

    private void save() {
        // 原子写：先写 *.tmp 再 move 覆盖，避免半截文件；失败清理 tmp
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Holder h = new Holder();
            h.workspaces = workspaces;
            h.currentWorkspaceName = currentName;
            Files.createDirectories(file.getParent());
            Files.write(tmp, new Gson().toJson(h).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) { }
            System.err.println("[minion] 写入 workspace.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 备份失败仅告警，load 仍继续重建默认
            System.err.println("[minion] workspace.json 损坏备份失败: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        // JDK8 无 walk 排序便利，用递归
        if (Files.isDirectory(root)) {
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path p : ds) deleteRecursively(p);
            }
        }
        Files.deleteIfExists(root);
    }

    private static class Holder {
        List<WorkspaceConfig> workspaces;
        String currentWorkspaceName;
    }
}
