# minion GUI 化改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 JavaFX 8 GUI（华丽深色科技感界面，仿 codebuddy）彻底替代 CLI 模式，支持多工作空间/多会话真并行，配置重构为 workspace.json + model.json + config.properties。

**Architecture:** 核心层（AgentLoop/工具/存储）几乎不动，新增 `com.minion.gui` 界面层 + `com.minion.gui.session` 会话外壳（多 AgentLoop 生命周期与 AgentUi 事件路由）。每会话一个 AgentLoop 实例+工作线程真并行；切走不打断，事件进缓冲，切回重放。

**Tech Stack:** JDK 8（Oracle JDK 8 自带 JavaFX）、JavaFX 8 原生控件 + CSS 深色主题、flexmark 0.64.8 Markdown 渲染、gson（现有）、JUnit4。

**Spec:** `docs/superpowers/specs/2026-08-11-gui-design.md`

## Global Constraints

- JDK 8 语法：禁止 var / List.of / String.isBlank / Files.readString / 方法引用陷阱（可用）
- 中文注释、中文 conventional commit（feat/fix/refactor/docs/test 前缀）
- gson 序列化：字段名 = JSON 键；反序列化目标用静态内部 Holder 类
- 测试 JUnit4 + TemporaryFolder；`mvn test` 必须全绿；每任务结束提交
- JavaFX 类（Node/Stage/Platform）**禁止**出现在单元测试中（headless 无 Toolkit）；纯逻辑抽类测试
- 运行时依赖 JDK 自带 jfxrt.jar（Oracle JDK 8 / 含 JavaFX 发行版），不打进 fat jar
- 新文件注释用中文，说明职责（现有代码风格）
- `Config` 裁剪后保留的 getter：skillsDir/readAllowOutside/confirmSkip/whitelistTools/whitelistCommands/browserPath/browserPort/browserUserDataDir/browserHeadless/browserTimeoutMs

## 文件结构

```
新增：
├── docs/superpowers/plans/2026-08-12-gui-implementation.md   （本计划）
├── src/main/java/com/minion/core/config/
│   ├── WorkspaceConfig.java    工作空间数据类（gson 字段=JSON 键）
│   ├── WorkspaceManager.java   workspace.json 读写/CRUD/重命名迁移
│   ├── ModelConfig.java        模型数据类
│   └── ModelManager.java       model.json 读写/CRUD/删最后拒绝
├── src/main/java/com/minion/core/agent/TitleGenerator.java   标题摘要纯逻辑
├── src/main/java/com/minion/gui/
│   ├── MinionApp.java          JavaFX Application 入口
│   ├── MainWindow.java         主窗口：顶部栏/左侧栏/右侧聊天/底部输入
│   ├── theme/theme.css         深色科技感主题
│   ├── session/
│   │   ├── EventList.java      会话 UI 事件缓冲（active 直通/inactive 缓存/重放）
│   │   ├── SessionController.java  AgentUi 实现 → EventList
│   │   ├── SessionHandle.java  会话句柄（loop/session/运行状态/标题）
│   │   └── SessionManager.java 会话外壳：装配/生命周期/send/stop/工作空间切换
│   ├── sidebar/SessionListView.java   左侧会话列表
│   ├── sidebar/WorkspaceListView.java 左侧工作空间列表
│   ├── chat/ChatView.java     消息区渲染（Markdown 块 → JavaFX 节点）
│   ├── chat/MarkdownRenderer.java  flexmark 解析 → Block 结构（纯函数可测）
│   ├── input/InputView.java   多行输入区 + 发送/终止按钮
│   └── dialog/
│       ├── ModelDialog.java   右上角 ⚙ 模型管理弹窗
│       └── GuiConfirmUi.java  ConfirmUi GUI 实现（弹窗确认）

修改：
├── pom.xml                    +jfxrt(system) +flexmark 3 模块
├── src/main/java/com/minion/Main.java   重写为 GUI 装配入口
├── src/main/java/com/minion/core/config/Config.java   裁剪 13 个 getter + jarDir() 公开
├── src/main/java/com/minion/core/agent/Session.java   +title；create 改签名
├── src/main/java/com/minion/core/agent/AgentLoop.java 构造注入 Session；setLlm；config 依赖清除
├── src/main/java/com/minion/core/agent/SystemPromptBuilder.java  构造注入 projectMdPath
├── src/main/java/com/minion/core/context/ContextManager.java    +maxTokens()
├── src/resource/config.properties    裁剪键
└── 测试：ConfigTest / SystemPromptBuilderTest / AgentLoopTest / AgentLoopCompactTest / SubAgentLoopTest 更新

删除（T15）：
├── src/main/java/com/minion/cli/   （Repl/CommandDispatcher/Renderer/StatsLine/StartupBanner/ConfirmReader）
├── src/main/java/com/minion/core/util/Ansi.java、ConsoleIo.java
└── src/test/java/com/minion/cli/ + AnsiTest + ConsoleIoTest
```

---

### Task 1: 依赖接入（jfxrt + flexmark）

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: 无
- Produces: `javafx.*`（Application/Stage/Scene/Node/Platform）与 `com.vladsch.flexmark.*` 可编译

- [ ] **Step 1: pom.xml 增加依赖**

在 `pom.xml` 的 `<dependencies>` 末尾（junit 之前）加入：

```xml
    <!-- JavaFX 8：编译期引用 JDK 自带 jfxrt.jar（运行时由 JDK ext 机制加载，不打入 fat jar） -->
    <dependency>
      <groupId>com.oracle</groupId>
      <artifactId>javafx</artifactId>
      <version>8.0</version>
      <scope>system</scope>
      <systemPath>${java.home}/lib/ext/jfxrt.jar</systemPath>
    </dependency>
    <!-- Markdown 渲染（0.64.x 为最后支持 JDK8 的版本线） -->
    <!-- 注意坐标：0.62.2 起核心 artifact 更名回 umbrella `flexmark`（flexmark-core 止于 0.60.x）；
         strikethrough 扩展 0.64.x 只有 gfm 变体（flexmark-ext-gfm-strikethrough） -->
    <dependency>
      <groupId>com.vladsch.flexmark</groupId>
      <artifactId>flexmark</artifactId>
      <version>0.64.8</version>
    </dependency>
    <dependency>
      <groupId>com.vladsch.flexmark</groupId>
      <artifactId>flexmark-ext-tables</artifactId>
      <version>0.64.8</version>
    </dependency>
    <dependency>
      <groupId>com.vladsch.flexmark</groupId>
      <artifactId>flexmark-ext-gfm-strikethrough</artifactId>
      <version>0.64.8</version>
    </dependency>
```

- [ ] **Step 2: 验证编译**

Run: `mvn compile`
Expected: BUILD SUCCESS（若报 `jfxrt.jar` 找不到，检查 `java -version` 是 Oracle JDK 8 且 `%JAVA_HOME%\jre\lib\ext\jfxrt.jar` 存在；maven 用 JAVA_HOME 的 java）

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: 接入 JavaFX 8 与 flexmark 依赖"
```

---

### Task 2: WorkspaceManager（workspace.json）

**Files:**
- Create: `src/main/java/com/minion/core/config/WorkspaceConfig.java`
- Create: `src/main/java/com/minion/core/config/WorkspaceManager.java`
- Test: `src/test/java/com/minion/core/config/WorkspaceManagerTest.java`

**Interfaces:**
- Consumes: 无（纯 JDK + gson）
- Produces:
  - `WorkspaceConfig{String workSpaceName; String workDir; String projectMd;}`（无参构造 + 3 参构造）
  - `WorkspaceManager.load(Path jarDir)` → 单例语义（每 jarDir 一个实例）
  - `List<WorkspaceConfig> list()`、`WorkspaceConfig get(String name)`、`WorkspaceConfig current()`
  - `boolean add(name, workDir, projectMd)`（重名/非法字符/空名 → false）
  - `boolean rename(oldName, newName)`（含 session 目录迁移）
  - `void update(name, workDir, projectMd)`、`boolean remove(String name)`（删 session 目录）
  - `void setCurrent(String name)`、`static Path sessionDirFor(Path jarDir, String name)` → `jarDir/session/<name>`

- [ ] **Step 1: 写失败测试** `WorkspaceManagerTest.java`

```java
package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class WorkspaceManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jarDir() throws IOException {
        return tmp.newFolder("jar").toPath();
    }

    /** 无文件时生成默认工作空间并落盘 */
    @Test
    public void load_createsDefaultWorkspace() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertEquals(1, m.list().size());
        assertEquals("default", m.list().get(0).workSpaceName);
        assertEquals(".", m.list().get(0).workDir);
        assertTrue(Files.exists(dir.resolve("workspace.json")));
    }

    /** 新增：重名/非法字符/空名拒绝 */
    @Test
    public void add_rejectsDuplicateAndIllegalNames() throws IOException {
        WorkspaceManager m = WorkspaceManager.load(jarDir());
        assertTrue(m.add("projA", "d:/a", "./project.md"));
        assertFalse(m.add("projA", "d:/b", ""));        // 重名
        assertFalse(m.add("", "d:/b", ""));             // 空名
        assertFalse(m.add("bad/name", "d:/b", ""));     // 含 / 非法字符
        assertFalse(m.add("bad:name", "d:/b", ""));     // 含 : 非法字符
        assertEquals(2, m.list().size());
    }

    /** 持久化：重载后列表与当前工作空间恢复 */
    @Test
    public void load_restoresPersistedState() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "./p.md");
        m.setCurrent("projA");
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals(2, m2.list().size());
        assertEquals("projA", m2.current().workSpaceName);
    }

    /** 重命名：列表更新 + session 目录迁移 */
    @Test
    public void rename_migratesSessionDir() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        Files.createDirectories(WorkspaceManager.sessionDirFor(dir, "projA"));
        Files.write(WorkspaceManager.sessionDirFor(dir, "projA").resolve("s1.json"),
                "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(m.rename("projA", "projB"));
        assertNull(m.get("projA"));
        assertNotNull(m.get("projB"));
        assertTrue(Files.exists(WorkspaceManager.sessionDirFor(dir, "projB").resolve("s1.json")));
        assertFalse(Files.exists(WorkspaceManager.sessionDirFor(dir, "projA")));
    }

    /** 重命名到已存在名拒绝，且不迁移 */
    @Test
    public void rename_rejectsExistingName() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        m.add("projB", "d:/b", "");
        assertFalse(m.rename("projA", "projB"));
        assertNotNull(m.get("projA"));
    }

    /** 删除：移除列表 + 删除 session 目录 */
    @Test
    public void remove_deletesSessionDir() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        Path sdir = WorkspaceManager.sessionDirFor(dir, "projA");
        Files.createDirectories(sdir);
        assertTrue(m.remove("projA"));
        assertNull(m.get("projA"));
        assertFalse(Files.exists(sdir));
    }

    /** 损坏文件：备份 .bak + 重建默认 */
    @Test
    public void load_corruptFileBacksUpAndRebuilds() throws IOException {
        Path dir = jarDir();
        Files.write(dir.resolve("workspace.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        WorkspaceManager m = WorkspaceManager.load(dir);
        assertEquals(1, m.list().size());
        assertTrue(Files.exists(dir.resolve("workspace.json.bak")));
    }

    /** current 指向的工作空间被删后回退到第一个 */
    @Test
    public void load_currentFallsBackWhenDeleted() throws IOException {
        Path dir = jarDir();
        WorkspaceManager m = WorkspaceManager.load(dir);
        m.add("projA", "d:/a", "");
        m.setCurrent("projA");
        m.remove("projA");
        WorkspaceManager m2 = WorkspaceManager.load(dir);
        assertEquals("default", m2.current().workSpaceName);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=WorkspaceManagerTest`
Expected: FAIL（编译错误：类不存在）

- [ ] **Step 3: 实现 WorkspaceConfig.java**

```java
package com.minion.core.config;

/** 工作空间配置项（workspace.json 条目，字段名 = JSON 键） */
public class WorkspaceConfig {

    public String workSpaceName;
    public String workDir;
    public String projectMd;

    public WorkspaceConfig() { }

    public WorkspaceConfig(String workSpaceName, String workDir, String projectMd) {
        this.workSpaceName = workSpaceName;
        this.workDir = workDir;
        this.projectMd = projectMd;
    }
}
```

- [ ] **Step 4: 实现 WorkspaceManager.java**

```java
package com.minion.core.config;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** jar 同目录 workspace.json；缺失生成默认，损坏备份 .bak 后重建 */
    public static WorkspaceManager load(Path jarDir) {
        WorkspaceManager m = new WorkspaceManager(jarDir.resolve(FILE_NAME));
        if (Files.exists(m.file)) {
            try {
                String json = new String(Files.readAllBytes(m.file), StandardCharsets.UTF_8);
                Holder h = new Gson().fromJson(json, Holder.class);
                if (h != null && h.workspaces != null && !h.workspaces.isEmpty()) {
                    m.workspaces.addAll(h.workspaces);
                    if (h.currentWorkspaceName != null) m.currentName = h.currentWorkspaceName;
                }
            } catch (Exception e) {
                backupCorrupt(m.file);
            }
        }
        if (m.workspaces.isEmpty()) {
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

    /** 名称合法性：非空、无非法字符、不重名 */
    public static boolean isValidName(String name, List<String> existing) {
        if (name == null || name.trim().isEmpty()) return false;
        if (name.matches(".*" + ILLEGAL_CHARS + ".*")) return false;
        return !existing.contains(name);
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
        if (!isValidName(name, names())) return false;
        workspaces.add(new WorkspaceConfig(name, workDir, projectMd));
        save();
        return true;
    }

    public boolean rename(String oldName, String newName) {
        if (get(oldName) == null) return false;
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
        try {
            Holder h = new Holder();
            h.workspaces = workspaces;
            h.currentWorkspaceName = currentName;
            Files.createDirectories(file.getParent());
            Files.write(file, new Gson().toJson(h).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[minion] 写入 workspace.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
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
```

注意：`rename` 中 session 目录迁移失败回滚时，若新目录已创建但 move 失败可能残留——move 是原子语义（同盘 rename），回滚后 `w.workSpaceName = oldName` 但旧目录已可能被移动。本实现 move 失败即未移动（Files.move 抛异常前不产生副作用），回滚正确。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=WorkspaceManagerTest`
Expected: PASS（9 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minion/core/config/WorkspaceConfig.java src/main/java/com/minion/core/config/WorkspaceManager.java src/test/java/com/minion/core/config/WorkspaceManagerTest.java
git commit -m "feat: WorkspaceManager 工作空间配置（workspace.json CRUD/重命名迁移/损坏备份）"
```

---

### Task 3: ModelManager（model.json）

**Files:**
- Create: `src/main/java/com/minion/core/config/ModelConfig.java`
- Create: `src/main/java/com/minion/core/config/ModelManager.java`
- Test: `src/test/java/com/minion/core/config/ModelManagerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ModelConfig{String displayName; String url; String apiKey; String modelName; String provider; boolean thinking; String reasoningEffort; int maxContextTokens; double compressThreshold; int keepRecentMessages;}`
  - `ModelManager.load(Path jarDir)`；`list()`、`get(String displayName)`、`current()`、`currentName()`
  - `boolean add(ModelConfig)`、`void update(ModelConfig)`、`boolean remove(String displayName)`（拒绝删最后一个）
  - `void setCurrent(String displayName)`
  - `ModelConfig createDefault()`（默认：displayName="deepseek-v4-flash"、url="https://api.deepseek.com/v1/chat/completions"、apiKey=""、modelName="deepseek-v4-flash"、provider="deepseek"、thinking=true、reasoningEffort="max"、maxContextTokens=900000、compressThreshold=0.8、keepRecentMessages=10）

- [ ] **Step 1: 写失败测试** `ModelManagerTest.java`

```java
package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ModelManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jarDir() throws IOException { return tmp.newFolder("jar").toPath(); }

    /** 无文件时生成默认模型并落盘 */
    @Test
    public void load_createsDefaultModel() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        assertEquals(1, m.list().size());
        ModelConfig c = m.current();
        assertEquals("deepseek-v4-flash", c.displayName);
        assertEquals(900000, c.maxContextTokens);
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
        assertEquals(2, m2.list().size());
        assertEquals("qwen-test", m2.currentName());
        assertEquals(8192, m2.current().maxContextTokens);
        assertEquals("qwen-max", m2.current().modelName);
    }

    /** 拒绝删除最后一个模型 */
    @Test
    public void remove_lastModelRejected() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        assertFalse(m.remove(m.currentName()));
        assertEquals(1, m.list().size());
    }

    /** 删除非最后模型成功，current 回退到剩余第一个 */
    @Test
    public void remove_otherModelOkAndCurrentFallsBack() throws IOException {
        Path dir = jarDir();
        ModelManager m = ModelManager.load(dir);
        ModelConfig q = new ModelConfig();
        q.displayName = "qwen-test"; q.url = "http://x"; q.modelName = "qwen-max";
        m.add(q);
        m.setCurrent("qwen-test");
        assertTrue(m.remove("qwen-test"));
        assertEquals(1, m.list().size());
        assertNotNull(m.current());
    }

    /** 损坏文件：备份 .bak + 重建默认 */
    @Test
    public void load_corruptFileBacksUpAndRebuilds() throws IOException {
        Path dir = jarDir();
        Files.write(dir.resolve("model.json"), "{broken".getBytes(StandardCharsets.UTF_8));
        ModelManager m = ModelManager.load(dir);
        assertEquals(1, m.list().size());
        assertTrue(Files.exists(dir.resolve("model.json.bak")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=ModelManagerTest`
Expected: FAIL（编译错误：类不存在）

- [ ] **Step 3: 实现 ModelConfig.java**

```java
package com.minion.core.config;

/** 模型配置项（model.json 条目，字段名 = JSON 键） */
public class ModelConfig {

    public String displayName;
    public String url;
    public String apiKey;
    public String modelName;
    public String provider;
    public boolean thinking;
    public String reasoningEffort;
    public int maxContextTokens;
    public double compressThreshold;
    public int keepRecentMessages;

    public ModelConfig() { }

    /** 深拷贝（编辑表单用，避免污染列表中对象） */
    public ModelConfig copy() {
        ModelConfig c = new ModelConfig();
        c.displayName = displayName;
        c.url = url;
        c.apiKey = apiKey;
        c.modelName = modelName;
        c.provider = provider;
        c.thinking = thinking;
        c.reasoningEffort = reasoningEffort;
        c.maxContextTokens = maxContextTokens;
        c.compressThreshold = compressThreshold;
        c.keepRecentMessages = keepRecentMessages;
        return c;
    }
}
```

- [ ] **Step 4: 实现 ModelManager.java**

```java
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
        if (Files.exists(m.file)) {
            try {
                String json = new String(Files.readAllBytes(m.file), StandardCharsets.UTF_8);
                Holder h = new Gson().fromJson(json, Holder.class);
                if (h != null && h.models != null && !h.models.isEmpty()) {
                    m.models.addAll(h.models);
                    if (h.currentModelName != null) m.currentName = h.currentModelName;
                }
            } catch (Exception e) {
                backupCorrupt(m.file);
            }
        }
        if (m.models.isEmpty()) {
            m.models.add(createDefault());
            m.currentName = m.models.get(0).displayName;
            m.save();
        }
        if (m.get(m.currentName) == null) m.currentName = m.models.get(0).displayName;
        return m;
    }

    public static ModelConfig createDefault() {
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

    public List<ModelConfig> list() {
        List<ModelConfig> copy = new ArrayList<ModelConfig>();
        for (ModelConfig c : models) copy.add(c);
        return copy;
    }

    public ModelConfig get(String displayName) {
        for (ModelConfig c : models) {
            if (c.displayName.equals(displayName)) return c;
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
        if (get(displayName) == null || models.size() <= 1) return false;
        models.remove(get(displayName));
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
        try {
            Holder h = new Holder();
            h.models = models;
            h.currentModelName = currentName;
            Files.createDirectories(file.getParent());
            Files.write(file, new Gson().toJson(h).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[minion] 写入 model.json 失败: " + e.getMessage());
        }
    }

    private static void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    private static class Holder {
        List<ModelConfig> models;
        String currentModelName;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=ModelManagerTest`
Expected: PASS（5 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minion/core/config/ModelConfig.java src/main/java/com/minion/core/config/ModelManager.java src/test/java/com/minion/core/config/ModelManagerTest.java
git commit -m "feat: ModelManager 模型配置（model.json CRUD/删最后拒绝/损坏备份）"
```

---

### Task 4: 核心层迁移（Session/AgentLoop/SystemPromptBuilder/Config 联动改造）

本任务是连锁改造，必须一次性完成才能编译通过：Session 加 title 与 create 签名、AgentLoop 构造注入 Session + setLlm + 清除 config 依赖、SystemPromptBuilder 注入 projectMdPath、ContextManager 加 maxTokens、Config 裁剪、Main 临时 stub、全部受影响测试更新。

**Files:**
- Modify: `src/main/java/com/minion/core/agent/Session.java`
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Modify: `src/main/java/com/minion/core/agent/SystemPromptBuilder.java`
- Modify: `src/main/java/com/minion/core/context/ContextManager.java`
- Modify: `src/main/java/com/minion/core/config/Config.java`
- Modify: `src/resource/config.properties`
- Modify: `src/main/java/com/minion/Main.java`
- Modify: `src/test/java/com/minion/core/config/ConfigTest.java`
- Modify: `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java`
- Modify: `src/test/java/com/minion/core/agent/AgentLoopTest.java`
- Modify: `src/test/java/com/minion/core/agent/AgentLoopCompactTest.java`
- Modify: `src/test/java/com/minion/core/agent/SubAgentLoopTest.java`

**Interfaces:**
- Consumes: Task 2/3 的 WorkspaceManager/ModelManager（Main 引用）
- Produces:
  - `Session.title` 字段；`Session.create(String workDir, String modelName)`
  - `AgentLoop(LlmClient llm, ToolRegistry registry, SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui, ContextManager contextManager, Workspace workspace, Session session)`（8 参，无 config）；`setLlm(LlmClient)`
  - `SystemPromptBuilder(String projectMdPath)`
  - `ContextManager.maxTokens()`
  - `Config.jarDir()`（public static）；删除 13 个 getter
  - `Main` 临时 stub

- [ ] **Step 1: 改 Session.java**

在字段区 `createdAt` 后加 title；create 签名改为参数化：

```java
    public String id;
    public String createdAt;
    /** 会话标题（GUI 显示用；新建会话由 LLM 摘要生成，恢复旧会话可能为 null） */
    public String title;
```

`create(Config config)` 整体替换为：

```java
    public static Session create(String workDir, String modelName) {
        Session s = new Session();
        s.id = generateId();
        s.createdAt = s.id;
        s.workDir = workDir;
        s.modelName = modelName;
        return s;
    }
```

`resume` 签名去掉未使用的 Config 参数（`resume(String id, String createdAt, String workDir, String modelName, List<Message> messages)`），并复制 title（调用方可能传入）：

```java
    public static Session resume(String id, String createdAt, String workDir,
                                 String modelName, String title, List<Message> messages) {
        Session s = new Session();
        s.id = id;
        s.createdAt = createdAt;
        s.workDir = workDir;
        s.modelName = modelName;
        s.title = title;
        s.messages = messages;
        return s;
    }
```

（`Session.resume` 现有调用方：`grep -rn "Session.resume" src/` 若有引用一并更新——预期无，仅签名兼容保留。）

- [ ] **Step 2: 改 AgentLoop.java**

a) 构造区：`private final LlmClient llm;` 改为非 final，并在构造后新增 setter（`interrupt()` 同位置附近）：

```java
    private volatile LlmClient llm;
```

```java
    /** 运行时切换模型（GUI 弹窗切换模型时调用；下轮请求生效） */
    public void setLlm(LlmClient llm) { this.llm = llm; }
```

b) 8 参构造整体替换（删除 6 参构造与默认 Workspace 逻辑）：

```java
    public AgentLoop(LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui,
                     ContextManager contextManager, Workspace workspace, Session session) {
        this.llm = llm;
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.confirmGate = confirmGate;
        this.ui = ui;
        this.contextManager = contextManager;
        this.workspace = workspace;
        this.session = session;
        // daemon 线程：main() 返回后 JVM 可正常退出（T21 REPL）
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "minion-tools");
            t.setDaemon(true);
            return t;
        });
        // T15：构造末尾自动注册 task 工具并注入默认子 agent 执行器
        registry.register(new com.minion.core.tools.TaskTool(this));
        setSubAgentRunner(args -> {
            String desc = args.has("description") ? args.get("description").getAsString() : "无描述";
            ui.onSubAgentStart(desc);
            return new SubAgentLoop(buildSystemPrompt(), desc, workspace.workDir(),
                    llm, registry, confirmGate, ui).run();
        });
    }
```

注意：`config` 字段删除（`private final Config config;` 及构造内 `this.config = config;`），`import com.minion.core.config.Config;` 一并删除。

c) `restoreSession` 补 title 复制（在 `session.modelName = s.modelName;` 后）：

```java
        session.title = s.title;
```

d) 压缩百分比计算：`runUserTurn` 中

```java
                        int pct = (int) (contextManager.estimate(session.messages) * 100
                                / config.maxContextTokens());
```

改为

```java
                        int pct = (int) (contextManager.estimate(session.messages) * 100
                                / contextManager.maxTokens());
```

- [ ] **Step 3: 改 SystemPromptBuilder.java**

构造与字段：

```java
    private final String projectMdPath;

    public SystemPromptBuilder(String projectMdPath) { this.projectMdPath = projectMdPath; }
```

`build` 内 `loadProjectMd(config.projectMdPath())` → `loadProjectMd(projectMdPath)`；删除 `import com.minion.core.config.Config;` 与 `config` 字段。

- [ ] **Step 4: 改 ContextManager.java**

先读该文件找到 `maxContextTokens` 字段名（构造参数），在类中新增：

```java
    /** 上下文窗口上限（AgentLoop 压缩百分比计算用） */
    public int maxTokens() { return maxContextTokens; }
```

（若字段名不同，以实际字段名为准。）

- [ ] **Step 5: 改 Config.java**

a) 删除 13 个 getter 与其上注释行：

```java
    public String modelUrl() ...
    public String modelKey() ...
    public String modelName() ...
    public String provider() ...
    public boolean thinkingEnabled() ...
    public String reasoningEffort() ...
    public int maxContextTokens() ...
    public double compressThreshold() ...
    public int keepRecentMessages() ...
    public String workDir() ...
    public String projectMdPath() ...
    public String sessionDir() ...
    public boolean uiColor() ...
```

（保留 `skillsDir()`。）

b) `private static Path jarDir()` 改为 public：

```java
    /** jar 所在目录（workspace.json/model.json/会话目录的基准） */
    public static Path jarDir() {
```

- [ ] **Step 6: 更新 config.properties 默认资源**

`src/resource/config.properties` 中删除整个 `# ===== 模型 =====` 段、`# ===== 上下文压缩 =====` 段、`work.dir`/`project.md.path`/`session.dir` 三行、`# ===== UI =====` 段与 `ui.color` 行。保留 `skills.dir`、`paths.read.allowOutside`、`confirm.*`、`browser.*`。同时把 `model.maxContextTokens` 相关说明删除。改后文件形如：

```properties
# ===== 路径 =====
work.dir 相关已迁移至 workspace.json（勿在此添加）
project.md.path 已迁移至 workspace.json
skills.dir=./skills
session.dir 已迁移（自动按工作空间名创建）
# 读逃逸：true 时 Read/Grep/Glob 可读取工作区外文件（写入工具仍受限）；false（默认）时越界读弹确认
paths.read.allowOutside=false

# ===== 高危操作确认 =====
confirm.skip=false
confirm.whitelist.tools=
confirm.whitelist.commands=

# ===== 浏览器工具(CDP 驱动 Chrome,需 Chrome 109+) =====
browser.path=
browser.port=9222
browser.userDataDir=./.minion/browser-profile
browser.headless=false
browser.timeoutMs=30000
```

（提示行注释均以 `#` 开头，Config.loadLines 会忽略。为稳妥直接删除整行，不写提示注释——删除 `work.dir`、`project.md.path`、`session.dir` 行即可。）

- [ ] **Step 7: Main.java 临时 stub**

```java
package com.minion;

public class Main {

    public static void main(String[] args) {
        System.err.println("[minion] GUI 界面开发中，请等待后续版本");
    }
}
```

（Task 7 恢复真实装配。）

- [ ] **Step 8: 更新 ConfigTest.java**

- `load_createsExternalFileWithDefaults`：删除 `modelUrl/modelName/reasoningEffort/maxContextTokens/compressThreshold/keepRecentMessages` 断言与 `contains("model.url")` 断言；保留 `confirmSkip`；`uiColor` 断言删除；改为断言 `skillsDir()`：

```java
    @Test
    public void load_createsExternalFileWithDefaults() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertFalse(c.confirmSkip());
        assertEquals("./skills", c.skillsDir());
        Path external = c.externalFile();
        assertTrue(Files.exists(external));
        assertTrue(new String(Files.readAllBytes(external), StandardCharsets.UTF_8).contains("skills.dir"));
    }
```

- `load_externalOverridesDefault`：改为验证保留的杂项：

```java
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
```

- 其余测试（appendWhitelist/browserDefaults/readAllowOutside）不动。

- [ ] **Step 9: 更新 SystemPromptBuilderTest.java**

构造调用 `new SystemPromptBuilder(config)` 全部改为 `new SystemPromptBuilder(projectMdPath)`。在该测试类顶部（`@Before` 或各测试内）定义：

```java
    private String projectMdPath() {
        return tmp.getRoot().getPath() + "/project.md";
    }
```

若测试中原本用 `Config.load(...)` 构造 config 仅为传给 SystemPromptBuilder，删除这些行；若 config 还用于别处（如 write project.md 到 config.projectMdPath() 指向的文件），改为写 `tmp.getRoot().getPath() + "/project.md"`。改完后各构造形如：

```java
        SystemPromptBuilder b = new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md");
```

先读文件按实际结构调整。

- [ ] **Step 10: 更新 AgentLoopTest / AgentLoopCompactTest / SubAgentLoopTest**

模式替换（先读各文件定位构造调用）：

a) `Session.create(config)` → `Session.create(tmp.getRoot().getPath(), "test-model")`
（测试类已有 `@Rule TemporaryFolder tmp` 的用 `tmp.getRoot().getPath()`；没有的加该 Rule。）

b) 每个 `new AgentLoop(...)` 调用改为新签名。原 8 参调用（含 cm/ws）去掉 config、追加 session；原 6 参调用（无 cm/ws）改为显式 ws + cm=null：

```java
        Workspace ws = new Workspace(tmp.getRoot().getPath());
        Session s = Session.create(tmp.getRoot().getPath(), "test-model");
        AgentLoop loop = new AgentLoop(llm, registry,
                new SystemPromptBuilder(tmp.getRoot().getPath() + "/project.md"),
                confirm, ui, cmOrNull, ws, s);
```

c) `import com.minion.core.config.Config;` 若不再使用则删除；`Config.load(...)` 若仅用于构造 AgentLoop 则删除。

d) AgentLoopTest 中已有 `ws` 局部变量的调用（如 L318/L328/L354 传 `null, ws`）仅替换构造签名：去掉 config、补 session 参数（ws 已存在）。

完成后：

- [ ] **Step 11: 全量测试**

Run: `mvn test`
Expected: 全绿（约 30 个测试类）。若个别测试仍引用已删 getter（如 `config.workDir()`），grep 修复：

```bash
grep -rn "config\.\(model\|workDir\|projectMdPath\|sessionDir\|maxContextTokens\|compressThreshold\|keepRecentMessages\|uiColor\|thinkingEnabled\|reasoningEffort\|provider\|modelUrl\|modelKey\|modelName\)()" src/test/java
```

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: 核心层迁移——Session 加 title/构造注入、AgentLoop 去 config 依赖、Config 裁剪为杂项配置"
```

---

### Task 5: TitleGenerator（新会话标题摘要）

**Files:**
- Create: `src/main/java/com/minion/core/agent/TitleGenerator.java`
- Test: `src/test/java/com/minion/core/agent/TitleGeneratorTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `TitleGenerator.MAX_TITLE_LEN=20`；`String buildPrompt(String firstUserMessage)`；`String clean(String raw)`；`String fallbackTitle(String firstUserMessage)`

- [ ] **Step 1: 写失败测试** `TitleGeneratorTest.java`

```java
package com.minion.core.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class TitleGeneratorTest {

    @Test
    public void buildPrompt_containsInstructionAndMessage() {
        String p = TitleGenerator.buildPrompt("帮我实现登录功能");
        assertTrue(p.contains("登录功能"));
        assertTrue(p.contains("20"));
    }

    @Test
    public void clean_stripsQuotesAndTrims() {
        assertEquals("修复乱码", TitleGenerator.clean("「修复乱码」"));
        assertEquals("修复乱码", TitleGenerator.clean("\"修复乱码\""));
        assertEquals("修复乱码", TitleGenerator.clean("  修复乱码  "));
        assertEquals("a b", TitleGenerator.clean("a\nb"));
    }

    @Test
    public void clean_truncatesOverLength() {
        String longTitle = "这是一个非常非常非常非常非常非常非常非常非常长的标题超过二十个字的长度限制";
        String c = TitleGenerator.clean(longTitle);
        assertTrue(c.length() <= TitleGenerator.MAX_TITLE_LEN);
    }

    @Test
    public void clean_emptyFallsBack() {
        assertEquals("新会话", TitleGenerator.clean(""));
        assertEquals("新会话", TitleGenerator.clean("   "));
        assertEquals("新会话", TitleGenerator.clean(null));
    }

    @Test
    public void fallbackTitle_truncatesAndDefaults() {
        assertEquals("新会话", TitleGenerator.fallbackTitle(""));
        assertEquals("新会话", TitleGenerator.fallbackTitle(null));
        assertEquals("修复中文乱码问题", TitleGenerator.fallbackTitle("修复中文乱码问题"));
        String longMsg = "这是一个超过三十个字的消息内容用来测试兜底标题的截断行为是否符合预期";
        assertTrue(TitleGenerator.fallbackTitle(longMsg).length() <= 30);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=TitleGeneratorTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 TitleGenerator.java**

```java
package com.minion.core.agent;

/** 新会话标题生成：LLM 摘要（completeChat 非流式）+ 本地兜底 */
public class TitleGenerator {

    public static final int MAX_TITLE_LEN = 20;

    /** 摘要请求 prompt：指令 + 用户首条消息（completeChat 的 system 侧） */
    public static String buildPrompt(String firstUserMessage) {
        return "为以下用户消息生成一个不超过 " + MAX_TITLE_LEN
                + " 字的会话标题。直接输出标题本身，不要引号、不要前缀、不要解释。\n\n"
                + firstUserMessage;
    }

    /** 摘要文本清洗：去引号/首尾空白/换行、超长截断；空则回退兜底标题 */
    public static String clean(String raw) {
        if (raw == null) return fallbackTitle("");
        String t = raw.trim().replace('\n', ' ').replace('\r', ' ');
        t = t.replaceAll("^[\"「『]+", "").replaceAll("[\"」』]+$", "");
        if (t.length() > MAX_TITLE_LEN) t = t.substring(0, MAX_TITLE_LEN);
        return t.isEmpty() ? fallbackTitle("") : t;
    }

    /** 兜底标题：用户消息前 30 字；空消息给「新会话」 */
    public static String fallbackTitle(String firstUserMessage) {
        String t = firstUserMessage == null ? "" : firstUserMessage.trim().replace('\n', ' ').replace('\r', ' ');
        if (t.length() > 30) t = t.substring(0, 30);
        return t.isEmpty() ? "新会话" : t;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=TitleGeneratorTest`
Expected: PASS（5 个测试全绿）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/agent/TitleGenerator.java src/test/java/com/minion/core/agent/TitleGeneratorTest.java
git commit -m "feat: TitleGenerator 新会话标题摘要（LLM 摘要清洗 + 兜底）"
```

---

### Task 6: EventList + SessionController（会话事件缓冲）

**Files:**
- Create: `src/main/java/com/minion/gui/session/EventList.java`
- Create: `src/main/java/com/minion/gui/session/SessionController.java`
- Test: `src/test/java/com/minion/gui/session/EventListTest.java`

**Interfaces:**
- Consumes: `AgentUi`（core/agent）
- Produces:
  - `EventList`：`add(Ev)`、`setActive(boolean, Listener)`（激活时重放全部存量）、`snapshot()`、`clear()`、`size()`；`Ev{Kind kind; String text; Object data;}`
  - `Kind`：USER_MESSAGE/THINKING/CONTENT/TOOL_CALL/TOOL_RESULT/SUB_AGENT_START/SUB_AGENT_DELTA/SUB_AGENT_DONE/STATS/ERROR/WARNING
  - `SessionController implements AgentUi`：`eventList()` getter；9 个 AgentUi 回调 → EventList.add（onToolCall 的 args 转 JSON 字符串存入 data）

- [ ] **Step 1: 写失败测试** `EventListTest.java`

```java
package com.minion.gui.session;

import com.minion.gui.session.EventList.Ev;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class EventListTest {

    private EventList newList() { return new EventList(); }

    /** inactive 时事件只入缓冲，不直通 */
    @Test
    public void inactive_buffersEvents() {
        EventList l = newList();
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.CONTENT, "b", null));
        assertEquals(2, l.size());
        assertTrue(seen.isEmpty());
    }

    /** 激活时重放全部存量，之后新事件直通 */
    @Test
    public void activate_replaysThenStreams() {
        EventList l = newList();
        l.setActive(false, null);
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.add(new Ev(EventList.Kind.THINKING, "t", null));
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        assertEquals(2, seen.size());
        l.add(new Ev(EventList.Kind.ERROR, "e", null));
        assertEquals(3, seen.size());
        assertEquals("e", seen.get(2).text);
        assertEquals(3, l.size());
    }

    /** 事件顺序保持（流式 delta 顺序敏感） */
    @Test
    public void preservesOrder() {
        EventList l = newList();
        l.setActive(false, null);
        for (int i = 0; i < 5; i++) l.add(new Ev(EventList.Kind.CONTENT, "d" + i, null));
        final List<Ev> seen = new ArrayList<Ev>();
        l.setActive(true, new EventList.Listener() {
            @Override public void onEvent(Ev e) { seen.add(e); }
        });
        for (int i = 0; i < 5; i++) assertEquals("d" + i, seen.get(i).text);
    }

    /** 清空后重放为空 */
    @Test
    public void clear_emptiesBuffer() {
        EventList l = newList();
        l.add(new Ev(EventList.Kind.CONTENT, "a", null));
        l.clear();
        assertEquals(0, l.size());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=EventListTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 EventList.java**

```java
package com.minion.gui.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话 UI 事件流：会话激活时事件直通监听器（FX 线程包装由监听器负责），
 * 未激活（切到其他会话/工作空间）时只入缓冲；切换回来 setActive(true) 重放全部存量。
 * 纯逻辑、无 JavaFX 依赖，可单测。
 */
public class EventList {

    public enum Kind {
        USER_MESSAGE, THINKING, CONTENT, TOOL_CALL, TOOL_RESULT,
        SUB_AGENT_START, SUB_AGENT_DELTA, SUB_AGENT_DONE, STATS, ERROR, WARNING
    }

    public static class Ev {
        public final Kind kind;
        public final String text;
        public final Object data;

        public Ev(Kind kind, String text, Object data) {
            this.kind = kind;
            this.text = text;
            this.data = data;
        }
    }

    public interface Listener {
        void onEvent(Ev e);
    }

    private final List<Ev> events = new ArrayList<Ev>();
    private volatile boolean active = false;
    private volatile Listener listener;

    /** 激活：重放存量后直通；去激活：listener 置空，事件只入缓冲 */
    public synchronized void setActive(boolean active, Listener listener) {
        this.active = active;
        this.listener = active ? listener : null;
        if (active && listener != null) {
            for (Ev e : events) listener.onEvent(e);
        }
    }

    public synchronized void add(Ev e) {
        events.add(e);
        if (active && listener != null) listener.onEvent(e);
    }

    public synchronized List<Ev> snapshot() { return new ArrayList<Ev>(events); }

    public synchronized void clear() { events.clear(); }

    public synchronized int size() { return events.size(); }
}
```

- [ ] **Step 4: 实现 SessionController.java**

```java
package com.minion.gui.session;

import com.google.gson.JsonObject;
import com.minion.core.agent.AgentUi;
import com.minion.core.tools.ToolResult;

/** AgentUi → EventList 路由：会话级事件缓冲 */
public class SessionController implements AgentUi {

    private final EventList events = new EventList();

    public EventList eventList() { return events; }

    @Override public void onUserMessage(String text) {
        events.add(new EventList.Ev(EventList.Kind.USER_MESSAGE, text, null));
    }
    @Override public void onThinking(String delta) {
        events.add(new EventList.Ev(EventList.Kind.THINKING, delta, null));
    }
    @Override public void onContent(String delta) {
        events.add(new EventList.Ev(EventList.Kind.CONTENT, delta, null));
    }
    @Override public void onToolCall(String name, JsonObject args) {
        events.add(new EventList.Ev(EventList.Kind.TOOL_CALL, name,
                args == null ? "{}" : args.toString()));
    }
    @Override public void onToolResult(String name, ToolResult result) {
        events.add(new EventList.Ev(EventList.Kind.TOOL_RESULT, name,
                result == null ? "" : (result.ok ? "ok" : "error:" + result.output)));
    }
    @Override public void onSubAgentStart(String description) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_START, description, null));
    }
    @Override public void onSubAgentDelta(String delta) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DELTA, delta, null));
    }
    @Override public void onSubAgentDone(String summary) {
        events.add(new EventList.Ev(EventList.Kind.SUB_AGENT_DONE, summary, null));
    }
    @Override public void onStatsLine(String line) {
        events.add(new EventList.Ev(EventList.Kind.STATS, line, null));
    }
    @Override public void onError(String message) {
        events.add(new EventList.Ev(EventList.Kind.ERROR, message, null));
    }
    @Override public void onWarning(String message) {
        events.add(new EventList.Ev(EventList.Kind.WARNING, message, null));
    }
}
```

（确认 `ToolResult` 有 `public boolean ok;` 与 `public String output;` 字段——若字段名不同以实际为准，先读 `ToolResult.java`。）

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=EventListTest`
Expected: PASS（4 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minion/gui/session/EventList.java src/main/java/com/minion/gui/session/SessionController.java src/test/java/com/minion/gui/session/EventListTest.java
git commit -m "feat: EventList 会话事件缓冲 + SessionController（AgentUi 路由，可单测）"
```

---

### Task 7: GUI 骨架（MinionApp + MainWindow + 深色主题 + 三区布局）

**Files:**
- Create: `src/main/java/com/minion/gui/MinionApp.java`
- Create: `src/main/java/com/minion/gui/MainWindow.java`
- Create: `src/resource/theme/theme.css`（注意：CSS 必须放 src/resource 才能进 classpath，不是 gui 包目录）
- Modify: `src/main/java/com/minion/Main.java`

**Interfaces:**
- Consumes: Task 2/3（WorkspaceManager/ModelManager）、Task 4（`Config.jarDir()`）
- Produces: `MinionApp.start(Config, WorkspaceManager, ModelManager)` 静态入口；`MainWindow(Stage)` 骨架

- [ ] **Step 1: 实现 theme.css**

```css
/* minion 深色科技感主题 */
.root {
    -fx-font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
    -fx-font-size: 13px;
    -fx-background-color: #0f1115;
}

/* 面板 */
.panel { -fx-background-color: #15181f; -fx-border-color: #232733; -fx-border-width: 0 1 0 0; }
.panel-dark { -fx-background-color: #101218; }
.card {
    -fx-background-color: #1a1d24;
    -fx-background-radius: 8;
    -fx-border-color: #232733;
    -fx-border-radius: 8;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0, 0, 2);
}

/* 顶部栏 */
.topbar {
    -fx-background-color: #12141a;
    -fx-border-color: transparent transparent #232733 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 8 12 8 12;
}
.topbar-title { -fx-text-fill: #e6e8ee; -fx-font-size: 14px; -fx-font-weight: bold; }
.topbar-model { -fx-text-fill: #8b949e; -fx-font-size: 12px; }

/* 按钮 */
.btn { -fx-background-radius: 6; -fx-padding: 6 14 6 14; -fx-cursor: hand; }
.btn-primary {
    -fx-background-color: linear-gradient(to bottom, #4f8cff, #3b6fe0);
    -fx-text-fill: white;
    -fx-font-weight: bold;
}
.btn-primary:hover { -fx-background-color: linear-gradient(to bottom, #5d98ff, #4f8cff); }
.btn-danger {
    -fx-background-color: linear-gradient(to bottom, #e0504f, #c93a39);
    -fx-text-fill: white;
    -fx-font-weight: bold;
}
.btn-danger:hover { -fx-background-color: linear-gradient(to bottom, #ef5f5e, #e0504f); }
.btn-ghost {
    -fx-background-color: transparent;
    -fx-text-fill: #8b949e;
    -fx-border-color: #232733;
    -fx-border-radius: 6;
}
.btn-ghost:hover { -fx-text-fill: #e6e8ee; -fx-border-color: #4f8cff; }

/* 输入区 */
.input-area {
    -fx-background-color: #1a1d24;
    -fx-background-radius: 8;
    -fx-border-color: #232733;
    -fx-border-radius: 8;
    -fx-text-fill: #e6e8ee;
    -fx-prompt-text-fill: #5a6270;
    -fx-control-inner-background: #1a1d24;
    -fx-highlight-fill: #3b6fe0;
}

/* 列表 */
.list-view { -fx-background-color: transparent; -fx-background-radius: 6; }
.list-view .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #c9cdd6;
    -fx-padding: 7 10 7 10;
}
.list-view .list-cell:hover { -fx-background-color: #1c2029; }
.list-view .list-cell:selected { -fx-background-color: #232a38; -fx-text-fill: #e6e8ee; }

/* 页签 */
.tab-pane .tab-header-area .tab-header-background { -fx-background-color: #12141a; }
.tab-pane .tab { -fx-background-color: #1a1d24; -fx-background-radius: 8 8 0 0; }
.tab-pane .tab:selected { -fx-background-color: #232a38; }
.tab-pane .tab-label { -fx-text-fill: #8b949e; }
.tab-pane .tab:selected .tab-label { -fx-text-fill: #e6e8ee; }

/* 消息 */
.msg-user {
    -fx-background-color: #23408f;
    -fx-background-radius: 10 10 2 10;
    -fx-padding: 10 14 10 14;
    -fx-text-fill: #eaf0ff;
}
.msg-assistant {
    -fx-background-color: #1a1d24;
    -fx-background-radius: 10 10 10 2;
    -fx-padding: 10 14 10 14;
    -fx-text-fill: #e6e8ee;
}
.msg-thinking { -fx-background-color: #13161c; -fx-background-radius: 6; -fx-text-fill: #6b7280; -fx-font-size: 12px; }
.code-block {
    -fx-background-color: #0d1117;
    -fx-background-radius: 6;
    -fx-border-color: #232733;
    -fx-border-radius: 6;
    -fx-padding: 8 12 8 12;
    -fx-font-family: "Consolas", monospace;
    -fx-font-size: 12px;
    -fx-text-fill: #c9d1d9;
}
.msg-error { -fx-background-color: #3d1d1d; -fx-background-radius: 6; -fx-text-fill: #f85149; }
.msg-warning { -fx-background-color: #3d2f14; -fx-background-radius: 6; -fx-text-fill: #e3b341; }

/* 状态点（呼吸动画） */
.status-dot { -fx-background-radius: 5; -fx-min-width: 8; -fx-min-height: 8; }
.status-dot-running {
    -fx-background-color: #3fb950;
    -fx-animation: pulse 1.2s infinite;
}

/* 滚动条 */
.scroll-pane { -fx-background-color: transparent; }
.scroll-pane > .viewport { -fx-background-color: transparent; }
.scroll-bar:vertical { -fx-background-color: transparent; }
.scroll-bar:vertical .thumb { -fx-background-color: #2a2f3a; -fx-background-radius: 4; }

.tooltip { -fx-background-color: #232a38; -fx-text-fill: #c9cdd6; -fx-background-radius: 4; }

/* 侧栏分区标题 */
.section-title { -fx-text-fill: #8b949e; -fx-font-size: 11px; -fx-font-weight: bold; }
```

- [ ] **Step 2: 实现 MinionApp.java**

```java
package com.minion.gui;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import javafx.application.Application;
import javafx.stage.Stage;

/** JavaFX 入口：静态配置注入 + 主窗口 */
public class MinionApp extends Application {

    private static Config config;
    private static WorkspaceManager workspaces;
    private static ModelManager models;

    /** Main 调用：装配配置后启动 GUI */
    public static void start(Config c, WorkspaceManager w, ModelManager m) {
        config = c;
        workspaces = w;
        models = m;
        launch();
    }

    @Override
    public void start(Stage stage) {
        new MainWindow(stage).show();
    }

    public static Config config() { return config; }
    public static WorkspaceManager workspaces() { return workspaces; }
    public static ModelManager models() { return models; }
}
```

- [ ] **Step 3: 实现 MainWindow.java（骨架：顶部栏 + 左栏容器 + 右侧占位）**

```java
package com.minion.gui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** 主窗口：顶部栏 / 左侧 1/5（上会话下工作空间）/ 右侧 4/5（消息区 + 输入区） */
public class MainWindow {

    private final Stage stage;

    public MainWindow(Stage stage) { this.stage = stage; }

    public void show() {
        stage.setTitle("minion");
        stage.setMinWidth(960);
        stage.setMinHeight(640);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");

        // 顶部栏：标识 | 当前模型 | 会话页签区 | ⚙
        HBox topbar = new HBox(10);
        topbar.getStyleClass().add("topbar");
        Label title = new Label("minion");
        title.getStyleClass().add("topbar-title");
        Label modelLabel = new Label("模型: " + MinionApp.models().currentName());
        modelLabel.getStyleClass().add("topbar-model");
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
        HBox.setHgrow(tabs, Priority.ALWAYS);
        Button gear = new Button("⚙");
        gear.getStyleClass().add("btn-ghost");
        gear.setOnAction(e -> { }); // Task 13 模型弹窗
        topbar.getChildren().addAll(title, modelLabel, tabs, gear);

        // 左侧 1/5：上会话管理 / 下工作空间管理（Task 9/12 填充）
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("panel");
        sidebar.setMinWidth(200);
        sidebar.setPrefWidth(220);
        Label sessionTitle = new Label("会话管理");
        sessionTitle.getStyleClass().add("section-title");
        Region sessionListPlaceholder = new Region(); // Task 9
        VBox.setVgrow(sessionListPlaceholder, Priority.ALWAYS);
        Label wsTitle = new Label("工作空间");
        wsTitle.getStyleClass().add("section-title");
        Region wsListPlaceholder = new Region();      // Task 12
        sidebar.getChildren().addAll(sessionTitle, sessionListPlaceholder, wsTitle, wsListPlaceholder);

        // 右侧 4/5：消息区 + 输入区占位（Task 10/11 填充）
        VBox right = new VBox(8);
        right.getStyleClass().add("panel-dark");
        Region chatPlaceholder = new Region();        // Task 10
        VBox.setVgrow(chatPlaceholder, Priority.ALWAYS);
        Region inputPlaceholder = new Region();       // Task 11
        right.getChildren().addAll(chatPlaceholder, inputPlaceholder);

        root.setTop(topbar);
        root.setLeft(sidebar);
        root.setCenter(right);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                getClass().getResource("/theme/theme.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }
}
```

- [ ] **Step 4: Main.java 恢复真实装配**

```java
package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.MinionApp;

public class Main {

    public static void main(String[] args) {
        Config config = Config.load();
        WorkspaceManager workspaces = WorkspaceManager.load(Config.jarDir());
        ModelManager models = ModelManager.load(Config.jarDir());
        MinionApp.start(config, workspaces, models);
    }
}
```

- [ ] **Step 5: 构建 + 手动验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS（`mvn test` 应仍全绿——JavaFX 类不进测试）

Run: `java -jar target/minion-0.1.0.jar`
Expected: 深色主题窗口：顶部栏（minion 标识 + 模型名 + ⚙）、左侧面板（会话管理/工作空间分区标题）、右侧深色区域。关闭窗口退出。

- [ ] **Step 6: Commit**

```bash
git add src/resource/theme/theme.css src/main/java/com/minion/gui/MinionApp.java src/main/java/com/minion/gui/MainWindow.java src/main/java/com/minion/Main.java
git commit -m "feat: GUI 骨架——JavaFX 主窗口三区布局与深色科技感主题"
```

---

### Task 8: SessionManager（会话外壳：装配、生命周期、send/stop）

**Files:**
- Create: `src/main/java/com/minion/gui/session/SessionHandle.java`
- Create: `src/main/java/com/minion/gui/session/SessionManager.java`
- Test: `src/test/java/com/minion/gui/session/SessionManagerTest.java`

**Interfaces:**
- Consumes: Task 2/3（WorkspaceManager/ModelManager）、Task 6（SessionController）、核心层（AgentLoop 新构造、Session.create、DeepSeekClient、ChromeLauncher/BrowserSession、SkillManager、各工具类、ConfirmGate/ConfirmUi、SessionStore、ContextManager/TokenCounter、SystemPromptBuilder 新构造）
- Produces:
  - `SessionHandle{String id; String workspaceName; String title; boolean titlePending; boolean running; SessionController controller; AgentLoop loop; Session session;}`
  - `SessionManager(ConfirmUi, Config, Path jarDir, WorkspaceManager, ModelManager, List<Skill>, BrowserSession)`（Config 为全局 skillsDir 等杂项）
  - `SessionHandle createSession(String titleOrNull)`（null → titlePending=true）
  - `void deleteSession(SessionHandle)`、`void renameSession(SessionHandle, String)`
  - `List<SessionHandle> sessions()`（当前工作空间）、`SessionHandle currentSession()`
  - `void activateSession(SessionHandle)`（EventList 激活切换）
  - `void send(SessionHandle, String)`（titlePending 先摘要 → 正式 runUserTurn）
  - `void stop(SessionHandle)`、`void switchWorkspace(String)`、`void shutdown()`
  - `LlmClient newLlm(ModelConfig)` 工厂
  - `interface Listener{ onSessionTitleChanged; onSessionRunningChanged; onSessionActivated; onWorkspaceChanged; onError; }`

**实现要点（对照 Main 现有装配——Task 4 后 Main 是 stub，用 `git show HEAD~1:src/main/java/com/minion/Main.java` 对照工具注册代码）：**

```java
package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;
import com.minion.core.agent.SystemPromptBuilder;
import com.minion.core.agent.TitleGenerator;
import com.minion.core.config.Config;
import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.context.ContextManager;
import com.minion.core.context.TokenCounter;
import com.minion.core.llm.DeepSeekClient;
import com.minion.core.llm.LlmClient;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.BashTool;
import com.minion.core.tools.EditTool;
import com.minion.core.tools.GlobTool;
import com.minion.core.tools.GrepTool;
import com.minion.core.tools.ReadTool;
import com.minion.core.tools.ToolRegistry;
import com.minion.core.tools.WebFetchTool;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.WriteTool;
import com.minion.core.tools.browser.BrowserDebugTool;
import com.minion.core.tools.browser.BrowserEvalTool;
import com.minion.core.tools.browser.BrowserScreenshotTool;
import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.BrowserTool;
import com.minion.core.tools.confirm.ConfirmGate;
import com.minion.core.tools.confirm.ConfirmUi;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话外壳：每会话一个 AgentLoop + 工作线程（真并行）；
 * 每工作空间一套上下文（ToolRegistry/Workspace/SessionStore/ConfirmGate）；
 * 切换不打断后台运行，EventList 事件缓冲由 UI 重放。
 */
public class SessionManager {

    public interface Listener {
        void onSessionTitleChanged(SessionHandle h);
        void onSessionRunningChanged(SessionHandle h, boolean running);
        void onSessionActivated(SessionHandle h);
        void onWorkspaceChanged();
        void onError(String message);
    }

    private final ConfirmUi confirmUi;
    private final Config config;
    private final Path jarDir;
    private final WorkspaceManager workspaces;
    private final ModelManager models;
    private final List<Skill> allSkills;
    private final BrowserSession browserSession; // 可为 null（测试）
    private final List<Listener> listeners = new ArrayList<Listener>();

    private final Map<String, WorkspaceCtx> ctxByName = new HashMap<String, WorkspaceCtx>();
    private String currentWorkspaceName;
    private SessionHandle currentSession;
    private final ExecutorService titlePool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minion-title");
        t.setDaemon(true);
        return t;
    });

    /** 每工作空间上下文 */
    private static class WorkspaceCtx {
        final String name;
        final ToolRegistry registry;
        final Workspace workspace;
        final SessionStore store;
        final ConfirmGate confirmGate;
        final ExecutorService pool;
        final List<SessionHandle> sessions = new ArrayList<SessionHandle>();

        WorkspaceCtx(String name, ToolRegistry registry, Workspace workspace,
                     SessionStore store, ConfirmGate confirmGate) {
            this.name = name;
            this.registry = registry;
            this.workspace = workspace;
            this.store = store;
            this.confirmGate = confirmGate;
            this.pool = Executors.newFixedThreadPool(1, r -> {
                Thread t = new Thread(r, "minion-session-" + name);
                t.setDaemon(true);
                return t;
            });
        }
    }

    public SessionManager(ConfirmUi confirmUi, Config config, Path jarDir,
                          WorkspaceManager workspaces, ModelManager models,
                          List<Skill> allSkills, BrowserSession browserSession) {
        this.confirmUi = confirmUi;
        this.config = config;
        this.jarDir = jarDir;
        this.workspaces = workspaces;
        this.models = models;
        this.allSkills = allSkills;
        this.browserSession = browserSession;
        loadWorkspaceContexts();
        this.currentWorkspaceName = workspaces.currentName();
    }

    public WorkspaceManager workspaces() { return workspaces; }
    public ModelManager models() { return models; }

    public void addListener(Listener l) { listeners.add(l); }

    private void notifyTitleChanged(SessionHandle h) {
        for (Listener l : listeners) l.onSessionTitleChanged(h);
    }
    private void notifyRunningChanged(SessionHandle h, boolean running) {
        for (Listener l : listeners) l.onSessionRunningChanged(h, running);
    }
    private void notifyActivated(SessionHandle h) {
        for (Listener l : listeners) l.onSessionActivated(h);
    }
    private void notifyWorkspaceChanged() {
        for (Listener l : listeners) l.onWorkspaceChanged();
    }
    private void notifyError(String msg) {
        for (Listener l : listeners) l.onError(msg);
    }

    /** 装配所有工作空间上下文（工具注册每空间独立，对照 Main 现有注册代码），并恢复历史会话 */
    private void loadWorkspaceContexts() {
        for (WorkspaceConfig w : workspaces.list()) {
            WorkspaceCtx ctx = buildCtx(w);
            ctxByName.put(w.workSpaceName, ctx);
            restoreSessions(ctx);
        }
    }

    /**
     * 恢复历史会话：store.list() 跳过损坏项（SessionStore 现有行为）；
     * 标题取落盘 session.title，titlePending=false（恢复会话已有标题或旧格式无标题→显示占位）。
     */
    private void restoreSessions(WorkspaceCtx ctx) {
        List<Session> restored;
        try {
            restored = ctx.store.list();
        } catch (Exception e) {
            notifyError("恢复会话失败: " + e.getMessage());
            return;
        }
        for (Session s : restored) {
            try {
                ModelConfig mc = models.current();
                LlmClient llm = newLlm(mc);
                ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                        mc.keepRecentMessages, llm,
                        TokenCounter.estimate(new SystemPromptBuilder(projectMdPath(ctx.name))
                                .build(allSkills, new ArrayList<Skill>())));
                SessionController controller = new SessionController();
                AgentLoop loop = new AgentLoop(llm, ctx.registry,
                        new SystemPromptBuilder(projectMdPath(ctx.name)),
                        ctx.confirmGate, controller, cm, ctx.workspace, s);
                loop.restoreSession(s); // 原地装载 + 半轮残留清洗 + cwd 恢复
                ctx.sessions.add(new SessionHandle(s.id, ctx.name, s, loop, controller,
                        s.title, false));
            } catch (Exception e) {
                notifyError("会话恢复失败（跳过）: " + e.getMessage());
            }
        }
    }

    private WorkspaceCtx buildCtx(WorkspaceConfig w) {
        ToolRegistry registry = new ToolRegistry();
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        Workspace workspace = new Workspace(w.workDir);
        ConfirmGate gate = new ConfirmGate(config, confirmUi);
        registry.register(new ReadTool(workspace, skillsDir, gate));
        registry.register(new WriteTool(workspace, skillsDir));
        registry.register(new EditTool(workspace, skillsDir));
        registry.register(new GlobTool(workspace, skillsDir, gate));
        registry.register(new GrepTool(workspace, skillsDir, gate));
        registry.register(new BashTool(workspace));
        registry.register(new WebFetchTool());
        if (browserSession != null) {
            registry.register(new BrowserTool(browserSession));
            registry.register(new BrowserEvalTool(browserSession));
            registry.register(new BrowserScreenshotTool(browserSession, workspace, skillsDir));
            registry.register(new BrowserDebugTool(browserSession));
        }
        return new WorkspaceCtx(w.workSpaceName, registry, workspace,
                new SessionStore(WorkspaceManager.sessionDirFor(jarDir, w.workSpaceName)), gate);
    }

    /** 创建会话（恢复会话传 title；新建传 null → titlePending） */
    public SessionHandle createSession(String title) {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        ModelConfig mc = models.current();
        Session s = Session.create(ctx.workspace.workDir(), mc.modelName);
        s.title = title;
        LlmClient llm = newLlm(mc);
        ContextManager cm = new ContextManager(mc.maxContextTokens, mc.compressThreshold,
                mc.keepRecentMessages, llm,
                TokenCounter.estimate(new SystemPromptBuilder(projectMdPath(currentWorkspaceName))
                        .build(allSkills, new ArrayList<Skill>())));
        SessionController controller = new SessionController();
        AgentLoop loop = new AgentLoop(llm, ctx.registry,
                new SystemPromptBuilder(projectMdPath(currentWorkspaceName)),
                ctx.confirmGate, controller, cm, ctx.workspace, s);
        SessionHandle h = new SessionHandle(s.id, currentWorkspaceName, s, loop, controller,
                title, title == null);
        ctx.sessions.add(h);
        try {
            ctx.store.save(s); // 立即落盘（含空会话）
        } catch (Exception e) {
            notifyError("会话落盘失败: " + e.getMessage());
        }
        return h;
    }

    private String projectMdPath(String workspaceName) {
        WorkspaceConfig c = workspaces.get(workspaceName);
        if (c == null || c.projectMd == null || c.projectMd.trim().isEmpty()) {
            return "./project.md";
        }
        return c.projectMd;
    }

    /** 新建 LlmClient（模型配置工厂；GUI 弹窗切模型也用它） */
    public LlmClient newLlm(ModelConfig mc) {
        return new DeepSeekClient(mc.url, mc.apiKey, mc.modelName,
                mc.thinking, mc.reasoningEffort, mc.provider);
    }

    public List<SessionHandle> sessions() {
        WorkspaceCtx ctx = ctxByName.get(currentWorkspaceName);
        return ctx == null ? new ArrayList<SessionHandle>() : new ArrayList<SessionHandle>(ctx.sessions);
    }

    public SessionHandle currentSession() { return currentSession; }

    public void renameSession(SessionHandle h, String newTitle) {
        h.title = newTitle;
        h.session.title = newTitle;
        persist(h);
        notifyTitleChanged(h);
    }

    public void deleteSession(SessionHandle h) {
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        if (h.running) stop(h);
        ctx.sessions.remove(h);
        try {
            ctx.store.delete(h.id);
        } catch (Exception e) {
            notifyError("删除会话文件失败: " + e.getMessage());
        }
        if (currentSession == h) currentSession = null;
    }

    public void activateSession(SessionHandle h) {
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentSession = h;
        h.controller.eventList().setActive(true, null);
        notifyActivated(h);
    }

    /** 工作空间切换（UI 层负责换绑视图；此处切上下文与激活态） */
    public void switchWorkspace(String name) {
        if (ctxByName.get(name) == null) return;
        if (currentSession != null) currentSession.controller.eventList().setActive(false, null);
        currentWorkspaceName = name;
        currentSession = null;
        workspaces.setCurrent(name);
        notifyWorkspaceChanged();
    }

    /** 发送：新会话（titlePending）先摘要生成标题，再跑正式任务 */
    public void send(final SessionHandle h, final String text) {
        final WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        ctx.pool.submit(new Runnable() {
            @Override public void run() {
                try {
                    if (h.titlePending) {
                        h.title = generateTitle(text);
                        h.titlePending = false;
                        h.session.title = h.title;
                        persist(h);
                        notifyTitleChanged(h);
                    }
                    h.running = true;
                    notifyRunningChanged(h, true);
                    try {
                        h.loop.runUserTurn(text);
                    } finally {
                        h.running = false;
                        notifyRunningChanged(h, false);
                    }
                } catch (Exception e) {
                    h.running = false;
                    notifyRunningChanged(h, false);
                    notifyError("任务执行异常: " + e.getMessage());
                }
            }
        });
    }

    /** 摘要标题：当前模型 completeChat + 10s 超时；失败回退 */
    private String generateTitle(String text) {
        ModelConfig mc = models.current();
        final LlmClient llm = newLlm(mc);
        Future<String> f = titlePool.submit(() -> {
            try {
                List<Message> msgs = new ArrayList<Message>();
                msgs.add(Message.user(text));
                return llm.completeChat(msgs, TitleGenerator.buildPrompt(text));
            } catch (Exception e) {
                return null;
            }
        });
        try {
            String raw = f.get(10, TimeUnit.SECONDS);
            return TitleGenerator.clean(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TitleGenerator.fallbackTitle(text);
        } catch (ExecutionException e) {
            return TitleGenerator.fallbackTitle(text);
        } catch (TimeoutException e) {
            f.cancel(true);
            return TitleGenerator.fallbackTitle(text);
        }
    }

    public void stop(SessionHandle h) {
        if (h != null && h.running) h.loop.interrupt();
    }

    private void persist(SessionHandle h) {
        WorkspaceCtx ctx = ctxByName.get(h.workspaceName);
        if (ctx == null) return;
        try {
            ctx.store.save(h.session);
        } catch (Exception e) {
            notifyError("会话落盘失败: " + e.getMessage());
        }
    }

    /** 关闭：终止所有运行中会话（窗口关闭时调用） */
    public void shutdown() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (h.running) h.loop.interrupt();
            }
        }
        titlePool.shutdownNow();
        for (WorkspaceCtx ctx : ctxByName.values()) {
            ctx.pool.shutdownNow();
        }
    }
}
```

- [ ] **Step 1: 实现 SessionHandle.java**

```java
package com.minion.gui.session;

import com.minion.core.agent.AgentLoop;
import com.minion.core.agent.Session;

/** 会话句柄：GUI 层持有的会话视图状态（后台运行实体为 AgentLoop） */
public class SessionHandle {

    public final String id;
    public final String workspaceName;
    public final Session session;
    public final AgentLoop loop;
    public final SessionController controller;

    /** 展示标题（新建会话由 LLM 摘要生成；恢复会话来自落盘） */
    public volatile String title;
    /** 新建会话尚未生成标题（发送时先摘要 → 再跑任务） */
    public volatile boolean titlePending;
    /** 是否正在后台运行（UI 徽标/终止按钮依据） */
    public volatile boolean running;

    public SessionHandle(String id, String workspaceName, Session session,
                         AgentLoop loop, SessionController controller, String title,
                         boolean titlePending) {
        this.id = id;
        this.workspaceName = workspaceName;
        this.session = session;
        this.loop = loop;
        this.controller = controller;
        this.title = title;
        this.titlePending = titlePending;
    }
}
```

- [ ] **Step 2: 写失败测试** `SessionManagerTest.java`（见下方；先跑确认编译失败）

```java
package com.minion.gui.session;

import com.minion.core.agent.Session;
import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.llm.Message;
import com.minion.core.skills.Skill;
import com.minion.core.storage.SessionStore;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.core.tools.confirm.ConfirmUi.Decision;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** SessionManager 纯逻辑测试：会话 CRUD、工作空间切换、标题回调（无 JavaFX/网络） */
public class SessionManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ConfirmUi FAKE_UI = new ConfirmUi() {
        @Override public Decision ask(String message) { return Decision.APPROVE; }
    };

    private SessionManager newManager() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar); // 默认值资源生成外部配置
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        return new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
    }

    /** 新建会话：无标题 → titlePending */
    @Test
    public void createSession_titlePendingUntilSend() throws Exception {
        SessionManager m = newManager();
        SessionHandle h = m.createSession(null);
        assertTrue(h.titlePending);
        assertNull(h.title);
        assertEquals(1, m.sessions().size());
    }

    /** 删除会话：从列表移除 */
    @Test
    public void deleteSession_removesFromList() throws Exception {
        SessionManager m = newManager();
        SessionHandle h = m.createSession(null);
        m.deleteSession(h);
        assertEquals(0, m.sessions().size());
    }

    /** 重命名：标题更新 + 回调通知 */
    @Test
    public void renameSession_notifiesListener() throws Exception {
        SessionManager m = newManager();
        final List<String> titles = new ArrayList<String>();
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { titles.add(h.title); }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { fail("不应有错误: " + message); }
        });
        SessionHandle h = m.createSession(null);
        m.renameSession(h, "修复登录");
        assertEquals("修复登录", h.title);
        assertEquals("修复登录", titles.get(titles.size() - 1));
    }

    /** 工作空间切换：当前工作空间变化，会话列表按空间隔离 */
    @Test
    public void switchWorkspace_changesContext() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ws.add("projA", tmp.newFolder("a").getPath(), "");
        ws.add("projB", tmp.newFolder("b").getPath(), "");
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("projA");
        SessionHandle h = m.createSession(null);
        assertEquals(1, m.sessions().size());
        m.switchWorkspace("projB");
        assertEquals(0, m.sessions().size()); // 每个工作空间独立会话集
        assertEquals("projB", m.workspaces().currentName());
    }

    /** 激活会话：当前会话切换 */
    @Test
    public void activateSession_flipsCurrent() throws Exception {
        SessionManager m = newManager();
        final List<SessionHandle> activated = new ArrayList<SessionHandle>();
        m.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) { }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) { }
            @Override public void onSessionActivated(SessionHandle h) { activated.add(h); }
            @Override public void onWorkspaceChanged() { }
            @Override public void onError(String message) { }
        });
        SessionHandle h1 = m.createSession(null);
        SessionHandle h2 = m.createSession(null);
        m.activateSession(h1);
        m.activateSession(h2);
        assertEquals(2, activated.size());
        assertEquals(h2, m.currentSession());
    }

    /** 会话文件位于 jarDir/session/<工作空间名>/ 下 */
    @Test
    public void sessionFilesInWorkspaceDir() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        SessionHandle h = m.createSession(null);
        Path f = WorkspaceManager.sessionDirFor(jar, ws.currentName()).resolve(h.id + ".json");
        assertTrue(Files.exists(f));
    }

    /** 启动恢复历史会话：落盘会话进入列表，标题回填且非 titlePending */
    @Test
    public void restore_loadsSessionsFromStore() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        // 预写一个会话文件（模拟上次运行落盘）
        Path dir = WorkspaceManager.sessionDirFor(jar, ws.currentName());
        Files.createDirectories(dir);
        Session s = Session.create(tmp.newFolder("w").getPath(), "deepseek-v4-flash");
        s.title = "已保存的会话";
        s.messages.add(Message.user("你好"));
        new SessionStore(dir).save(s);

        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        assertEquals(1, m.sessions().size());
        assertEquals("已保存的会话", m.sessions().get(0).title);
        assertFalse(m.sessions().get(0).titlePending);
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q test -Dtest=SessionManagerTest`
Expected: FAIL（类不存在）

- [ ] **Step 4: 实现 SessionManager.java（上方案例）**

按上面给出的完整代码创建 `SessionManager.java`（对照 `git show HEAD~1:src/main/java/com/minion/Main.java` 核对工具构造签名）。

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=SessionManagerTest`
Expected: PASS（6 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minion/gui/session/SessionHandle.java src/main/java/com/minion/gui/session/SessionManager.java src/test/java/com/minion/gui/session/SessionManagerTest.java
git commit -m "feat: SessionManager 会话外壳——多 AgentLoop 真并行、工作空间上下文、摘要标题、send/stop"
```

---

### Task 9: 会话列表 + 页签 UI

**Files:**
- Create: `src/main/java/com/minion/gui/sidebar/SessionListView.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`
- Modify: `src/main/java/com/minion/gui/MinionApp.java`（静态注入加 SessionManager，start 变 4 参）
- Modify: `src/main/java/com/minion/Main.java`（临时装配传 SessionManager；Task 15 替换最终版）

**Interfaces:**
- Consumes: Task 7 MainWindow、Task 8 SessionManager/SessionHandle/Listener
- Produces: 左侧会话列表（标题+状态点+右键菜单）+ 顶部页签（标题+状态点+关闭=删除）

- [ ] **Step 1: 实现 SessionListView.java**

```java
package com.minion.gui.sidebar;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/** 左侧会话列表：标题 + 运行状态点 + 右键菜单（重命名/删除）；单击切换 */
public class SessionListView extends ListView<SessionHandle> {

    private final SessionManager manager;

    public SessionListView(final SessionManager manager) {
        this.manager = manager;
        setCellFactory(v -> new SessionCell());
        setOnMouseClicked(e -> {
            SessionHandle h = getSelectionModel().getSelectedItem();
            if (h != null && e.getClickCount() == 1) manager.activateSession(h);
        });
    }

    public void refresh() {
        Platform.runLater(() -> getItems().setAll(manager.sessions()));
    }

    private class SessionCell extends ListCell<SessionHandle> {
        @Override protected void updateItem(SessionHandle h, boolean empty) {
            super.updateItem(h, empty);
            if (empty || h == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String label = h.title == null ? "(新会话)" : h.title;
            Circle dot = new Circle(4);
            dot.getStyleClass().add("status-dot");
            if (h.running) dot.getStyleClass().add("status-dot-running");
            HBox box = new HBox(6);
            javafx.scene.control.Label name = new javafx.scene.control.Label(label);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(dot, name, spacer);
            setGraphic(box);

            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem("重命名");
            rename.setOnAction(e -> {
                TextInputDialog d = new TextInputDialog(h.title);
                d.setTitle("重命名会话");
                d.setHeaderText("输入新标题");
                d.showAndWait().ifPresent(t -> manager.renameSession(h, t));
            });
            MenuItem del = new MenuItem("删除");
            del.setOnAction(e -> {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "删除会话「" + (h.title == null ? h.id : h.title) + "」？",
                        ButtonType.OK, ButtonType.CANCEL);
                a.setTitle("删除会话");
                a.showAndWait().ifPresent(bt -> {
                    if (bt == ButtonType.OK) manager.deleteSession(h);
                });
            });
            menu.getItems().addAll(rename, del);
            setContextMenu(menu);
        }
    }
}
```

- [ ] **Step 2: MainWindow 接线（Task 7 骨架改造）**

- 字段：`private SessionManager manager; private TabPane tabs; private ChatView chatView; private InputView inputView;`（chatView/inputView 在 Task 10/11 创建，本任务先定义字段与页签逻辑）
- 构造：`public MainWindow(Stage stage, SessionManager manager)`
- MinionApp 同步改为 4 参静态注入（MainWindow 需要真实 SessionManager）：

```java
    private static SessionManager sessionManager;

    public static void start(Config c, WorkspaceManager w, ModelManager m, SessionManager s) {
        config = c;
        workspaces = w;
        models = m;
        sessionManager = s;
        launch();
    }

    public static SessionManager sessionManager() { return sessionManager; }
```

- `MinionApp.start` 内 `new MainWindow(stage)` 改为 `new MainWindow(stage, MinionApp.sessionManager())`
- Main.java（Task 7 stub 更新为临时装配；确认交互临时放行——GuiConfirmUi 在 Task 14 才建，Task 15 替换最终版）：

```java
package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.skills.Skill;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.MinionApp;
import com.minion.gui.session.SessionManager;

import java.util.ArrayList;

public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        java.nio.file.Path jarDir = Config.jarDir();
        WorkspaceManager workspaces = WorkspaceManager.load(jarDir);
        ModelManager models = ModelManager.load(jarDir);
        // 临时装配（Task 15 替换为最终版：GuiConfirmUi + 技能扫描 + 浏览器）
        ConfirmUi confirmUi = new ConfirmUi() {
            @Override public ConfirmUi.Decision ask(String message) { return ConfirmUi.Decision.APPROVE; }
        };
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, new ArrayList<Skill>(), null);
        MinionApp.start(config, workspaces, models, manager);
    }
}
```
- `show()` 中：
  - `MinionApp.models()` 从构造注入的 `manager.models()` 读取
  - 左侧占位替换：

```java
        SessionListView sessionList = new SessionListView(manager);
        VBox.setVgrow(sessionList, Priority.ALWAYS);
        Button newSession = new Button("＋ 新建会话");
        newSession.getStyleClass().add("btn-ghost");
        newSession.setMaxWidth(Double.MAX_VALUE);
        newSession.setOnAction(e -> onNewSession());
        VBox sessionBox = new VBox(6);
        sessionBox.getChildren().addAll(newSession, sessionList);
        VBox.setVgrow(sessionBox, Priority.ALWAYS);
        sidebar.getChildren().setAll(sessionTitle, sessionBox, wsTitle, wsListPlaceholder);
```

  - 注册 manager 监听（Tab 维护）：

```java
        manager.addListener(new SessionManager.Listener() {
            @Override public void onSessionTitleChanged(SessionHandle h) {
                Platform.runLater(() -> updateTab(h));
            }
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                Platform.runLater(() -> updateTab(h));
                // inputView.onRunningChanged 接线在 Task 11
            }
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> selectTab(h));
                // chatView 重建绑定在 Task 10 Step 8 追加
            }
            @Override public void onWorkspaceChanged() {
                Platform.runLater(() -> { sessionList.refresh(); rebuildTabs(); });
            }
            @Override public void onError(String message) {
                // 消息区未建（Task 10 前）→ 先落控制台；Task 10 改为 chatView 横幅
                System.err.println("[minion] " + message);
            }
        });
```

  - 页签方法：

```java
    private void onNewSession() {
        SessionHandle h = manager.createSession(null); // titlePending，无页签
        manager.activateSession(h);
        // 消息区/输入区绑定由 Task 10/11 在 onSessionActivated 中接线
    }

    private void updateTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h.id.equals(t.getUserData())) {
                t.setText(h.title == null ? "(新会话)" : h.title);
                t.setGraphic(runningIndicator(h));
                return;
            }
        }
        if (h.title != null) addTab(h); // 标题生成后才建页签
    }

    private void addTab(SessionHandle h) {
        if (h.title == null) return;
        Tab t = new Tab(h.title);
        t.setUserData(h.id);
        t.setGraphic(runningIndicator(h));
        t.setClosable(true);
        t.setOnCloseRequest(e -> {
            e.consume();
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除会话「" + h.title + "」？", ButtonType.OK, ButtonType.CANCEL);
            a.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    manager.deleteSession(h);
                    tabs.getTabs().remove(t);
                }
            });
        });
        tabs.getTabs().add(t);
        tabs.getSelectionModel().select(t);
    }

    private void selectTab(SessionHandle h) {
        for (Tab t : tabs.getTabs()) {
            if (h.id.equals(t.getUserData())) {
                tabs.getSelectionModel().select(t);
                return;
            }
        }
    }

    private javafx.scene.Node runningIndicator(SessionHandle h) {
        Circle dot = new Circle(4);
        dot.getStyleClass().add("status-dot");
        if (h.running) dot.getStyleClass().add("status-dot-running");
        return dot;
    }

    private void rebuildTabs() {
        tabs.getTabs().clear();
        for (SessionHandle h : manager.sessions()) {
            if (h.title != null) addTab(h);
        }
    }
```

- [ ] **Step 3: 构建 + 手动验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

Run: `java -jar target/minion-0.1.0.jar`
Expected: 左侧出现"新建会话"按钮；点击新建 → 列表出现"(新会话)"项并激活；右键有重命名/删除菜单；页签区在标题生成前为空。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/minion/gui/sidebar/SessionListView.java src/main/java/com/minion/gui/MainWindow.java
git commit -m "feat: 会话列表与页签 UI（新建/重命名/删除/状态点）"
```

---

### Task 10: ChatView + MarkdownRenderer（消息区渲染）

**Files:**
- Create: `src/main/java/com/minion/gui/chat/MarkdownRenderer.java`
- Create: `src/main/java/com/minion/gui/chat/BlockNodeFactory.java`
- Create: `src/main/java/com/minion/gui/chat/ChatView.java`
- Test: `src/test/java/com/minion/gui/chat/MarkdownRendererTest.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`

**Interfaces:**
- Consumes: flexmark 0.64.8、EventList（Task 6）
- Produces:
  - `MarkdownRenderer.parse(String md)` → `List<Block>`（纯函数可测）；`Block{Type; text; lang; level; spans; items; rows}`；`Span{text; style}`；`TableRowData{cells; header}`
  - `ChatView(EventList, SessionHandle)`：`bind(boolean active)`、`clear()`、`appendSystemLine(String)`（错误横幅）
  - `BlockNodeFactory.create(Block)` → JavaFX Node

- [ ] **Step 1: 写失败测试** `MarkdownRendererTest.java`

```java
package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MarkdownRendererTest {

    @Test
    public void parse_plainText() {
        List<Block> blocks = MarkdownRenderer.parse("hello world");
        assertEquals(1, blocks.size());
        assertEquals(Block.Type.PARAGRAPH, blocks.get(0).type);
        assertEquals("hello world", blocks.get(0).text);
    }

    @Test
    public void parse_headingAndCodeFence() {
        List<Block> blocks = MarkdownRenderer.parse("# 标题\n\n```java\nint a = 1;\n```");
        assertEquals(2, blocks.size());
        assertEquals(Block.Type.HEADING, blocks.get(0).type);
        assertEquals(1, blocks.get(0).level);
        assertEquals("标题", blocks.get(0).text);
        assertEquals(Block.Type.CODE, blocks.get(1).type);
        assertEquals("java", blocks.get(1).lang);
        assertTrue(blocks.get(1).text.contains("int a = 1;"));
    }

    @Test
    public void parse_inlineMarkup() {
        List<Block> blocks = MarkdownRenderer.parse("**加粗** 和 `行内码`");
        assertEquals(1, blocks.size());
        assertEquals(3, blocks.get(0).spans.size());
        assertEquals("加粗", blocks.get(0).spans.get(0).text);
        assertEquals("bold", blocks.get(0).spans.get(0).style);
        assertEquals("行内码", blocks.get(0).spans.get(2).text);
        assertTrue(blocks.get(0).spans.get(2).style.contains("code"));
    }

    @Test
    public void parse_unorderedList() {
        List<Block> blocks = MarkdownRenderer.parse("- 甲\n- 乙");
        assertEquals(1, blocks.size());
        assertEquals(Block.Type.LIST, blocks.get(0).type);
        assertEquals(2, blocks.get(0).items.size());
        assertEquals("甲", blocks.get(0).items.get(0).text);
    }

    @Test
    public void parse_strikethrough() {
        List<Block> blocks = MarkdownRenderer.parse("~~删除~~");
        assertEquals(1, blocks.size());
        assertEquals("删除", blocks.get(0).spans.get(0).text);
        assertTrue(blocks.get(0).spans.get(0).style.contains("strike"));
    }

    @Test
    public void parse_empty() {
        List<Block> blocks = MarkdownRenderer.parse("");
        assertEquals(0, blocks.size());
    }
}
```

（表格解析依赖 flexmark TablesExtension，0.64 的 TableBlock 类路径 `com.vladsch.flexmark.ast.TableBlock` + `com.vladsch.flexmark.ext.tables.TablesExtension`——若编译/断言异常，按实际 jar 调整 import；表格测试在实现稳定后补 `parse_table` 断言，本任务先保证上述 6 个通过。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=MarkdownRendererTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 MarkdownRenderer.java**

```java
package com.minion.gui.chat;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Document;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown → 块结构（纯函数，不依赖 JavaFX，可单测）。
 * UI 层把 Block 转 JavaFX 节点（BlockNodeFactory）。
 */
public class MarkdownRenderer {

    public static class Span {
        public final String text;
        public final String style; // plain / bold / italic / code / strike 组合
        public Span(String text, String style) {
            this.text = text;
            this.style = style;
        }
    }

    public static class TableRowData {
        public final List<String> cells = new ArrayList<String>();
        public final boolean header;
        public TableRowData(boolean header) { this.header = header; }
    }

    public static class Block {
        public enum Type { PARAGRAPH, HEADING, CODE, LIST, QUOTE, TABLE, RULE }

        public final Type type;
        public String text;
        public String lang;
        public int level;
        public List<Span> spans = new ArrayList<Span>();
        public List<Block> items = new ArrayList<Block>();
        public List<TableRowData> rows = new ArrayList<TableRowData>();

        public Block(Type type) { this.type = type; }
    }

    /** 解析 Markdown → 块列表（空/空白文本 → 空列表） */
    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<Block>();
        if (md == null || md.trim().isEmpty()) return out;
        Parser parser = Parser.builder()
                .extensions(Arrays.asList(
                        TablesExtension.create(),
                        StrikethroughExtension.create()))
                .build();
        Document doc = parser.parse(md);
        for (Node n : doc.getChildren()) {
            convert(n, out);
        }
        return out;
    }

    private static void convert(Node n, List<Block> out) {
        if (n instanceof Heading) {
            Heading h = (Heading) n;
            Block b = new Block(Block.Type.HEADING);
            b.level = h.getLevel();
            b.text = collectText(h);
            out.add(b);
        } else if (n instanceof Paragraph) {
            Block b = new Block(Block.Type.PARAGRAPH);
            b.spans = collectSpans((Paragraph) n);
            b.text = collectText(n);
            out.add(b);
        } else if (n instanceof FencedCodeBlock) {
            FencedCodeBlock f = (FencedCodeBlock) n;
            Block b = new Block(Block.Type.CODE);
            b.lang = f.getInfo() == null ? "" : f.getInfo().toString().trim();
            b.text = f.getContentChars().toString().replaceAll("\\n$", "");
            out.add(b);
        } else if (n instanceof BulletList || n instanceof OrderedList) {
            Block b = new Block(Block.Type.LIST);
            for (Node child : n.getChildren()) {
                if (child instanceof ListItem) {
                    Block item = new Block(Block.Type.PARAGRAPH);
                    item.text = collectText(child);
                    item.spans = collectSpans(child);
                    b.items.add(item);
                }
            }
            out.add(b);
        } else if (n instanceof BlockQuote) {
            Block b = new Block(Block.Type.QUOTE);
            b.text = collectText(n);
            out.add(b);
        } else if (n instanceof ThematicBreak) {
            out.add(new Block(Block.Type.RULE));
        } else {
            Block b = new Block(Block.Type.PARAGRAPH);
            b.text = collectText(n);
            out.add(b);
        }
    }

    private static List<Span> collectSpans(Node node) {
        List<Span> spans = new ArrayList<Span>();
        walkInline(node, "", spans);
        return spans;
    }

    /** 递归收集行内富文本 span；style 继承当前样式（可组合：boldcode 等） */
    private static void walkInline(Node node, String style, List<Span> spans) {
        if (node instanceof Text) {
            String t = ((Text) node).getChars().toString();
            if (!t.isEmpty()) spans.add(new Span(t, style));
        } else if (node instanceof Code) {
            String t = stripCodeMarks(((Code) node).getChars().toString());
            if (!t.isEmpty()) spans.add(new Span(t, style + "code"));
        } else if (node instanceof StrongEmphasis) {
            walkInline(node, style + "bold", spans);
        } else if (node instanceof Emphasis) {
            walkInline(node, style + "italic", spans);
        } else if (node instanceof Strikethrough) {
            walkInline(node, style + "strike", spans);
        } else if (node instanceof SoftLineBreak) {
            spans.add(new Span("\n", style));
        } else {
            for (Node child : node.getChildren()) walkInline(child, style, spans);
        }
    }

    private static String stripCodeMarks(String s) {
        if (s.startsWith("`")) s = s.substring(1);
        if (s.endsWith("`") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String collectText(Node node) {
        StringBuilder sb = new StringBuilder();
        appendText(node, sb);
        return sb.toString().trim();
    }

    private static void appendText(Node node, StringBuilder sb) {
        if (node instanceof Text) {
            sb.append(((Text) node).getChars().toString());
        } else {
            for (Node child : node.getChildren()) appendText(child, sb);
        }
    }
}
```

（表格解析后续补：`import com.vladsch.flexmark.ast.TableBlock/TableHead/TableBody/TableRow/TableCell` 并加 convert 分支——首次实现若这些类路径与 flexmark 0.64.8 不符，用 `jar tf ~/.m2/repository/com/vladsch/flexmark/flexmark-core/0.64.8/flexmark-core-0.64.8.jar | grep Table` 确认实际类名后补上。**表格列为"可后补"项：核心断言（6 个测试）先行通过，表格功能在 Task 10 Step 5 后补实现并补测试。**）

- [ ] **Step 4: 运行测试，修正 flexmark API 细节**

Run: `mvn -q test -Dtest=MarkdownRendererTest`
Expected: 修正 import/API 后 PASS（6 个测试全绿）。测试断言语义不可改；实现可按实际 API 调整（如 `getInfo()`、`getContentChars()` 的类型差异）。

- [ ] **Step 5: 补表格支持（TableBlock 分支 + parse_table 测试）**

确认 flexmark 0.64.8 表格类实际位置后，在 `convert` 中加入：

```java
        } else if (n instanceof TableBlock) {
            Block b = new Block(Block.Type.TABLE);
            for (Node section : n.getChildren()) {
                if (!(section instanceof TableHead) && !(section instanceof TableBody)) continue;
                boolean header = section instanceof TableHead;
                for (Node rowNode : section.getChildren()) {
                    if (!(rowNode instanceof TableRow)) continue;
                    TableRowData row = new TableRowData(header);
                    for (Node cellNode : rowNode.getChildren()) {
                        if (cellNode instanceof TableCell) row.cells.add(collectText(cellNode));
                    }
                    b.rows.add(row);
                }
            }
            out.add(b);
        }
```

并补测试：

```java
    @Test
    public void parse_table() {
        List<Block> blocks = MarkdownRenderer.parse("| a | b |\n|---|---|\n| 1 | 2 |");
        assertEquals(1, blocks.size());
        assertEquals(Block.Type.TABLE, blocks.get(0).type);
        assertEquals(2, blocks.get(0).rows.size());
        assertEquals(2, blocks.get(0).rows.get(0).cells.size());
        assertEquals("a", blocks.get(0).rows.get(0).cells.get(0));
    }
```

- [ ] **Step 6: 实现 BlockNodeFactory.java**

```java
package com.minion.gui.chat;

import com.minion.gui.chat.MarkdownRenderer.Block;
import com.minion.gui.chat.MarkdownRenderer.Span;
import com.minion.gui.chat.MarkdownRenderer.TableRowData;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/** Block 结构 → JavaFX 节点（样式类与 theme.css 对应） */
public class BlockNodeFactory {

    public static javafx.scene.Node create(Block b) {
        switch (b.type) {
            case HEADING: {
                Label l = new Label(b.text);
                l.setStyle("-fx-font-size: " + Math.max(13, 17 - b.level)
                        + "px; -fx-font-weight: bold; -fx-text-fill: #e6e8ee;");
                return l;
            }
            case CODE: {
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.getStyleClass().add("code-block");
                return l;
            }
            case PARAGRAPH: {
                TextFlow flow = new TextFlow();
                if (b.spans.isEmpty()) flow.getChildren().add(new Text(b.text == null ? "" : b.text));
                for (Span s : b.spans) flow.getChildren().add(spanText(s));
                flow.setPadding(new Insets(2, 0, 2, 0));
                return flow;
            }
            case LIST: {
                VBox box = new VBox(2);
                for (Block item : b.items) {
                    HBox row = new HBox(6);
                    Label bullet = new Label("•");
                    bullet.getStyleClass().add("msg-thinking");
                    TextFlow flow = new TextFlow();
                    for (Span s : item.spans) flow.getChildren().add(spanText(s));
                    row.getChildren().addAll(bullet, flow);
                    box.getChildren().add(row);
                }
                return box;
            }
            case QUOTE: {
                Label l = new Label(b.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-thinking");
                l.setStyle("-fx-border-color: #4f8cff; -fx-border-width: 0 0 0 3; -fx-padding: 4 8 4 8;");
                return l;
            }
            case TABLE: {
                GridPane grid = new GridPane();
                grid.setHgap(16);
                grid.setVgap(4);
                grid.getStyleClass().add("code-block");
                int rowIdx = 0;
                for (TableRowData r : b.rows) {
                    for (int c = 0; c < r.cells.size(); c++) {
                        Text t = new Text(r.cells.get(c));
                        if (r.header) {
                            t.setFont(Font.font(t.getFont().getFamily(), FontWeight.BOLD, t.getFont().getSize()));
                        }
                        grid.add(t, c, rowIdx);
                    }
                    rowIdx++;
                }
                return grid;
            }
            default:
                return new Label(b.text == null ? "" : b.text);
        }
    }

    private static Text spanText(Span s) {
        Text t = new Text(s.text);
        if (s.style.contains("bold")) {
            t.setFont(Font.font(t.getFont().getFamily(), FontWeight.BOLD, t.getFont().getSize()));
        }
        if (s.style.contains("italic")) {
            t.setFont(Font.font(t.getFont().getFamily(), FontPosture.ITALIC, t.getFont().getSize()));
        }
        if (s.style.contains("strike")) t.setStrikethrough(true);
        if (s.style.contains("code")) t.setStyle("-fx-font-family: Consolas; -fx-fill: #79c0ff;");
        return t;
    }
}
```

- [ ] **Step 7: 实现 ChatView.java**

```java
package com.minion.gui.chat;

import com.minion.gui.session.EventList;
import com.minion.gui.session.EventList.Ev;
import com.minion.gui.session.SessionHandle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 会话消息区：订阅 EventList（事件来自后台线程，Listener 内 Platform.runLater 包装），
 * 渲染用户消息/助手消息（Markdown 流式重渲染）/思考块/工具卡片/错误横幅。
 */
public class ChatView extends VBox {

    private final EventList events;
    private final SessionHandle handle;
    private final StringBuilder pendingContent = new StringBuilder();
    private final StringBuilder pendingThinking = new StringBuilder();

    public ChatView(EventList events, SessionHandle handle) {
        this.events = events;
        this.handle = handle;
        getStyleClass().add("panel-dark");
        setSpacing(8);
        setStyle("-fx-padding: 16;");
        clear();
    }

    public static ChatView forSession(SessionHandle h) {
        return new ChatView(h.controller.eventList(), h);
    }

    /** 绑定/解绑事件流：active=true 重放存量 + 直通；false 只入缓冲 */
    public void bind(boolean active) {
        events.setActive(active, new EventList.Listener() {
            @Override public void onEvent(Ev e) {
                Platform.runLater(() -> onEventFx(e));
            }
        });
    }

    public void clear() {
        getChildren().clear();
        pendingContent.setLength(0);
        pendingThinking.setLength(0);
        getChildren().add(hint("输入消息开始新的会话"));
    }

    private void onEventFx(Ev e) {
        switch (e.kind) {
            case USER_MESSAGE: {
                Label l = new Label(e.text);
                l.setWrapText(true);
                l.getStyleClass().add("msg-user");
                getChildren().add(l);
                break;
            }
            case THINKING:
                pendingThinking.append(e.text);
                replaceLast(thinkingBlock());
                break;
            case CONTENT:
                pendingContent.append(e.text);
                replaceLast(assistantBlock(pendingContent.toString()));
                break;
            case TOOL_CALL: {
                VBox card = new VBox(4);
                card.getStyleClass().add("card");
                Label name = new Label("🔧 " + e.text);
                name.getStyleClass().add("msg-thinking");
                Label detail = new Label(shorten(e.data == null ? "{}" : e.data.toString(), 120));
                detail.getStyleClass().add("msg-thinking");
                card.getChildren().addAll(name, detail);
                getChildren().add(card);
                break;
            }
            case TOOL_RESULT: {
                String data = e.data == null ? "" : e.data.toString();
                Label l = new Label(data.startsWith("ok") ? "✅ " + e.text + " 成功" : "❌ " + e.text + " 失败");
                l.getStyleClass().add("msg-thinking");
                getChildren().add(l);
                break;
            }
            case ERROR:
                getChildren().add(alert(e.text, "msg-error"));
                break;
            case WARNING:
                getChildren().add(alert(e.text, "msg-warning"));
                break;
            case STATS:
                getChildren().add(alert(e.text, "msg-thinking"));
                break;
            case SUB_AGENT_START:
                getChildren().add(alert("▶ 子任务: " + e.text, "msg-thinking"));
                break;
            case SUB_AGENT_DONE:
                getChildren().add(alert("✓ 子任务完成: " + e.text, "msg-thinking"));
                break;
            default:
                break;
        }
    }

    /** 系统行（错误横幅等，MainWindow.showError 入口） */
    public void appendSystemLine(String text) {
        getChildren().add(alert(text, "msg-error"));
    }

    private Node hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("msg-thinking");
        return l;
    }

    private Node alert(String text, String style) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add(style);
        return l;
    }

    private Node thinkingBlock() {
        Label l = new Label("思考: " + pendingThinking.toString());
        l.setWrapText(true);
        l.getStyleClass().add("msg-thinking");
        return l;
    }

    private Node assistantBlock(String md) {
        VBox box = new VBox(6);
        box.getStyleClass().add("msg-assistant");
        for (MarkdownRenderer.Block b : MarkdownRenderer.parse(md)) {
            box.getChildren().add(BlockNodeFactory.create(b));
        }
        return box;
    }

    /** 流式增量：替换最后一块（思考或助手消息），非流式事件直接追加 */
    private void replaceLast(Node block) {
        if (getChildren().isEmpty()) {
            getChildren().add(block);
            return;
        }
        Node last = getChildren().get(getChildren().size() - 1);
        if (isStreaming(last)) {
            getChildren().set(getChildren().size() - 1, block);
        } else {
            getChildren().add(block);
        }
    }

    private boolean isStreaming(Node n) {
        return (n instanceof VBox && ((VBox) n).getStyleClass().contains("msg-assistant"))
                || (n instanceof Label && ((Label) n).getStyleClass().contains("msg-thinking"));
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
```

- [ ] **Step 8: MainWindow 接入 ChatView（每会话一个实例，激活时重建 + 重放渲染）**

- 字段：`private ChatView chatView; private ScrollPane chatScroll;`
- `show()` 右侧占位替换（启动时无激活会话，右侧保持空占位）：

```java
        chatScroll = new ScrollPane();
        chatScroll.setFitToWidth(true);
        chatScroll.setContent(new Region()); // 激活会话后换 ChatView
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        right.getChildren().setAll(chatScroll, inputPlaceholder);
```

- `show()` 的 `manager.addListener` 中，`onSessionActivated` 回调替换为：

```java
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> {
                    selectTab(h);
                    // 每会话一个 ChatView（绑定其 EventList）：重建 + bind(true) 清空后重放存量
                    chatView = ChatView.forSession(h);
                    chatView.bind(true);
                    chatScroll.setContent(chatView);
                });
            }
```

- `onError` 回调改为横幅（chatView 未激活时为 null，仍落控制台）：

```java
            @Override public void onError(String message) {
                Platform.runLater(() -> {
                    if (chatView != null) chatView.appendSystemLine(message);
                    System.err.println("[minion] " + message);
                });
            }
```

- [ ] **Step 8a: 修正 ChatView.bind——激活时先清空再重放（EventList.setActive 同步重放存量，不清会重复渲染）**

Task 10 Step 7 的 `ChatView.bind` 替换为：

```java
    /** 绑定/解绑事件流：active=true 先清空，再经 EventList 同步重放存量 + 后续直通 */
    public void bind(boolean active) {
        if (active) clear(); // FX 线程调用：先清再重放，避免存量事件重复渲染
        events.setActive(active, new EventList.Listener() {
            @Override public void onEvent(Ev e) {
                Platform.runLater(() -> onEventFx(e));
            }
        });
    }
```

- [ ] **Step 9: 构建 + 手动验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

Run: `java -jar target/minion-0.1.0.jar`
Expected: 新建会话 → 干净页面提示；有 key 时发送 → 助手消息流式出现，代码块深色渲染。

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/minion/gui/chat/ src/test/java/com/minion/gui/chat/MarkdownRendererTest.java src/main/java/com/minion/gui/MainWindow.java
git commit -m "feat: 消息区渲染——Markdown 块结构与流式消息流（ChatView/BlockNodeFactory）"
```

---

### Task 11: 输入区 + 发送/终止 + 摘要标题流程接线

**Files:**
- Create: `src/main/java/com/minion/gui/input/InputView.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`

**Interfaces:**
- Consumes: Task 8 send/stop、Task 10 ChatView
- Produces: `InputView`（多行 TextArea、Ctrl+Enter 发送、Enter 换行、发送/终止按钮切换、Tooltip）；MainWindow 发送管线（摘要→正式任务由 SessionManager.send 内置）

- [ ] **Step 1: 实现 InputView.java**

```java
package com.minion.gui.input;

import com.minion.gui.session.SessionHandle;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 底部输入区：多行 TextArea（自适应 1→6 行）+ 发送/终止按钮 */
public class InputView extends VBox {

    private final SessionManager manager;
    private final TextArea input = new TextArea();
    private final Button sendButton = new Button("⤒ 发送");
    private volatile SessionHandle current;
    /** 发送后保留草稿直至本轮结束（供终止后修改再发）；结束时若未被用户修改则清空 */
    private String lastSent;

    public InputView(final SessionManager manager) {
        this.manager = manager;
        getStyleClass().add("panel-dark");
        setSpacing(8);
        setStyle("-fx-padding: 12 16 12 16;");

        input.getStyleClass().add("input-area");
        input.setWrapText(true);
        input.setPromptText("输入消息…  (Ctrl+Enter 发送)");
        input.setPrefRowCount(2);
        input.setMaxHeight(6 * 24);

        input.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN).match(e)) {
                e.consume();
                onSend();
            }
        });

        sendButton.getStyleClass().add("btn-primary");
        updateButton(false);

        HBox row = new HBox(10);
        row.getChildren().add(input);
        HBox.setHgrow(input, Priority.ALWAYS);
        row.getChildren().add(sendButton);
        getChildren().add(row);
    }

    /** MainWindow 激活会话时调用 */
    public void bindSession(SessionHandle h) {
        this.current = h;
        this.lastSent = null;
        Platform.runLater(() -> updateButton(h.running));
    }

    public void onRunningChanged(SessionHandle h, boolean running) {
        if (current != h) return;
        Platform.runLater(() -> {
            updateButton(running);
            if (!running && lastSent != null && input.getText().equals(lastSent)) {
                input.clear(); // 本轮结束且用户未修改 → 清空草稿，准备新输入
                lastSent = null;
            }
        });
    }

    private void updateButton(boolean running) {
        if (running) {
            sendButton.setText("■ 终止");
            sendButton.getStyleClass().remove("btn-primary");
            sendButton.getStyleClass().add("btn-danger");
            sendButton.setTooltip(new Tooltip("终止当前运行"));
            sendButton.setOnAction(e -> manager.stop(current));
        } else {
            sendButton.setText("⤒ 发送");
            sendButton.getStyleClass().remove("btn-danger");
            sendButton.getStyleClass().add("btn-primary");
            sendButton.setTooltip(new Tooltip("发送 (Ctrl+Enter)"));
            sendButton.setOnAction(e -> onSend());
        }
    }

    private void onSend() {
        String text = input.getText();
        if (text == null || text.trim().isEmpty()) return;
        if (current == null) return;
        lastSent = text; // 不清空：草稿保留至本轮结束，供终止后修改再发
        manager.send(current, text); // 摘要标题 + 正式任务由 SessionManager 统一处理
    }
}
```

- [ ] **Step 2: MainWindow 接线**

- 字段 `private InputView inputView;`
- `show()` 右侧：`inputPlaceholder` 替换为：

```java
        inputView = new InputView(manager);
        right.getChildren().setAll(chatScroll, inputView);
```

- `onSessionActivated` 回调（Task 10 版本）追加输入区绑定：

```java
            @Override public void onSessionActivated(SessionHandle h) {
                Platform.runLater(() -> {
                    selectTab(h);
                    chatView = ChatView.forSession(h);
                    chatView.bind(true);
                    chatScroll.setContent(chatView);
                    if (inputView != null) inputView.bindSession(h);
                });
            }
```

- `onSessionRunningChanged` 回调（Task 9 版本）追加按钮态同步：

```java
            @Override public void onSessionRunningChanged(SessionHandle h, boolean running) {
                Platform.runLater(() -> updateTab(h));
                if (inputView != null) inputView.onRunningChanged(h, running);
            }
```

（`onNewSession` 无需额外接线：`activateSession` 触发 `onSessionActivated` → `bindSession` 自动完成）

- [ ] **Step 3: 构建 + 手动验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

Run: `java -jar target/minion-0.1.0.jar`
Expected:
1. 多行输入；Enter 换行；Ctrl+Enter 发送
2. 发送按钮悬停提示"发送 (Ctrl+Enter)"
3. 运行中按钮变红色"■ 终止"（悬停"终止当前运行"），点击后恢复
4. 发送后：摘要标题生成（页签出现）→ 任务流式输出
5. 无 key 时发送 → 红色错误横幅（模型配置引导见 Task 13）
6. 草稿保留语义：发送后输入框保留原文直至本轮结束；结束后自动清空；运行中修改草稿 → 结束后保留修改

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/minion/gui/input/InputView.java src/main/java/com/minion/gui/MainWindow.java
git commit -m "feat: 输入区——多行输入/Ctrl+Enter 发送/运行中终止按钮/摘要标题流程接线"
```

---

### Task 12: 工作空间 UI（列表 + 新建/重命名/修改/删除/切换）

**Files:**
- Create: `src/main/java/com/minion/gui/sidebar/WorkspaceListView.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（加工作空间 CRUD 代理）
- Test: `src/test/java/com/minion/gui/session/SessionManagerTest.java`（追加用例）

**Interfaces:**
- Consumes: Task 2 `WorkspaceManager`（`list()/get(name)/currentName()/setCurrent(name)/add(name, workDir, projectMd)/rename(old, new)/update(name, workDir, projectMd)/delete(name)`——重名/非法字符/删最后一个均抛 `IllegalArgumentException`）、Task 8 `SessionManager.switchWorkspace(String)`
- Produces:
  - `SessionManager.addWorkspace(String name, String workDir, String projectMd)`（add 配置 + 建 ctx）
  - `SessionManager.renameWorkspace(String oldName, String newName)`（配置改名 + ctx 换键 + 当前名同步 + 通知）
  - `SessionManager.updateWorkspace(String name, String workDir, String projectMd)`（仅更新配置，**重启后对新会话生效**——运行中会话持有旧 workspace 引用，热更新会连锁重建上下文，YAGNI 不做）
  - `SessionManager.deleteWorkspace(String name)`（终止该空间所有会话 → 关线程池 → 删配置条目 → 递归删 `jarDir/session/<name>/` → 若删除的是当前空间则切到剩余第一个 → 通知）
  - `WorkspaceListView(SessionManager)`：`refresh()` 重建列表并选中当前空间

- [ ] **Step 1: 追加失败测试**（SessionManagerTest 尾部）

```java
    /** 新建工作空间：配置落盘 + 会话上下文可建 */
    @Test
    public void addWorkspace_buildsContext() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.addWorkspace("projX", tmp.newFolder("x").getPath(), "");
        m.switchWorkspace("projX");
        SessionHandle h = m.createSession(null);
        assertEquals(1, m.sessions().size());
        assertEquals("projX", m.workspaces().currentName());
    }

    /** 删除工作空间：配置删除 + 会话目录删除 + 当前空间回落 */
    @Test
    public void deleteWorkspace_removesConfigAndDir() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ws.add("projA", tmp.newFolder("a").getPath(), "");
        ws.add("projB", tmp.newFolder("b").getPath(), "");
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("projA");
        m.createSession(null);
        Path sessionDir = WorkspaceManager.sessionDirFor(jar, "projA");
        assertTrue(Files.exists(sessionDir));

        m.deleteWorkspace("projA");
        assertNull(ws.get("projA"));
        assertFalse(Files.exists(sessionDir));
        assertNotEquals("projA", m.workspaces().currentName());
        assertEquals(0, m.sessions().size());
    }

    /** 重命名工作空间：配置迁移 + 会话目录迁移 + 会话上下文随新名 */
    @Test
    public void renameWorkspace_migratesSessionDir() throws Exception {
        Path jar = tmp.newFolder("jar").toPath();
        Config config = Config.load(jar);
        WorkspaceManager ws = WorkspaceManager.load(jar);
        ModelManager models = ModelManager.load(jar);
        SessionManager m = new SessionManager(FAKE_UI, config, jar, ws, models,
                new ArrayList<Skill>(), null);
        m.switchWorkspace("default");
        m.createSession(null);
        Path oldDir = WorkspaceManager.sessionDirFor(jar, "default");
        assertTrue(Files.exists(oldDir));

        m.renameWorkspace("default", "主空间");
        assertNull(ws.get("default"));
        assertNotNull(ws.get("主空间"));
        assertFalse(Files.exists(oldDir));
        assertTrue(Files.exists(WorkspaceManager.sessionDirFor(jar, "主空间")));
        assertEquals("主空间", m.workspaces().currentName());
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=SessionManagerTest`
Expected: FAIL（编译错误：`addWorkspace/deleteWorkspace/renameWorkspace` 不存在）

- [ ] **Step 3: SessionManager 追加工作空间 CRUD 代理**（加在 `switchWorkspace` 之后）

```java
    /** 新建工作空间：配置落盘 + 建上下文（不自动切换，用户点击列表项切换）。false=名称非法或重名 */
    public boolean addWorkspace(String name, String workDir, String projectMd) {
        if (!workspaces.add(name, workDir, projectMd)) return false;
        ctxByName.put(name, buildCtx(workspaces.get(name)));
        return true;
    }

    /** 重命名：配置迁移 + 会话目录迁移（WorkspaceManager.rename 内部完成）+ ctx 换键 + 当前名同步。false=新名非法/重名 */
    public boolean renameWorkspace(String oldName, String newName) {
        if (!workspaces.rename(oldName, newName)) return false;
        WorkspaceCtx ctx = ctxByName.remove(oldName);
        ctxByName.put(newName, ctx);
        if (currentWorkspaceName.equals(oldName)) currentWorkspaceName = newName;
        notifyWorkspaceChanged();
        return true;
    }

    /**
     * 修改工作空间：仅更新配置落盘。运行中的会话持有旧 workspace 引用，
     * 热更新会连锁重建整套上下文（registry/store/loop 引用），YAGNI 不做——
     * 修改在重启后对新会话生效。
     */
    public void updateWorkspace(String name, String workDir, String projectMd) {
        workspaces.update(name, workDir, projectMd);
    }

    /**
     * 删除工作空间：WorkspaceManager.remove 拒绝删除最后一个并已删会话目录；
     * 此处先终止该空间所有会话 → 关池 → 删配置（remove）→ 当前名同步。
     * false=空间不存在或删最后一个被拒绝
     */
    public boolean deleteWorkspace(String name) {
        WorkspaceCtx ctx = ctxByName.get(name);
        if (ctx == null) return false;
        if (!workspaces.remove(name)) return false; // 删最后一个被拒，会话上下文不动
        for (SessionHandle h : ctx.sessions) {
            if (h.running) h.loop.interrupt();
        }
        ctx.pool.shutdownNow();
        ctxByName.remove(name);
        if (currentWorkspaceName.equals(name)) {
            currentWorkspaceName = workspaces.currentName(); // remove 已回落 currentName
            currentSession = null;
            notifyWorkspaceChanged();
        }
        return true;
    }
```

- [ ] **Step 4: 实现 WorkspaceListView.java**

```java
package com.minion.gui.sidebar;

import com.minion.core.config.WorkspaceConfig;
import com.minion.core.config.WorkspaceManager;
import com.minion.gui.session.SessionManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.Optional;

/** 左侧工作空间列表：单击切换；右键菜单（重命名/修改/删除）；顶部"新建"按钮由 MainWindow 放置 */
public class WorkspaceListView extends ListView<String> {

    private final SessionManager manager;
    private final WorkspaceManager workspaces;

    public WorkspaceListView(SessionManager manager) {
        this.manager = manager;
        this.workspaces = manager.workspaces();
        setCellFactory(v -> new WsCell());
        setOnMouseClicked(e -> {
            String name = getSelectionModel().getSelectedItem();
            if (name != null && e.getClickCount() == 1) manager.switchWorkspace(name);
        });
    }

    public void refresh() {
        Platform.runLater(() -> {
            getItems().clear();
            for (WorkspaceConfig w : workspaces.list()) getItems().add(w.workSpaceName);
            getSelectionModel().select(workspaces.currentName());
        });
    }

    private class WsCell extends ListCell<String> {
        @Override protected void updateItem(String name, boolean empty) {
            super.updateItem(name, empty);
            if (empty || name == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            setText(name);
            if (name.equals(workspaces.currentName())) {
                setText(name + "  ●"); // 当前工作空间标记
            }

            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem("重命名");
            rename.setOnAction(e -> doRename(name));
            MenuItem edit = new MenuItem("修改");
            edit.setOnAction(e -> doEdit(name));
            MenuItem del = new MenuItem("删除");
            del.setOnAction(e -> doDelete(name));
            menu.getItems().addAll(rename, edit, del);
            setContextMenu(menu);
        }
    }

    private void doRename(String oldName) {
        TextInputDialog d = new TextInputDialog(oldName);
        d.setTitle("重命名工作空间");
        d.setHeaderText("输入新名称（会同步迁移会话目录）");
        Optional<String> result = d.showAndWait();
        if (!result.isPresent()) return;
        if (!manager.renameWorkspace(oldName, result.get().trim())) {
            error("重命名失败", "名称非法或已存在");
        }
        refresh();
    }

    /** 修改：workDir / project.md 可改；名称不动（重命名是单独操作） */
    private void doEdit(String name) {
        WorkspaceConfig w = workspaces.get(name);
        Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
        d.setTitle("修改工作空间");
        d.setHeaderText("工作空间「" + name + "」（修改将在重启后对新会话生效）");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        TextField workDir = new TextField(w.workDir);
        TextField projectMd = new TextField(w.projectMd == null ? "" : w.projectMd);
        grid.addRow(0, new Label("work.dir:"), workDir);
        grid.addRow(1, new Label("project.md:"), projectMd);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            WorkspaceConfig out = new WorkspaceConfig();
            out.workSpaceName = name;
            out.workDir = workDir.getText().trim();
            out.projectMd = projectMd.getText().trim();
            return out;
        });
        Optional<WorkspaceConfig> result = d.showAndWait();
        if (!result.isPresent()) return;
        manager.updateWorkspace(name, result.get().workDir, result.get().projectMd);
    }

    private void doDelete(String name) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "删除工作空间「" + name + "」？其下所有会话与 " + "session/" + name + "/ 目录将一并删除。",
                ButtonType.OK, ButtonType.CANCEL);
        a.setTitle("删除工作空间");
        Optional<ButtonType> r = a.showAndWait();
        if (r.isPresent() && r.get() == ButtonType.OK) {
            if (!manager.deleteWorkspace(name)) {
                error("删除失败", "至少保留一个工作空间");
            }
            refresh();
        }
    }

    private void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.showAndWait();
    }
}
```

- [ ] **Step 5: MainWindow 接线**

- 左侧下区占位替换：

```java
        WorkspaceListView wsList = new WorkspaceListView(manager);
        VBox.setVgrow(wsList, Priority.ALWAYS);
        Button newWs = new Button("＋ 新建工作空间");
        newWs.getStyleClass().add("btn-ghost");
        newWs.setMaxWidth(Double.MAX_VALUE);
        newWs.setOnAction(e -> {
            Dialog<WorkspaceConfig> d = new Dialog<WorkspaceConfig>();
            d.setTitle("新建工作空间");
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            GridPane g = new GridPane();
            g.setHgap(8); g.setVgap(8); g.setPadding(new Insets(10));
            TextField n = new TextField();
            n.setPromptText("名称");
            TextField wd = new TextField();
            wd.setPromptText("work.dir");
            TextField pm = new TextField();
            pm.setPromptText("project.md（可空）");
            g.addRow(0, new Label("名称:"), n);
            g.addRow(1, new Label("work.dir:"), wd);
            g.addRow(2, new Label("project.md:"), pm);
            d.getDialogPane().setContent(g);
            d.setResultConverter(bt -> {
                if (bt != ButtonType.OK) return null;
                WorkspaceConfig out = new WorkspaceConfig();
                out.workSpaceName = n.getText().trim();
                out.workDir = wd.getText().trim();
                out.projectMd = pm.getText().trim();
                return out;
            });
            Optional<WorkspaceConfig> r = d.showAndWait();
            if (r.isPresent()) {
                if (!manager.addWorkspace(r.get().workSpaceName, r.get().workDir, r.get().projectMd)) {
                    Alert a = new Alert(Alert.AlertType.ERROR, "名称非法或已存在", ButtonType.OK);
                    a.setTitle("新建失败");
                    a.showAndWait();
                }
                wsList.refresh();
            }
        });
        VBox wsBox = new VBox(6);
        wsBox.getChildren().addAll(newWs, wsList);
        VBox.setVgrow(wsBox, Priority.ALWAYS);
        sidebar.getChildren().setAll(sessionTitle, sessionBox, wsTitle, wsBox);
```

- `onWorkspaceChanged` 回调中追加：`wsList.refresh();`（需将 wsList 提升为字段或用局部 final 捕获）

- [ ] **Step 6: 运行测试**

Run: `mvn -q test -Dtest=SessionManagerTest`
Expected: PASS（新增 3 个用例 + 原有 6 个全绿）

- [ ] **Step 7: 构建 + 手动验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

Run: `java -jar target/minion-0.1.0.jar`
Expected: 左下工作空间列表显示默认空间并标"●"；新建 → 列表出现；右键重命名 → 会话目录迁移；切换空间 → 右侧会话列表随之切换；删除 → 确认后消失。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/minion/gui/sidebar/WorkspaceListView.java src/main/java/com/minion/gui/MainWindow.java src/main/java/com/minion/gui/session/SessionManager.java src/test/java/com/minion/gui/session/SessionManagerTest.java
git commit -m "feat: 工作空间 UI——列表/新建/重命名/修改/删除/切换（会话目录随名迁移）"
```

---

### Task 13: 模型管理弹窗（⚙）

**Files:**
- Create: `src/main/java/com/minion/gui/dialog/ModelDialog.java`
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（⚙ 按钮接线 + 顶部模型名刷新）

**Interfaces:**
- Consumes: Task 3 `ModelManager`（`list()/current()/currentName()/setCurrent(String)/add(ModelConfig)/update(ModelConfig)/remove(String)`；删最后一个抛 `IllegalArgumentException`）、`ModelConfig{displayName, url, apiKey, modelName, provider, thinking, reasoningEffort, maxContextTokens, compressThreshold, keepRecentMessages}`
- Produces: `ModelDialog.show(Window owner, ModelManager models)` 静态方法（列表 + 选择切换 + 新建/修改/删除按钮）；切换后回调由调用方刷新顶部标签

- [ ] **Step 1: 实现 ModelDialog.java**

```java
package com.minion.gui.dialog;

import com.minion.core.config.ModelConfig;
import com.minion.core.config.ModelManager;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/** 右上角 ⚙ 弹窗：模型列表 + 单击切换 + 新建/修改/删除 */
public class ModelDialog {

    public static void show(Window owner, final ModelManager models) {
        Dialog<Void> d = new Dialog<Void>();
        d.initOwner(owner);
        d.setTitle("模型管理");
        d.setHeaderText("选择模型并配置参数（新会话使用当前模型）");
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        final ListView<String> list = new ListView<String>();
        refresh(list, models);
        list.setPrefSize(360, 220);

        HBox actions = new HBox(8);
        javafx.scene.control.Button add = new javafx.scene.control.Button("新建");
        javafx.scene.control.Button edit = new javafx.scene.control.Button("修改");
        javafx.scene.control.Button del = new javafx.scene.control.Button("删除");
        add.getStyleClass().add("btn-ghost");
        edit.getStyleClass().add("btn-ghost");
        del.getStyleClass().add("btn-ghost");

        add.setOnAction(e -> {
            ModelConfig mc = form(null);
            if (mc != null) {
                if (!models.add(mc)) error("新建失败", "标识名非法或已存在");
            }
            refresh(list, models);
        });
        edit.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            ModelConfig mc = form(models.get(sel));
            if (mc != null) models.update(mc);
            refresh(list, models);
        });
        del.setOnAction(e -> {
            String sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "删除模型「" + sel + "」？", ButtonType.OK, ButtonType.CANCEL);
            Optional<ButtonType> r = a.showAndWait();
            if (r.isPresent() && r.get() == ButtonType.OK) {
                if (!models.remove(sel)) error("删除失败", "至少保留一个模型");
            }
            refresh(list, models);
        });
        actions.getChildren().addAll(add, edit, del);

        VBox box = new VBox(10);
        box.getChildren().addAll(list, actions);
        box.setPadding(new Insets(10));
        d.getDialogPane().setContent(box);

        d.showAndWait();
    }

    private static void refresh(ListView<String> list, ModelManager models) {
        list.getItems().clear();
        for (ModelConfig m : models.list()) {
            list.getItems().add(m.displayName + (m.displayName.equals(models.currentName())
                    ? "  ●" : ""));
        }
        list.getSelectionModel().select(0);
    }

    /** 新建（mc==null 带默认值）/ 修改（mc!=null 预填）表单；OK 返回配置，取消返回 null */
    private static ModelConfig form(ModelConfig mc) {
        Dialog<ModelConfig> d = new Dialog<ModelConfig>();
        d.setTitle(mc == null ? "新建模型" : "修改模型");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));

        TextField displayName = new TextField(mc == null ? "" : mc.displayName);
        displayName.setPromptText("页签显示标识");
        TextField url = new TextField(mc == null ? "https://api.deepseek.com/v1/chat/completions" : mc.url);
        TextField apiKey = new TextField(mc == null ? "" : mc.apiKey);
        apiKey.setPromptText("sk-...");
        TextField modelName = new TextField(mc == null ? "" : mc.modelName);
        TextField provider = new TextField(mc == null ? "deepseek" : mc.provider);
        CheckBox thinking = new CheckBox("深度思考");
        thinking.setSelected(mc != null && mc.thinking);
        ComboBox<String> effort = new ComboBox<String>();
        effort.getItems().addAll("low", "medium", "high", "max");
        effort.setValue(mc == null ? "max" : mc.reasoningEffort);
        TextField maxCtx = new TextField(mc == null ? "900000" : String.valueOf(mc.maxContextTokens));
        TextField thr = new TextField(mc == null ? "0.8" : String.valueOf(mc.compressThreshold));
        TextField keep = new TextField(mc == null ? "10" : String.valueOf(mc.keepRecentMessages));

        grid.addRow(0, new Label("标识名:"), displayName);
        grid.addRow(1, new Label("URL:"), url);
        grid.addRow(2, new Label("API Key:"), apiKey);
        grid.addRow(3, new Label("模型名:"), modelName);
        grid.addRow(4, new Label("provider:"), provider);
        grid.addRow(5, new Label("思考:"), thinking);
        grid.addRow(6, new Label("effort:"), effort);
        grid.addRow(7, new Label("maxContextTokens:"), maxCtx);
        grid.addRow(8, new Label("compressThreshold:"), thr);
        grid.addRow(9, new Label("keepRecentMessages:"), keep);
        d.getDialogPane().setContent(grid);

        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            ModelConfig out = new ModelConfig();
            out.displayName = displayName.getText().trim();
            out.url = url.getText().trim();
            out.apiKey = apiKey.getText().trim();
            out.modelName = modelName.getText().trim();
            out.provider = provider.getText().trim();
            out.thinking = thinking.isSelected();
            out.reasoningEffort = effort.getValue() == null ? "max" : effort.getValue();
            out.maxContextTokens = parseLong(maxCtx.getText(), 900000);
            out.compressThreshold = parseDouble(thr.getText(), 0.8);
            out.keepRecentMessages = parseLong(keep.getText(), 10);
            return out;
        });
        Optional<ModelConfig> r = d.showAndWait();
        return r.isPresent() ? r.get() : null;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.showAndWait();
    }
}
```

- [ ] **Step 2: MainWindow ⚙ 接线**

Task 7 骨架的 `gear.setOnAction(e -> { });` 替换：

```java
        gear.setOnAction(e -> {
            ModelDialog.show(stage, manager.models());
            // 顶部模型名刷新（切换模型后显示新标识）
            modelLabel.setText("模型: " + manager.models().currentName());
        });
```

- [ ] **Step 3: 构建 + 手动验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

Run: `java -jar target/minion-0.1.0.jar`
Expected: 点 ⚙ 弹出模型列表（当前项标 ●）；新建/修改/删除可用；删最后一个被拒绝并提示；关闭弹窗后顶部模型名与选择一致。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/minion/gui/dialog/ModelDialog.java src/main/java/com/minion/gui/MainWindow.java
git commit -m "feat: 模型管理弹窗——列表切换/新建/修改/删除（含上下文压缩参数）"
```

---

### Task 14: 确认弹窗（GuiConfirmUi）+ 移除 ConfirmReader

**Files:**
- Create: `src/main/java/com/minion/gui/confirm/GuiConfirmUi.java`
- Create: `src/main/java/com/minion/gui/dialog/ConfirmDialog.java`
- Delete: `src/main/java/com/minion/cli/ConfirmReader.java`
- Delete: `src/test/java/com/minion/cli/ConfirmReaderTest.java`
- Test: `src/test/java/com/minion/core/tools/confirm/GuiConfirmUiTest.java`

**Interfaces:**
- Consumes: `ConfirmUi`（core，`Decision ask(String)`；Decision: APPROVE/REJECT/APPROVE_WHITELIST/APPROVE_SESSION）
- Produces: `GuiConfirmUi implements ConfirmUi`（ask 经 FutureTask 弹 FX 弹窗并阻塞工具线程）；`ConfirmDialog.show(String message)` → Decision（**必须在 FX 线程调用**，返回前阻塞等待用户点击）

- [ ] **Step 1: 写失败测试** `GuiConfirmUiTest.java`（纯逻辑：线程行为 + 无 FX 环境下的防御）

```java
package com.minion.core.tools.confirm;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * GuiConfirmUi 线程语义测试（不启动 JavaFX Application Thread——
 * Platform.runLater 在未启动时直接排队不执行，因此本测试仅验证
 * ask() 在无 FX 线程时返回 REJECT 不挂死）。
 */
public class GuiConfirmUiTest {

    @Test
    public void ask_withoutFxThread_returnsReject() throws Exception {
        final ConfirmUi ui = new com.minion.gui.confirm.GuiConfirmUi();
        final CountDownLatch done = new CountDownLatch(1);
        final ConfirmUi.Decision[] result = new ConfirmUi.Decision[1];
        Thread t = new Thread(() -> {
            result[0] = ui.ask("! 高危操作 Bash → rm -rf");
            done.countDown();
        });
        t.start();
        assertTrue("ask 不应挂死", done.await(5, TimeUnit.SECONDS));
        assertEquals(ConfirmUi.Decision.REJECT, result[0]);
    }

    @Test
    public void implementsConfirmUi() {
        ConfirmUi ui = new com.minion.gui.confirm.GuiConfirmUi();
        assertTrue(ui instanceof ConfirmUi);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q test -Dtest=GuiConfirmUiTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 ConfirmDialog.java**

```java
package com.minion.gui.dialog;

import com.minion.core.tools.confirm.ConfirmUi;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

/** 高危/越界操作确认弹窗；必须在 FX 线程调用（GuiConfirmUi 经 Platform.runLater 保证） */
public class ConfirmDialog {

    public static ConfirmUi.Decision show(String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle("确认操作");
        a.setHeaderText(message);
        ButtonType approve = new ButtonType("✅ 批准", ButtonBar.ButtonData.YES);
        ButtonType reject = new ButtonType("❌ 拒绝", ButtonBar.ButtonData.NO);
        ButtonType session = new ButtonType("本次会话全部批准", ButtonBar.ButtonData.OK_DONE);
        ButtonType whitelist = new ButtonType("批准并记住", ButtonBar.ButtonData.OTHER);
        a.getButtonTypes().setAll(approve, reject, session, whitelist);
        a.showAndWait();
        ButtonType r = a.getResult();
        if (r == approve) return ConfirmUi.Decision.APPROVE;
        if (r == session) return ConfirmUi.Decision.APPROVE_SESSION;
        if (r == whitelist) return ConfirmUi.Decision.APPROVE_WHITELIST;
        return ConfirmUi.Decision.REJECT;
    }
}
```

- [ ] **Step 4: 实现 GuiConfirmUi.java**

```java
package com.minion.gui.confirm;

import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.dialog.ConfirmDialog;
import javafx.application.Platform;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * GUI 确认交互：工具线程 ask → FutureTask 投递 FX 线程弹窗 → 阻塞等待结果。
 * FX 线程未启动（测试）时 FutureTask 排队不执行，超时兜底 REJECT，不挂死。
 */
public class GuiConfirmUi implements ConfirmUi {

    @Override
    public Decision ask(String message) {
        final FutureTask<Decision> task = new FutureTask<Decision>(() -> ConfirmDialog.show(message));
        Platform.runLater(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.REJECT;
        } catch (ExecutionException e) {
            return Decision.REJECT;
        }
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `mvn -q test -Dtest=GuiConfirmUiTest`
Expected: PASS

- [ ] **Step 6: 删除 ConfirmReader 及测试**

Run: `git rm src/main/java/com/minion/cli/ConfirmReader.java src/test/java/com/minion/cli/ConfirmReaderTest.java`
Expected: 删除成功

Run: `grep -rn "ConfirmReader" src/main src/test`
Expected: 无输出（无残留引用）

- [ ] **Step 7: 构建**

Run: `mvn clean package`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/minion/gui/confirm/GuiConfirmUi.java src/main/java/com/minion/gui/dialog/ConfirmDialog.java src/test/java/com/minion/core/tools/confirm/GuiConfirmUiTest.java
git commit -m "feat: 确认弹窗 GUI 化（GuiConfirmUi 经 FutureTask 阻塞工具线程），移除 ConfirmReader"
```

---

### Task 15: 收尾——Main 最终装配、窗口关闭语义、移除 CLI 与 util 清理、文档同步

**Files:**
- Delete: `src/main/java/com/minion/cli/`（Repl.java、CommandDispatcher.java、Renderer.java、StatsLine.java、StartupBanner.java——ConfirmReader 已在 Task 14 删）
- Delete: `src/main/java/com/minion/core/util/Ansi.java`、`src/main/java/com/minion/core/util/ConsoleIo.java`
- Delete: `src/test/java/com/minion/cli/`（CommandDispatcherTest、ReplDispatchTest、RendererTest、StartupBannerTest、StatsLineTest、SafeGlyphs）
- Delete: `src/test/java/com/minion/core/util/AnsiTest.java`、`ConsoleIoTest.java`
- Modify: `src/main/java/com/minion/Main.java`（最终装配）
- Modify: `src/main/java/com/minion/gui/MinionApp.java`（静态注入加 SessionManager）
- Modify: `src/main/java/com/minion/gui/MainWindow.java`（窗口关闭语义）
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`（加 `hasRunning()`）
- Modify: `README.md`、`docs/ARCHITECTURE.md`

**Interfaces:**
- Consumes: 全部先前任务
- Produces: 最终可交付 `java -jar target/minion-0.1.0.jar`

- [ ] **Step 1: 确认 MinionApp 4 参注入已就位（Task 9 完成）**

Run: `grep -n "sessionManager" src/main/java/com/minion/gui/MinionApp.java`
Expected: `start(Config c, WorkspaceManager w, ModelManager m, SessionManager s)` 与 `sessionManager()` getter 已存在（无需改动）

- [ ] **Step 2: SessionManager 加 `hasRunning()`**（在 `shutdown()` 前）

```java
    /** 是否有会话正在后台运行（窗口关闭确认用，跨工作空间） */
    public boolean hasRunning() {
        for (WorkspaceCtx ctx : ctxByName.values()) {
            for (SessionHandle h : ctx.sessions) {
                if (h.running) return true;
            }
        }
        return false;
    }
```

- [ ] **Step 3: MainWindow 关闭语义**（`show()` 末尾、`stage.show()` 前）

```java
        stage.setOnCloseRequest(e -> {
            if (!manager.hasRunning()) {
                manager.shutdown();
                return;
            }
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "仍有会话正在运行，确认退出？", ButtonType.OK, ButtonType.CANCEL);
            a.setTitle("退出确认");
            a.showAndWait();
            if (a.getResult() == ButtonType.OK) {
                manager.shutdown();
            } else {
                e.consume(); // 取消关闭
            }
        });
```

- [ ] **Step 4: Main 最终装配**（整文件替换）

```java
package com.minion;

import com.minion.core.config.Config;
import com.minion.core.config.ModelManager;
import com.minion.core.config.WorkspaceManager;
import com.minion.core.skills.Skill;
import com.minion.core.skills.SkillManager;
import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.CdpClient;
import com.minion.core.tools.browser.ChromeLauncher;
import com.minion.core.tools.confirm.ConfirmUi;
import com.minion.gui.MinionApp;
import com.minion.gui.confirm.GuiConfirmUi;
import com.minion.gui.session.SessionManager;

import java.nio.file.Paths;
import java.util.List;

/** 入口：装配配置/技能/浏览器/GUI，启动 JavaFX 主窗口（GUI 为唯一界面，CLI 已移除） */
public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        java.nio.file.Path jarDir = Config.jarDir();
        WorkspaceManager workspaces = WorkspaceManager.load(jarDir);
        ModelManager models = ModelManager.load(jarDir);

        // 全局技能目录（所有工作空间/模型共用）
        String skillsDir = Paths.get(config.skillsDir()).toAbsolutePath().normalize().toString();
        SkillManager skillManager = new SkillManager(skillsDir);
        List<Skill> skills = skillManager.scan();

        // 浏览器工具（懒启动 Chrome；退出钩子关停自启进程）
        ChromeLauncher chrome = new ChromeLauncher(config.browserPath(), config.browserPort(),
                Paths.get(config.browserUserDataDir()), config.browserHeadless(),
                config.browserTimeoutMs());
        BrowserSession browserSession = new BrowserSession(chrome, new CdpClient(10000,
                config.browserTimeoutMs()));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> chrome.stop()));

        ConfirmUi confirmUi = new GuiConfirmUi();
        SessionManager manager = new SessionManager(confirmUi, config, jarDir,
                workspaces, models, skills, browserSession);

        MinionApp.start(config, workspaces, models, manager);
    }
}
```

（`Config.jarDir()` 来自 Task 4；Config 保留的 getter：`browserPath/browserPort/browserUserDataDir/browserHeadless/browserTimeoutMs/skillsDir/confirmSkip/whitelistTools/whitelistCommands/readAllowOutside/appendWhitelist/externalFile` 均在。）

- [ ] **Step 5: 删除 CLI 与 util 包**

Run:
```bash
git rm -r src/main/java/com/minion/cli
git rm src/main/java/com/minion/core/util/Ansi.java src/main/java/com/minion/core/util/ConsoleIo.java
git rm -r src/test/java/com/minion/cli
git rm src/test/java/com/minion/core/util/AnsiTest.java src/test/java/com/minion/core/util/ConsoleIoTest.java
```
Expected: 全部删除

Run: `grep -rn "com.minion.cli\|Ansi\|ConsoleIo" src/main src/test --include=*.java`
Expected: 无输出（无残留引用；测试目录里 SafeGlyphs 已随 cli 删除）

- [ ] **Step 6: 运行全量测试**

Run: `mvn test`
Expected: BUILD SUCCESS，全绿（核心层回归 + 新增 config/gui 逻辑测试；JavaFX 类不进单测）

- [ ] **Step 7: 更新 README.md**

- 启动方式改为：`java -jar minion-0.1.0.jar`（GUI，需 JDK 8 含 JavaFX——Oracle JDK 8 或 Zulu/AdoptOpenJDK 8 含 OpenJFX 的发行版；Win7 用户注意 Win7 只支持到 8u251 之前的 Oracle 版本）
- 配置三件套：workspace.json（工作空间）、model.json（模型）、config.properties（browser/confirm/paths/skills.dir）
- 会话存储：jar 同目录 `session/<workSpaceName>/`
- 快捷操作：Ctrl+Enter 发送、Enter 换行、⚙ 模型管理、关闭页签=删除会话
- 移除全部 CLI 用法说明（-c/-r/交互命令）

- [ ] **Step 8: 更新 docs/ARCHITECTURE.md**

- 包结构：`gui/`（MainWindow/sidebar/chat/input/dialog/confirm/session）、`core/config/`（WorkspaceManager/ModelManager）、删除 `cli/` 与 `util/Ansi`、`util/ConsoleIo`
- 线程模型：每会话一个 AgentLoop + 工作线程；EventList 事件缓冲；GuiConfirmUi FutureTask 阻塞工具线程
- 工具注册位置：SessionManager.loadWorkspaceContexts（每工作空间独立注册）

- [ ] **Step 9: 最终构建验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS

Run: `java -jar target/minion-0.1.0.jar`
Expected: 完整 GUI 可运行：工作空间 CRUD/切换、会话 CRUD/切换、发送/终止/摘要标题、Markdown 渲染、模型弹窗、高危操作确认弹窗、关闭窗口有运行会话时确认。

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: 移除 CLI 与 Ansi/ConsoleIo，Main 最终装配 GUI，README/ARCHITECTURE 同步"
```


---
