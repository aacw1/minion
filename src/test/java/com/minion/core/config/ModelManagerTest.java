package com.minion.core.config;

import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ModelManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jarDir() throws IOException { return tmp.newFolder("jar").toPath(); }

    /** 无文件时生成 deepseek + qwen 双默认并落盘 */
    @Test
    public void load_createsDefaultModels() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        assertEquals(2, m.list().size());
        ModelConfig c = m.current();
        assertEquals("deepseek-v4-flash", c.displayName);
        assertEquals(900000, c.maxContextTokens);
        assertEquals("", c.apiKey);
        ModelConfig q = m.get("qwen3-max");
        assertNotNull(q);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", q.url);
        assertEquals("qwen3-max", q.modelName);
        assertEquals("qwen", q.provider);
        assertTrue(q.thinking);
        assertEquals(131072, q.maxContextTokens);
        assertEquals(0.8, q.compressThreshold, 1e-9);
        assertEquals(10, q.keepRecentMessages);
        assertEquals("", q.apiKey);
        assertTrue(Files.exists(dir.resolve("model.json")));
    }

    /** 新增 + 持久化重载 */
    @Test
    public void addAndReload_restoresModels() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        ModelConfig q = new ModelConfig();
        q.displayName = "qwen-test"; q.url = "http://x"; q.modelName = "qwen-max";
        q.thinking = false; q.maxContextTokens = 8192;
        assertTrue(m.add(q));
        m.setCurrent("qwen-test");
        ModelManager m2 = ModelManager.load(dir);
        assertEquals(3, m2.list().size());
        assertEquals("qwen-test", m2.currentName());
        assertEquals(8192, m2.current().maxContextTokens);
        assertEquals("qwen-max", m2.current().modelName);
    }

    /** 拒绝删除最后一个模型 */
    @Test
    public void remove_lastModelRejected() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        assertTrue(m.remove("deepseek-v4-flash")); // 先删到只剩一个
        assertEquals(1, m.list().size());
        assertFalse(m.remove(m.currentName()));    // 最后一个不可删
        assertEquals(1, m.list().size());
    }

    /** 删除当前模型成功，current 回退到剩余第一个 */
    @Test
    public void remove_otherModelOkAndCurrentFallsBack() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        m.setCurrent("qwen3-max");
        assertTrue(m.remove("deepseek-v4-flash"));
        assertEquals(1, m.list().size());
        assertEquals("qwen3-max", m.current().displayName);
    }

    /** 损坏文件：备份 .bak + 重建默认 */
    @Test
    public void load_corruptFileBacksUpAndRebuilds() throws IOException {
        Path dir = jarDir();
        Files.write(dir.resolve("model.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        ModelManager m = ModelManager.load(dir);
        assertEquals(2, m.list().size());
        assertTrue(Files.exists(dir.resolve("model.json.bak")));
    }

    /** 合法 JSON 但结构无效（键名错误）：备份 .bak + 重建默认（区别于文件缺失不备份） */
    @Test
    public void load_structureInvalidJsonBacksUpAndRebuilds() throws IOException {
        Path dir = jarDir();
        Files.write(dir.resolve("model.json"),
                "{\"model\":[{\"displayName\":\"x\"}]}".getBytes(StandardCharsets.UTF_8));
        ModelManager m = ModelManager.load(dir);
        assertEquals(2, m.list().size());
        assertEquals("deepseek-v4-flash", m.current().displayName);
        assertTrue(Files.exists(dir.resolve("model.json.bak")));
    }

    /** displayName 为 null 的条目被过滤，不破坏"至少一个可用模型"不变式 */
    @Test
    public void load_nullDisplayNameEntryFiltered() throws IOException {
        Path dir = jarDir();
        String json = "{\"models\":[{\"displayName\":null,\"url\":\"http://x\"}],\"currentModelName\":\"deepseek-v4-flash\"}";
        Files.write(dir.resolve("model.json"), json.getBytes(StandardCharsets.UTF_8));
        ModelManager m = ModelManager.load(dir);
        assertEquals(2, m.list().size());
        assertNotNull(m.current());
        assertEquals("deepseek-v4-flash", m.current().displayName);
    }

    /** 合法已有配置（仅 deepseek 自定义模型）：重载后原样保留，不追加千问、不备份（零迁移） */
    @Test
    public void load_validExistingFileUntouched() throws IOException {
        Path dir = jarDir();
        String json = "{\"models\":[{\"displayName\":\"custom-deepseek\",\"url\":\"http://custom\",\"apiKey\":\"sk-x\","
                + "\"modelName\":\"deepseek-custom\",\"provider\":\"deepseek\",\"thinking\":false,"
                + "\"maxContextTokens\":64000,\"compressThreshold\":0.6,\"keepRecentMessages\":5}],"
                + "\"currentModelName\":\"custom-deepseek\"}";
        Files.write(dir.resolve("model.json"), json.getBytes(StandardCharsets.UTF_8));
        ModelManager m = ModelManager.load(dir);
        assertEquals(1, m.list().size());
        ModelConfig c = m.current();
        assertEquals("custom-deepseek", c.displayName);
        assertEquals("http://custom", c.url);
        assertEquals("sk-x", c.apiKey);
        assertFalse(c.thinking);
        assertEquals(64000, c.maxContextTokens);
        assertFalse(Files.exists(dir.resolve("model.json.bak")));
    }

    /** 原子写：save 后无 .tmp 残留，文件内容可被 Gson 重新解析 */
    @Test
    public void save_atomicWriteNoTmpAndReparsable() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir); // 首次落盘走原子写
        ModelConfig q = new ModelConfig();
        q.displayName = "qwen-test"; q.url = "http://x"; q.modelName = "qwen-max";
        assertTrue(m.add(q));                    // 再次触发原子写
        assertFalse("原子写不得残留 model.json.tmp", Files.exists(dir.resolve("model.json.tmp")));
        String json = new String(Files.readAllBytes(dir.resolve("model.json")), StandardCharsets.UTF_8);
        Map<String, Object> parsed = new Gson().fromJson(json, Map.class); // 内容可被 Gson 重新解析
        List<?> models = (List<?>) parsed.get("models");
        assertNotNull(models);
        assertEquals(3, models.size());
    }
}
