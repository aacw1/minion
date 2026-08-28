package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class ConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 测试用纯净默认值资源：本机 src/resource/config.properties 含真实配置，直接 load 会漂移 */
    private static final String TEST_DEFAULTS = "/config-test.properties";

    /** 外部文件缺失时，从 classpath 加载默认值，并生成外部文件 */
    @Test
    public void load_createsExternalFileWithDefaults() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertFalse(c.confirmSkip());
        assertEquals("./skills", c.skillsDir());
        Path external = c.externalFile();
        assertTrue(Files.exists(external));
        assertTrue(new String(Files.readAllBytes(external), StandardCharsets.UTF_8).contains("skills.dir"));
    }

    /** 外部文件覆盖默认值 */
    @Test
    public void load_externalOverridesDefault() throws IOException {
        Path root = tmp.getRoot().toPath();
        Config c1 = Config.load(root, TEST_DEFAULTS);
        Path ext = c1.externalFile();
        Files.write(ext, ("skills.dir=/my/skills\nconfirm.skip=true\n"
                + "browser.port=9999\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertEquals("/my/skills", c2.skillsDir());
        assertTrue(c2.confirmSkip());
        assertEquals(9999, c2.browserPort());
    }

    /** 白名单追加：去重、写入外部文件 */
    @Test
    public void appendWhitelist_deduplicatesAndPersists() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        c.appendWhitelist("confirm.whitelist.tools", "write");
        c.appendWhitelist("confirm.whitelist.tools", "write");
        c.appendWhitelist("confirm.whitelist.tools", "edit");
        assertTrue(c.whitelistTools().containsAll(new HashSet<String>(java.util.Arrays.asList("write", "edit"))));
        Config c2 = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertTrue(c2.whitelistTools().containsAll(new HashSet<String>(java.util.Arrays.asList("write", "edit"))));
    }

    /** browser.* 默认值：空外部配置 → 走 getter 内置 fallback */
    @Test
    public void browserDefaults() throws Exception {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertEquals("", c.browserPath());
        assertEquals(9222, c.browserPort());
        assertEquals("./.minion/browser-profile", c.browserUserDataDir());
        assertFalse(c.browserHeadless());
        assertEquals(30000, c.browserTimeoutMs());
    }

    /** 需求 2：外部文件数值被写坏（手改/历史脏数据）→ browser.port 回落默认值，不抛异常致启动崩溃 */
    @Test
    public void browserPort_invalidFallsBackToDefault() throws IOException {
        Path root = tmp.getRoot().toPath();
        Config c = Config.load(root, TEST_DEFAULTS);
        Files.write(c.externalFile(), "\nbrowser.port=abc\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertEquals(9222, c2.browserPort());
    }

    /** 需求 2：browser.timeoutMs 数值非法 → 回落默认值，不抛异常 */
    @Test
    public void browserTimeoutMs_invalidFallsBackToDefault() throws IOException {
        Path root = tmp.getRoot().toPath();
        Config c = Config.load(root, TEST_DEFAULTS);
        Files.write(c.externalFile(), "\nbrowser.timeoutMs=abc\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertEquals(30000, c2.browserTimeoutMs());
    }

    /** T:paths.read.allowOutside 默认 false，外部文件可覆盖为 true */
    @Test
    public void readAllowOutside_defaultsFalseAndOverridable() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertFalse(c.readAllowOutside());

        Path root = tmp.getRoot().toPath();
        Config c1 = Config.load(root, TEST_DEFAULTS);
        Files.write(c1.externalFile(), "\npaths.read.allowOutside=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertTrue(c2.readAllowOutside());
    }

    /** 输入发送键：无键（空/旧配置）回落默认 true（Enter 发送） */
    @Test
    public void enterSends_defaultsTrueWhenKeyMissing() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertTrue(c.enterSends());
    }

    /** 工具空输出占位：无该配置（含旧配置）默认 false；外部文件可覆盖为 true */
    @Test
    public void emptyOutputPlaceholder_defaultsFalseAndOverridable() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertFalse(c.emptyOutputPlaceholder());

        Path root = tmp.getRoot().toPath();
        Config c1 = Config.load(root, TEST_DEFAULTS);
        Files.write(c1.externalFile(), "\nagent.emptyOutput.placeholder=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertTrue(c2.emptyOutputPlaceholder());
    }

    /** 输入发送键：外部文件显式 false 覆盖默认（用户选择优先，保护旧默认用户），重载后保持 */
    @Test
    public void enterSends_explicitFalseOverridesDefault() throws IOException {
        Path root = tmp.getRoot().toPath();
        Config c1 = Config.load(root, TEST_DEFAULTS);
        Files.write(c1.externalFile(), "\ninput.enterSends=false\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertFalse(c2.enterSends());
    }

    /** 输入发送键：外部文件显式 true 覆盖默认，重载后保持 */
    @Test
    public void enterSends_explicitTrueOverridesDefault() throws IOException {
        Path root = tmp.getRoot().toPath();
        Config c1 = Config.load(root, TEST_DEFAULTS);
        Files.write(c1.externalFile(), "\ninput.enterSends=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root, TEST_DEFAULTS);
        assertTrue(c2.enterSends());
    }

    /** 需求 2/13：Config.set 更新内存并写回外部文件（设置窗基础设置页保存用） */
    @Test
    public void set_updatesMemoryAndPersists() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        c.set("confirm.skip", "true");
        assertTrue(c.confirmSkip());
        // 重载验证外部文件已写回
        Config c2 = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertTrue(c2.confirmSkip());
    }
}
