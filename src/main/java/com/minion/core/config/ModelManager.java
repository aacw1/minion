package com.minion.core.config;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 模型配置：jarDir/model.json 单文件多模型；至少保留一个模型 */
public class ModelManager {

    public static final String FILE_NAME = "model.json";

    private final Path file;
    private final List<ModelConfig> models = new ArrayList<ModelConfig>();
    private String currentName;

    private ModelManager(Path file) { this.file = file; }

    /** jar 同目录 model.json；缺失生成默认模型，损坏备份 .bak 后重建 */
    public static ModelManager load(Path jarDir) {
        ModelManager m = new ModelManager(jarDir.resolve(FILE_NAME));
        boolean loaded = false;
        if (Files.exists(m.file)) {
            try {
                String json = new String(Files.readAllBytes(m.file), StandardCharsets.UTF_8);
                Holder h = new Gson().fromJson(json, Holder.class);
                if (h != null && h.models != null && !h.models.isEmpty()) { // models 键存在且非空才采用
                    for (ModelConfig c : h.models) {
                        // 过滤 null / displayName 缺失条目，保证列表无不可用元素
                        if (c != null && c.displayName != null && !c.displayName.trim().isEmpty()) {
                            // reasoningEffort 缺失兜底：qwen 默认 xhigh（平台档位最高），其余默认 max
                            if (c.reasoningEffort == null || c.reasoningEffort.trim().isEmpty()) {
                                c.reasoningEffort = "qwen".equalsIgnoreCase(c.provider) ? "xhigh" : "max";
                            }
                            m.models.add(c);
                        }
                    }
                    if (h.currentModelName != null) m.currentName = h.currentModelName;
                    loaded = true;
                }
                if (m.models.isEmpty()) loaded = false; // 无有效条目视为未加载成功
            } catch (Exception e) {
                backupCorrupt(m.file);
            }
        }
        if (!loaded) {
            if (Files.exists(m.file)) backupCorrupt(m.file); // 文件存在但不可用：先备份再重建，避免配置丢失
            m.models.add(createDeepseek());
            m.models.add(createQwen());
            m.currentName = m.models.get(0).displayName;
            m.save();
        }
        // 回退检查：current 指向不存在的模型时取第一个（列表已保证非空且无 null）
        if (m.get(m.currentName) == null) m.currentName = m.models.get(0).displayName;
        return m;
    }

    public static ModelConfig createDeepseek() {
        ModelConfig c = new ModelConfig();
        c.displayName = "deepseek-v4-flash";
        c.url = "https://api.deepseek.com/v1/chat/completions";
        c.apiKey = "";
        c.modelName = "deepseek-v4-flash";
        c.provider = "deepseek";
        c.thinking = true;
        c.reasoningEffort = "max";
        c.maxContextTokens = 900000;
        c.compressThreshold = 0.8;
        c.keepRecentMessages = 10;
        return c;
    }

    /** 千问（阿里百炼 DashScope OpenAI 兼容模式）；reasoningEffort 对 qwen 无效，客户端仅发 enable_thinking */
    public static ModelConfig createQwen() {
        ModelConfig c = new ModelConfig();
        c.displayName = "qwen3-max";
        c.url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        c.apiKey = "";
        c.modelName = "qwen3-max";
        c.provider = "qwen";
        c.thinking = true;
        c.reasoningEffort = "max";
        c.maxContextTokens = 131072;
        c.compressThreshold = 0.8;
        c.keepRecentMessages = 10;
        return c;
    }

    public List<ModelConfig> list() {
        List<ModelConfig> copy = new ArrayList<ModelConfig>();
        for (ModelConfig c : models) copy.add(c);
        return copy;
    }

    public ModelConfig get(String displayName) {
        for (ModelConfig c : models) {
            if (c != null && c.displayName != null && c.displayName.equals(displayName)) return c;
        }
        return null;
    }

    public ModelConfig current() { return get(currentName); }

    public String currentName() { return currentName; }

    public boolean add(ModelConfig c) {
        if (c == null || c.displayName == null || c.displayName.trim().isEmpty()) return false;
        if (get(c.displayName) != null) return false;
        models.add(c);
        if (models.size() == 1) currentName = c.displayName;
        save();
        return true;
    }

    public void update(ModelConfig c) {
        if (c == null || c.displayName == null) return;
        ModelConfig old = get(c.displayName);
        if (old == null) return;
        old.url = c.url;
        old.apiKey = c.apiKey;
        old.modelName = c.modelName;
        old.provider = c.provider;
        old.thinking = c.thinking;
        old.reasoningEffort = c.reasoningEffort;
        old.maxContextTokens = c.maxContextTokens;
        old.compressThreshold = c.compressThreshold;
        old.keepRecentMessages = c.keepRecentMessages;
        save();
    }

    public boolean remove(String displayName) {
        ModelConfig c = get(displayName);
        if (c == null || models.size() <= 1) return false;
        models.remove(c);
        if (currentName.equals(displayName)) currentName = models.get(0).displayName;
        save();
        return true;
    }

    public void setCurrent(String displayName) {
        if (get(displayName) == null) return;
        currentName = displayName;
        save();
    }

    private void save() {
        // 原子写：先写 *.tmp 再 move 覆盖，避免半截文件；失败清理 tmp 并告警
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Holder h = new Holder();
            h.models = models;
            h.currentModelName = currentName;
            Files.createDirectories(file.getParent());
            Files.write(tmp, new Gson().toJson(h).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) { }
            System.err.println("[minion] 写入 model.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 备份失败仅告警，load 仍继续重建默认
            System.err.println("[minion] model.json 损坏备份失败: " + e.getMessage());
        }
    }

    private static class Holder {
        List<ModelConfig> models;
        String currentModelName;
    }
}
