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
            // 主说明文件留空（null = 不使用）：不预置 ./project.md 这种可能不存在的路径
            m.workspaces.add(new WorkspaceConfig(DEFAULT_NAME, ".", null, null));
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

    /**
     * 新建工作空间。false = 名称非法/重名，或路径不合法（校验见 {@link #pathsUsable}：
     * 项目路径**必填且必须是已存在的文件夹**——它是文件工具与 Bash 的守卫边界；
     * 主说明文件可选，填了必须是已存在的文件；技能路径可选，填了必须是文件夹）。
     */
    public boolean add(String name, String workDir, String projectMd, String projectSkillsDir) {
        if (name == null) return false;
        name = name.trim(); // 先 trim 再校验/存储
        if (!isValidName(name, names())) return false;
        WorkspaceConfig c = new WorkspaceConfig(name, trimOrNull(workDir), trimOrNull(projectMd),
                trimOrNull(projectSkillsDir));
        if (!pathsUsable(c)) return false;
        workspaces.add(c);
        save();
        return true;
    }

    /**
     * 路径字段合法性：项目路径必须是已存在文件夹；主说明文件可选，填了必须是**已存在的文件**
     * （指向文件夹或不存在都让提示词静默失效，宁可拒绝）；项目级技能路径可选，填了也必须是文件夹。
     */
    private static boolean pathsUsable(WorkspaceConfig c) {
        if (!WorkspacePaths.isExistingDir(c.workDir, null)) return false;
        if (c.projectMd != null && !WorkspacePaths.isExistingFile(c.projectMd, WorkspacePaths.workDirAbs(c))) {
            return false;
        }
        return c.projectSkillsDir == null
                || WorkspacePaths.isExistingDir(c.projectSkillsDir, WorkspacePaths.workDirAbs(c));
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

    /**
     * 覆盖式更新三个路径字段。false = 空间不存在，或路径不合法（校验同 {@link #pathsUsable}：
     * 项目路径必须是已存在文件夹，主说明文件填了必须是已存在文件，技能路径填了也必须是文件夹）；
     * 拒绝时不落盘，原配置不变。
     */
    public boolean update(String name, String workDir, String projectMd, String projectSkillsDir) {
        WorkspaceConfig w = get(name);
        if (w == null) return false;
        WorkspaceConfig c = new WorkspaceConfig(name, trimOrNull(workDir), trimOrNull(projectMd),
                trimOrNull(projectSkillsDir));
        if (!pathsUsable(c)) return false;
        w.workDir = c.workDir;
        w.projectMd = c.projectMd;
        w.projectSkillsDir = c.projectSkillsDir;
        save();
        return true;
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

    /** 移动工作空间顺序（UI 拖拽排序）；名字不存在或索引越界返回 false；位置不变视为成功不落盘 */
    public boolean move(String name, int newIndex) {
        WorkspaceConfig w = get(name);
        if (w == null) return false;
        if (newIndex < 0 || newIndex >= workspaces.size()) return false;
        int from = workspaces.indexOf(w);
        if (from == newIndex) return true;
        workspaces.remove(from);
        workspaces.add(newIndex, w);
        save();
        return true;
    }

    private List<String> names() {
        List<String> n = new ArrayList<String>();
        for (WorkspaceConfig w : workspaces) n.add(w.workSpaceName);
        return n;
    }

    /** 空白→null：三个路径字段统一走这里，空白等价未配置 */
    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
