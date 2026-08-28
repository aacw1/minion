# minion 编码助手 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个生产级命令行编码助手 minion（Claude Code 风格），对接 DeepSeek V4（thinking max），支持工具循环、子 agent、技能、上下文压缩、会话落盘恢复、高危操作确认与白名单、流式美化交互与 token 统计。

**Architecture:** 单模块 Maven 项目（JDK8）。核心（com.minion.core）与 UI（com.minion.cli）分离：AgentLoop/SubAgentLoop 通过 `AgentUi` 接口回调渲染，通过 `LlmClient` 接口对接模型（测试用 FakeLlmClient）。主线程 JLine REPL，每次用户输入后 AgentLoop 在独立线程运行，工具（含多个子 agent）在固定线程池并行。

**Tech Stack:** JDK 8 / Maven / Gson 2.10.1 / OkHttp 3.14.9 / JLine 3.25.1 / SnakeYAML 2.2 / JUnit 4.13.2 + MockWebServer 3.14.9（均 test scope）

## Global Constraints

- JDK 8 语法（无 var / records / text blocks / instanceof pattern）
- 依赖版本锁定：gson 2.10.1、okhttp 3.14.9、jline 3.25.1、snakeyaml 2.2；测试：junit 4.13.2、mockwebserver 3.14.9
- 资源目录为 `src/resource`（pom `<resources>` 指向它，不是 src/main/resources）
- config.properties：classpath 默认值 + jar 同目录外部文件覆盖；外部文件缺失时首启自动生成；UTF-8 逐行 `key=value` 解析（# 注释）
- 模型：`deepseek-v4-flash`，`thinking: {"type":"enabled"}` + `reasoning_effort: "max"`，`stream: true`
- assistant 消息的 `reasoning_content` 必须原样回传（否则 400）
- 主循环工具轮数上限 10000（写死）；子 agent 不限轮数、不限输出
- HTTP 超时写死：连接 30s、读取 300s
- 压缩：阈值 0.8 × maxContextTokens，保留最近 keepRecentMessages=10 条原文，按「完整回合链」切块，摘要消息 role=USER + summary=true 且不再被压缩
- Bash 工具：cwd=work.dir，输出截断 30000 字符，超时 120s（命令可覆写），危险命令首 token 前缀匹配：`rm del rd rmdir format mkfs dd shutdown taskkill pkill killall fdisk mkfs.ext4`（大写不敏感）
- 确认：`confirm.skip=true` 跳过；白名单 `confirm.whitelist.tools`（工具名）/ `confirm.whitelist.commands`（命令前缀）；交互 [回车/Y] 确认、[N] 拒绝、[W] 确认+写白名单、[A] 本会话放行
- 会话落盘 `session.dir/<yyyyMMdd-HHmmss>.json`，原子写（tmp + rename）
- 统计行格式：`⏱ 12.3s · in 8.2k · out 3.4k · thinking 2.1k · ctx 61.4k/128k (48%)`；tokens < 1000 显示原值
- 文件工具路径限制在 work.dir 内
- 所有代码包前缀 `com.minion`；core 不依赖 cli（通过接口隔离）

---

### Task 1: 项目脚手架

**Files:**
- Create: `pom.xml`
- Create: `src/resource/config.properties`
- Create: `src/main/java/com/minion/Main.java`
- Create: `.gitignore`
- Create: `docs/superpowers/plans/README.md`（指向本计划，方便追踪）

**Interfaces:**
- Produces: `com.minion.Main`（入口占位，`main(String[] args)` 打印 "minion v0.1.0"）

- [ ] **Step 1: 创建 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.minion</groupId>
  <artifactId>minion</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>2.10.1</version>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>okhttp</artifactId>
      <version>3.14.9</version>
    </dependency>
    <dependency>
      <groupId>org.jline</groupId>
      <artifactId>jline</artifactId>
      <version>3.25.1</version>
    </dependency>
    <dependency>
      <groupId>org.yaml</groupId>
      <artifactId>snakeyaml</artifactId>
      <version>2.2</version>
    </dependency>
    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.13.2</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <version>3.14.9</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <resources>
      <resource>
        <directory>src/resource</directory>
      </resource>
    </resources>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>2.22.2</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.4.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.minion.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 创建默认配置 src/resource/config.properties**

```properties
# ===== 模型 =====
model.url=https://api.deepseek.com/v1/chat/completions
model.key=sk-your-key
model.name=deepseek-v4-flash
model.thinking=true
model.reasoningEffort=max
model.maxContextTokens=131072

# ===== 上下文压缩 =====
context.compressThreshold=0.8
context.keepRecentMessages=10

# ===== 路径 =====
work.dir=.
project.md.path=./project.md
skills.dir=./skills
session.dir=./.minion/sessions

# ===== 高危操作确认 =====
confirm.skip=false
confirm.whitelist.tools=
confirm.whitelist.commands=

# ===== UI =====
ui.color=true
```

- [ ] **Step 3: 创建 Main 占位**

```java
package com.minion;

public class Main {
    public static void main(String[] args) {
        System.out.println("minion v0.1.0");
    }
}
```

- [ ] **Step 4: 创建 .gitignore 并 git init**

```
target/
.idea/
*.iml
.minion/
```

```bash
git init
```

- [ ] **Step 5: 构建验证**

```bash
mvn -q clean package
java -jar target/minion-0.1.0.jar
```

Expected: 输出 `minion v0.1.0`

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "chore: project scaffold with maven deps and default config"
```

---

### Task 2: Config 加载与覆盖

**Files:**
- Create: `src/main/java/com/minion/core/config/Config.java`
- Test: `src/test/java/com/minion/core/config/ConfigTest.java`

**Interfaces:**
- Produces: `Config` — `static Config load()`（classpath 默认 + jar 同目录外部覆盖；外部缺失自动生成）；类型化 getter：`modelUrl() modelKey() modelName() thinkingEnabled() reasoningEffort() maxContextTokens() compressThreshold() keepRecentMessages() workDir() projectMdPath() skillsDir() sessionDir() confirmSkip() whitelistTools():Set<String> whitelistCommands():Set<String> uiColor()`；`void appendWhitelist(String section, String value)`（追加到外部文件，含注释，去重）；`Path externalFile()`
- 包 `com.minion.core.config`

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.*;

public class ConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 外部文件缺失时，从 classpath 加载默认值，并生成外部文件 */
    @Test
    public void load_createsExternalFileWithDefaults() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath());
        assertEquals("https://api.deepseek.com/v1/chat/completions", c.modelUrl());
        assertEquals("deepseek-v4-flash", c.modelName());
        assertEquals("max", c.reasoningEffort());
        assertEquals(131072, c.maxContextTokens());
        assertEquals(0.8, c.compressThreshold(), 0.001);
        assertEquals(10, c.keepRecentMessages());
        assertFalse(c.confirmSkip());
        assertTrue(c.uiColor());
        Path external = c.externalFile();
        assertTrue(Files.exists(external));
        assertTrue(new String(Files.readAllBytes(external), StandardCharsets.UTF_8).contains("model.url"));
    }

    /** 外部文件覆盖默认值 */
    @Test
    public void load_externalOverridesDefault() throws IOException {
        Path root = tmp.getRoot().toPath();
        Config c1 = Config.load(root);
        Path ext = c1.externalFile();
        Files.write(ext, ("model.name=my-model\nmodel.key=sk-test-key\n"
                + "confirm.skip=true\nui.color=false\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        Config c2 = Config.load(root);
        assertEquals("my-model", c2.modelName());
        assertEquals("sk-test-key", c2.modelKey());
        assertTrue(c2.confirmSkip());
        assertFalse(c2.uiColor());
        assertEquals(131072, c2.maxContextTokens()); // 未覆盖的取默认
    }

    /** 白名单追加：去重、写入外部文件 */
    @Test
    public void appendWhitelist_deduplicatesAndPersists() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath());
        c.appendWhitelist("confirm.whitelist.tools", "write");
        c.appendWhitelist("confirm.whitelist.tools", "write");
        c.appendWhitelist("confirm.whitelist.tools", "edit");
        assertTrue(c.whitelistTools().containsAll(Set.of("write", "edit")));
        Config c2 = Config.load(tmp.getRoot().toPath());
        assertTrue(c2.whitelistTools().containsAll(Set.of("write", "edit")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=ConfigTest
```

Expected: 编译失败（Config 不存在）

- [ ] **Step 3: 实现 Config**

```java
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
        Config c = new Config(overrideDir.resolve(EXTERNAL_FILE_NAME));
        loadResource(c.props);
        if (Files.exists(c.externalFile)) {
            loadFile(c.props, c.externalFile);
        } else {
            try {
                Files.createDirectories(overrideDir);
                String defaults = new String(
                        readResource(DEFAULT_RESOURCE), StandardCharsets.UTF_8);
                Files.write(c.externalFile, defaults.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("[minion] 无法生成外部配置文件: " + e.getMessage());
            }
        }
        return c;
    }

    private static Path jarDir() {
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

    private static void loadResource(Map<String, String> m) {
        try { loadLines(m, new String(readResource(DEFAULT_RESOURCE), StandardCharsets.UTF_8).split("\\r?\\n")); }
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

    public String modelUrl()     { return get("model.url", ""); }
    public String modelKey()     { return get("model.key", ""); }
    public String modelName()    { return get("model.name", "deepseek-v4-flash"); }
    public boolean thinkingEnabled() { return Boolean.parseBoolean(get("model.thinking", "true")); }
    public String reasoningEffort()  { return get("model.reasoningEffort", "max"); }
    public int maxContextTokens() {
        return Integer.parseInt(get("model.maxContextTokens", "131072"));
    }
    public double compressThreshold() { return Double.parseDouble(get("context.compressThreshold", "0.8")); }
    public int keepRecentMessages()   { return Integer.parseInt(get("context.keepRecentMessages", "10")); }
    public String workDir()      { return get("work.dir", "."); }
    public String projectMdPath(){ return get("project.md.path", "./project.md"); }
    public String skillsDir()    { return get("skills.dir", "./skills"); }
    public String sessionDir()   { return get("session.dir", "./.minion/sessions"); }
    public boolean confirmSkip() { return Boolean.parseBoolean(get("confirm.skip", "false")); }
    public Set<String> whitelistTools()    { return csv(get("confirm.whitelist.tools", "")); }
    public Set<String> whitelistCommands() { return csv(get("confirm.whitelist.commands", "")); }
    public boolean uiColor()     { return Boolean.parseBoolean(get("ui.color", "true")); }

    private static Set<String> csv(String s) {
        Set<String> set = new HashSet<String>();
        for (String part : s.split(",")) {
            if (!part.trim().isEmpty()) set.add(part.trim().toLowerCase());
        }
        return set;
    }

    public Path externalFile() { return externalFile; }

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
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=ConfigTest
```

Expected: 3 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/config src/test/java/com/minion/core/config
git commit -m "feat: config loading with external override and whitelist persistence"
```

---

### Task 3: Message 与 ToolCall 模型

**Files:**
- Create: `src/main/java/com/minion/core/llm/Message.java`
- Create: `src/main/java/com/minion/core/llm/ToolCall.java`
- Test: `src/test/java/com/minion/core/llm/MessageTest.java`

**Interfaces:**
- Produces: `com.minion.core.llm.Message` — `enum Role {SYSTEM, USER, ASSISTANT, TOOL}`；字段 `role content reasoningContent toolCalls toolCallId name summary`（public final 或 getter，统一用字段）；静态工厂 `system(String) user(String) assistant(String) toolResult(String toolCallId, String name, String content)`；`JsonObject toApiJson()`（assistant 带 reasoningContent 时输出 `reasoning_content`；assistant 的 toolCalls 输出 `tool_calls`；TOOL 消息输出 `tool_call_id`+`name`；content 为 null 时不输出该键）
- Produces: `com.minion.core.llm.ToolCall` — 字段 `id type name arguments`；`static ToolCall fromApi(JsonObject)`；`JsonObject toApiJson()`；Gson 可序列化/反序列化（`toJson`/`fromJson` 往返）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class MessageTest {

    private final Gson gson = new Gson();

    @Test
    public void assistantWithReasoning_roundTripsReasoningContent() {
        Message m = Message.assistant("hello");
        m.reasoningContent = "think think";
        JsonObject api = m.toApiJson();
        assertEquals("assistant", api.get("role").getAsString());
        assertEquals("think think", api.get("reasoning_content").getAsString());

        // 落盘往返（含 reasoningContent）
        String json = gson.toJson(m);
        Message back = gson.fromJson(json, Message.class);
        assertEquals("think think", back.reasoningContent);
        assertEquals("hello", back.content);
    }

    @Test
    public void assistantWithToolCalls_emitsToolCalls_noContent() {
        ToolCall tc = new ToolCall();
        tc.id = "call_1";
        tc.type = "function";
        tc.name = "Read";
        tc.arguments = "{\"path\":\"a.txt\"}";
        Message m = Message.assistant(null);
        m.toolCalls = Collections.singletonList(tc);
        JsonObject api = m.toApiJson();
        assertFalse(api.has("content"));
        assertEquals("call_1", api.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .get("id").getAsString());
    }

    @Test
    public void toolResult_emitsToolCallId() {
        Message m = Message.toolResult("call_1", "Read", "file content");
        JsonObject api = m.toApiJson();
        assertEquals("tool", api.get("role").getAsString());
        assertEquals("call_1", api.get("tool_call_id").getAsString());
        assertEquals("Read", api.get("name").getAsString());
    }

    @Test
    public void toolCall_roundTrip() {
        ToolCall tc = new ToolCall();
        tc.id = "c1"; tc.type = "function"; tc.name = "Bash";
        tc.arguments = "{\"command\":\"ls\"}";
        String json = gson.toJson(tc);
        ToolCall back = gson.fromJson(json, ToolCall.class);
        assertEquals("c1", back.id);
        assertEquals("Bash", back.name);
        assertEquals("{\"command\":\"ls\"}", back.arguments);

        JsonObject api = tc.toApiJson();
        assertEquals("Bash", api.get("function").getAsJsonObject().get("name").getAsString());
        ToolCall fromApi = ToolCall.fromApi(api);
        assertEquals("Bash", fromApi.name);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=MessageTest
```

Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现 ToolCall 与 Message**

```java
package com.minion.core.llm;

import com.google.gson.JsonObject;

/** 工具调用（模型发出或落盘持久化） */
public class ToolCall {
    public String id;
    public String type = "function";
    public String name;
    public String arguments; // 参数 JSON 字符串

    public JsonObject toApiJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", arguments == null ? "{}" : arguments);
        o.add("function", fn);
        return o;
    }

    public static ToolCall fromApi(JsonObject o) {
        ToolCall tc = new ToolCall();
        tc.id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsString() : "";
        if (o.has("type") && !o.get("type").isJsonNull()) tc.type = o.get("type").getAsString();
        JsonObject fn = o.has("function") ? o.getAsJsonObject("function") : null;
        if (fn != null) {
            if (fn.has("name") && !fn.get("name").isJsonNull()) tc.name = fn.get("name").getAsString();
            if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) tc.arguments = fn.get("arguments").getAsString();
        }
        return tc;
    }
}
```

```java
package com.minion.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/** 会话消息。reasoningContent 必须持久化并在回传时带上（DeepSeek 400 要求）。 */
public class Message {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Role role;
    public String content;
    public String reasoningContent;   // 仅 assistant
    public List<ToolCall> toolCalls;  // 仅 assistant
    public String toolCallId;         // 仅 tool
    public String name;               // 仅 tool（工具名）
    public boolean summary;           // true = 压缩摘要消息，不再参与压缩

    public static Message system(String content) {
        Message m = new Message();
        m.role = Role.SYSTEM;
        m.content = content;
        return m;
    }

    public static Message user(String content) {
        Message m = new Message();
        m.role = Role.USER;
        m.content = content;
        return m;
    }

    public static Message assistant(String content) {
        Message m = new Message();
        m.role = Role.ASSISTANT;
        m.content = content;
        return m;
    }

    public static Message toolResult(String toolCallId, String name, String content) {
        Message m = new Message();
        m.role = Role.TOOL;
        m.toolCallId = toolCallId;
        m.name = name;
        m.content = content;
        return m;
    }

    /** 请求体消息 JSON。content 为 null 时不输出（assistant 工具调用消息无 content） */
    public JsonObject toApiJson() {
        JsonObject o = new JsonObject();
        o.addProperty("role", role.name().toLowerCase());
        if (content != null) o.addProperty("content", content);
        if (role == Role.ASSISTANT) {
            if (reasoningContent != null) o.addProperty("reasoning_content", reasoningContent);
            if (toolCalls != null && !toolCalls.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (ToolCall tc : toolCalls) arr.add(tc.toApiJson());
                o.add("tool_calls", arr);
            }
        }
        if (role == Role.TOOL) {
            o.addProperty("tool_call_id", toolCallId);
            o.addProperty("name", name);
        }
        return o;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=MessageTest
```

Expected: 4 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/llm src/test/java/com/minion/core/llm
git commit -m "feat: message and toolcall model with reasoning content round-trip"
```

---

### Task 4: TokenCounter 估算

**Files:**
- Create: `src/main/java/com/minion/core/context/TokenCounter.java`
- Test: `src/test/java/com/minion/core/context/TokenCounterTest.java`

**Interfaces:**
- Produces: `com.minion.core.context.TokenCounter` — `static int estimate(String)`（中文字符 0.7 token/字，其他 0.25 token/字符，向上取整）；`static int estimateMessages(List<Message>)`（每条消息 +4 常量开销，content/reasoningContent/工具参数都计）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.context;

import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TokenCounterTest {

    @Test
    public void estimate_zhAndEn() {
        // 4 个中文字 ≈ 2.8 → ceil 3
        assertEquals(3, TokenCounter.estimate("你好世界"));
        // 8 个 ASCII ≈ 2
        assertEquals(2, TokenCounter.estimate("abcdefgh"));
        assertTrue(TokenCounter.estimate("中文 mixed 123") > 0);
    }

    @Test
    public void estimateMessages_countsAllFields() {
        Message u = Message.user("读取文件并分析");
        Message a = Message.assistant("好的");
        a.reasoningContent = "先看结构";
        ToolCall tc = new ToolCall();
        tc.name = "Read";
        tc.arguments = "{\"path\":\"src/Main.java\"}";
        a.toolCalls = Collections.singletonList(tc);
        Message t = Message.toolResult("c1", "Read", "public class Main {}");
        int n = TokenCounter.estimateMessages(Arrays.asList(u, a, t));
        // 每条消息至少 4 开销
        assertTrue(n >= 12);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=TokenCounterTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 TokenCounter**

```java
package com.minion.core.context;

import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;

/** 启发式 token 估算：中文 1 字 ≈ 0.7 token，其他 1 字符 ≈ 0.25 token */
public class TokenCounter {

    private static final int MSG_OVERHEAD = 4;

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            tokens += isCjk(c) ? 0.7 : 0.25;
        }
        return (int) Math.ceil(tokens);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3000 && c <= 0x303F);
    }

    public static int estimateMessages(java.util.List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += MSG_OVERHEAD;
            total += estimate(m.content);
            if (m.reasoningContent != null) total += estimate(m.reasoningContent);
            if (m.toolCalls != null) {
                for (ToolCall tc : m.toolCalls) {
                    total += estimate(tc.name) + estimate(tc.arguments);
                }
            }
        }
        return total;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=TokenCounterTest
```

Expected: 2 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/context src/test/java/com/minion/core/context
git commit -m "feat: heuristic token counter"
```

---

### Task 5: Tool 接口、ToolRegistry、SchemaGenerator

**Files:**
- Create: `src/main/java/com/minion/core/tools/Tool.java`
- Create: `src/main/java/com/minion/core/tools/ToolResult.java`
- Create: `src/main/java/com/minion/core/tools/ToolRegistry.java`
- Create: `src/main/java/com/minion/core/tools/SchemaGenerator.java`
- Create: `src/main/java/com/minion/core/tools/example/ExampleTool.java`（示例工具，供测试与后续 Task 参照）
- Test: `src/test/java/com/minion/core/tools/ToolRegistryTest.java`
- Test: `src/test/java/com/minion/core/tools/SchemaGeneratorTest.java`

**Interfaces:**
- Produces: `Tool` — `String name(); String description(); JsonObject schema(); ToolResult execute(JsonObject args) throws Exception; boolean isHighRisk(JsonObject args)`（默认 false）
- Produces: `ToolResult` — `ToolResult success(String output)`、`ToolResult error(String message)`、`boolean ok`、`String output`；`String preview()`（首行 + 总行数，如 `"public class Main {}\n(12 lines)"`，单行则原样返回）
- Produces: `ToolRegistry` — `void register(Tool)`、`Tool get(String name)`（无则 null）、`List<Tool> all()`、`List<JsonObject> schemas()`
- Produces: `SchemaGenerator` — `static JsonObject objectSchema(String description, String[] properties, String[] required)`（properties 全为 string 类型）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ToolRegistryTest {

    @Test
    public void registerAndGet() {
        ToolRegistry reg = new ToolRegistry();
        Tool tool = new ExampleTool();
        reg.register(tool);
        assertEquals(tool, reg.get("example"));
        assertNull(reg.get("nope"));
        assertEquals(1, reg.all().size());
        assertEquals(1, reg.schemas().size());
        JsonObject schema = reg.schemas().get(0);
        assertEquals("example", schema.get("name").getAsString());
        assertEquals("object", schema.getAsJsonObject("parameters").get("type").getAsString());
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SchemaGeneratorTest {

    @Test
    public void objectSchema_shape() {
        JsonObject s = SchemaGenerator.objectSchema("示例工具", new String[]{"a", "b"}, new String[]{"a"});
        assertEquals("object", s.get("type").getAsString());
        assertEquals("示例工具", s.get("description").getAsString());
        JsonObject props = s.getAsJsonObject("properties");
        assertTrue(props.has("a"));
        assertTrue(props.has("b"));
        assertEquals("string", props.get("a").getAsJsonObject().get("type").getAsString());
        assertEquals(1, s.getAsJsonArray("required").size());
        assertEquals("a", s.getAsJsonArray("required").get(0).getAsString());
    }
}
```

```java
package com.minion.core.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ToolResultTest {

    @Test
    public void preview_multiLineShowsFirstLineAndCount() {
        ToolResult r = ToolResult.success("line1\nline2\nline3");
        assertEquals("line1\n(3 lines)", r.preview());
        ToolResult single = ToolResult.success("only one");
        assertEquals("only one", single.preview());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=ToolRegistryTest,SchemaGeneratorTest,ToolResultTest
```

Expected: 编译失败

- [ ] **Step 3: 实现接口与注册表**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

public interface Tool {
    String name();
    String description();
    JsonObject schema();
    ToolResult execute(JsonObject args) throws Exception;
    default boolean isHighRisk(JsonObject args) { return false; }
}
```

```java
package com.minion.core.tools;

public class ToolResult {
    public final boolean ok;
    public final String output;

    private ToolResult(boolean ok, String output) {
        this.ok = ok;
        this.output = output;
    }

    public static ToolResult success(String output) { return new ToolResult(true, output); }
    public static ToolResult error(String message) { return new ToolResult(false, message); }

    /** 首行 + 总行数摘要；单行原样返回 */
    public String preview() {
        String[] lines = output.split("\\r?\\n");
        if (lines.length <= 1) return output;
        return lines[0] + "\n(" + lines.length + " lines)";
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();

    public void register(Tool tool) { tools.put(tool.name().toLowerCase(), tool); }

    public Tool get(String name) { return tools.get(name == null ? null : name.toLowerCase()); }

    public List<Tool> all() { return new ArrayList<Tool>(tools.values()); }

    public List<JsonObject> schemas() {
        List<JsonObject> list = new ArrayList<JsonObject>();
        for (Tool t : tools.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "function");
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("parameters", t.schema());
            list.add(o);
        }
        return list;
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class SchemaGenerator {

    /** 全 string 参数的对象 schema */
    public static JsonObject objectSchema(String description, String[] properties, String[] required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", description);
        JsonObject props = new JsonObject();
        for (String p : properties) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            props.add(p, prop);
        }
        schema.add("properties", props);
        if (required.length > 0) {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            schema.add("required", req);
        }
        return schema;
    }
}
```

```java
package com.minion.core.tools.example;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** 示例工具：回显参数。仅用于测试与工具编写模板。 */
public class ExampleTool implements Tool {
    @Override
    public String name() { return "example"; }

    @Override
    public String description() { return "回显 text 参数（示例工具）"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("回显文本", new String[]{"text"}, new String[]{"text"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String text = args.has("text") ? args.get("text").getAsString() : "";
        return ToolResult.success("echo: " + text);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=ToolRegistryTest,SchemaGeneratorTest,ToolResultTest
```

Expected: 3 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools src/test/java/com/minion/core/tools
git commit -m "feat: tool interface, registry and schema generator"
```

---

### Task 6: DeepSeekClient 流式客户端 + Usage 统计

**Files:**
- Create: `src/main/java/com/minion/core/llm/LlmClient.java`
- Create: `src/main/java/com/minion/core/llm/DeepSeekClient.java`
- Create: `src/main/java/com/minion/core/llm/LlmException.java`
- Create: `src/main/java/com/minion/core/llm/StreamHandler.java`
- Create: `src/main/java/com/minion/core/llm/Usage.java`
- Create: `src/main/java/com/minion/core/llm/UsageTracker.java`
- Test: `src/test/java/com/minion/core/llm/DeepSeekClientTest.java`
- Test: `src/test/java/com/minion/core/llm/FakeLlmClient.java`（后续任务复用的测试桩）
- Test: `src/test/java/com/minion/core/llm/UsageTrackerTest.java`

**Interfaces:**
- Produces: `LlmClient` — `void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) throws LlmException; String completeChat(List<Message> messages, String systemPrompt)`（非流式，返回 content，用于压缩）
- Produces: `StreamHandler` — `default void onThinking(String delta){}`、`default void onContent(String delta){}`、`default void onUsage(Usage usage){}`、`void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls)`、`default void onError(LlmException e){}`
- Produces: `LlmException` — `enum Type {AUTH, RATE_LIMIT, BAD_REQUEST, NETWORK, TIMEOUT, OTHER}`；字段 `type message retryable`；`static LlmException of(int httpCode, String body)`
- Produces: `Usage` — 字段 `inputTokens outputTokens reasoningTokens totalTokens`；`static Usage fromJson(JsonObject usage)`；`static Usage estimate(List<Message> messages, String output)`（估算兜底）
- Produces: `UsageTracker` — `void record(Usage)`、`int sessionInput() sessionOutput() sessionThinking() sessionTotal()`、`Usage last()`
- Produces: `FakeLlmClient`（test）— 脚本化：`void addTurn(String content)` / `void addTurnWithTools(List<ToolCall>, String content)` 依次出牌；`List<Message> lastRequestMessages()`；构造参数 `thinkingEnabled` 记录请求中的 reasoning 回传情况；`compressResult` 字段供 completeChat 返回

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DeepSeekClientTest {

    private MockWebServer server;

    @Before
    public void setup() { server = new MockWebServer(); }

    @After
    public void teardown() throws Exception { server.shutdown(); }

    private DeepSeekClient newClient() {
        return new DeepSeekClient(server.url("/").toString(),
                "sk-test", "deepseek-v4-flash", true, "max");
    }

    @Test
    public void streamChat_parsesDeltasAndFinish() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考片段\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"completion_tokens_details\":{\"reasoning_tokens\":20}}}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final StringBuilder thinking = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final List<Object> out = new ArrayList<Object>();

        newClient().streamChat(Collections.<Message>singletonList(Message.user("hi")),
                null, new StreamHandler() {
                    @Override
                    public void onThinking(String delta) { thinking.append(delta); }
                    @Override
                    public void onContent(String delta) { content.append(delta); }
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) {
                        out.add(finishReason);
                        out.add(usage);
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("思考片段", thinking.toString());
        assertEquals("你好，世界", content.toString());
        assertEquals("stop", out.get(0));
        Usage usage = (Usage) out.get(1);
        assertEquals(100, usage.inputTokens);
        assertEquals(50, usage.outputTokens);
        assertEquals(20, usage.reasoningTokens);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("deepseek-v4-flash", json.get("model").getAsString());
        assertTrue(json.get("stream").getAsBoolean());
        assertEquals("max", json.get("reasoning_effort").getAsString());
        assertEquals("enabled", json.getAsJsonObject("thinking").get("type").getAsString());
    }

    @Test
    public void streamChat_parsesToolCallDeltas() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"Read\",\"arguments\":\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"a\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\".txt\\\"}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sse, 1));

        final CountDownLatch done = new CountDownLatch(1);
        final List<Object> out = new ArrayList<Object>();
        newClient().streamChat(Collections.<Message>singletonList(Message.user("读文件")),
                null, new StreamHandler() {
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) {
                        out.add(finishReason);
                        out.add(toolCalls);
                        done.countDown();
                    }
                });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("tool_calls", out.get(0));
        List<ToolCall> tcs = (List<ToolCall>) out.get(1);
        assertEquals(1, tcs.size());
        assertEquals("call_1", tcs.get(0).id);
        assertEquals("Read", tcs.get(0).name);
        assertEquals("{\"path\":\"a.txt\"}", tcs.get(0).arguments);
    }

    @Test
    public void request_roundTripsReasoningContent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n", 1));
        Message assistant = Message.assistant("已分析");
        assistant.reasoningContent = "历史思考";
        List<Message> messages = new ArrayList<Message>();
        messages.add(Message.user("继续"));
        messages.add(assistant);
        newClient().streamChat(messages, null, new StreamHandler() {
            @Override
            public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
        });
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("历史思考", json.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("reasoning_content").getAsString());
    }

    @Test
    public void httpError_mapsToLlmException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
        DeepSeekClient client = newClient();
        try {
            client.streamChat(Collections.<Message>singletonList(Message.user("x")), null,
                    new StreamHandler() {
                        @Override
                        public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
                    });
            fail("should throw");
        } catch (LlmException e) {
            assertEquals(LlmException.Type.RATE_LIMIT, e.type);
            assertTrue(e.retryable);
        }
    }

    @Test
    public void completeChat_returnsContent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"摘要结果\"}}]}\n\ndata: [DONE]\n\n", 1));
        String r = newClient().completeChat(Collections.<Message>singletonList(Message.user("压缩")), "你是一个压缩器");
        assertEquals("摘要结果", r);
    }
}
```

```java
package com.minion.core.llm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UsageTrackerTest {

    @Test
    public void accumulates() {
        UsageTracker t = new UsageTracker();
        Usage u1 = new Usage();
        u1.inputTokens = 100; u1.outputTokens = 50; u1.reasoningTokens = 20;
        Usage u2 = new Usage();
        u2.inputTokens = 200; u2.outputTokens = 30; u2.reasoningTokens = 0;
        t.record(u1);
        t.record(u2);
        assertEquals(300, t.sessionInput());
        assertEquals(80, t.sessionOutput());
        assertEquals(20, t.sessionThinking());
        assertEquals(380, t.sessionTotal());
        assertEquals(u2, t.last());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=DeepSeekClientTest,UsageTrackerTest
```

Expected: 编译失败

- [ ] **Step 3: 实现接口与客户端**

```java
package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.List;

public interface LlmClient {
    /** 流式对话；handler 回调在调用线程。tools 为空列表/null 表示不带工具。 */
    void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException;

    /** 非流式对话（压缩等内部请求），返回 content */
    String completeChat(List<Message> messages, String systemPrompt) throws LlmException;

    /** 中断进行中的请求（Ctrl+C / 用户打断） */
    default void cancel() { }
}
```

```java
package com.minion.core.llm;

import java.util.List;

public interface StreamHandler {
    default void onThinking(String delta) { }
    default void onContent(String delta) { }
    default void onUsage(Usage usage) { }
    void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls);
    default void onError(LlmException e) { }
}
```

```java
package com.minion.core.llm;

public class LlmException extends Exception {

    public enum Type { AUTH, RATE_LIMIT, BAD_REQUEST, NETWORK, TIMEOUT, OTHER }

    public final Type type;
    public final boolean retryable;

    public LlmException(Type type, String message, boolean retryable) {
        super(message);
        this.type = type;
        this.retryable = retryable;
    }

    public static LlmException of(int httpCode, String body) {
        if (httpCode == 401 || httpCode == 403) {
            return new LlmException(Type.AUTH, "认证失败(" + httpCode + ")，请检查 config.properties 的 model.key", false);
        }
        if (httpCode == 429) {
            return new LlmException(Type.RATE_LIMIT, "请求过于频繁(" + httpCode + ")，限流中，请稍后重试", true);
        }
        if (httpCode == 400) {
            return new LlmException(Type.BAD_REQUEST, "请求被拒绝(400)，可能是消息格式或思考内容回传问题", false);
        }
        return new LlmException(Type.OTHER, "API 错误(" + httpCode + "): " + truncate(body), httpCode >= 500);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
```

```java
package com.minion.core.llm;

public class Usage {
    public int inputTokens;
    public int outputTokens;
    public int reasoningTokens;
    public int totalTokens;

    public static Usage fromJson(com.google.gson.JsonObject usage) {
        Usage u = new Usage();
        if (usage == null) return u;
        if (usage.has("prompt_tokens") && !usage.get("prompt_tokens").isJsonNull())
            u.inputTokens = usage.get("prompt_tokens").getAsInt();
        if (usage.has("completion_tokens") && !usage.get("completion_tokens").isJsonNull())
            u.outputTokens = usage.get("completion_tokens").getAsInt();
        if (usage.has("completion_tokens_details")
                && usage.get("completion_tokens_details").isJsonObject()
                && usage.getAsJsonObject("completion_tokens_details").has("reasoning_tokens")) {
            u.reasoningTokens = usage.getAsJsonObject("completion_tokens_details")
                    .get("reasoning_tokens").getAsInt();
        }
        u.totalTokens = u.inputTokens + u.outputTokens;
        return u;
    }

    /** 取不到 API usage 时的估算兜底 */
    public static Usage estimate(java.util.List<Message> messages, String output) {
        Usage u = new Usage();
        u.inputTokens = com.minion.core.context.TokenCounter.estimateMessages(messages);
        u.outputTokens = com.minion.core.context.TokenCounter.estimate(output);
        u.totalTokens = u.inputTokens + u.outputTokens;
        return u;
    }
}
```

```java
package com.minion.core.llm;

public class UsageTracker {
    private int sessionInput;
    private int sessionOutput;
    private int sessionThinking;
    private Usage last;

    public synchronized void record(Usage usage) {
        if (usage == null) return;
        last = usage;
        sessionInput += usage.inputTokens;
        sessionOutput += usage.outputTokens;
        sessionThinking += usage.reasoningTokens;
    }

    public synchronized int sessionInput() { return sessionInput; }
    public synchronized int sessionOutput() { return sessionOutput; }
    public synchronized int sessionThinking() { return sessionThinking; }
    public synchronized int sessionTotal() { return sessionInput + sessionOutput; }
    public synchronized Usage last() { return last; }
}
```

```java
package com.minion.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** DeepSeek Chat Completions 流式客户端（SSE）。 */
public class DeepSeekClient implements LlmClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT = 30;
    private static final int READ_TIMEOUT = 300;

    private final String url;
    private final String apiKey;
    private final String model;
    private final boolean thinking;
    private final String reasoningEffort;
    private final OkHttpClient http;

    public DeepSeekClient(String url, String apiKey, String model,
                          boolean thinking, String reasoningEffort) {
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.thinking = thinking;
        this.reasoningEffort = reasoningEffort;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }

    private Request buildRequest(List<Message> messages, List<JsonObject> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        if (thinking) {
            JsonObject th = new JsonObject();
            th.addProperty("type", "enabled");
            body.add("thinking", th);
            body.addProperty("reasoning_effort", reasoningEffort);
        }
        JsonArray msgs = new JsonArray();
        for (Message m : messages) msgs.add(m.toApiJson());
        body.add("messages", msgs);
        if (tools != null && !tools.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (JsonObject t : tools) arr.add(t);
            body.add("tools", arr);
        }
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSON, body.toString()));
        return rb.build();
    }

    private volatile okhttp3.Call currentCall;

    /** 中断进行中的请求（Ctrl+C / 用户打断） */
    public void cancel() {
        okhttp3.Call c = currentCall;
        if (c != null) c.cancel();
    }

    @Override
    public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler)
            throws LlmException {
        List<ToolCall> acc = new ArrayList<ToolCall>();
        StringBuilder content = new StringBuilder();
        StringBuilder thinkingSb = new StringBuilder();
        Usage usage = null;
        String finish = "stop";
        currentCall = http.newCall(buildRequest(messages, tools));
        try (Response response = currentCall.execute()) {
            if (!response.isSuccessful()) throw LlmException.of(response.code(), responseBody(response));
            if (response.body() == null) throw new LlmException(LlmException.Type.OTHER, "空响应", false);
            BufferedSource source = response.body().source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                JsonObject choice = chunk.getAsJsonArray("choices").get(0).getAsJsonObject();
                JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                        ? choice.getAsJsonObject("delta") : null;
                if (delta != null) {
                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                        String d = delta.get("reasoning_content").getAsString();
                        thinkingSb.append(d);
                        handler.onThinking(d);
                    }
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String d = delta.get("content").getAsString();
                        content.append(d);
                        handler.onContent(d);
                    }
                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        accumulateToolCalls(delta.getAsJsonArray("tool_calls"), acc);
                    }
                }
                if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                    finish = choice.get("finish_reason").getAsString();
                }
                if (chunk.has("usage") && chunk.get("usage").isJsonObject()
                        && !chunk.get("usage").isJsonNull()) {
                    usage = Usage.fromJson(chunk.getAsJsonObject("usage"));
                }
            }
        } catch (IOException e) {
            if (isTimeout(e)) {
                throw new LlmException(LlmException.Type.TIMEOUT, "请求超时: " + e.getMessage(), true);
            }
            throw new LlmException(LlmException.Type.NETWORK, "网络错误: " + e.getMessage(), true);
        } finally {
            currentCall = null;
        }
        if (usage == null) {
            usage = Usage.estimate(messages, content.toString());
        }
        if (finish.equals("tool_calls") && acc.isEmpty()) {
            throw new LlmException(LlmException.Type.BAD_REQUEST,
                    "模型声明工具调用但未返回工具参数", false);
        }
        handler.onUsage(usage);
        handler.onFinish(finish, usage, acc);
    }

    private void accumulateToolCalls(JsonArray deltas, List<ToolCall> acc) {
        for (int i = 0; i < deltas.size(); i++) {
            JsonObject d = deltas.get(i).getAsJsonObject();
            int index = d.has("index") ? d.get("index").getAsInt() : 0;
            while (acc.size() <= index) {
                ToolCall tc = new ToolCall();
                tc.id = "";
                tc.arguments = "";
                acc.add(tc);
            }
            ToolCall tc = acc.get(index);
            if (d.has("id") && !d.get("id").isJsonNull()) tc.id = d.get("id").getAsString();
            if (d.has("type") && !d.get("type").isJsonNull()) tc.type = d.get("type").getAsString();
            if (d.has("function") && d.get("function").isJsonObject()) {
                JsonObject fn = d.getAsJsonObject("function");
                if (fn.has("name") && !fn.get("name").isJsonNull()) tc.name = fn.get("name").getAsString();
                if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                    tc.arguments = tc.arguments == null ? "" : tc.arguments
                            + fn.get("arguments").getAsString();
                }
            }
        }
    }

    @Override
    public String completeChat(List<Message> messages, String systemPrompt) throws LlmException {
        final StringBuilder out = new StringBuilder();
        final LlmException[] err = new LlmException[1];
        List<Message> all = new ArrayList<Message>();
        all.add(Message.system(systemPrompt));
        all.addAll(messages);
        streamChat(all, null, new StreamHandler() {
            @Override
            public void onContent(String delta) { out.append(delta); }
            @Override
            public void onFinish(String finishReason, Usage usage, List<ToolCall> toolCalls) { }
            @Override
            public void onError(LlmException e) { err[0] = e; }
        });
        if (err[0] != null) throw err[0];
        return out.toString();
    }

    private boolean isTimeout(IOException e) {
        return e instanceof java.net.SocketTimeoutException
                || (e.getCause() != null && e.getCause() instanceof java.net.SocketTimeoutException);
    }

    private String responseBody(Response r) {
        try { return r.body() != null ? r.body().string() : ""; }
        catch (IOException e) { return ""; }
    }
}
```

FakeLlmClient（test fixture，后续 AgentLoop/SubAgent/压缩任务复用）：

```java
package com.minion.core.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 脚本化测试桩：addTurn/addTurnWithTools 依次出牌；completeChat 返回 compressResult */
public class FakeLlmClient implements LlmClient {

    public String compressResult = "【摘要】历史对话要点";
    private final List<ScriptedTurn> turns = new ArrayList<ScriptedTurn>();
    private int cursor = 0;
    public List<Message> lastRequestMessages = new ArrayList<Message>();

    public static class ScriptedTurn {
        public final List<ToolCall> toolCalls;
        public final String content;
        public ScriptedTurn(List<ToolCall> toolCalls, String content) {
            this.toolCalls = toolCalls;
            this.content = content;
        }
    }

    public void addTurn(String content) { turns.add(new ScriptedTurn(null, content)); }

    public void addTurnWithTools(List<ToolCall> toolCalls, String content) {
        turns.add(new ScriptedTurn(toolCalls, content));
    }

    @Override
    public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) {
        lastRequestMessages = new ArrayList<Message>(messages);
        ScriptedTurn turn = turns.get(Math.min(cursor, turns.size() - 1));
        cursor++;
        Usage u = new Usage();
        u.inputTokens = 10;
        u.outputTokens = 5;
        if (turn.toolCalls != null && !turn.toolCalls.isEmpty()) {
            handler.onFinish("tool_calls", u, turn.toolCalls);
        } else {
            handler.onContent(turn.content);
            handler.onFinish("stop", u, new ArrayList<ToolCall>());
        }
    }

    @Override
    public String completeChat(List<Message> messages, String systemPrompt) {
        lastRequestMessages = new ArrayList<Message>(messages);
        return compressResult;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=DeepSeekClientTest,UsageTrackerTest
```

Expected: 6 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/llm src/test/java/com/minion/core/llm
git commit -m "feat: deepseek streaming client with SSE parse and usage tracking"
```

---

### Task 7: SystemPromptBuilder（内置提示 + project.md + 技能）

**Files:**
- Create: `src/main/java/com/minion/core/agent/SystemPromptBuilder.java`
- Test: `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java`

**Interfaces:**
- Produces: `com.minion.core.agent.SystemPromptBuilder` — 构造 `SystemPromptBuilder(Config)`；`String build(List<Skill> allSkills, List<Skill> loadedSkills)`；内部 `static String loadProjectMd(String path)`（存在读内容，不存在返回空串）；顺序：内置提示 → `=== 项目介绍 ===`（project.md 内容）→ `=== 可用技能 ===`（技能名+描述列表，提示模型任务匹配时建议用户 /skill 加载）→ `=== 已加载技能 ===`（loadedSkills 指令全文）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.agent;

import com.minion.core.skills.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class SystemPromptBuilderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void build_includesProjectMdAndSkillsInOrder() throws Exception {
        Path work = tmp.getRoot().toPath();
        File md = new File(work.toFile(), "project.md");
        Files.write(md.toPath(), "这是一个测试项目".getBytes(StandardCharsets.UTF_8));
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), "model.name=x\nwork.dir=.\nproject.md.path="
                + md.getAbsolutePath() + "\n".getBytes(StandardCharsets.UTF_8));

        com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
        SystemPromptBuilder b = new SystemPromptBuilder(config);

        Skill available = new Skill("review", "代码审查技能", "审查指令全文", "SKILL.md");
        Skill loaded = new Skill("debug", "调试技能", "调试指令全文", "SKILL.md");
        String prompt = b.build(java.util.Collections.singletonList(available),
                java.util.Collections.singletonList(loaded));

        int iProject = prompt.indexOf("=== 项目介绍 ===");
        int iSkills = prompt.indexOf("=== 可用技能 ===");
        int iLoaded = prompt.indexOf("=== 已加载技能 ===");
        assertTrue(iProject > 0);
        assertTrue(iSkills > iProject);
        assertTrue(iLoaded > iSkills);
        assertTrue(prompt.contains("这是一个测试项目"));
        assertTrue(prompt.contains("review — 代码审查技能"));
        assertTrue(prompt.contains("调试指令全文"));
    }

    @Test
    public void build_missingProjectMd_skipsSection() throws Exception {
        Path work = tmp.getRoot().toPath();
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), "model.name=x\nproject.md.path=./nope.md\n".getBytes(StandardCharsets.UTF_8));
        com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
        String prompt = new SystemPromptBuilder(config).build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList(),
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertFalse(prompt.contains("=== 项目介绍 ==="));
        assertFalse(prompt.contains("=== 可用技能 ==="));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=SystemPromptBuilderTest
```

Expected: 编译失败（Skill 类尚未创建——本任务先创建 `com.minion.core.skills.Skill` 最小类，完整技能系统在 Task 19）

- [ ] **Step 3: 创建 Skill 最小类与 SystemPromptBuilder**

```java
package com.minion.core.skills;

/** 技能（frontmatter + 指令）。完整扫描/解析在 Task 19。 */
public class Skill {
    public final String name;
    public final String description;
    public final String instructions; // SKILL.md 正文
    public final String file;         // 展示用文件名

    public Skill(String name, String description, String instructions, String file) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.file = file;
    }

    public String hint() { return name + " — " + (description == null || description.isEmpty() ? "无描述" : description); }
}
```

```java
package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.skills.Skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** 系统提示词组装：内置提示 → 项目介绍(project.md) → 可用技能提示 → 已加载技能指令 */
public class SystemPromptBuilder {

    private static final String BUILTIN =
            "你是 minion，一个运行在命令行里的代码开发助手。你可以调用工具读写文件、执行命令、搜索代码。\n"
          + "规则：\n"
          + "1. 使用工具前先想清楚目标，避免无谓调用；Bash 命令在项目工作目录下执行。\n"
          + "2. 修改文件前先 Read 确认当前内容；Edit 必须精确匹配原文。\n"
          + "3. 复杂任务可用 task 工具派发子 agent 并行处理，子 agent 会返回结果摘要。\n"
          + "4. 回答使用简洁中文，代码块使用 ``` 标记。\n"
          + "5. 涉及删除/覆盖等破坏性操作时，等待用户确认（系统会拦截）。";

    private final Config config;

    public SystemPromptBuilder(Config config) { this.config = config; }

    public String build(List<Skill> allSkills, List<Skill> loadedSkills) {
        StringBuilder sb = new StringBuilder(BUILTIN);
        String projectMd = loadProjectMd(config.projectMdPath());
        if (!projectMd.isEmpty()) {
            sb.append("\n\n=== 项目介绍 ===\n").append(projectMd.trim());
        }
        if (allSkills != null && !allSkills.isEmpty()) {
            sb.append("\n\n=== 可用技能 ===\n");
            sb.append("以下是可用的技能，当任务与之匹配时，建议用户输入 /skill <技能名> 加载：\n");
            for (Skill s : allSkills) sb.append("- ").append(s.hint()).append('\n');
        }
        if (loadedSkills != null && !loadedSkills.isEmpty()) {
            sb.append("\n\n=== 已加载技能 ===\n");
            for (Skill s : loadedSkills) {
                sb.append("\n## 技能 ").append(s.name).append("\n\n").append(s.instructions).append('\n');
            }
        }
        return sb.toString();
    }

    static String loadProjectMd(String path) {
        try {
            Path p = Paths.get(path);
            if (Files.exists(p) && Files.isRegularFile(p)) {
                byte[] bytes = Files.readAllBytes(p);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[minion] 读取 project.md 失败: " + e.getMessage());
        }
        return "";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=SystemPromptBuilderTest
```

Expected: 2 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/agent src/main/java/com/minion/core/skills src/test/java/com/minion/core/agent
git commit -m "feat: system prompt builder with project.md and skill sections"
```

---

### Task 8: 文件工具 Read / Glob / Grep

**Files:**
- Create: `src/main/java/com/minion/core/tools/ReadTool.java`
- Create: `src/main/java/com/minion/core/tools/GlobTool.java`
- Create: `src/main/java/com/minion/core/tools/GrepTool.java`
- Create: `src/main/java/com/minion/core/tools/PathsGuard.java`
- Test: `src/test/java/com/minion/core/tools/FileToolsTest.java`

**Interfaces:**
- Produces: `PathsGuard` — `static Path resolve(String workDir, String path)`（解析为绝对路径）；`static boolean inside(String workDir, Path p)`（路径必须在 workDir 内，否则返回 false）；`static ToolResult errorIfOutside(String workDir, Path p)`（越界返回 error ToolResult）
- Produces: `ReadTool(workDir)` — args `{path, offset?, limit?, lineNumbers?}`；越界/不存在返回 error；默认 limit 2000 行，`lineNumbers` 为 true 时输出带行号
- Produces: `GlobTool(workDir)` — args `{pattern}`；相对 workDir 的 glob（`**/*.java` 等）；输出匹配文件路径列表（最多 200 条，超出截断提示）
- Produces: `GrepTool(workDir)` — args `{pattern, path?, glob?, ignoreCase?, maxResults?}`；默认在当前目录递归，输出 `文件:行号:行内容`（最多 250 条，超出提示截断）；pattern 为 Java 正则

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class FileToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private ReadTool read;
    private GlobTool glob;
    private GrepTool grep;

    @org.junit.Before
    public void setup() throws Exception {
        work = tmp.getRoot().getAbsolutePath();
        read = new ReadTool(work);
        glob = new GlobTool(work);
        grep = new GrepTool(work);
    }

    private JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void read_withLineNumbers() throws Exception {
        Files.write(p("a.txt"), "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"lineNumbers\":true}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("1: line1"));
        assertTrue(r.output.contains("3: line3"));
    }

    @Test
    public void read_outsideWorkDir_rejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-outside-test.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"" + outside.getAbsolutePath() + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
    }

    @Test
    public void read_missingFile_error() throws Exception {
        ToolResult r = read.execute(args("{\"path\":\"nope.txt\"}"));
        assertFalse(r.ok);
    }

    @Test
    public void glob_matches() throws Exception {
        Files.createDirectories(p("src/sub"));
        Files.write(p("src/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(p("src/sub/B.java"), "y".getBytes(StandardCharsets.UTF_8));
        Files.write(p("src/C.txt"), "z".getBytes(StandardCharsets.UTF_8));
        ToolResult r = glob.execute(args("{\"pattern\":\"**/*.java\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("A.java"));
        assertTrue(r.output.contains("B.java"));
        assertFalse(r.output.contains("C.txt"));
    }

    @Test
    public void grep_matchesWithContext() throws Exception {
        Files.write(p("a.java"), "public class A {}\nint count = 1;\n// count here".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("a.java:2:"));
        assertTrue(r.output.contains("a.java:3:"));
    }

    private Path p(String rel) {
        return java.nio.file.Paths.get(work, rel);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=FileToolsTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 PathsGuard 与三个工具**

```java
package com.minion.core.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathsGuard {

    /** 解析相对工作路径的绝对路径（相对路径以 workDir 为基准） */
    public static Path resolve(String workDir, String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p.normalize();
        return Paths.get(workDir, path).normalize();
    }

    /** 是否在 workDir 内（含 workDir 本身） */
    public static boolean inside(String workDir, Path p) {
        try {
            Path root = Paths.get(workDir).toRealPath();
            Path target = p.toRealPath();
            return target.startsWith(root);
        } catch (IOException e) {
            return false;
        }
    }

    public static ToolResult errorIfOutside(String workDir, Path p) {
        if (!inside(workDir, p)) {
            return ToolResult.error("路径在工作路径之外，已拒绝: " + p);
        }
        return null;
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读文件。参数: path(必), offset(行偏移), limit(默认2000), lineNumbers(是否带行号) */
public class ReadTool implements Tool {

    private static final int DEFAULT_LIMIT = 2000;

    private final String workDir;

    public ReadTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Read"; }

    @Override
    public String description() { return "读取文件内容，支持行号、偏移与行数限制"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("读取文件内容",
                new String[]{"path", "offset", "limit", "lineNumbers"},
                new String[]{"path"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String path = args.has("path") ? args.get("path").getAsString() : "";
        if (path.isEmpty()) return ToolResult.error("缺少 path 参数");
        Path p = PathsGuard.resolve(workDir, path);
        ToolResult guard = PathsGuard.errorIfOutside(workDir, p);
        if (guard != null) return guard;
        if (!Files.exists(p)) return ToolResult.error("文件不存在: " + p);
        if (Files.isDirectory(p)) return ToolResult.error("是目录: " + p);

        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : DEFAULT_LIMIT;
        boolean lineNumbers = args.has("lineNumbers") && args.get("lineNumbers").getAsBoolean();

        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        int to = Math.min(lines.size(), offset + limit);
        for (int i = offset; i < to; i++) {
            if (lineNumbers) sb.append(i + 1).append(": ");
            sb.append(lines.get(i)).append('\n');
        }
        if (to < lines.size()) {
            sb.append("... 共 ").append(lines.size()).append(" 行，已显示 ")
              .append(to - offset).append(" 行（可用 offset/limit 翻页）\n");
        }
        return ToolResult.success(sb.toString());
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** 路径模式匹配。参数: pattern(必, 如 **\/*.java) */
public class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;

    private final String workDir;

    public GlobTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Glob"; }

    @Override
    public String description() { return "按 glob 模式在工作路径内查找文件，如 **/*.java"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("按 glob 模式查找文件",
                new String[]{"pattern"}, new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (pattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        final PathMatcher matcher = FileSystems.getDefault()
                .getPathMatcher("glob:" + pattern);
        final Path root = Paths.get(workDir);
        final List<String> found = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = root.relativize(file);
                if (matcher.matches(rel)) {
                    found.add(rel.toString().replace('\\', '/'));
                }
                return found.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
            }
        });
        StringBuilder sb = new StringBuilder();
        for (String f : found) sb.append(f).append('\n');
        if (found.size() >= MAX_RESULTS) sb.append("... 结果超过 ").append(MAX_RESULTS).append(" 条，已截断\n");
        return ToolResult.success(sb.toString().trim().isEmpty()
                ? "未找到匹配文件: " + pattern : sb.toString());
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 正则内容搜索。参数: pattern(必), path(可选搜索起点), ignoreCase, maxResults */
public class GrepTool implements Tool {

    private static final int MAX_RESULTS = 250;

    private final String workDir;

    public GrepTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Grep"; }

    @Override
    public String description() { return "在工作路径内按正则搜索文件内容，输出 文件:行号:内容"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("正则搜索文件内容",
                new String[]{"pattern", "path", "ignoreCase", "maxResults"},
                new String[]{"pattern"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        if (pattern.isEmpty()) return ToolResult.error("缺少 pattern 参数");
        final Pattern p;
        try {
            int flags = (args.has("ignoreCase") && args.get("ignoreCase").getAsBoolean())
                    ? Pattern.CASE_INSENSITIVE : 0;
            p = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("正则语法错误: " + e.getMessage());
        }
        String start = args.has("path") ? args.get("path").getAsString() : ".";
        final Path root = Paths.get(workDir, start);
        if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
        final int max = args.has("maxResults") ? args.get("maxResults").getAsInt() : MAX_RESULTS;
        final StringBuilder sb = new StringBuilder();
        final int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (count[0] >= max) return FileVisitResult.TERMINATE;
                Path rel = Paths.get(workDir).relativize(file);
                try {
                    java.util.List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && count[0] < max; i++) {
                        if (p.matcher(lines.get(i)).find()) {
                            sb.append(rel.toString().replace('\\', '/')).append(':')
                              .append(i + 1).append(": ").append(lines.get(i).trim()).append('\n');
                            count[0]++;
                        }
                    }
                } catch (IOException ignored) { }
                return count[0] >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        if (count[0] >= max) sb.append("... 结果超过 ").append(max).append(" 条，已截断\n");
        return ToolResult.success(sb.toString().trim().isEmpty()
                ? "未匹配: " + pattern : sb.toString());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=FileToolsTest
```

Expected: 6 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools src/test/java/com/minion/core/tools
git commit -m "feat: read, glob and grep tools with workdir path guard"
```

---

### Task 9: Write / Edit 工具 + 覆盖确认判定

**Files:**
- Create: `src/main/java/com/minion/core/tools/WriteTool.java`
- Create: `src/main/java/com/minion/core/tools/EditTool.java`
- Test: `src/test/java/com/minion/core/tools/EditToolsTest.java`

**Interfaces:**
- Produces: `WriteTool(workDir)` — args `{path, content}`；`isHighRisk` = 目标文件已存在；自动创建父目录
- Produces: `EditTool(workDir)` — args `{path, oldString, newString, replaceAll?}`；精确匹配替换；oldString 未匹配（或匹配多次且非 replaceAll）返回 error；`isHighRisk` 恒 true

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class EditToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private WriteTool write;
    private EditTool edit;

    @org.junit.Before
    public void setup() {
        work = tmp.getRoot().getAbsolutePath();
        write = new WriteTool(work);
        edit = new EditTool(work);
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private Path p(String rel) { return Paths.get(work, rel); }

    @Test
    public void write_newFile_andRiskFalse() throws Exception {
        assertFalse(write.isHighRisk(args("{\"path\":\"new.txt\"}"))); // 尚不存在
        ToolResult r = write.execute(args("{\"path\":\"new.txt\",\"content\":\"hello\"}"));
        assertTrue(r.ok);
        assertEquals("hello", new String(Files.readAllBytes(p("new.txt")), StandardCharsets.UTF_8));
        assertTrue(write.isHighRisk(args("{\"path\":\"new.txt\"}"))); // 写入后覆盖需确认
    }

    @Test
    public void write_overwrite_highRisk() throws Exception {
        Files.write(p("a.txt"), "old".getBytes(StandardCharsets.UTF_8));
        assertTrue(write.isHighRisk(args("{\"path\":\"a.txt\"}")));
        ToolResult r = write.execute(args("{\"path\":\"a.txt\",\"content\":\"new\"}"));
        assertTrue(r.ok);
        assertEquals("new", new String(Files.readAllBytes(p("a.txt")), StandardCharsets.UTF_8));
    }

    @Test
    public void edit_replace_matches() throws Exception {
        Files.write(p("b.txt"), "int x = 1;\nint y = 2;".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"int x = 1;\",\"newString\":\"int x = 100;\"}"));
        assertTrue(r.ok);
        String content = new String(Files.readAllBytes(p("b.txt")), StandardCharsets.UTF_8);
        assertTrue(content.contains("int x = 100;"));
        assertFalse(content.contains("int x = 1;"));
    }

    @Test
    public void edit_noMatch_returnsError() throws Exception {
        Files.write(p("b.txt"), "abc".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"zzz\",\"newString\":\"x\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("未找到"));
    }

    @Test
    public void edit_multiMatch_requiresReplaceAll() throws Exception {
        Files.write(p("b.txt"), "x=1\nx=2".getBytes(StandardCharsets.UTF_8));
        ToolResult r = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"x=\",\"newString\":\"y=\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("多处匹配"));
        ToolResult r2 = edit.execute(args("{\"path\":\"b.txt\",\"oldString\":\"x=\",\"newString\":\"y=\",\"replaceAll\":true}"));
        assertTrue(r2.ok);
        assertTrue(new String(Files.readAllBytes(p("b.txt")), StandardCharsets.UTF_8).contains("y="));
    }

    @Test
    public void edit_alwaysHighRisk() {
        assertTrue(edit.isHighRisk(new JsonObject()));
    }

    @Test
    public void write_outsideRejected() throws Exception {
        Path outside = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "minion-out-w.txt");
        ToolResult r = write.execute(args("{\"path\":\"" + outside + "\",\"content\":\"x\"}"));
        assertFalse(r.ok);
        assertFalse(Files.exists(outside));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=EditToolsTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 WriteTool 与 EditTool**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 写文件。覆盖已存在文件为高危操作（需确认）。 */
public class WriteTool implements Tool {

    private final String workDir;

    public WriteTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Write"; }

    @Override
    public String description() { return "写入文件内容（覆盖已存在文件前会请求确认）"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("写入文件",
                new String[]{"path", "content"}, new String[]{"path", "content"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) {
        if (!args.has("path")) return false;
        Path p = PathsGuard.resolve(workDir, args.get("path").getAsString());
        return Files.exists(p);
    }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        if (!args.has("path") || !args.has("content")) return ToolResult.error("缺少 path/content 参数");
        Path p = PathsGuard.resolve(workDir, args.get("path").getAsString());
        ToolResult guard = PathsGuard.errorIfOutside(workDir, p);
        if (guard != null) return guard;
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, args.get("content").getAsString().getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已写入 " + p + " (" + args.get("content").getAsString().length() + " 字符)");
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 精确字符串替换。始终为高危操作（修改现有文件）。 */
public class EditTool implements Tool {

    private final String workDir;

    public EditTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Edit"; }

    @Override
    public String description() { return "在文件中做精确字符串替换，需严格匹配原文"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("编辑文件",
                new String[]{"path", "oldString", "newString", "replaceAll"},
                new String[]{"path", "oldString", "newString"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) { return true; }

    @Override
    public ToolResult execute(JsonObject args) throws IOException {
        if (!args.has("path") || !args.has("oldString") || !args.has("newString")) {
            return ToolResult.error("缺少 path/oldString/newString 参数");
        }
        Path p = PathsGuard.resolve(workDir, args.get("path").getAsString());
        ToolResult guard = PathsGuard.errorIfOutside(workDir, p);
        if (guard != null) return guard;
        if (!Files.exists(p)) return ToolResult.error("文件不存在: " + p);

        String oldString = args.get("oldString").getAsString();
        String newString = args.get("newString").getAsString();
        String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);

        int count = countOccurrences(content, oldString);
        if (count == 0) {
            return ToolResult.error("未找到待替换内容，请先 Read 确认当前内容。oldString=" + preview(oldString));
        }
        boolean replaceAll = args.has("replaceAll") && args.get("replaceAll").getAsBoolean();
        if (count > 1 && !replaceAll) {
            return ToolResult.error("oldString 匹配 " + count + " 处，需 replaceAll=true 或提供更精确的 oldString");
        }
        String updated = replaceAll ? content.replace(oldString, newString)
                : content.replaceFirst(java.util.regex.Pattern.quote(oldString),
                        java.util.regex.Matcher.quoteReplacement(newString));
        Files.write(p, updated.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已替换 " + (replaceAll ? count : 1) + " 处: " + p);
    }

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static String preview(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > 60 ? one.substring(0, 60) + "..." : one;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=EditToolsTest
```

Expected: 7 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools src/test/java/com/minion/core/tools
git commit -m "feat: write and edit tools with overwrite risk detection"
```

---

### Task 10: Bash 工具 + 危险命令检测

**Files:**
- Create: `src/main/java/com/minion/core/tools/BashTool.java`
- Create: `src/main/java/com/minion/core/tools/DangerousCommands.java`
- Test: `src/test/java/com/minion/core/tools/BashToolTest.java`

**Interfaces:**
- Produces: `BashTool(workDir)` — args `{command, timeoutSeconds?}`；`isHighRisk` = 首 token 命中危险命令表；输出合并 stdout+stderr，截断 30000 字符，末尾注明截断；超时（默认 120s）kill 进程并返回错误
- Produces: `DangerousCommands` — `static boolean isDangerous(String command)`（取首 token 小写前缀匹配：`rm del rd rmdir format mkfs dd shutdown taskkill pkill killall fdisk mkfs.ext4`）；`static String firstToken(String command)`
- Windows 实现细节：优先 Git Bash（PATH 或 `C:\Program Files\Git\bin\bash.exe`），否则 Windows 用 `cmd /c`、Unix 用 `/bin/sh -c`

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class BashToolTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private BashTool bash;

    @org.junit.Before
    public void setup() {
        bash = new BashTool(tmp.getRoot().getAbsolutePath());
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    public void dangerousCommands_detected() {
        assertTrue(DangerousCommands.isDangerous("rm -rf /tmp/x"));
        assertTrue(DangerousCommands.isDangerous("RM -RF x"));
        assertTrue(DangerousCommands.isDangerous("del /s /q x"));
        assertTrue(DangerousCommands.isDangerous("taskkill /f /im java.exe"));
        assertFalse(DangerousCommands.isDangerous("ls -la"));
        assertFalse(DangerousCommands.isDangerous("git status"));
        assertFalse(DangerousCommands.isDangerous("mvn clean package")); // clean 不是危险词
    }

    @Test
    public void bash_isHighRisk_matchesDetection() {
        assertTrue(bash.isHighRisk(args("{\"command\":\"rm -rf x\"}")));
        assertFalse(bash.isHighRisk(args("{\"command\":\"echo hi\"}")));
    }

    @Test
    public void execute_echo() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"echo hello-from-minion\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("hello-from-minion"));
    }

    @Test
    public void execute_exitCode() throws Exception {
        ToolResult r = bash.execute(args("{\"command\":\"exit 3\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("exit code 3"));
    }

    @Test
    public void execute_timeout() throws Exception {
        long start = System.currentTimeMillis();
        ToolResult r = bash.execute(args("{\"command\":\"sleep 30\",\"timeoutSeconds\":1}"));
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(r.ok);
        assertTrue(r.output.contains("超时"));
        assertTrue(elapsed < 10000);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=BashToolTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 DangerousCommands 与 BashTool**

```java
package com.minion.core.tools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 危险命令检测：首 token 小写前缀匹配 */
public class DangerousCommands {

    private static final Set<String> DANGEROUS = new HashSet<String>(Arrays.asList(
            "rm", "del", "rd", "rmdir", "format", "mkfs", "dd",
            "shutdown", "taskkill", "pkill", "killall", "fdisk", "mkfs.ext4"));

    /** 取命令第一个 token（空白分割，剥离引号） */
    public static String firstToken(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return "";
        int i = 0;
        while (i < trimmed.length() && !Character.isWhitespace(trimmed.charAt(i))) i++;
        return trimmed.substring(0, i).toLowerCase();
    }

    public static boolean isDangerous(String command) {
        String token = firstToken(command);
        if (token.isEmpty()) return false;
        for (String d : DANGEROUS) {
            if (token.equals(d) || token.startsWith(d + "/")) return true;
        }
        return false;
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 命令执行：工作路径内、默认超时 120s、输出截断 30k。危险命令需确认。 */
public class BashTool implements Tool {

    public static final int DEFAULT_TIMEOUT = 120;
    private static final int MAX_OUTPUT = 30000;

    private final String workDir;

    public BashTool(String workDir) { this.workDir = workDir; }

    @Override
    public String name() { return "Bash"; }

    @Override
    public String description() { return "在工作目录执行 shell 命令（默认超时120秒，危险命令需确认）"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("执行 shell 命令",
                new String[]{"command", "timeoutSeconds"}, new String[]{"command"});
    }

    @Override
    public boolean isHighRisk(JsonObject args) {
        return args.has("command") && DangerousCommands.isDangerous(args.get("command").getAsString());
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        String command = args.has("command") ? args.get("command").getAsString() : "";
        if (command.isEmpty()) return ToolResult.error("缺少 command 参数");
        int timeout = args.has("timeoutSeconds") ? args.get("timeoutSeconds").getAsInt() : DEFAULT_TIMEOUT;

        List<String> cmd = buildShellCommand(command);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);
        final Process process = pb.start();
        final StringBuilder output = new StringBuilder();
        final boolean[] timedOut = {false};

        Thread reader = new Thread(() -> {
            try {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    appendTruncated(output, line);
                    appendTruncated(output, "\n");
                }
            } catch (IOException ignored) { }
        });
        reader.start();

        final Future<?> killer = java.util.concurrent.Executors.newSingleThreadExecutor()
                .submit(() -> {
                    try {
                        Thread.sleep(timeout * 1000L);
                        if (process.isAlive()) {
                            timedOut[0] = true;
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException ignored) { }
                });

        int exitCode = process.waitFor();
        reader.join(5000);
        killer.cancel(true);
        if (timedOut[0]) {
            return ToolResult.error("命令超时（" + timeout + "s），已终止: " + command);
        }
        if (exitCode != 0) {
            return ToolResult.error("exit code " + exitCode + "（命令失败，输出如下）\n" + output);
        }
        return ToolResult.success(output.toString());
    }

    private static void appendTruncated(StringBuilder sb, String s) {
        if (sb.length() >= MAX_OUTPUT) return;
        int room = MAX_OUTPUT - sb.length();
        if (s.length() > room) {
            sb.append(s, 0, room).append("\n... 输出已截断(>").append(MAX_OUTPUT).append("字符)\n");
        } else {
            sb.append(s);
        }
    }

    /** Windows 优先 Git Bash，否则 cmd /c；Unix 用 /bin/sh -c */
    private static List<String> buildShellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String bash = findGitBash();
            if (bash != null) return Arrays.asList(bash, "-c", command);
            return Arrays.asList("cmd", "/c", command);
        }
        return Arrays.asList("/bin/sh", "-c", command);
    }

    private static String findGitBash() {
        String[] candidates = {
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe"};
        for (String c : candidates) {
            if (new File(c).exists()) return c;
        }
        return null;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=BashToolTest
```

Expected: 5 个测试全 PASS（timeout 测试约 1-2s）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools src/test/java/com/minion/core/tools
git commit -m "feat: bash tool with timeout, truncation and dangerous command detection"
```

---

### Task 11: ConfirmGate 高危确认与白名单

**Files:**
- Create: `src/main/java/com/minion/core/tools/confirm/ConfirmUi.java`
- Create: `src/main/java/com/minion/core/tools/confirm/ConfirmGate.java`
- Test: `src/test/java/com/minion/core/tools/confirm/ConfirmGateTest.java`
- Test fixture: `src/test/java/com/minion/core/tools/confirm/FakeConfirmUi.java`

**Interfaces:**
- Produces: `ConfirmUi` — `enum Decision {APPROVE, REJECT, APPROVE_WHITELIST, APPROVE_SESSION}`；`Decision ask(String message)`
- Produces: `ConfirmGate` — 构造 `ConfirmGate(Config, ConfirmUi)`；`boolean check(Tool tool, JsonObject args)`（返回是否放行执行）；逻辑：confirm.skip → true；命中白名单（工具名 / 命令首 token）→ true；否则 ask()；APPROVE → true；REJECT → false；APPROVE_WHITELIST → appendWhitelist + true；APPROVE_SESSION → 本实例后续全部 true。ask 同步加锁（防止并行工具确认提示串行）
- Test fixture: `FakeConfirmUi` — `queue: Queue<Decision>`，`List<String> asked`（记录被询问的消息）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ConfirmGateTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private com.minion.core.config.Config config;

    private ConfirmGate gate(ConfirmUi ui) throws Exception {
        Path work = tmp.getRoot().toPath();
        return new ConfirmGate(config, ui);
    }

    private com.minion.core.tools.Tool writeTool() {
        return new com.minion.core.tools.WriteTool(tmp.getRoot().getAbsolutePath());
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @org.junit.Before
    public void setup() throws Exception {
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
    }

    @Test
    public void skipFlag_bypassesAsk() throws Exception {
        Files.write(java.nio.file.Paths.get(config.externalFile().toString()),
                "confirm.skip=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void approve_reject_whitelist() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals(1, ui.asked.size());
        assertTrue(ui.asked.get(0).contains("Write"));

        FakeConfirmUi ui2 = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        assertFalse(gate(ui2).check(writeTool(), args("{\"path\":\"a.txt\"}")));
    }

    @Test
    public void whitelistWrite_persistsToExternalConfig() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_WHITELIST);
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(config.whitelistTools().contains("write"));
        // 重新加载后仍生效
        com.minion.core.config.Config reloaded =
                com.minion.core.config.Config.load(tmp.getRoot().toPath());
        assertTrue(reloaded.whitelistTools().contains("write"));
    }

    @Test
    public void whitelistedTool_noAsk() throws Exception {
        config.appendWhitelist("confirm.whitelist.tools", "write");
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void whitelistedCommand_noAsk() throws Exception {
        config.appendWhitelist("confirm.whitelist.commands", "rm");
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.check(new com.minion.core.tools.BashTool(tmp.getRoot().getAbsolutePath()),
                args("{\"command\":\"rm -rf x\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void approveSession_bypassesRest() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(
                ConfirmUi.Decision.APPROVE_SESSION, ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        // 第二个本来会 REJECT，但会话已放行
        assertTrue(g.check(writeTool(), args("{\"path\":\"b.txt\"}")));
        assertEquals(1, ui.asked.size());
    }
}
```

```java
package com.minion.core.tools.confirm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class FakeConfirmUi implements ConfirmUi {
    private final Queue<Decision> queue = new ArrayDeque<Decision>();
    public final List<String> asked = new ArrayList<String>();

    public FakeConfirmUi(Decision... decisions) {
        for (Decision d : decisions) queue.add(d);
    }

    @Override
    public Decision ask(String message) {
        asked.add(message);
        Decision d = queue.poll();
        return d == null ? Decision.APPROVE : d;
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=ConfirmGateTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 ConfirmUi 与 ConfirmGate**

```java
package com.minion.core.tools.confirm;

public interface ConfirmUi {
    enum Decision { APPROVE, REJECT, APPROVE_WHITELIST, APPROVE_SESSION }

    /** 询问用户；返回决策。由实现方负责渲染提示与读取输入。 */
    Decision ask(String message);
}
```

```java
package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.minion.core.config.Config;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.DangerousCommands;
import com.minion.core.tools.Tool;

/** 高危操作确认：跳过开关 / 白名单 / Y-N-W-A 交互 */
public class ConfirmGate {

    private final Config config;
    private final ConfirmUi ui;
    private boolean sessionBypass = false;

    public ConfirmGate(Config config, ConfirmUi ui) {
        this.config = config;
        this.ui = ui;
    }

    /** 返回 true = 放行执行 */
    public synchronized boolean check(Tool tool, JsonObject args) {
        if (!tool.isHighRisk(args)) return true;
        if (sessionBypass || config.confirmSkip()) return true;
        if (isWhitelisted(tool, args)) return true;

        String detail = highRiskDetail(tool, args);
        ConfirmUi.Decision d = ui.ask("⚠ 高危操作 " + detail);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        if (d == ConfirmUi.Decision.APPROVE_WHITELIST) {
            addToWhitelist(tool, args);
            return true;
        }
        if (d == ConfirmUi.Decision.APPROVE_SESSION) {
            sessionBypass = true;
            return true;
        }
        return false;
    }

    private boolean isWhitelisted(Tool tool, JsonObject args) {
        String toolName = tool.name().toLowerCase();
        if (config.whitelistTools().contains(toolName)) return true;
        if (tool instanceof BashTool && args.has("command")) {
            String first = DangerousCommands.firstToken(args.get("command").getAsString());
            for (String w : config.whitelistCommands()) {
                if (first.equals(w)) return true;
            }
        }
        return false;
    }

    private void addToWhitelist(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command")) {
            config.appendWhitelist("confirm.whitelist.commands",
                    DangerousCommands.firstToken(args.get("command").getAsString()));
        } else {
            config.appendWhitelist("confirm.whitelist.tools", tool.name());
        }
    }

    private String highRiskDetail(Tool tool, JsonObject args) {
        if (tool instanceof BashTool && args.has("command")) {
            return "Bash → " + args.get("command").getAsString();
        }
        if (args.has("path")) {
            return tool.name() + " → " + args.get("path").getAsString();
        }
        return tool.name();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=ConfirmGateTest
```

Expected: 6 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/confirm src/test/java/com/minion/core/tools/confirm
git commit -m "feat: confirm gate with skip flag, whitelist and Y/N/W/A decisions"
```

---

### Task 12: TodoWrite 工具

**Files:**
- Create: `src/main/java/com/minion/core/tools/TodoWriteTool.java`
- Create: `src/main/java/com/minion/core/agent/TodoList.java`
- Test: `src/test/java/com/minion/core/tools/TodoWriteToolTest.java`

**Interfaces:**
- Produces: `com.minion.core.agent.TodoList` — `List<TodoItem> items`；`TodoItem {String text; boolean done}`；`void replace(List<TodoItem>)`、`void markDone(int index)`、`String render()`（`- [ ] 任务` / `- [x] 任务` 每行）
- Produces: `TodoWriteTool(TodoList)` — args `{action: "update"|"mark", items?: [{text, done}], index?: int}`；`update` 整体替换；`mark` 按索引勾选；返回渲染后的清单

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.TodoList;
import org.junit.Test;

import static org.junit.Assert.*;

public class TodoWriteToolTest {

    private final TodoList list = new TodoList();
    private final TodoWriteTool tool = new TodoWriteTool(list);

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    public void update_replacesList() {
        ToolResult r = tool.execute(args("{\"action\":\"update\",\"items\":[{\"text\":\"任务A\",\"done\":false},{\"text\":\"任务B\",\"done\":true}]}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("- [ ] 任务A"));
        assertTrue(r.output.contains("- [x] 任务B"));
        assertEquals(2, list.items.size());
    }

    @Test
    public void mark_checksIndex() {
        tool.execute(args("{\"action\":\"update\",\"items\":[{\"text\":\"A\",\"done\":false},{\"text\":\"B\",\"done\":false}]}"));
        ToolResult r = tool.execute(args("{\"action\":\"mark\",\"index\":0}"));
        assertTrue(r.ok);
        assertTrue(list.items.get(0).done);
        assertFalse(list.items.get(1).done);
    }

    @Test
    public void mark_outOfRange_error() {
        tool.execute(args("{\"action\":\"update\",\"items\":[{\"text\":\"A\",\"done\":false}]}"));
        ToolResult r = tool.execute(args("{\"action\":\"mark\",\"index\":9}"));
        assertFalse(r.ok);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=TodoWriteToolTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 TodoList 与 TodoWriteTool**

```java
package com.minion.core.agent;

import java.util.ArrayList;
import java.util.List;

/** 任务清单（会话内状态） */
public class TodoList {

    public static class TodoItem {
        public String text;
        public boolean done;

        public TodoItem() { }

        public TodoItem(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }

    public final List<TodoItem> items = new ArrayList<TodoItem>();

    public void replace(List<TodoItem> newItems) {
        items.clear();
        items.addAll(newItems);
    }

    public boolean markDone(int index) {
        if (index < 0 || index >= items.size()) return false;
        items.get(index).done = true;
        return true;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            sb.append(i + 1).append(". ").append(item.done ? "[x]" : "[ ]")
              .append(' ').append(item.text).append('\n');
        }
        return sb.toString();
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minion.core.agent.TodoList;

/** 任务清单跟踪。参数: action=update|mark, items(update时), index(mark时) */
public class TodoWriteTool implements Tool {

    private final TodoList list;

    public TodoWriteTool(TodoList list) { this.list = list; }

    @Override
    public String name() { return "TodoWrite"; }

    @Override
    public String description() { return "维护任务清单：update 整体替换任务列表，mark 勾选完成任务"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("更新任务清单",
                new String[]{"action", "items", "index"}, new String[]{"action"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String action = args.has("action") ? args.get("action").getAsString() : "";
        if (action.equals("update")) {
            JsonArray arr = args.has("items") && args.get("items").isJsonArray()
                    ? args.getAsJsonArray("items") : new JsonArray();
            java.util.List<TodoList.TodoItem> items = new java.util.ArrayList<TodoList.TodoItem>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                items.add(new TodoList.TodoItem(
                        o.has("text") ? o.get("text").getAsString() : "",
                        o.has("done") && o.get("done").getAsBoolean()));
            }
            list.replace(items);
            return ToolResult.success("任务清单:\n" + list.render());
        }
        if (action.equals("mark")) {
            if (!args.has("index")) return ToolResult.error("缺少 index 参数");
            boolean ok = list.markDone(args.get("index").getAsInt());
            if (!ok) return ToolResult.error("index 超出范围");
            return ToolResult.success("任务清单:\n" + list.render());
        }
        return ToolResult.error("未知 action: " + action + "（应为 update 或 mark）");
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=TodoWriteToolTest
```

Expected: 3 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools src/main/java/com/minion/core/agent src/test/java/com/minion/core/tools
git commit -m "feat: todowrite tool with session todo list"
```

---

### Task 13: WebFetch 工具

**Files:**
- Create: `src/main/java/com/minion/core/tools/WebFetchTool.java`
- Test: `src/test/java/com/minion/core/tools/WebFetchToolTest.java`

**Interfaces:**
- Produces: `WebFetchTool()` — args `{url}`；OkHttp GET（10s 超时），HTML 标签剥离转文本，保留 title 与正文，截断 20000 字符；非 2xx 返回 error

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class WebFetchToolTest {

    private MockWebServer server;
    private WebFetchTool tool;

    @Before
    public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        tool = new WebFetchTool();
    }

    @After
    public void teardown() throws Exception { server.shutdown(); }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @Test
    public void fetch_stripsHtml() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "<html><head><title>测试页</title></head><body><h1>标题</h1><p>正文内容</p></body></html>"));
        ToolResult r = tool.execute(args("{\"url\":\"" + server.url("/page").toString() + "\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("测试页"));
        assertTrue(r.output.contains("正文内容"));
        assertFalse(r.output.contains("<h1>"));
    }

    @Test
    public void fetch_404_returnsError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        ToolResult r = tool.execute(args("{\"url\":\"" + server.url("/nope").toString() + "\"}"));
        assertFalse(r.ok);
    }

    @Test
    public void fetch_badUrl_returnsError() throws Exception {
        ToolResult r = tool.execute(args("{\"url\":\"not-a-url\"}"));
        assertFalse(r.ok);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=WebFetchToolTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 WebFetchTool**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 抓取 URL 并转为纯文本（HTML 剥离）。 */
public class WebFetchTool implements Tool {

    private static final int MAX_TEXT = 20000;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Override
    public String name() { return "WebFetch"; }

    @Override
    public String description() { return "抓取网页并转为纯文本摘要"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("抓取网页",
                new String[]{"url"}, new String[]{"url"});
    }

    @Override
    public ToolResult execute(JsonObject args) throws Exception {
        if (!args.has("url")) return ToolResult.error("缺少 url 参数");
        String url = args.get("url").getAsString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("URL 无效: " + url);
        }
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "minion/0.1")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ToolResult.error("HTTP " + response.code() + " 获取失败");
            }
            if (response.body() == null) return ToolResult.error("空响应");
            String html = response.body().string();
            String text = stripHtml(html);
            if (text.length() > MAX_TEXT) {
                text = text.substring(0, MAX_TEXT) + "\n... 内容过长已截断";
            }
            return ToolResult.success(text);
        } catch (Exception e) {
            return ToolResult.error("抓取失败: " + e.getMessage());
        }
    }

    /** 剥离 script/style/标签，压缩空白 */
    static String stripHtml(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p>|</h[1-6]>|</li>|</tr>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
             .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
             .replaceAll("&quot;", "\"");
        String title = "";
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (m.find()) title = m.group(1).trim();
        String body = s.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
        return title.isEmpty() ? body : "标题: " + title + "\n\n" + body;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=WebFetchToolTest
```

Expected: 3 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools src/test/java/com/minion/core/tools
git commit -m "feat: webfetch tool"
```

---

### Task 14: AgentLoop 主循环（并行工具、中断、统计）

**Files:**
- Create: `src/main/java/com/minion/core/agent/AgentUi.java`
- Create: `src/main/java/com/minion/core/agent/Session.java`
- Create: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`
- Test fixture: `src/test/java/com/minion/core/agent/RecordingUi.java`

**Interfaces:**
- Produces: `AgentUi`（core 的 UI 回调接口）— `default void onUserMessage(String text){}`、`default void onThinking(String delta){}`、`default void onContent(String delta){}`、`default void onToolCall(String name, JsonObject args){}`、`default void onToolResult(String name, ToolResult result){}`、`default void onSubAgentStart(String description){}`、`default void onSubAgentDelta(String delta){}`、`default void onSubAgentDone(String summary){}`、`default void onStatsLine(String line){}`、`default void onError(String message){}`、`default void onWarning(String message){}`
- Produces: `Session` — 字段 `String id; String createdAt; String workDir; String modelName; List<Message> messages; TodoList todos; UsageTracker usage;`；`static Session create(Config)`；`String preview()`（最后一条用户消息截断 50 字）
- Produces: `AgentLoop` — 构造 `AgentLoop(Config, LlmClient, ToolRegistry, SystemPromptBuilder, ConfirmGate, AgentUi)`；`void runUserTurn(String input)`（同步阻塞）；`void interrupt()`；`List<Message> messages()`；`UsageTracker usage()`；`List<Skill> loadedSkills()` 与 `void loadSkill(Skill)`、`List<Skill> allSkills()`、`void setAllSkills(List<Skill>)`（技能接入点，Task 19 使用）；`void compactNow()`（Task 17 填充——本任务先留空实现）
- 行为：runUserTurn 内：追加 user 消息 → 循环（round < 10000 且未中断）：请求（system 消息 = SystemPromptBuilder.build + 历史消息）→ onFinish tool_calls 时并行执行工具（固定线程池，ConfirmGate 先行）→ 工具结果追加为 tool 消息 → 继续；stop 则结束。每轮结束调用 `UsageTracker.record`。中断时打印警告。测试可注入 `roundLimit` 与 `threads` 参数（包内可见字段）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ConfirmGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class AgentLoopTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Config config;
    private FakeLlmClient llm;
    private ToolRegistry registry;
    private RecordingUi ui;
    private ConfirmGate confirm;

    @org.junit.Before
    public void setup() throws Exception {
        config = Config.load(tmp.getRoot().toPath());
        llm = new FakeLlmClient();
        registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ExampleTool());
        registry.register(new com.minion.core.tools.BashTool(config.workDir()));
        ui = new RecordingUi();
        confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
    }

    private AgentLoop newLoop() {
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, ui);
        loop.roundLimit = 10; // 测试用
        return loop;
    }

    @Test
    public void singleTurn_noTools() {
        llm.addTurn("好的");
        AgentLoop loop = newLoop();
        loop.runUserTurn("你好");
        // 0:user 1:assistant
        assertEquals(2, loop.messages().size());
        assertEquals(Message.Role.USER, loop.messages().get(0).role);
        assertEquals("你好", loop.messages().get(0).content);
        assertEquals(Message.Role.ASSISTANT, loop.messages().get(1).role);
        assertEquals("好的", loop.messages().get(1).content);
        // 请求 = system + 全部历史
        assertEquals(Message.Role.SYSTEM, llm.lastRequestMessages.get(0).role);
        assertEquals(2, llm.lastRequestMessages.size());
        assertEquals(1, ui.contentParts.size());
    }

    @Test
    public void toolLoop_executesAndReturns() {
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"hi\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("处理完成");
        AgentLoop loop = newLoop();
        loop.runUserTurn("调用一下");
        // 0:user 1:assistant(tool_calls) 2:tool(result) 3:assistant(final)
        List<Message> msgs = loop.messages();
        assertEquals(4, msgs.size());
        assertEquals(Message.Role.TOOL, msgs.get(2).role);
        assertTrue(msgs.get(2).content.contains("echo: hi"));
        assertEquals("处理完成", msgs.get(3).content);
        assertEquals(1, ui.toolCalls.size());
        assertEquals("example", ui.toolCalls.get(0));
        assertEquals(1, ui.toolResults.size());
    }

    @Test
    public void roundLimit_stopsLoop() {
        for (int i = 0; i < 5; i++) {
            ToolCall tc = new ToolCall();
            tc.id = "c" + i;
            tc.name = "example";
            tc.arguments = "{\"text\":\"x\"}";
            llm.addTurnWithTools(Collections.singletonList(tc), null);
        }
        AgentLoop loop = newLoop();
        loop.roundLimit = 3;
        loop.runUserTurn("循环");
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("工具轮数上限")));
        // 1 user + 3 × (assistant工具调用 + tool结果)
        assertEquals(7, loop.messages().size());
        assertEquals(Message.Role.TOOL, loop.messages().get(loop.messages().size() - 1).role);
    }

    @Test
    public void parallelTools_bothExecuted() {
        ToolCall tc1 = new ToolCall();
        tc1.id = "a1";
        tc1.name = "example";
        tc1.arguments = "{\"text\":\"one\"}";
        ToolCall tc2 = new ToolCall();
        tc2.id = "a2";
        tc2.name = "example";
        tc2.arguments = "{\"text\":\"two\"}";
        llm.addTurnWithTools(Arrays.asList(tc1, tc2), null);
        llm.addTurn("完成");
        AgentLoop loop = newLoop();
        loop.runUserTurn("并行");
        Message tool1 = loop.messages().get(2);
        Message tool2 = loop.messages().get(3);
        assertEquals(Message.Role.TOOL, tool1.role);
        assertEquals(Message.Role.TOOL, tool2.role);
        assertTrue(tool1.content.contains("one"));
        assertTrue(tool2.content.contains("two"));
        assertEquals(2, ui.toolCalls.size());
    }

    @Test
    public void confirmReject_toolReturnsRejected() {
        FakeConfirmUi rejectUi = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        // 用 Bash 危险命令触发确认
        ToolCall tc = new ToolCall();
        tc.id = "c1";
        tc.name = "Bash";
        tc.arguments = "{\"command\":\"rm -rf x\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("好，换个方案");
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config),
                new ConfirmGate(config, rejectUi), ui);
        loop.roundLimit = 10;
        loop.runUserTurn("删掉");
        Message tool = loop.messages().get(2);
        assertTrue(tool.content.contains("拒绝"));
        assertTrue(tool.content.contains("rm"));
    }

    @Test
    public void interrupt_cancelsInFlightTurn() throws Exception {
        BlockingLlmClient blocking = new BlockingLlmClient();
        blocking.addTurn("长回复");
        AgentLoop loop = new AgentLoop(config, blocking, registry,
                new SystemPromptBuilder(config), confirm, ui);
        loop.roundLimit = 10;
        Thread t = new Thread(() -> loop.runUserTurn("长任务"));
        t.start();
        assertTrue(blocking.entered.await(5, TimeUnit.SECONDS));
        loop.interrupt();
        assertTrue(blocking.cancelled);
        t.join(5000);
        assertFalse(t.isAlive());
        // 0:user 1:assistant（打断前已收到的回复）
        assertEquals(2, loop.messages().size());
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("中断")));
    }

    /** 可阻塞的测试客户端：进入请求后等待 interrupt 触发 cancel */
    public static class BlockingLlmClient extends FakeLlmClient {
        public final CountDownLatch entered = new CountDownLatch(1);
        public volatile boolean cancelled = false;

        @Override
        public void cancel() { cancelled = true; }

        @Override
        public void streamChat(List<Message> messages, List<JsonObject> tools, StreamHandler handler) {
            entered.countDown();
            try { Thread.sleep(300); } catch (InterruptedException e) { }
            super.streamChat(messages, tools, handler);
        }
    }

    @Test
    public void usage_recorded() {
        llm.addTurn("x");
        AgentLoop loop = newLoop();
        loop.runUserTurn("统计");
        assertEquals(15, loop.usage().sessionTotal()); // Fake: input 10 + output 5
    }
}
```

```java
package com.minion.core.agent;

import com.minion.core.llm.Usage;
import com.minion.core.tools.ToolResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class RecordingUi implements AgentUi {
    public final List<String> contentParts = new ArrayList<String>();
    public final List<String> thinking = new ArrayList<String>();
    public final List<String> toolCalls = new ArrayList<String>();
    public final List<String> toolResults = new ArrayList<String>();
    public final List<String> warnings = new ArrayList<String>();
    public final List<String> errors = new ArrayList<String>();
    public final List<Usage> usages = new ArrayList<Usage>();

    @Override public void onThinking(String delta) { thinking.add(delta); }
    @Override public void onContent(String delta) { contentParts.add(delta); }
    @Override public void onToolCall(String name, JsonObject args) { toolCalls.add(name); }
    @Override public void onToolResult(String name, ToolResult result) { toolResults.add(name); }
    @Override public void onWarning(String message) { warnings.add(message); }
    @Override public void onError(String message) { errors.add(message); }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=AgentLoopTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 Session、AgentUi、AgentLoop**

```java
package com.minion.core.agent;

public interface AgentUi {
    default void onUserMessage(String text) { }
    default void onThinking(String delta) { }
    default void onContent(String delta) { }
    default void onToolCall(String name, com.google.gson.JsonObject args) { }
    default void onToolResult(String name, com.minion.core.tools.ToolResult result) { }
    default void onSubAgentStart(String description) { }
    default void onSubAgentDelta(String delta) { }
    default void onSubAgentDone(String summary) { }
    default void onStatsLine(String line) { }
    default void onError(String message) { }
    default void onWarning(String message) { }
}
```

```java
package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.llm.Message;
import com.minion.core.llm.UsageTracker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 会话状态：消息、任务清单、统计。可整体序列化落盘（字段即 JSON 结构）。 */
public class Session {

    public String id;
    public String createdAt;
    public String workDir;
    public String modelName;
    public List<Message> messages = new ArrayList<Message>();
    public TodoList todos = new TodoList();
    public UsageTracker usage = new UsageTracker();

    public static Session create(Config config) {
        Session s = new Session();
        s.id = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        s.createdAt = s.id;
        s.workDir = config.workDir();
        s.modelName = config.modelName();
        return s;
    }

    /** 恢复时用（Task 18） */
    public static Session resume(Config config, String id, String createdAt, String workDir,
                                 String modelName, List<Message> messages) {
        Session s = new Session();
        s.id = id;
        s.createdAt = createdAt;
        s.workDir = workDir;
        s.modelName = modelName;
        s.messages = messages;
        return s;
    }

    /** 历史会话列表展示：时间 + 最后用户消息摘要 */
    public String preview() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role == Message.Role.USER) {
                String text = m.content == null ? "" : m.content.replace('\n', ' ');
                return text.length() > 50 ? text.substring(0, 50) + "..." : text;
            }
        }
        return "(空会话)";
    }
}
```

```java
package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.skills.Skill;
import com.minion.core.tools.ConfirmGate;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 主 agent 循环：请求 → 工具执行 → 回传，直到模型不再调用工具。 */
public class AgentLoop {

    public static final int DEFAULT_ROUND_LIMIT = 10000;

    private final Config config;
    private final LlmClient llm;
    private final ToolRegistry registry;
    private final SystemPromptBuilder promptBuilder;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    private final Session session;

    private volatile boolean interrupted = false;
    private List<Skill> allSkills = new ArrayList<Skill>();
    private List<Skill> loadedSkills = new ArrayList<Skill>();
    private java.util.function.Function<JsonObject, String> subAgentRunner; // Task 15 注入

    public int roundLimit = DEFAULT_ROUND_LIMIT;
    public int threads = 4;
    private final ExecutorService pool;

    public AgentLoop(Config config, LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui) {
        this.config = config;
        this.llm = llm;
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.session = Session.create(config);
        this.pool = Executors.newFixedThreadPool(threads);
    }

    public Session session() { return session; }
    public List<Message> messages() { return session.messages; }
    public UsageTracker usage() { return session.usage; }
    public List<Skill> allSkills() { return allSkills; }
    public void setAllSkills(List<Skill> skills) { this.allSkills = skills; }
    public List<Skill> loadedSkills() { return loadedSkills; }
    public void loadSkill(Skill skill) { loadedSkills.add(skill); }
    public void setSubAgentRunner(java.util.function.Function<JsonObject, String> runner) {
        this.subAgentRunner = runner;
    }

    public void interrupt() {
        interrupted = true;
        llm.cancel(); // 中断进行中的流式请求
    }

    public void compactNow() {
        // Task 17 填充：触发一次压缩
    }

    public void runUserTurn(String input) {
        interrupted = false;
        ui.onUserMessage(input);
        session.messages.add(Message.user(input));
        int rounds = 0;
        int retries = 0;
        try {
            while (!interrupted) {
                if (rounds >= roundLimit) {
                    ui.onWarning("达到工具轮数上限(" + roundLimit + ")，已停止本轮");
                    break;
                }
                String system = promptBuilder.build(allSkills, loadedSkills);
                List<Message> request = new ArrayList<Message>();
                request.add(Message.system(system));
                request.addAll(session.messages);

                final List<ToolCall>[] toolCalls = new List[1];
                final Usage[] usage = new Usage[1];
                final String[] finish = new String[1];
                final StringBuilder content = new StringBuilder();
                final StringBuilder thinking = new StringBuilder();
                try {
                    llm.streamChat(request, registry.schemas(), new com.minion.core.llm.StreamHandler() {
                        @Override
                        public void onThinking(String delta) {
                            thinking.append(delta);
                            ui.onThinking(delta);
                        }
                        @Override
                        public void onContent(String delta) {
                            content.append(delta);
                            ui.onContent(delta);
                        }
                        @Override
                        public void onFinish(String finishReason, Usage u, List<ToolCall> tcs) {
                            finish[0] = finishReason;
                            usage[0] = u;
                            toolCalls[0] = tcs;
                        }
                        @Override
                        public void onError(LlmException e) {
                            finish[0] = "error";
                            ui.onError(e.getMessage());
                        }
                    });
                } catch (LlmException e) {
                    if (e.retryable && retries < 1) {
                        retries++;
                        ui.onWarning("请求失败（" + e.getMessage() + "），自动重试 1 次");
                        continue; // 消息未变，直接重发本轮
                    }
                    ui.onError(e.getMessage());
                    break;
                }
                if (interrupted) break;

                if (usage[0] != null) session.usage.record(usage[0]);
                if ("error".equals(finish[0])) break;

                // assistant 回复（含思考与工具调用）入会话历史——reasoningContent 回传硬性要求
                Message assistantMsg = Message.assistant(
                        content.length() == 0 ? null : content.toString());
                assistantMsg.reasoningContent = thinking.length() == 0 ? null : thinking.toString();
                assistantMsg.toolCalls = toolCalls[0];
                session.messages.add(assistantMsg);

                if (toolCalls[0] == null || toolCalls[0].isEmpty()
                        || !"tool_calls".equals(finish[0])) {
                    break;
                }
                rounds++;

                List<ToolCall> calls = toolCalls[0];
                List<Future<ToolResult>> futures = new ArrayList<Future<ToolResult>>();
                for (ToolCall call : calls) {
                    futures.add(pool.submit(() -> runOneTool(call)));
                }
                for (int i = 0; i < calls.size(); i++) {
                    ToolResult result;
                    try {
                        result = futures.get(i).get();
                    } catch (Exception e) {
                        result = ToolResult.error("工具执行异常: " + e.getMessage());
                    }
                    if (result == null) result = ToolResult.error("工具执行失败");
                    session.messages.add(Message.toolResult(
                            calls.get(i).id, calls.get(i).name, result.output));
                    ui.onToolResult(calls.get(i).name, result);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ui.onWarning("已中断");
        } catch (Exception e) {
            ui.onError("异常: " + e.getMessage());
        }
        if (interrupted) ui.onWarning("本轮已被中断");
    }

    private ToolResult runOneTool(ToolCall call) throws Exception {
        Tool tool = registry.get(call.name);
        if (tool == null) {
            return ToolResult.error("未知工具: " + call.name);
        }
        JsonObject args;
        try {
            args = JsonParser.parseString(call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
        } catch (Exception e) {
            return ToolResult.error("工具参数 JSON 解析失败: " + e.getMessage()
                    + "，请检查 arguments 格式");
        }
        if (!confirmGate.check(tool, args)) {
            return ToolResult.error("用户拒绝了该操作（" + call.name + "），请调整方案");
        }
        ui.onToolCall(call.name, args);
        try {
            return tool.execute(args);
        } catch (Exception e) {
            return ToolResult.error("工具执行异常: " + e.getMessage());
        }
    }

    /** 派发子 agent（Task 15 由 TaskTool 调用） */
    public String runSubAgent(JsonObject args) {
        if (subAgentRunner == null) {
            return "子 agent 不可用";
        }
        return subAgentRunner.apply(args);
    }
}
```

注意：`roundLimit` 与 `threads` 是公开字段（测试注入 + 生产默认）；`pool` 用 `Executors.newFixedThreadPool(threads)` 创建时 threads 默认 4（构造时用默认字段值，测试在构造后覆盖 roundLimit 即可，threads 不改）。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=AgentLoopTest
```

Expected: 7 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/agent src/test/java/com/minion/core/agent
git commit -m "feat: agent loop with tool execution, parallel tools, interrupt and usage"
```

---

### Task 15: SubAgentLoop 嵌套循环 + Task 工具

**Files:**
- Create: `src/main/java/com/minion/core/agent/SubAgentLoop.java`
- Create: `src/main/java/com/minion/core/tools/TaskTool.java`
- Test: `src/test/java/com/minion/core/agent/SubAgentLoopTest.java`

**Interfaces:**
- Produces: `SubAgentLoop` — 构造 `SubAgentLoop(String systemPrompt, String taskDescription, String workDir, LlmClient, ToolRegistry, ConfirmGate, AgentUi)`；`String run()`（阻塞直到子任务完成，返回最终文本）；独立 messages 数组 `[system(主system+任务), user(任务)]`；工具集 = registry 中剔除 `task`；无轮数上限；内部异常返回错误文本
- Produces: `TaskTool(AgentLoop)` — `TaskTool` 持有 `AgentLoop` 引用，`execute` 调 `loop.runSubAgent(args)`；args `{description, prompt?}`；`isHighRisk` false
- 修改: `AgentLoop` 构造后自动注册 TaskTool 并注入 subAgentRunner：`loop.setSubAgentRunner(args -> new SubAgentLoop(system, desc, workDir, llm, registry, confirm, ui).run())`
- 在 Task 14 的 `AgentLoop` 中增加包内可见方法 `String buildSystemPrompt()`（复用 promptBuilder.build）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.tools.ConfirmGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static org.junit.Assert.*;

public class SubAgentLoopTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void subAgent_runsOwnLoop_returnsFinalText() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ExampleTool());
        // 子 agent 无 task 工具
        assertNull(registry.get("task"));
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();

        // 子 agent 内部：工具调用一轮 → 总结
        ToolCall tc = new ToolCall();
        tc.id = "s1";
        tc.name = "example";
        tc.arguments = "{\"text\":\"子任务\"}";
        llm.addTurnWithTools(Collections.singletonList(tc), null);
        llm.addTurn("子任务结果：完成");

        SubAgentLoop sub = new SubAgentLoop("主系统提示", "调研一下",
                config.workDir(), llm, registry, confirm, ui);
        String result = sub.run();
        assertEquals("子任务结果：完成", result);
        // 子 agent 请求 = [system, user(任务描述)]
        assertEquals(Message.Role.SYSTEM, llm.lastRequestMessages.get(0).role);
        assertTrue(llm.lastRequestMessages.get(1).content.contains("调研一下"));
        // tool 结果已进入子 agent 自己的消息
        assertTrue(ui.toolCalls.contains("example"));
    }

    @Test
    public void subAgent_loopStopsWhenNoMoreTools() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ExampleTool());
        llm.addTurn("直接回答");
        SubAgentLoop sub = new SubAgentLoop("sys", "任务", config.workDir(), llm, registry,
                new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE)),
                new RecordingUi());
        assertEquals("直接回答", sub.run());
    }

    @Test
    public void taskTool_dispatches() throws Exception {
        com.minion.core.config.Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ExampleTool());
        FakeConfirmUi confirmUi = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate confirm = new ConfirmGate(config, confirmUi);
        RecordingUi ui = new RecordingUi();
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, ui);
        loop.setSubAgentRunner(args ->
                new SubAgentLoop("sys", args.get("description").getAsString(),
                        config.workDir(), llm, registry, confirm, ui).run());

        com.minion.core.tools.TaskTool task = new com.minion.core.tools.TaskTool(loop);
        llm.addTurn("子agent结果");
        JsonObject args = JsonParser.parseString("{\"description\":\"完成子任务\"}").getAsJsonObject();
        com.minion.core.tools.ToolResult r = task.execute(args);
        assertTrue(r.ok);
        assertEquals("子agent结果", r.output);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=SubAgentLoopTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 SubAgentLoop 与 TaskTool**

```java
package com.minion.core.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.LlmException;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import com.minion.core.llm.Usage;
import com.minion.core.tools.ConfirmGate;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** 子 agent：独立消息数组 + 完整工具集（无 task），无轮数上限，返回最终文本 */
public class SubAgentLoop {

    private static final String SUB_SYSTEM_SUFFIX =
            "\n\n你是一个子 agent。只负责完成上述任务，完成后用最终文本总结结果（不要客套）。";

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ConfirmGate confirmGate;
    private final AgentUi ui;
    private final List<Message> messages = new ArrayList<Message>();

    public SubAgentLoop(String systemPrompt, String taskDescription, String workDir,
                        LlmClient llm, ToolRegistry registry, ConfirmGate confirmGate, AgentUi ui) {
        this.llm = llm;
        this.registry = registry;
        this.confirmGate = confirmGate;
        this.ui = ui;
        messages.add(Message.system(systemPrompt + SUB_SYSTEM_SUFFIX));
        messages.add(Message.user("任务: " + taskDescription));
    }

    public String run() {
        ui.onSubAgentStart(messages.get(1).content);
        try {
            while (true) {
                final List<ToolCall>[] toolCalls = new List[1];
                final String[] finish = new String[1];
                final StringBuilder content = new StringBuilder();
                llm.streamChat(messages, registry.schemas(), new com.minion.core.llm.StreamHandler() {
                    @Override
                    public void onContent(String delta) {
                        content.append(delta);
                        ui.onSubAgentDelta(delta);
                    }
                    @Override
                    public void onFinish(String finishReason, Usage usage, List<ToolCall> tcs) {
                        finish[0] = finishReason;
                        toolCalls[0] = tcs;
                    }
                    @Override
                    public void onError(LlmException e) { finish[0] = "error"; ui.onError(e.getMessage()); }
                });
                if (toolCalls[0] == null || toolCalls[0].isEmpty()
                        || !"tool_calls".equals(finish[0])) {
                    ui.onSubAgentDone(content.toString());
                    return content.toString();
                }
                for (ToolCall call : toolCalls[0]) {
                    ToolResult result = runOneTool(call);
                    messages.add(Message.toolResult(call.id, call.name, result.output));
                    ui.onToolResult(call.name, result);
                }
            }
        } catch (LlmException e) {
            ui.onError("子 agent 请求失败: " + e.getMessage());
            return "子 agent 失败: " + e.getMessage();
        } catch (Exception e) {
            ui.onError("子 agent 异常: " + e.getMessage());
            return "子 agent 异常: " + e.getMessage();
        }
    }

    private ToolResult runOneTool(ToolCall call) throws Exception {
        Tool tool = registry.get(call.name);
        if (tool == null) return ToolResult.error("未知工具: " + call.name);
        JsonObject args;
        try {
            args = JsonParser.parseString(call.arguments == null ? "{}" : call.arguments).getAsJsonObject();
        } catch (Exception e) {
            return ToolResult.error("工具参数 JSON 解析失败: " + e.getMessage());
        }
        if (!confirmGate.check(tool, args)) {
            return ToolResult.error("用户拒绝了该操作（" + call.name + "）");
        }
        ui.onToolCall(call.name, args);
        return tool.execute(args);
    }
}
```

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentLoop;

/** 派发子 agent。由 AgentLoop 提供执行器。 */
public class TaskTool implements Tool {

    private final AgentLoop loop;

    public TaskTool(AgentLoop loop) { this.loop = loop; }

    @Override
    public String name() { return "task"; }

    @Override
    public String description() { return "派发一个子 agent 完成独立子任务（完整工具集，可并行）。参数 description 说明任务，prompt 可选指定返回格式"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("派发子 agent 任务",
                new String[]{"description", "prompt"}, new String[]{"description"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("description")) return ToolResult.error("缺少 description 参数");
        String result = loop.runSubAgent(args);
        return ToolResult.success("[子agent 完成]\n" + result);
    }
}
```

修改 AgentLoop（Task 14 文件，追加方法）：

```java
    /** 当前系统提示（含已加载技能），子 agent 复用 */
    public String buildSystemPrompt() {
        return promptBuilder.build(allSkills, loadedSkills);
    }
```

以及构造后注册 Task 工具 + 注入 runner（在 `runUserTurn` 前由 Main/装配方调用，或构造末尾自动完成——选择构造末尾）：

在 AgentLoop 构造末尾追加：

```java
        registry.register(new com.minion.core.tools.TaskTool(this));
        setSubAgentRunner(args -> {
            String desc = args.has("description") ? args.get("description").getAsString() : "无描述";
            ui.onSubAgentStart(desc);
            return new SubAgentLoop(buildSystemPrompt(), desc, config.workDir(),
                    llm, registry, confirmGate, ui).run();
        });
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=SubAgentLoopTest,AgentLoopTest
```

Expected: 全部 PASS（AgentLoopTest 需通过——TaskTool 注册后 ToolRegistry 含 task，不影响既有断言）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/agent src/main/java/com/minion/core/tools src/test/java/com/minion/core/agent
git commit -m "feat: sub agent loop and task tool with nested tool execution"
```

---

### Task 16: ContextManager 压缩（链切块 + 摘要请求）

**Files:**
- Create: `src/main/java/com/minion/core/context/ContextManager.java`
- Test: `src/test/java/com/minion/core/context/ContextManagerTest.java`

**Interfaces:**
- Produces: `com.minion.core.context.ContextManager` — 构造 `ContextManager(int maxContextTokens, double threshold, int keepRecent, LlmClient llm, int systemTokens)`；`boolean shouldCompress(List<Message> messages)`；`List<Message> compress(List<Message> messages)`（返回压缩后的新列表；失败时原样返回并打印警告）；`int estimate(List<Message>)`（= systemTokens + TokenCounter.estimateMessages）
- 静态可测方法：`static List<List<Message>> chunkChains(List<Message>)`（链 = user→assistant(工具)→tool…→assistant(无工具)；summary 消息跳过不参与）
- 压缩逻辑：`chains = chunkChains(对话历史)`；从最早开始选链，直到剩余未压缩消息 ≤ keepRecent 或全部压缩完（链不可拆）；被选链合并为一个压缩批；摘要请求 messages=[system(压缩器提示), user(批次 JSON)]；生成 `Message.user(summary)` 且 `summary=true` 置于最前

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.context;

import com.minion.core.llm.FakeLlmClient;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ContextManagerTest {

    private static Message assistantWithTools(String... names) {
        Message m = Message.assistant(null);
        List<ToolCall> tcs = new ArrayList<ToolCall>();
        for (String n : names) {
            ToolCall tc = new ToolCall();
            tc.id = "c_" + n;
            tc.name = n;
            tc.arguments = "{}";
            tcs.add(tc);
        }
        m.toolCalls = tcs;
        return m;
    }

    private static List<Message> sampleHistory() {
        List<Message> msgs = new ArrayList<Message>();
        msgs.add(Message.user("任务1"));
        msgs.add(assistantWithTools("Read"));
        msgs.add(Message.toolResult("c_Read", "Read", "内容1"));
        msgs.add(Message.assistant("任务1完成"));
        msgs.add(Message.user("任务2"));
        msgs.add(Message.assistant("直接完成"));
        return msgs;
    }

    @Test
    public void chunkChains_keepsToolPairingIntact() {
        List<List<Message>> chains = ContextManager.chunkChains(sampleHistory());
        assertEquals(2, chains.size());
        // 链1 = user + assistant(tools) + tool + assistant(无工具)
        assertEquals(4, chains.get(0).size());
        assertEquals("任务1", chains.get(0).get(0).content);
        assertEquals("任务1完成", chains.get(0).get(3).content);
        // 链2 = user + assistant
        assertEquals(2, chains.get(1).size());
    }

    @Test
    public void chunkChains_skipsSummaryMessages() {
        List<Message> msgs = new ArrayList<Message>();
        Message summary = Message.user("【摘要】之前的内容");
        summary.summary = true;
        msgs.add(summary);
        msgs.add(Message.user("新问题"));
        msgs.add(Message.assistant("回答"));
        List<List<Message>> chains = ContextManager.chunkChains(msgs);
        assertEquals(1, chains.size());
        assertFalse(chains.get(0).get(0).summary);
    }

    @Test
    public void shouldCompress_overThreshold() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        // 100*0.8=80 token 触发；20 字符 ≈ 5 token
        List<Message> big = new ArrayList<Message>();
        for (int i = 0; i < 30; i++) {
            big.add(Message.user("一二三四五六七八九十"));
            big.add(Message.assistant("abcdefghij"));
        }
        assertTrue(cm.shouldCompress(big));
        List<Message> small = Collections.singletonList(Message.user("hi"));
        assertFalse(cm.shouldCompress(small));
    }

    @Test
    public void compress_replacesOldWithSummary() {
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】完成了任务1和任务2";
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        List<Message> result = cm.compress(sampleHistory());
        // summary 置前
        Message first = result.get(0);
        assertTrue(first.summary);
        assertTrue(first.content.contains("【摘要】"));
        // keepRecent=2 保留最后 2 条原文（链2）
        assertEquals(3, result.size());
        assertEquals("任务2", result.get(1).content);
        // 压缩请求带专用 system
        assertTrue(llm.lastRequestMessages.get(0).content.contains("压缩器"));
    }

    @Test
    public void compress_llmFailure_returnsOriginal() {
        FakeLlmClient llm = new FakeLlmClient();
        ContextManager cm = new ContextManager(100, 0.8, 2, llm, 0);
        // FakeLlmClient.completeChat 不抛异常，这里通过设置 compressResult 为空串模拟
        llm.compressResult = "";
        List<Message> result = cm.compress(sampleHistory());
        // 空摘要 → 视为失败，原样返回
        assertEquals(sampleHistory().size(), result.size());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=ContextManagerTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 ContextManager**

```java
package com.minion.core.context;

import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 上下文管理：token 估算、阈值判断、链式压缩（完整回合链为单位，摘要置前） */
public class ContextManager {

    private static final String COMPRESS_SYSTEM =
            "你是 minion 的上下文压缩器。把用户提供的对话历史压缩成一段中文摘要，保留："
          + "未完成的任务与目标、已做出的关键决策及原因、使用过的工具与结果要点、"
          + "相关文件路径、代码约定、用户偏好。只输出摘要正文，不要客套，500 字以内。";

    private final int maxContextTokens;
    private final double threshold;
    private final int keepRecent;
    private final LlmClient llm;
    private final int systemTokens;

    public ContextManager(int maxContextTokens, double threshold, int keepRecent,
                          LlmClient llm, int systemTokens) {
        this.maxContextTokens = maxContextTokens;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
        this.llm = llm;
        this.systemTokens = systemTokens;
    }

    public int estimate(List<Message> messages) {
        return systemTokens + TokenCounter.estimateMessages(messages);
    }

    public boolean shouldCompress(List<Message> messages) {
        return estimate(messages) >= maxContextTokens * threshold;
    }

    /** 按完整回合链切块。summary 消息跳过（已压缩过，不再参与）。 */
    public static List<List<Message>> chunkChains(List<Message> messages) {
        List<List<Message>> chains = new ArrayList<List<Message>>();
        List<Message> cur = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.summary) {
                flush(chains, cur);
                continue;
            }
            cur.add(m);
            if (m.role == Message.Role.ASSISTANT
                    && (m.toolCalls == null || m.toolCalls.isEmpty())) {
                flush(chains, cur); // 无工具调用的 assistant 结束一条链
            }
        }
        flush(chains, cur);
        return chains;
    }

    private static void flush(List<List<Message>> chains, List<Message> cur) {
        if (!cur.isEmpty()) {
            chains.add(new ArrayList<Message>(cur));
            cur.clear();
        }
    }

    /** 压缩：链为单位，摘要置前；保留最近 keepRecent 条原文。失败原样返回。 */
    public List<Message> compress(List<Message> messages) {
        List<List<Message>> chains = chunkChains(messages);
        int keep = 0;
        int take = 0;
        for (int i = chains.size() - 1; i >= 0; i--) {
            int size = chains.get(i).size();
            if (keep + size <= keepRecent) {
                keep += size;
            } else {
                take = i + 1;
                break;
            }
        }
        if (take == 0) return messages; // 全部要保留，无需压缩

        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < take; i++) {
            for (Message m : chains.get(i)) {
                batch.append('[').append(m.role).append(']');
                if (m.content != null) batch.append(' ').append(m.content);
                if (m.reasoningContent != null) batch.append(" (思考: ").append(m.reasoningContent).append(')');
                if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                    batch.append(" [工具: ");
                    for (com.minion.core.llm.ToolCall tc : m.toolCalls) {
                        batch.append(tc.name).append(' ');
                    }
                    batch.append(']');
                }
                batch.append('\n');
            }
        }
        String summary;
        try {
            summary = llm.completeChat(
                    Collections.singletonList(Message.user(batch.toString())), COMPRESS_SYSTEM);
        } catch (Exception e) {
            System.err.println("[minion] 压缩失败，跳过本轮: " + e.getMessage());
            return messages;
        }
        if (summary == null || summary.trim().isEmpty()) {
            System.err.println("[minion] 压缩返回空摘要，跳过本轮");
            return messages;
        }

        List<Message> result = new ArrayList<Message>();
        Message summaryMsg = Message.user("【历史对话摘要】\n" + summary.trim());
        summaryMsg.summary = true;
        result.add(summaryMsg);
        for (int i = take; i < chains.size(); i++) {
            result.addAll(chains.get(i)); // 保留未被压缩的链；旧 summary 由新摘要取代
        }
        return result;
    }
}
```

（`compress` 中保留「take 之后的所有原文」；keepRecent 语义即「最后 keepRecent 条原文」——链无法完美对齐条数时允许略超，安全优先。简化逻辑：压缩 take 之前的链，保留剩余全部。）

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=ContextManagerTest
```

Expected: 5 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/context src/test/java/com/minion/core/context
git commit -m "feat: context manager with chain chunking and summary compression"
```

---

### Task 17: 自动压缩接入 AgentLoop + /compact

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Test: `src/test/java/com/minion/core/agent/AgentLoopCompactTest.java`

**Interfaces:**
- 修改 AgentLoop：新增构造参数之后的重载——`AgentLoop(Config, LlmClient, ToolRegistry, SystemPromptBuilder, ConfirmGate, AgentUi, ContextManager)`（旧构造委托给它，ContextManager 为 null 表示不压缩）；`runUserTurn` 内每次请求前：`contextManager != null && contextManager.shouldCompress(messages)` → `messages = contextManager.compress(messages)` 并 `ui.onWarning("上下文已达 " + pct + "%，已自动压缩")`；`compactNow()` 改为真实实现（立即压缩 + 提示）
- 测试：AutoCompressTest + CompactCommand 逻辑（CommandDispatcher 在 Task 21，此处只测压缩触发与 compactNow）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.agent;

import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.tools.ConfirmGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class AgentLoopCompactTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void autoCompress_triggersOverThreshold() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】被压缩的历史";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        // 小上下文上限，快速触发压缩
        ContextManager cm = new ContextManager(60, 0.8, 2, llm, 0);
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, ui, cm);
        loop.roundLimit = 10;
        // 塞满历史：3 轮 user+assistant ≈ 每轮 12 token
        for (int i = 0; i < 3; i++) {
            llm.addTurn("回复" + i);
            loop.runUserTurn("问题" + i);
        }
        // 第 4 轮触发压缩
        llm.addTurn("压缩后回复");
        loop.runUserTurn("触发压缩");
        boolean compressed = ui.warnings.stream().anyMatch(w -> w.contains("自动压缩"));
        assertTrue("应触发自动压缩", compressed);
        assertTrue(loop.messages().get(0).summary);
    }

    @Test
    public void compactNow_compressesImmediately() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        llm.compressResult = "【摘要】手动压缩";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.minion.core.tools.ExampleTool());
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        ContextManager cm = new ContextManager(100000, 0.8, 1, llm, 0);
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, ui, cm);
        llm.addTurn("回复");
        loop.runUserTurn("问题");
        assertFalse(loop.messages().get(0).summary); // 未触发
        loop.compactNow();
        assertTrue(loop.messages().get(0).summary);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("已压缩")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=AgentLoopCompactTest
```

Expected: 编译失败（AgentLoop 尚无带 ContextManager 的构造）

- [ ] **Step 3: 修改 AgentLoop**

```java
// 新增字段
    private final ContextManager contextManager;

// 旧构造委托新构造（contextManager = null）
    public AgentLoop(Config config, LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui) {
        this(config, llm, registry, promptBuilder, confirmGate, ui, null);
    }

    public AgentLoop(Config config, LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui,
                     ContextManager contextManager) {
        this.config = config;
        this.llm = llm;
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.contextManager = contextManager;
        this.session = Session.create(config);
        this.pool = Executors.newFixedThreadPool(threads);
        registry.register(new com.minion.core.tools.TaskTool(this));
        setSubAgentRunner(args -> {
            String desc = args.has("description") ? args.get("description").getAsString() : "无描述";
            ui.onSubAgentStart(desc);
            return new SubAgentLoop(buildSystemPrompt(), desc, config.workDir(),
                    llm, registry, confirmGate, ui).run();
        });
    }
```

`runUserTurn` 的 while 循环内、构造请求之前插入压缩检查：

```java
                if (contextManager != null && contextManager.shouldCompress(session.messages)) {
                    int before = session.messages.size();
                    session.messages = contextManager.compress(session.messages);
                    if (session.messages.size() < before) {
                        int pct = (int) (contextManager.estimate(session.messages) * 100
                                / config.maxContextTokens());
                        ui.onWarning("上下文已达 " + pct + "%，已自动压缩历史（技能不受影响）");
                    }
                }
```

`compactNow` 实现：

```java
    public void compactNow() {
        if (contextManager == null) {
            ui.onWarning("未启用上下文压缩");
            return;
        }
        int before = session.messages.size();
        session.messages = contextManager.compress(session.messages);
        if (session.messages.size() < before) {
            ui.onWarning("已手动压缩上下文（历史摘要已置前）");
        } else {
            ui.onWarning("暂无可压缩内容");
        }
    }

    /** REPL 统计用：上下文估算 */
    public ContextManager contextManager() { return contextManager; }

    /** REPL 渲染用 */
    public AgentUi ui() { return ui; }
```

注意：`session.messages` 改为可赋值——`Session.messages` 保持 `List<Message>`，通过 `session.messages = ...` 需要字段非 final（已是）。`messages()` 返回引用，压缩后调用方应使用 `messages()` 重新取值（测试中 `loop.messages().get(0)` 是重新调用，安全）。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=AgentLoopCompactTest,AgentLoopTest
```

Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/agent src/test/java/com/minion/core/agent
git commit -m "feat: auto compression trigger and manual compact in agent loop"
```

---

### Task 18: SessionStore 持久化与恢复

**Files:**
- Create: `src/main/java/com/minion/core/storage/SessionStore.java`
- Test: `src/test/java/com/minion/core/storage/SessionStoreTest.java`

**Interfaces:**
- Produces: `com.minion.core.storage.SessionStore` — 构造 `SessionStore(Path dir)`；`Path save(Session)`（原子写：tmp+rename，返回文件路径）；`List<SessionMeta> list()`（按时间倒序；`SessionMeta{id, createdAt, preview}`）；`Session load(String id)`（gson 反序列化；失败抛 IOException）；`Session latest()`
- Gson 适配：`Message`/`ToolCall`/`TodoList`/`UsageTracker` 需要默认无参构造与 public 字段（已满足）；`Message.role` 是枚举——gson 默认序列化枚举名，可往返；`Session.id/createdAt/...` public 字段
- 保存时机：AgentLoop 在每次请求完成后调用（Task 19 接入？——本任务先做 SessionStore 本身 + 在 AgentLoop 加可选 autoSave 回调：`AgentLoop.setSessionStore(SessionStore)`，runUserTurn 每轮工具循环后与结束时保存）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.storage;

import com.minion.core.agent.Session;
import com.minion.core.config.Config;
import com.minion.core.llm.Message;
import com.minion.core.llm.ToolCall;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SessionStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Session makeSession(Config config) {
        Session s = Session.create(config);
        s.messages.add(Message.user("你好"));
        Message a = Message.assistant("已分析");
        a.reasoningContent = "思考过程"; // 关键：reasoning 必须持久化
        s.messages.add(a);
        ToolCall tc = new ToolCall();
        tc.id = "c1"; tc.name = "Read"; tc.arguments = "{}";
        Message a2 = Message.assistant(null);
        a2.toolCalls = Collections.singletonList(tc);
        s.messages.add(a2);
        s.messages.add(Message.toolResult("c1", "Read", "内容"));
        Message sum = Message.user("【摘要】旧历史");
        sum.summary = true;
        s.messages.add(0, sum);
        return s;
    }

    @Test
    public void saveLoad_roundTrip() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().resolve("sessions"));
        Session s = makeSession(config);
        store.save(s);

        Session loaded = store.load(s.id);
        assertEquals(s.id, loaded.id);
        assertEquals(5, loaded.messages.size());
        assertEquals("思考过程", loaded.messages.get(1).reasoningContent);
        assertEquals("c1", loaded.messages.get(2).toolCalls.get(0).id);
        assertTrue(loaded.messages.get(0).summary);
        assertEquals(Message.Role.TOOL, loaded.messages.get(4).role);
        assertEquals(config.workDir(), loaded.workDir);
    }

    @Test
    public void list_sortedByNewest() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().resolve("sessions"));
        Session a = makeSession(config);
        Session b = makeSession(config);
        // 手动保证时间序：写两次
        store.save(a);
        Thread.sleep(1100);
        store.save(b);
        List<SessionStore.SessionMeta> list = store.list();
        assertEquals(2, list.size());
        assertEquals(b.id, list.get(0).id);
        assertEquals(a.id, list.get(1).id);
        assertFalse(list.get(0).preview.isEmpty());
    }

    @Test
    public void latest_returnsMostRecent() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        SessionStore store = new SessionStore(tmp.getRoot().resolve("sessions"));
        Session a = makeSession(config);
        store.save(a);
        Session loaded = store.latest();
        assertEquals(a.id, loaded.id);
        assertEquals(5, loaded.messages.size());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=SessionStoreTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 SessionStore**

```java
package com.minion.core.storage;

import com.google.gson.Gson;
import com.minion.core.agent.Session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 会话落盘：原子写（tmp+rename），列表按时间倒序 */
public class SessionStore {

    private final Gson gson = new Gson();
    private final Path dir;

    public SessionStore(Path dir) { this.dir = dir; }

    public Path save(Session session) throws IOException {
        Files.createDirectories(dir);
        String json = gson.toJson(session);
        Path tmp = dir.resolve(session.id + ".json.tmp");
        Path target = dir.resolve(session.id + ".json");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    public Session load(String id) throws IOException {
        Path f = dir.resolve(id + ".json");
        if (!Files.exists(f)) throw new IOException("会话不存在: " + id);
        String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        return gson.fromJson(json, Session.class);
    }

    public List<SessionMeta> list() throws IOException {
        List<SessionMeta> metas = new ArrayList<SessionMeta>();
        if (!Files.isDirectory(dir)) return metas;
        Files.newDirectoryStream(dir, "*.json").forEach(f -> {
            try {
                Session s = gson.fromJson(
                        new String(Files.readAllBytes(f), StandardCharsets.UTF_8), Session.class);
                metas.add(new SessionMeta(s.id, s.createdAt, s.preview()));
            } catch (IOException ignored) { }
        });
        metas.sort(Comparator.comparing((SessionMeta m) -> m.createdAt).reversed());
        return metas;
    }

    public Session latest() throws IOException {
        List<SessionMeta> metas = list();
        if (metas.isEmpty()) return null;
        return load(metas.get(0).id);
    }

    public static class SessionMeta {
        public final String id;
        public final String createdAt;
        public final String preview;

        public SessionMeta(String id, String createdAt, String preview) {
            this.id = id;
            this.createdAt = createdAt;
            this.preview = preview;
        }
    }
}
```

AgentLoop 增加自动保存（修改 Task 14 文件）：

```java
    private SessionStore store; // com.minion.core.storage.SessionStore

    public void setSessionStore(SessionStore store) { this.store = store; }

    private void saveSession() {
        if (store != null) {
            try { store.save(session); }
            catch (Exception e) { ui.onWarning("会话落盘失败: " + e.getMessage()); }
        }
    }
```

`runUserTurn` 中：工具循环每轮结束后（for 循环追加 tool 消息后）调用 `saveSession()`；循环末尾（break 后、方法返回前）再调用一次 `saveSession()`。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=SessionStoreTest,AgentLoopTest
```

Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/storage src/test/java/com/minion/core/storage src/main/java/com/minion/core/agent
git commit -m "feat: session store with atomic save and resume"
```

---

### Task 19: 技能系统（SkillManager + /skills /skill）

**Files:**
- Create: `src/main/java/com/minion/core/skills/SkillManager.java`
- Create: `src/main/java/com/minion/cli/CommandDispatcher.java`（命令分发，Task 21 的 REPL 复用）
- Test: `src/test/java/com/minion/core/skills/SkillManagerTest.java`
- Test: `src/test/java/com/minion/cli/CommandDispatcherTest.java`

**Interfaces:**
- Produces: `com.minion.core.skills.SkillManager` — 构造 `SkillManager(String skillsDir)`；`List<Skill> scan()`（目录格式 `skills/<name>/SKILL.md` + 单文件 `skills/<name>.skill.md`；SnakeYAML 解析 frontmatter（`---` 分隔），name 缺省取目录名，description 缺省空串；frontmatter 缺失则整个文件作为指令、name 取目录/文件名）
- Produces: `com.minion.cli.CommandDispatcher` — 构造 `CommandDispatcher(AgentLoop, Config, SessionStore, SkillManager, AgentUi)`；`enum Command {HELP, EXIT, SKILLS, SKILL, RESUME, COMPACT, TOKENS, CLEAR, MODEL}`；`Object dispatch(String input)`（`/help`→HELP 等；`/skill X`→加载；`/resume`→列出并交互选择（本任务返回需要选择列表的信号，交互在 REPL）；`/tokens`→返回统计字符串；`/clear`→清空 messages；`/model`→脱敏配置字符串）；未知命令返回 `null`（REPL 视为普通消息）；非 `/` 开头返回 `null`

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class SkillManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void scan_directoryFormat_withFrontmatter() throws Exception {
        Path skillsDir = tmp.getRoot().resolve("skills");
        Path debug = skillsDir.resolve("debugging");
        Files.createDirectories(debug);
        Files.write(debug.resolve("SKILL.md"),
                ("---\nname: debugging\ndescription: 调试技能\nmetadata:\n  type: process\n---\n"
                        + "调试指令正文").getBytes(StandardCharsets.UTF_8));
        SkillManager mgr = new SkillManager(skillsDir.toString());
        List<Skill> skills = mgr.scan();
        assertEquals(1, skills.size());
        Skill s = skills.get(0);
        assertEquals("debugging", s.name);
        assertEquals("调试技能", s.description);
        assertTrue(s.instructions.contains("调试指令正文"));
        assertFalse(s.instructions.contains("---"));
    }

    @Test
    public void scan_singleFileFormat() throws Exception {
        Path skillsDir = tmp.getRoot().resolve("skills");
        Files.createDirectories(skillsDir);
        Files.write(skillsDir.resolve("review.skill.md"),
                ("---\ndescription: 代码审查\n---\n审查要点：读、写、测").getBytes(StandardCharsets.UTF_8));
        SkillManager mgr = new SkillManager(skillsDir.toString());
        List<Skill> skills = mgr.scan();
        assertEquals(1, skills.size());
        assertEquals("review", skills.get(0).name); // name 缺省取文件名
        assertEquals("代码审查", skills.get(0).description);
    }

    @Test
    public void scan_noFrontmatter_usesWholeFile() throws Exception {
        Path skillsDir = tmp.getRoot().resolve("skills");
        Path t = skillsDir.resolve("mytool");
        Files.createDirectories(t);
        Files.write(t.resolve("SKILL.md"), "纯指令，没有 frontmatter".getBytes(StandardCharsets.UTF_8));
        SkillManager mgr = new SkillManager(skillsDir.toString());
        List<Skill> skills = mgr.scan();
        assertEquals(1, skills.size());
        assertEquals("mytool", skills.get(0).name);
        assertTrue(skills.get(0).instructions.contains("纯指令"));
    }

    @Test
    public void scan_missingDir_returnsEmpty() {
        SkillManager mgr = new SkillManager(tmp.getRoot().resolve("nope").toString());
        assertTrue(mgr.scan().isEmpty());
    }
}
```

```java
package com.minion.cli;

import com.google.gson.Gson;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.RecordingUi;
import com.minion.core.config.Config;
import com.minion.core.llm.FakeLlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.ConfirmGate;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.FakeConfirmUi;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class CommandDispatcherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AgentLoop loop;
    private CommandDispatcher dispatcher;

    @org.junit.Before
    public void setup() throws Exception {
        Config config = Config.load(tmp.getRoot().toPath());
        FakeLlmClient llm = new FakeLlmClient();
        ToolRegistry registry = new ToolRegistry();
        RecordingUi ui = new RecordingUi();
        ConfirmGate confirm = new ConfirmGate(config, new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        loop = new AgentLoop(config, llm, registry,
                new com.minion.core.agent.SystemPromptBuilder(config), confirm, ui);
        dispatcher = new CommandDispatcher(loop, config,
                new SessionStore(tmp.getRoot().resolve("sessions")), null, ui);
    }

    @Test
    public void dispatch_knownCommands() {
        assertEquals(CommandDispatcher.Command.HELP, dispatcher.dispatch("/help"));
        assertEquals(CommandDispatcher.Command.EXIT, dispatcher.dispatch("/quit"));
        assertEquals(CommandDispatcher.Command.EXIT, dispatcher.dispatch("/exit"));
        assertEquals(CommandDispatcher.Command.SKILLS, dispatcher.dispatch("/skills"));
        assertEquals(CommandDispatcher.Command.COMPACT, dispatcher.dispatch("/compact"));
        assertEquals(CommandDispatcher.Command.TOKENS, dispatcher.dispatch("/tokens"));
        assertEquals(CommandDispatcher.Command.CLEAR, dispatcher.dispatch("/clear"));
        assertEquals(CommandDispatcher.Command.MODEL, dispatcher.dispatch("/model"));
        assertEquals(CommandDispatcher.Command.RESUME, dispatcher.dispatch("/resume"));
    }

    @Test
    public void dispatch_unknownAndPlainText() {
        assertNull(dispatcher.dispatch("/nope"));
        assertNull(dispatcher.dispatch("普通消息"));
    }

    @Test
    public void skill_loadAddsToLoop() {
        Skill skill = new Skill("review", "审查技能", "审查指令", "SKILL.md");
        dispatcher.dispatchSkill(skill);
        assertEquals(1, loop.loadedSkills().size());
        assertEquals("review", loop.loadedSkills().get(0).name);
    }

    @Test
    public void tokens_returnsFormattedStats() {
        Object r = dispatcher.dispatch("/tokens");
        assertTrue(r instanceof String);
        assertTrue(((String) r).contains("in"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=SkillManagerTest,CommandDispatcherTest
```

Expected: 编译失败（RecordingUi 在 src/test 下，CommandDispatcherTest 同层可引用）

- [ ] **Step 3: 实现 SkillManager 与 CommandDispatcher**

```java
package com.minion.core.skills;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 技能扫描：skills/<name>/SKILL.md（superpowers 格式）或 skills/<name>.skill.md */
public class SkillManager {

    private final String skillsDir;

    public SkillManager(String skillsDir) { this.skillsDir = skillsDir; }

    public List<Skill> scan() {
        List<Skill> skills = new ArrayList<Skill>();
        Path root = Paths.get(skillsDir);
        if (!Files.isDirectory(root)) return skills;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    Path md = entry.resolve("SKILL.md");
                    if (Files.exists(md)) {
                        skills.add(parse(md, entry.getFileName().toString()));
                    }
                } else {
                    String name = entry.getFileName().toString();
                    if (name.endsWith(".skill.md")) {
                        skills.add(parse(entry, name.substring(0, name.length() - ".skill.md".length())));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[minion] 扫描技能失败: " + e.getMessage());
        }
        skills.sort((a, b) -> a.name.compareTo(b.name));
        return skills;
    }

    static Skill parse(Path file, String fallbackName) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                if (end > 0) {
                    String fm = text.substring(3, end);
                    String body = text.substring(end + 4);
                    try {
                        Object loaded = new Yaml().load(fm);
                        Map<String, Object> yaml = loaded instanceof Map
                                ? (Map<String, Object>) loaded : null;
                        String name = fallbackName;
                        String desc = "";
                        if (yaml != null) {
                            if (yaml.get("name") != null) name = String.valueOf(yaml.get("name"));
                            if (yaml.get("description") != null) desc = String.valueOf(yaml.get("description"));
                        }
                        return new Skill(name, desc, body.trim(), file.getFileName().toString());
                    } catch (Exception e) {
                        System.err.println("[minion] 技能 frontmatter 解析失败(" + file + "): " + e.getMessage());
                        return new Skill(fallbackName, "", text.trim(), file.getFileName().toString());
                    }
                }
            }
            return new Skill(fallbackName, "", text.trim(), file.getFileName().toString());
        } catch (IOException e) {
            return new Skill(fallbackName, "", "(读取失败)", file.getFileName().toString());
        }
    }
}
```

```java
package com.minion.cli;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.AgentUi;
import com.minion.core.config.Config;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;

import java.util.List;
import java.util.Locale;

/** /命令分发。返回 null = 不是命令（按普通消息处理）。 */
public class CommandDispatcher {

    public enum Command { HELP, EXIT, SKILLS, SKILL, RESUME, COMPACT, TOKENS, CLEAR, MODEL }

    private final AgentLoop loop;
    private final Config config;
    private final SessionStore store;
    private final SkillManager skillManager;
    private final AgentUi ui;

    public CommandDispatcher(AgentLoop loop, Config config, SessionStore store,
                             SkillManager skillManager, AgentUi ui) {
        this.loop = loop;
        this.config = config;
        this.store = store;
        this.skillManager = skillManager;
        this.ui = ui;
    }

    /** 返回 Command / String(展示内容) / null(非命令) */
    public Object dispatch(String input) {
        if (input == null || !input.startsWith("/")) return null;
        String trimmed = input.trim();
        String cmd = trimmed.toLowerCase(Locale.ROOT);
        String[] parts = trimmed.split("\\s+");
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "/help":
                return Command.HELP;
            case "/exit":
            case "/quit":
                return Command.EXIT;
            case "/skills":
                return Command.SKILLS;
            case "/skill":
                if (parts.length < 2) return "用法: /skill <技能名>（/skills 查看列表）";
                return dispatchSkillByName(parts[1]);
            case "/resume":
                return Command.RESUME;
            case "/compact":
                loop.compactNow();
                return Command.COMPACT;
            case "/tokens":
                return formatTokens();
            case "/clear":
                return Command.CLEAR;
            case "/model":
                return formatModel();
            default:
                return null;
        }
    }

    public void dispatchSkill(Skill skill) {
        loop.loadSkill(skill);
        ui.onWarning("已加载技能: " + skill.name);
    }

    private Object dispatchSkillByName(String name) {
        if (skillManager == null) return "技能系统未启用";
        for (Skill s : skillManager.scan()) {
            if (s.name.equalsIgnoreCase(name)) {
                dispatchSkill(s);
                return "已加载技能: " + s.name;
            }
        }
        return "未找到技能: " + name + "（/skills 查看列表）";
    }

    private String formatTokens() {
        com.minion.core.llm.UsageTracker t = loop.usage();
        return String.format(Locale.ROOT,
                "会话统计: in %d · out %d · thinking %d · 合计 %d",
                t.sessionInput(), t.sessionOutput(), t.sessionThinking(), t.sessionTotal());
    }

    private String formatModel() {
        return "模型: " + config.modelName() + " · 思考: " + (config.thinkingEnabled() ? "on("
                + config.reasoningEffort() + ")" : "off")
                + " · 上下文上限: " + config.maxContextTokens()
                + " · key: " + maskKey(config.modelKey());
    }

    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "(未配置)";
        if (key.equals("sk-your-key")) return "(默认占位，请修改)";
        return key.length() <= 6 ? "***" : key.substring(0, 3) + "***" + key.substring(key.length() - 3);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=SkillManagerTest,CommandDispatcherTest
```

Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/skills src/main/java/com/minion/cli src/test/java/com/minion/core/skills src/test/java/com/minion/cli
git commit -m "feat: skill manager with snakeyaml frontmatter and command dispatcher"
```

---

### Task 20: Renderer 渲染与统计行

**Files:**
- Create: `src/main/java/com/minion/core/util/Ansi.java`
- Create: `src/main/java/com/minion/cli/Renderer.java`
- Create: `src/main/java/com/minion/cli/StatsLine.java`
- Test: `src/test/java/com/minion/core/util/AnsiTest.java`
- Test: `src/test/java/com/minion/cli/StatsLineTest.java`

**Interfaces:**
- Produces: `com.minion.core.util.Ansi` — `static String wrap(String s, String code)`（`\u001b[<code>m` + s + `\u001b[0m`）；常量 `DIM="2" CYAN="36" GREEN="32" YELLOW="33" RED="31" GRAY="90" BOLD="1" ITALIC="3"`
- Produces: `StatsLine` — `static String format(UsageTracker usage, long elapsedMillis, int currentCtx, int maxCtx)` → `⏱ 12.3s · in 8.2k · out 3.4k · thinking 2.1k · ctx 61.4k/128k (48%)`；`static String formatTokens(int n)`（≥1000 → `%.1fk`）
- Produces: `com.minion.cli.Renderer implements AgentUi` — 构造 `Renderer(boolean color)`；`onThinking` 输出 `dim italic 前缀"▸ "`；`onContent` 普通输出（无前缀，流式）；`onToolCall` 输出 `🔧 名称 → 参数摘要`；`onToolResult` 输出 `灰色预览`；`onSubAgentStart` `⌁ 子agent: desc`；`onStatsLine` 原样输出；`onError` 红；`onWarning` 黄。全部输出到 `System.out`。text 方法按 color 开关决定是否包 ANSI

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class AnsiTest {

    @Test
    public void wrap_addsCodes() {
        assertEquals("\u001b[2mtext\u001b[0m", Ansi.wrap("text", Ansi.DIM));
    }
}
```

```java
package com.minion.cli;

import com.minion.core.llm.Usage;
import com.minion.core.llm.UsageTracker;
import org.junit.Test;

import static org.junit.Assert.*;

public class StatsLineTest {

    @Test
    public void formatTokens_units() {
        assertEquals("512", StatsLine.formatTokens(512));
        assertEquals("8.2k", StatsLine.formatTokens(8200));
        assertEquals("1.0k", StatsLine.formatTokens(1000));
    }

    @Test
    public void format_fullLine() {
        UsageTracker t = new UsageTracker();
        Usage u = new Usage();
        u.inputTokens = 8200;
        u.outputTokens = 3400;
        u.reasoningTokens = 2100;
        t.record(u);
        String line = StatsLine.format(t, 12300, 61400, 131072);
        assertTrue(line.contains("⏱ 12.3s"));
        assertTrue(line.contains("in 8.2k"));
        assertTrue(line.contains("out 3.4k"));
        assertTrue(line.contains("thinking 2.1k"));
        assertTrue(line.contains("ctx 61.4k/131072 (47%)"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=AnsiTest,StatsLineTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 Ansi、StatsLine、Renderer**

```java
package com.minion.core.util;

public class Ansi {
    public static final String DIM = "2";
    public static final String CYAN = "36";
    public static final String GREEN = "32";
    public static final String YELLOW = "33";
    public static final String RED = "31";
    public static final String GRAY = "90";
    public static final String BOLD = "1";
    public static final String ITALIC = "3";

    public static String wrap(String s, String code) {
        return "\u001b[" + code + "m" + s + "\u001b[0m";
    }
}
```

```java
package com.minion.cli;

import com.minion.core.llm.UsageTracker;

import java.util.Locale;

public class StatsLine {

    public static String format(UsageTracker usage, long elapsedMillis,
                                int currentCtx, int maxCtx) {
        double secs = elapsedMillis / 1000.0;
        int pct = maxCtx > 0 ? (int) Math.round(currentCtx * 100.0 / maxCtx) : 0;
        return String.format(Locale.ROOT,
                "⏱ %.1fs · in %s · out %s · thinking %s · ctx %s/%d (%d%%)",
                secs,
                formatTokens(usage.sessionInput()),
                formatTokens(usage.sessionOutput()),
                formatTokens(usage.sessionThinking()),
                formatTokens(currentCtx), maxCtx, pct);
    }

    public static String formatTokens(int n) {
        if (n < 1000) return String.valueOf(n);
        return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
    }
}
```

```java
package com.minion.cli;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;
import com.minion.core.util.Ansi;
import com.minion.core.tools.ToolResult;

/** 终端渲染：颜色开关、流式增量输出 */
public class Renderer implements AgentUi {

    private final boolean color;

    public Renderer(boolean color) { this.color = color; }

    private String text(String s, String code) {
        return color ? Ansi.wrap(s, code) : s;
    }

    @Override
    public void onUserMessage(String text) {
        System.out.println();
        System.out.println(text(("❯ " + text), Ansi.CYAN + ";" + Ansi.BOLD));
    }

    @Override
    public void onThinking(String delta) {
        System.out.print(text("▸ " + delta, Ansi.DIM + ";" + Ansi.ITALIC));
        System.out.flush();
    }

    @Override
    public void onContent(String delta) {
        System.out.print(delta);
        System.out.flush();
    }

    @Override
    public void onToolCall(String name, JsonObject args) {
        System.out.println();
        String argPreview = args != null ? args.toString() : "";
        if (argPreview.length() > 80) argPreview = argPreview.substring(0, 80) + "...";
        System.out.println(text("🔧 " + name + " → " + argPreview, Ansi.CYAN));
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
        System.out.println(text("· " + (result.ok ? "" : "✗ ") + name + ": " + result.preview(), Ansi.GRAY));
    }

    @Override
    public void onSubAgentStart(String description) {
        System.out.println();
        System.out.println(text("⌁ 子agent: " + description, Ansi.CYAN + ";" + Ansi.BOLD));
    }

    @Override
    public void onSubAgentDelta(String delta) {
        System.out.print(text(delta, Ansi.GRAY));
        System.out.flush();
    }

    @Override
    public void onSubAgentDone(String summary) {
        System.out.println();
        System.out.println(text("⌁ 子agent完成", Ansi.CYAN));
    }

    @Override
    public void onStatsLine(String line) {
        System.out.println(text(line, Ansi.GREEN));
    }

    @Override
    public void onError(String message) {
        System.out.println(text("✗ " + message, Ansi.RED));
    }

    @Override
    public void onWarning(String message) {
        System.out.println(text("⚠ " + message, Ansi.YELLOW));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q test -Dtest=AnsiTest,StatsLineTest
```

Expected: 3 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/util src/main/java/com/minion/cli src/test/java/com/minion/core/util src/test/java/com/minion/cli
git commit -m "feat: ansi renderer and stats line formatting"
```

---

### Task 21: REPL、Main 入口、-c/-r、启动脚本与 README

**Files:**
- Create: `src/main/java/com/minion/cli/ConfirmReader.java`（JLine 确认交互 ConfirmUi 实现）
- Create: `src/main/java/com/minion/cli/Repl.java`
- Modify: `src/main/java/com/minion/Main.java`
- Create: `minion.bat`
- Create: `README.md`
- Test: `src/test/java/com/minion/cli/ReplDispatchTest.java`（REPL 的命令→动作映射逻辑，不启动 JLine）

**Interfaces:**
- Produces: `ConfirmReader` — 构造 `ConfirmReader(LineReader)`；实现 `ConfirmUi`：打印黄色警告行 + `[回车/Y]确认 [N]拒绝 [W]确认+白名单 [A]本会话放行:`，读一行：空/`y`/`Y`→APPROVE；`n`/`N`→REJECT；`w`/`W`→APPROVE_WHITELIST；`a`/`A`→APPROVE_SESSION；其他→重新询问
- Produces: `Repl` — 构造 `Repl(Config, LlmClient, AgentLoop, CommandDispatcher, SkillManager)`；`void start()`（JLine 循环；补全 = /命令 + 技能名；Ctrl+C 首次打断 agent 循环，再次退出；每轮结束打印统计行（需 elapsed + ctx 估算）；/help 输出帮助文本；/skills 列出；/clear 清空 messages；/resume 列出会话供选择（JLine 数字选择）；/exit 保存并退出）
- 修改 `Main` — 参数解析：`-c "任务"` 单次执行（非交互，自动确认开关开启，执行后打印统计行退出）；`-r` 恢复最近会话；默认交互模式。装配：Config → DeepSeekClient → 工具注册（Read/Write/Edit/Glob/Grep/Bash/TodoWrite/WebFetch）→ SkillManager → SystemPromptBuilder → ConfirmGate(ConfirmReader) → Renderer → ContextManager → SessionStore → AgentLoop（setAllSkills / setSessionStore）→ Repl/单次执行
- 创建 `minion.bat`：`@echo off` + `java -jar %~dp0target\minion-0.1.0.jar %*`
- 创建 `README.md`：简介、依赖、构建、配置说明（config.properties 各项）、用法（交互 / -c / -r）、/命令表、技能格式、危险确认与白名单说明
- Test: `ReplDispatchTest` — 验证 `Repl` 的「输入是否命令 + 命令枚举」映射（从 Repl 抽出包内可见静态方法 `static boolean isCommand(String)`、`static CommandKind classify(String)` 复用 CommandDispatcher）

- [ ] **Step 1: 写失败测试**

```java
package com.minion.cli;

import org.junit.Test;

import static org.junit.Assert.*;

public class ReplDispatchTest {

    @Test
    public void isCommand_detectsSlashOnly() {
        assertTrue(Repl.isCommand("/help"));
        assertTrue(Repl.isCommand("/skill review"));
        assertFalse(Repl.isCommand("hello"));
        assertFalse(Repl.isCommand("/"));
        assertFalse(Repl.isCommand(""));
    }
}
```

（REPL 主体通过手测清单验证——见 Step 5。）

- [ ] **Step 2: 运行确认失败**

```bash
mvn -q test -Dtest=ReplDispatchTest
```

Expected: 编译失败

- [ ] **Step 3: 实现 ConfirmReader、Repl、Main、minion.bat、README**

```java
package com.minion.cli;

import com.minion.core.tools.confirm.ConfirmUi;
import org.jline.reader.LineReader;

/** JLine 确认交互实现 */
public class ConfirmReader implements ConfirmUi {

    private final LineReader reader;

    public ConfirmReader(LineReader reader) { this.reader = reader; }

    @Override
    public Decision ask(String message) {
        while (true) {
            System.out.println();
            System.out.println(message);
            String line = reader.readLine("[回车/Y]确认 [N]拒绝 [W]确认+加入白名单 [A]本会话放行: ");
            if (line == null) return Decision.REJECT;
            String t = line.trim().toLowerCase();
            if (t.isEmpty() || t.equals("y") || t.equals("yes")) return Decision.APPROVE;
            if (t.equals("n") || t.equals("no")) return Decision.REJECT;
            if (t.equals("w") || t.equals("whitelist")) return Decision.APPROVE_WHITELIST;
            if (t.equals("a") || t.equals("all")) return Decision.APPROVE_SESSION;
            System.out.println("无效输入，请重试");
        }
    }
}
```

```java
package com.minion.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.AgentUi;
import com.minion.core.config.Config;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Signal;
import org.jline.utils.SignalHandler;

import java.util.ArrayList;
import java.util.List;

/** 交互式 REPL：JLine 行编辑、补全、Ctrl+C 中断、命令分发、统计行 */
public class Repl {

    private final Config config;
    private final LlmClient llm;
    private final AgentLoop loop;
    private final CommandDispatcher dispatcher;
    private final SkillManager skillManager;
    private final SessionStore store;
    private volatile boolean exitRequested = false;

    public Repl(Config config, LlmClient llm, AgentLoop loop,
                CommandDispatcher dispatcher, SkillManager skillManager, SessionStore store) {
        this.config = config;
        this.llm = llm;
        this.loop = loop;
        this.dispatcher = dispatcher;
        this.skillManager = skillManager;
        this.store = store;
    }

    public static boolean isCommand(String input) {
        if (input == null || input.isEmpty() || input.equals("/")) return false;
        return input.trim().startsWith("/");
    }

    public void start() throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        List<String> completions = new ArrayList<String>();
        completions.addAll(java.util.Arrays.asList(
                "/help", "/exit", "/quit", "/skills", "/skill", "/resume",
                "/compact", "/tokens", "/clear", "/model"));
        if (skillManager != null) {
            for (Skill s : skillManager.scan()) completions.add("/skill " + s.name);
        }
        Completer completer = new StringsCompleter(completions);
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(completer)
                .build();

        terminal.handle(Signal.INT, sig -> {
            if (exitRequested) {
                System.out.println("\n再见");
                System.exit(0);
            }
            exitRequested = true;
            loop.interrupt();
            System.out.println("\n(已请求中断，按 Ctrl+C 再次退出)");
        });

        System.out.println(renderer().wrapBanner("minion — 代码开发助手  (输入 /help 查看命令)"));
        printResumeHint();

        while (!exitRequested) {
            String input;
            try {
                input = reader.readLine("❯ ");
            } catch (org.jline.reader.EndOfFileException e) {
                break;
            } catch (org.jline.reader.UserInterruptException e) {
                continue;
            }
            if (input == null) break;
            String trimmed = input.trim();
            if (trimmed.isEmpty()) continue;

            if (isCommand(trimmed)) {
                handleCommand(trimmed);
            } else {
                long start = System.currentTimeMillis();
                loop.runUserTurn(trimmed);
                long elapsed = System.currentTimeMillis() - start;
                int currentCtx = loop.contextManager() != null
                        ? loop.contextManager().estimate(loop.messages())
                        : TokenCounter.estimateMessages(loop.messages());
                String stats = StatsLine.format(loop.usage(), elapsed, currentCtx, config.maxContextTokens());
                System.out.println(renderer().green(stats));
            }
        }
        try {
            store.save(loop.session());
        } catch (Exception e) {
            System.out.println("退出保存失败: " + e.getMessage());
        }
        System.out.println("再见");
    }

    private void handleCommand(String input) {
        Object r = dispatcher.dispatch(input);
        if (r instanceof CommandDispatcher.Command) {
            switch ((CommandDispatcher.Command) r) {
                case HELP:
                    System.out.println(helpText());
                    break;
                case EXIT:
                    exitRequested = true;
                    break;
                case SKILLS:
                    printSkills();
                    break;
                case RESUME:
                    resumeFlow();
                    break;
                case COMPACT:
                    break; // dispatch 内已执行
                case CLEAR:
                    loop.messages().clear();
                    System.out.println("已清空当前上下文（会话文件保留）");
                    break;
                default:
                    break;
            }
        } else if (r instanceof String) {
            System.out.println(r);
        }
    }

    private void printSkills() {
        if (skillManager == null) {
            System.out.println("技能系统未启用");
            return;
        }
        List<Skill> skills = skillManager.scan();
        if (skills.isEmpty()) {
            System.out.println("没有发现技能（技能目录: " + config.skillsDir() + "）");
            return;
        }
        System.out.println("可用技能:");
        for (Skill s : skills) {
            String loaded = loop.loadedSkills().stream()
                    .anyMatch(x -> x.name.equals(s.name)) ? " [已加载]" : "";
            System.out.println("  /skill " + s.name + " — " + s.description + loaded);
        }
    }

    private void resumeFlow() {
        try {
            List<SessionStore.SessionMeta> metas = store.list();
            if (metas.isEmpty()) {
                System.out.println("没有历史会话");
                return;
            }
            System.out.println("历史会话:");
            for (int i = 0; i < metas.size(); i++) {
                System.out.println("  [" + (i + 1) + "] " + metas.get(i).createdAt
                        + " — " + metas.get(i).preview);
            }
            System.out.print("选择会话编号（回车取消）: ");
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in));
            String line = br.readLine();
            if (line == null || line.trim().isEmpty()) return;
            int idx = Integer.parseInt(line.trim()) - 1;
            if (idx < 0 || idx >= metas.size()) {
                System.out.println("无效编号");
                return;
            }
            resumeSession(metas.get(idx).id);
        } catch (Exception e) {
            System.out.println("恢复失败: " + e.getMessage());
        }
    }

    private void resumeSession(String id) throws Exception {
        com.minion.core.agent.Session s = store.load(id);
        loop.restoreSession(s);
        System.out.println("已恢复会话 " + s.createdAt + "（" + s.messages.size() + " 条消息）");
    }

    private void printResumeHint() {
        try {
            if (store != null && store.latest() != null) {
                System.out.println("(检测到上次会话，输入 /resume 恢复)");
            }
        } catch (Exception ignored) { }
    }

    static String helpText() {
        return "命令:\n"
                + "  /help         帮助\n"
                + "  /exit /quit   退出并保存会话\n"
                + "  /skills       列出技能\n"
                + "  /skill <名>   加载技能\n"
                + "  /resume       恢复历史会话\n"
                + "  /compact      立即压缩上下文\n"
                + "  /tokens       会话 token 统计\n"
                + "  /clear        清空当前上下文（会话文件保留）\n"
                + "  /model        模型配置概览\n"
                + "其他输入将作为消息发给模型。Ctrl+C 中断当前任务，再按退出。";
    }

    private Renderer renderer() { return (Renderer) loop.ui(); }
}
```

注意：为让 Repl 能访问 loop 的上下文管理器/UI，在 AgentLoop 增加包内公开方法：

```java
    public ContextManager contextManager() { return contextManager; }
    public AgentUi ui() { return ui; }
    public void restoreSession(com.minion.core.agent.Session s) {
        session.messages = s.messages;
        session.id = s.id;
        session.createdAt = s.createdAt;
        session.workDir = s.workDir;
        session.modelName = s.modelName;
    }
```

Renderer 增加两个公开便捷方法：

```java
    public String green(String s) { return text(s, Ansi.GREEN); }
    public String wrapBanner(String s) { return color ? Ansi.wrap(s, Ansi.CYAN + ";" + Ansi.BOLD) : s; }
```

Main（完整装配）：

```java
package com.minion;

import com.minion.cli.ConfirmReader;
import com.minion.cli.CommandDispatcher;
import com.minion.cli.Renderer;
import com.minion.cli.Repl;
import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.config.Config;
import com.minion.core.context.ContextManager;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.*;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Paths;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        if (config.modelKey().isEmpty() || config.modelKey().equals("sk-your-key")) {
            System.err.println("[minion] 请先编辑 jar 同目录的 config.properties，配置 model.key");
            if (args.length == 0) return; // 交互模式必须配置 key
        }

        LlmClient llm = new DeepSeekClient(config.modelUrl(), config.modelKey(),
                config.modelName(), config.thinkingEnabled(), config.reasoningEffort());
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        ConfirmUi confirmUi = new ConfirmReader(reader);
        Renderer renderer = new Renderer(config.uiColor());

        ToolRegistry registry = new ToolRegistry();
        String workDir = config.workDir();
        registry.register(new ReadTool(workDir));
        registry.register(new WriteTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new GlobTool(workDir));
        registry.register(new GrepTool(workDir));
        registry.register(new BashTool(workDir));
        registry.register(new TodoWriteTool(new com.minion.core.agent.TodoList()));
        registry.register(new WebFetchTool());

        SkillManager skillManager = new SkillManager(config.skillsDir());
        List<Skill> skills = skillManager.scan();

        SessionStore store = new SessionStore(Paths.get(config.sessionDir()));

        // 交互模式下确认用 ConfirmReader；-c 模式全部放行（脚本化）
        ConfirmGate confirm = new ConfirmGate(config,
                args.length > 0 && "-c".equals(args[0])
                        ? ui -> ConfirmUi.Decision.APPROVE : confirmUi);

        ContextManager ctx = new ContextManager(config.maxContextTokens(),
                config.compressThreshold(), config.keepRecentMessages(),
                llm, TokenCounter.estimate(new SystemPromptBuilder(config)
                        .build(skills, java.util.Collections.<Skill>emptyList())));

        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, renderer, ctx);
        loop.setAllSkills(skills);
        loop.setSessionStore(store);

        if (args.length >= 2 && "-c".equals(args[0])) {
            long start = System.currentTimeMillis();
            loop.runUserTurn(args[1]);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println(com.minion.cli.StatsLine.format(loop.usage(), elapsed,
                    ctx.estimate(loop.messages()), config.maxContextTokens()));
            return;
        }

        if (args.length >= 1 && "-r".equals(args[0])) {
            com.minion.core.agent.Session latest = store.latest();
            if (latest != null) {
                loop.restoreSession(latest);
                System.out.println("已恢复会话 " + latest.createdAt
                        + "（" + latest.messages.size() + " 条消息）");
            } else {
                System.out.println("没有历史会话，开始新会话");
            }
        }

        Repl repl = new Repl(config, llm, loop,
                new CommandDispatcher(loop, config, store, skillManager, renderer),
                skillManager, store);
        repl.start();
    }
}
```

（`buildSystemPreview` 是 systemTokens 的近似——正式值在 Repl/装配处用 SystemPromptBuilder.build(skills, empty) 的长度估算更准，实施时用 `TokenCounter.estimate(new SystemPromptBuilder(config).build(skills, java.util.Collections.emptyList()))` 替换。key 校验：`modelKey()` 返回空或占位时交互模式拒绝启动。）

minion.bat：

```bat
@echo off
java -jar "%~dp0target\minion-0.1.0.jar" %*
```

README.md：

```markdown
# minion

类 Claude Code 的命令行代码开发助手，对接 DeepSeek（thinking max），JDK8 + Maven 单模块。

## 构建

    mvn clean package

产物: target/minion-0.1.0.jar（含依赖）。Windows 可用 minion.bat 启动。

## 配置

首次运行会在 jar 同目录生成 config.properties（默认值来自 src/resource/config.properties）。
关键项: model.key（必填）、model.url、model.name、model.thinking/reasoningEffort、
model.maxContextTokens、context.compressThreshold/keepRecentMessages、work.dir、
project.md.path（项目介绍，自动拼入系统提示词）、skills.dir、session.dir、
confirm.skip、confirm.whitelist.tools/commands。

## 使用

    minion                # 交互模式
    minion -c "修复编译错误"   # 单次执行
    minion -r             # 恢复最近会话

/help 查看全部命令。/skills 列出技能，/skill <名> 加载（技能目录 skills/<名>/SKILL.md，
frontmatter: name/description/metadata）。

## 高危操作确认

Write 覆盖、Edit、危险命令（rm/del/format/taskkill 等）执行前会请求确认：
[回车/Y]确认  [N]拒绝  [W]确认+写入白名单（自动追加到 config.properties）  [A]本会话放行。
confirm.skip=true 可跳过所有确认。

## 上下文压缩

达 maxContextTokens×compressThreshold 自动压缩（大模型摘要历史，技能不受影响），
或 /compact 手动触发。token 统计优先取 API 返回的 usage，取不到时估算。
```

- [ ] **Step 4: 运行全部测试**

```bash
mvn -q test
```

Expected: 全部 PASS。然后 `mvn -q clean package` 确认打包。

- [ ] **Step 5: 手测清单**

```bash
java -jar target/minion-0.1.0.jar
```

手测：
1. 首启生成 config.properties（编辑 model.key 后重启）
2. `你好` → 流式回复 + 统计行（⏱/in/out/thinking/ctx/%）
3. `读取当前目录文件列表并告诉我` → 工具状态行 + 确认/执行 + 回复
4. `写一个文件 test.txt 内容 hello` → Write 确认提示（回车确认）
5. `rm -rf xxx`（Bash）→ 危险确认；按 W 后 config.properties 出现白名单
6. `/skills` `/skill` 加载 superpowers 格式技能
7. `/compact` `/tokens` `/model` `/clear`
8. 长会话触发自动压缩（压缩后技能指令仍在 system prompt）
9. `-c "任务"` 单次执行输出统计
10. 退出后 `/resume` 或 `-r` 恢复，对话上下文连续
11. Ctrl+C 中断长任务

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "feat: repl, main entry, launch script and readme"
```

---

## 自审记录

- **Spec 覆盖**：配置(§4)→T1/T2；消息模型(§6)→T3；工具(§8)→T5/T8/T9/T10/T12/T13；确认(§9)→T11；AgentLoop(§7)→T14；子agent→T15；压缩(§10)→T16/T17；持久化(§11)→T18；技能(§3)→T7/T19；UI/统计(§12)→T20/T21；错误处理(§13)→T6(异常映射)+T14(重试/中断)+各工具 error ToolResult；测试(§14)→每任务内嵌
- **占位符**：无 TBD/TODO；每步含完整代码
- **类型一致性**：`AgentUi` 接口在 T14 定义、T20 实现、T15 使用；`ConfirmUi.Decision` 在 T11 定义、T21 实现 ConfirmReader、T14 测试用 FakeConfirmUi；`ContextManager` 构造签名在 T16 定义、T17 使用一致；`AgentLoop` 多构造在 T17 引入、T21 Main 使用带 ContextManager 版本；`loop.messages()` 返回引用在压缩后需重新取值（T17 已注明）
- **自审内联修正（已入文档）**：
  - T14 `runUserTurn` 重写：assistant 回复（content+reasoningContent+toolCalls）入历史（回传硬性要求）；重试改为 `continue` 防重复追加用户消息；`interrupt()` 调 `llm.cancel()`（T6 给 LlmClient/DeepSeekClient 增加 cancel）；future.get 异常兜底
  - T14 测试修正：singleTurn 断言、roundLimit 消息数 7、interrupt 改用可阻塞 BlockingLlmClient
  - T16 `compress` 移除死代码（keptFrom/sumUpTo），改链遍历保留；补 Collections 导入
  - T8/T9 Write 测试断言顺序修正；T8 测试辅助方法 Paths→p
  - T15 子 agent 任务描述断言位置修正（user 消息而非 system）
  - T17 compactNow 测试 keepRecent 1
  - T20 StatsLine 百分比用 Math.round（测试期望 47%）
