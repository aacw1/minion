package com.minion.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置：classpath 默认值 + 外部覆盖（jar 同目录 config.properties，首启自动生成） */
public class Config {

    public static final String EXTERNAL_FILE_NAME = "config.properties";
    private static final String DEFAULT_RESOURCE = "/config.properties";

    private final Map<String, String> props = new HashMap<String, String>();
    private final Path externalFile;

    private Config(Path externalFile) { this.externalFile = externalFile; }

    public static Config load() { return load(jarDir()); }

    /** @param overrideDir 外部配置文件所在目录（测试注入） */
    public static Config load(Path overrideDir) {
        return load(overrideDir, DEFAULT_RESOURCE);
    }

    /** @param defaultResource 默认值资源路径（测试注入纯净默认值，避免读到本机真实配置） */
    static Config load(Path overrideDir, String defaultResource) {
        Config c = new Config(overrideDir.resolve(EXTERNAL_FILE_NAME));
        loadResource(c.props, defaultResource);
        if (Files.exists(c.externalFile)) {
            loadFile(c.props, c.externalFile);
        } else {
            try {
                Files.createDirectories(overrideDir);
                String defaults = new String(
                        readResource(defaultResource), StandardCharsets.UTF_8);
                Files.write(c.externalFile, defaults.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[minion] 无法生成外部配置文件: " + e.getMessage());
            }
        }
        return c;
    }

    /** jar 所在目录（workspace.json/model.json/会话目录的基准） */
    public static Path jarDir() {
        try {
            Path p = Paths.get(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return p.getParent() != null ? p.getParent() : Paths.get(".");
        } catch (Exception e) { return Paths.get("."); }
    }

    private static byte[] readResource(String name) throws IOException {
        java.io.InputStream in = Config.class.getResourceAsStream(name);
        if (in == null) throw new IOException("missing resource " + name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void loadResource(Map<String, String> m, String resource) {
        try { loadLines(m, new String(readResource(resource), StandardCharsets.UTF_8).split("\\r?\\n")); }
        catch (IOException e) { throw new IllegalStateException("默认配置缺失", e); }
    }

    private static void loadFile(Map<String, String> m, Path f) {
        try { loadLines(m, Files.readAllLines(f, StandardCharsets.UTF_8).toArray(new String[0])); }
        catch (IOException e) { System.err.println("[minion] 读取外部配置失败: " + e.getMessage()); }
    }

    private static void loadLines(Map<String, String> m, String[] lines) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i <= 0) continue;
            m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
    }

    public String get(String key, String def) {
        String v = props.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public String skillsDir()    { return get("skills.dir", "./skills"); }

    /** 读逃逸：true 时 Read/Grep/Glob 可读取工作区外文件（写入类工具不受影响，仍受限） */
    public boolean readAllowOutside() { return Boolean.parseBoolean(get("paths.read.allowOutside", "false")); }
    public boolean confirmSkip() { return Boolean.parseBoolean(get("confirm.skip", "false")); }
    public Set<String> whitelistTools()    { return csv(get("confirm.whitelist.tools", "")); }
    public Set<String> whitelistCommands() { return csv(get("confirm.whitelist.commands", "")); }
    public String browserPath()       { return get("browser.path", ""); }
    public int browserPort()          { return Integer.parseInt(get("browser.port", "9222")); }
    public String browserUserDataDir(){ return get("browser.userDataDir", "./.minion/browser-profile"); }
    public boolean browserHeadless()  { return Boolean.parseBoolean(get("browser.headless", "false")); }
    public int browserTimeoutMs()     { return Integer.parseInt(get("browser.timeoutMs", "30000")); }

    private static Set<String> csv(String s) {
        Set<String> set = new HashSet<String>();
        for (String part : s.split(",")) {
            if (!part.trim().isEmpty()) set.add(part.trim().toLowerCase());
        }
        return set;
    }

    public Path externalFile() { return externalFile; }

    /**
     * 运行时写回配置：更新内存 + 重写外部 config.properties（保留注释行，替换/追加 key 行）。
     * 实时生效核对：confirmSkip/whitelist/readAllowOutside 每次使用即读 Config → 立即生效；
     * skills.dir 由新会话 buildCtx 读取 → 新会话生效。
     */
    public void set(String key, String value) {
        props.put(key, value);
        try {
            List<String> lines = Files.exists(externalFile)
                    ? Files.readAllLines(externalFile, StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();
            StringBuilder sb = new StringBuilder();
            boolean replaced = false;
            for (String line : lines) {
                if (line.trim().startsWith(key + "=")) {
                    sb.append(key).append('=').append(value).append('\n');
                    replaced = true;
                } else {
                    sb.append(line).append('\n');
                }
            }
            if (!replaced) sb.append(key).append('=').append(value).append('\n');
            Files.write(externalFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[minion] 写入配置失败: " + e.getMessage());
        }
    }

    /** 追加白名单（去重）。section 形如 confirm.whitelist.tools */
    public void appendWhitelist(String section, String value) {
        String v = value.trim().toLowerCase();
        if (v.isEmpty()) return;
        try {
            String existing = props.containsKey(section) ? props.get(section) : "";
            Set<String> set = csv(existing);
            if (set.contains(v)) return;
            List<String> lines = Files.exists(externalFile)
                    ? Files.readAllLines(externalFile, StandardCharsets.UTF_8)
                    : new java.util.ArrayList<String>();
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(v).append(" added by minion ").append(new java.util.Date()).append('\n');
            boolean replaced = false;
            for (String line : lines) {
                if (line.trim().startsWith(section + "=")) {
                    String sep = line.trim().endsWith("=") || set.isEmpty() ? "" : ",";
                    sb.append(section).append('=').append(existing)
                      .append(sep).append(v).append('\n');
                    replaced = true;
                } else {
                    sb.append(line).append('\n');
                }
            }
            if (!replaced) {
                sb.append(section).append('=').append(existing)
                  .append(existing.isEmpty() ? "" : ",").append(v).append('\n');
            }
            Files.write(externalFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            props.put(section, set.isEmpty() ? v : existing + "," + v);
        } catch (IOException e) {
            System.err.println("[minion] 写入白名单失败: " + e.getMessage());
        }
    }
}
