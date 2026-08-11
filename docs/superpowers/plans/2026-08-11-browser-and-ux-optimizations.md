# 浏览器自动化 + 开发体验优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 minion 新增浏览器自动化/网页调试工具(CDP 驱动 Chrome 109),并实施三项优化:Bash 会话级 cd(含文件工具跟随)、WebFetchTool 中文编码探测、`/new` `/delete` 会话管理。

**Architecture:** 浏览器工具走 CDP 协议(okhttp 自带 WebSocket,零新增依赖):ChromeLauncher 启动/探测 Chrome,CdpClient 封装命令/事件,BrowserSession 组合二者,4 个工具面向模型。cd 优化引入 Workspace 类(workDir 固定 + cwd 可变)注入 Bash 与文件工具,相对路径以 cwd 为基准,守卫边界仍为 workDir+skillsDir。会话管理在 SessionStore 加 delete、AgentLoop 加 startNewSession。

**Tech Stack:** JDK 8、Maven、gson、okhttp 3.14.9(WebSocket 内建)、jline、JUnit4、mockwebserver 3.14.9(WebSocket 模拟)。

**设计文档:** `docs/superpowers/specs/2026-08-10-browser-automation-design.md`

## Global Constraints

- JDK 8 兼容:不引入 JDK9+ API(`var`、`List.of`、`Optional.stream` 等)
- **零新增依赖**(浏览器工具用 okhttp 内建 WebSocket)
- 注释与文档用中文;commit 用 conventional 格式(中文描述)
- 工具错误返回失败态 `ToolResult`(不抛异常给界面)
- 新增配置项必须同步 `src/resource/config.properties`
- 每个任务结束 `mvn test` 通过后才算完成,再 commit
- 现有测试全部保持通过(FileToolsTest/EditToolsTest/BashToolTest/AgentLoopTest 等构造签名变化需同步适配)

---

### Task 1: Workspace 类(会话级工作区)

**Files:**
- Create: `src/main/java/com/minion/core/tools/Workspace.java`
- Test: `src/test/java/com/minion/core/tools/WorkspaceTest.java`

**Interfaces:**
- Consumes: 无(新类)
- Produces: `Workspace(String workDir)`;`String workDir()`;`Path cwd()`;`Path cd(String path)`(成功返回新 cwd,失败返回 null 且不切换);`void restore(String cwdStr)`(恢复会话用);`void resetCwd()`

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** Workspace:workDir 固定,cwd 可变,cd 仅限工作区内 */
public class WorkspaceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String workDir;
    private Workspace ws;

    @Before
    public void setUp() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(workDir);
    }

    @Test
    public void initialCwdIsWorkDir() {
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdIntoSubdir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        Path target = ws.cd("sub");
        assertNotNull(target);
        assertTrue(target.endsWith("sub"));
        assertEquals(target, ws.cwd());
    }

    @Test
    public void cdOutsideRejected() throws Exception {
        assertNull(ws.cd(".."));
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdUnknownDirRejected() {
        assertNull(ws.cd("不存在"));
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdEmptyReturnsToWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        ws.cd("sub");
        ws.cd("");
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void cdAbsoluteInsideWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        Path target = ws.cd(Paths.get(workDir, "sub").toAbsolutePath().toString());
        assertNotNull(target);
    }

    @Test
    public void cdAbsoluteOutsideRejected() {
        assertNull(ws.cd(tmp.newFolder().getAbsolutePath()));
    }

    @Test
    public void cdRelativeFromCurrentCwd() throws Exception {
        Files.createDirectories(Paths.get(workDir, "a", "b"));
        ws.cd("a");
        Path target = ws.cd("b");
        assertNotNull(target);
        assertTrue(target.endsWith("b"));
    }

    @Test
    public void restoreValidCwd() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        ws.restore(Paths.get(workDir, "sub").toAbsolutePath().toString());
        assertTrue(ws.cwd().endsWith("sub"));
    }

    @Test
    public void restoreInvalidCwdIgnored() throws Exception {
        ws.restore(Paths.get(workDir, "不存在").toAbsolutePath().toString());
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
        ws.restore(Paths.get(tmp.getRoot().getParent().getAbsolutePath()).toString());
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
        ws.restore(null);
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }

    @Test
    public void resetCwdReturnsToWorkDir() throws Exception {
        Files.createDirectories(Paths.get(workDir, "sub"));
        ws.cd("sub");
        ws.resetCwd();
        assertEquals(Paths.get(workDir).toAbsolutePath().normalize(), ws.cwd());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=WorkspaceTest`
Expected: 编译失败(`Workspace` 类不存在)

- [ ] **Step 3: 实现 Workspace**

```java
package com.minion.core.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 会话级工作区:workDir 固定(守卫边界),cwd 可变(Bash cd 更新,初始 = workDir)。
 * Bash 与文件工具共用同一实例:cd 后相对路径行为与 shell 一致。
 * cd 仅限工作区内,保证与 PathsGuard 守卫口径一致。
 */
public class Workspace {

    private final String workDir;
    private volatile Path cwd;

    public Workspace(String workDir) {
        this.workDir = workDir;
        this.cwd = Paths.get(workDir).toAbsolutePath().normalize();
    }

    public String workDir() { return workDir; }

    public Path cwd() { return cwd; }

    /**
     * 切换当前目录。成功返回新 cwd;目标不在工作区内或不存在返回 null(不切换)。
     * 空串/空白表示回到工作区根。
     */
    public Path cd(String path) {
        Path target;
        if (path == null || path.trim().isEmpty()) {
            target = Paths.get(workDir).toAbsolutePath().normalize();
        } else {
            target = cwd.resolve(path).normalize().toAbsolutePath();
        }
        if (!target.startsWith(Paths.get(workDir).toAbsolutePath().normalize())) return null;
        if (!Files.isDirectory(target)) return null;
        cwd = target;
        return cwd;
    }

    /** 恢复会话时用:路径有效则恢复,无效(已删除/越界)保持现状 */
    public void restore(String cwdStr) {
        if (cwdStr == null || cwdStr.isEmpty()) return;
        Path p = Paths.get(cwdStr).toAbsolutePath().normalize();
        if (p.startsWith(Paths.get(workDir).toAbsolutePath().normalize())
                && Files.isDirectory(p)) {
            cwd = p;
        }
    }

    /** 回到工作区根(新会话/清理时用) */
    public void resetCwd() {
        cwd = Paths.get(workDir).toAbsolutePath().normalize();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=WorkspaceTest`
Expected: 12 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/Workspace.java src/test/java/com/minion/core/tools/WorkspaceTest.java
git commit -m "feat: 新增 Workspace 会话级工作区(cd 持久化的基础)"
```

---

### Task 2: BashTool 支持 cd 持久化

**Files:**
- Modify: `src/main/java/com/minion/core/tools/BashTool.java`
- Modify: `src/main/java/com/minion/Main.java`(BashTool 构造调用点)
- Test: `src/test/java/com/minion/core/tools/BashToolTest.java`(构造适配 + 新增 cd 用例)

**Interfaces:**
- Consumes: `Workspace`(Task 1)
- Produces: BashTool 构造改为 `BashTool(Workspace workspace)`;纯 `cd <dir>` 命令持久化 cwd,其余命令在 `workspace.cwd()` 下执行

- [ ] **Step 1: 写失败测试(先适配现有测试构造,再加 cd 用例)**

打开 `src/test/java/com/minion/core/tools/BashToolTest.java`,把构造调用点改为 `new BashTool(new Workspace(workDir))(workDir 为测试临时目录变量)`。新增用例:

```java
@Test
public void cdPersistsAcrossCommands() throws Exception {
    Files.createDirectories(Paths.get(workDir, "sub"));
    BashTool tool = new BashTool(new Workspace(workDir));
    ToolResult r1 = tool.execute(json("command", "cd sub"));
    assertTrue(r1.output, r1.output.contains("当前目录"));
    ToolResult r2 = tool.execute(json("command", "pwd"));
    assertTrue(r2.output, r2.output.contains("sub"));
}

@Test
public void cdOutsideWorkDirRejected() {
    BashTool tool = new BashTool(new Workspace(workDir));
    ToolResult r = tool.execute(json("command", "cd .."));
    assertTrue(r.output, r.output.contains("失败"));
    ToolResult r2 = tool.execute(json("command", "pwd"));
    assertFalse(r2.output.contains(".."));
}

@Test
public void cdUnknownDirRejected() {
    BashTool tool = new BashTool(new Workspace(workDir));
    ToolResult r = tool.execute(json("command", "cd 不存在"));
    assertTrue(r.output, r.output.contains("失败"));
}

@Test
public void cdCompoundCommandNotPersisted() throws Exception {
    Files.createDirectories(Paths.get(workDir, "sub"));
    BashTool tool = new BashTool(new Workspace(workDir));
    // cd a && pwd 走 shell:进程内生效,不持久化
    ToolResult r1 = tool.execute(json("command", "cd sub && pwd"));
    assertTrue(r1.output, r1.output.contains("sub"));
    ToolResult r2 = tool.execute(json("command", "pwd"));
    assertFalse(r2.output, r2.output.contains("sub"));
}

@Test
public void cdNoArgReturnsToWorkDir() throws Exception {
    Files.createDirectories(Paths.get(workDir, "sub"));
    BashTool tool = new BashTool(new Workspace(workDir));
    tool.execute(json("command", "cd sub"));
    tool.execute(json("command", "cd"));
    ToolResult r = tool.execute(json("command", "pwd"));
    assertFalse(r.output.contains("sub"));
}
```

(`json(...)` 为 BashToolTest 现有构造 JsonObject 的辅助方法;若不存在,用 `new JsonObject()` + `addProperty` 构造,参考现有用例风格。`workDir` 为测试类里临时目录字段。)

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=BashToolTest`
Expected: 编译失败(BashTool 构造签名已改)或 cd 用例失败

- [ ] **Step 3: 实现 BashTool 改动**

```java
// 1) 类顶部新增 import
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 2) 字段与构造改为 Workspace
private final Workspace workspace;

public BashTool(Workspace workspace) { this.workspace = workspace; }

// 3) 纯 cd 命令识别(整命令只有 cd [dir],不含 && ; | 等)
private static final Pattern CD_PATTERN =
        Pattern.compile("^cd(?:[ \\t]+(\\S+))?[ \\t]*$");

// 4) execute() 开头(参数校验通过后)插入:
        String trimmed = command.trim();
        Matcher cdM = CD_PATTERN.matcher(trimmed);
        if (cdM.matches()) {
            Path target = workspace.cd(cdM.group(1));
            if (target == null) {
                return ToolResult.error("cd 失败: 目录不存在或在工作路径之外(cd 仅限工作区内),当前目录: " + workspace.cwd());
            }
            return ToolResult.success("当前目录: " + target);
        }

// 5) 执行目录改为 cwd(原 pb.directory(new File(workDir)) 一行替换):
        pb.directory(workspace.cwd().toFile());
```

新增 import:`java.nio.file.Path`。

`Main.java` 同步改(该行):

```java
registry.register(new BashTool(workspace));
```

(workspace 变量在 Task 4 才定义,此时先临时在 BashTool 注册行前定义 `Workspace workspace = new Workspace(workDir);` 保证编译;Task 4 会挪到统一位置。)

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=BashToolTest`
Expected: 全部通过(含新增 5 个 cd 用例)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/BashTool.java src/main/java/com/minion/Main.java src/test/java/com/minion/core/tools/BashToolTest.java
git commit -m "feat: Bash 工具支持会话级 cd 持久化"
```

---

### Task 3: 文件工具相对路径跟随 cwd

**Files:**
- Modify: `src/main/java/com/minion/core/tools/ReadTool.java`
- Modify: `src/main/java/com/minion/core/tools/WriteTool.java`
- Modify: `src/main/java/com/minion/core/tools/EditTool.java`
- Modify: `src/main/java/com/minion/core/tools/GlobTool.java`
- Modify: `src/main/java/com/minion/core/tools/GrepTool.java`
- Modify: `src/main/java/com/minion/Main.java`(5 个工具构造调用点)
- Test: `src/test/java/com/minion/core/tools/FileToolsTest.java`、`src/test/java/com/minion/core/tools/EditToolsTest.java`(构造适配)
- Test: `src/test/java/com/minion/core/tools/WorkspacePathTest.java`(新增)

**Interfaces:**
- Consumes: `Workspace`(Task 1)
- Produces: 5 个文件工具构造改为 `(Workspace workspace, String skillsDir)` 与 `(Workspace workspace)`(单参保留 skillsDir=null);相对路径以 `workspace.cwd()` 为基准解析,守卫边界仍为 `workspace.workDir()` + skillsDir

- [ ] **Step 1: 写失败测试(WorkspacePathTest 新增)**

```java
package com.minion.core.tools;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** cd 后文件工具相对路径以 cwd 为基准 */
public class WorkspacePathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String workDir;

    private static JsonObject json(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    @Test
    public void readRelativeToCwd() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        Files.createDirectories(Paths.get(workDir, "sub"));
        Files.write(Paths.get(workDir, "sub", "a.txt"), "hello-cwd".getBytes(StandardCharsets.UTF_8));
        Workspace ws = new Workspace(workDir);
        ws.cd("sub");
        ReadTool tool = new ReadTool(ws);
        ToolResult r = tool.execute(json("path", "a.txt"));
        assertTrue(r.output, r.output.contains("hello-cwd"));
    }

    @Test
    public void writeRelativeToCwd() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        Files.createDirectories(Paths.get(workDir, "sub"));
        Workspace ws = new Workspace(workDir);
        ws.cd("sub");
        WriteTool tool = new WriteTool(ws);
        ToolResult r = tool.execute(json2("path", "b.txt", "content", "x"));
        assertTrue(r.output, r.output.contains("b.txt"));
        assertTrue(Files.exists(Paths.get(workDir, "sub", "b.txt")));
    }

    @Test
    public void globRelativeToCwd() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        Files.createDirectories(Paths.get(workDir, "sub"));
        Files.write(Paths.get(workDir, "sub", "g.java"), new byte[0]);
        Files.write(Paths.get(workDir, "g.java"), new byte[0]);
        Workspace ws = new Workspace(workDir);
        ws.cd("sub");
        GlobTool tool = new GlobTool(ws);
        ToolResult r = tool.execute(json("pattern", "*.java"));
        assertTrue(r.output, r.output.contains("g.java"));
        assertTrue(r.output, !r.output.contains(".."));
    }

    private static JsonObject json2(String k1, String v1, String k2, String v2) {
        JsonObject o = new JsonObject();
        o.addProperty(k1, v1);
        o.addProperty(k2, v2);
        return o;
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=WorkspacePathTest`
Expected: 编译失败(新构造签名不存在)

- [ ] **Step 3: 实现 5 个工具改动**

每个文件:字段 `private final String workDir;` 改为 `private final Workspace workspace;`;构造改 `(Workspace workspace, String skillsDir)` + 保留 `(Workspace workspace)` 单参构造;以下各点:

**ReadTool**(`execute` 内两处):
```java
Path p = PathsGuard.resolve(workspace.cwd().toString(), path);
ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, p);
```

**WriteTool**(`isHighRisk` 与 `execute` 内 resolve 基准;`outsideGuard` 的 workDir 引用全换 `workspace.workDir()`):
```java
Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
```
(两处;`outsideGuard` 中 `Paths.get(workDir).toRealPath()` → `Paths.get(workspace.workDir()).toRealPath()`,`insideLexical(workDir, ...)` → `insideLexical(workspace.workDir(), ...)`)

**EditTool**(`execute` 内):
```java
Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, p);
```

**GlobTool**(`execute` 内遍历根与输出基准):
```java
final Path workRoot = workspace.cwd();
final List<Path> roots = new ArrayList<Path>();
roots.add(workRoot);
if (skillsDir != null && !skillsDir.isEmpty() && Files.isDirectory(Paths.get(skillsDir))) {
    Path skillsAbs = Paths.get(skillsDir).toAbsolutePath().normalize();
    if (!skillsAbs.startsWith(workRoot.toAbsolutePath().normalize())) roots.add(skillsAbs);
}
```
(其余逻辑不变:relativize/inWork 判定基于 workRoot)

**GrepTool**(`execute` 内):
```java
final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, root);
final Path rootAbs = workspace.cwd().toAbsolutePath().normalize();
...
Path rel = rootInWork ? workspace.cwd().relativize(file) : file;
```

**Main.java**(5 行注册处):
```java
registry.register(new ReadTool(workspace, skillsDir));
registry.register(new WriteTool(workspace, skillsDir));
registry.register(new EditTool(workspace, skillsDir));
registry.register(new GlobTool(workspace, skillsDir));
registry.register(new GrepTool(workspace, skillsDir));
```

- [ ] **Step 4: 适配现有测试构造并跑全量**

`FileToolsTest.java`、`EditToolsTest.java` 中所有 `new ReadTool(...)`/`new WriteTool(...)`/`new EditTool(...)`/`new GlobTool(...)`/`new GrepTool(...)` 调用点改为传 `new Workspace(测试临时目录)` 实例(文件内先 `Workspace ws = new Workspace(workDir);` 复用)。

Run: `mvn test`
Expected: 全部通过(含新增 WorkspacePathTest 3 个用例)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/ReadTool.java src/main/java/com/minion/core/tools/WriteTool.java src/main/java/com/minion/core/tools/EditTool.java src/main/java/com/minion/core/tools/GlobTool.java src/main/java/com/minion/core/tools/GrepTool.java src/main/java/com/minion/Main.java src/test/java/com/minion/core/tools/WorkspacePathTest.java src/test/java/com/minion/core/tools/FileToolsTest.java src/test/java/com/minion/core/tools/EditToolsTest.java
git commit -m "feat: 文件工具相对路径跟随会话 cwd"
```

---

### Task 4: 会话装配与 cwd 持久化

**Files:**
- Modify: `src/main/java/com/minion/core/agent/Session.java`
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Modify: `src/main/java/com/minion/Main.java`
- Test: `src/test/java/com/minion/core/storage/SessionStoreTest.java`(cwd 序列化)
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`、`AgentLoopCompactTest.java`、`ReplDispatchTest.java`(构造适配)

**Interfaces:**
- Consumes: `Workspace`(Task 1);`Session` 现有结构
- Produces: `Session.cwd`(String,可空);`AgentLoop` 构造新增 `Workspace` 参数(末位);`AgentLoop.restoreSession` 恢复 cwd;`AgentLoop.startNewSession()`;`Workspace.resetCwd()`(Task 1 已有)

- [ ] **Step 1: 写失败测试**

`SessionStoreTest.java` 新增(先看现有用例风格,追加):

```java
@Test
public void sessionCwdSerialized() throws Exception {
    Session s = Session.create(new Config());
    s.cwd = "/tmp/some/dir";
    Path f = store.save(s);
    Session loaded = store.load(s.id);
    assertEquals("/tmp/some/dir", loaded.cwd);
}
```

(`store` 为 SessionStoreTest 现有临时目录字段;`Config` 构造若需要参数,参考现有用例的创建方式。)

`AgentLoopTest` 等现有测试的 `new AgentLoop(...)` 调用点末尾追加 `, new Workspace(临时目录)` 参数(全文件统一)。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=SessionStoreTest,AgentLoopTest`
Expected: cwd 断言失败(null)或编译失败(构造签名)

- [ ] **Step 3: 实现**

**Session.java** — 字段区追加:
```java
    /** 会话级工作目录(相对路径基准);null/空 = 跟随工作区根 */
    public String cwd;
```

**AgentLoop.java**:
```java
// 字段区追加:
    private final Workspace workspace;

// 构造(两个构造都改:第 2 个完整构造加末位参数):
    public AgentLoop(Config config, LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui,
                     ContextManager contextManager, Workspace workspace) {
        ...
        this.workspace = workspace;
// 6 参构造委托处追加 workspace 参数(Config.workDir() 已有,Config.java:104):
    public AgentLoop(Config config, LlmClient llm, ToolRegistry registry,
                     SystemPromptBuilder promptBuilder, ConfirmGate confirmGate, AgentUi ui) {
        this(config, llm, registry, promptBuilder, confirmGate, ui, null,
                new Workspace(config.workDir()));
    }

// restoreSession 末尾追加:
        workspace.restore(s.cwd);

// 新增方法(会话内容清理):
    /** /new:清空当前会话内容并回到工作区根 */
    public void startNewSession() {
        session.messages.clear();
        session.todos = new TodoList();
        session.usage = new UsageTracker();
        workspace.resetCwd();
    }
```
import 追加:`com.minion.core.tools.Workspace`。

**Main.java** — 把 Task 2 里临时定义的 workspace 挪到统一位置(registry 注册之前),并传给 AgentLoop:
```java
        Workspace workspace = new Workspace(workDir);
        registry.register(new ReadTool(workspace, skillsDir));
        registry.register(new WriteTool(workspace, skillsDir));
        registry.register(new EditTool(workspace, skillsDir));
        registry.register(new GlobTool(workspace, skillsDir));
        registry.register(new GrepTool(workspace, skillsDir));
        registry.register(new BashTool(workspace));
        ...
        AgentLoop loop = new AgentLoop(config, llm, registry,
                new SystemPromptBuilder(config), confirm, renderer, ctx, workspace);
```
import 追加:`com.minion.core.tools.Workspace`。

- [ ] **Step 4: 跑全量测试确认通过**

Run: `mvn test`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/agent/Session.java src/main/java/com/minion/core/agent/AgentLoop.java src/main/java/com/minion/Main.java src/test/java/com/minion/core/storage/SessionStoreTest.java src/test/java/com/minion/core/agent/AgentLoopTest.java src/test/java/com/minion/core/agent/AgentLoopCompactTest.java src/test/java/com/minion/cli/ReplDispatchTest.java
git commit -m "feat: 会话 cwd 持久化与 AgentLoop 装配 Workspace"
```

---

### Task 5: WebFetchTool 中文编码探测

**Files:**
- Modify: `src/main/java/com/minion/core/tools/WebFetchTool.java`
- Test: `src/test/java/com/minion/core/tools/WebFetchToolTest.java`

**Interfaces:**
- Consumes: 无(WebFetchTool 现有结构)
- Produces: `static Charset detectCharset(String contentType, byte[] head)`;`RawBody` 改持 `byte[]`;GBK/GB2312/GB18030 页面正确解码

- [ ] **Step 1: 写失败测试(追加到 WebFetchToolTest)**

```java
@Test
public void gbkPageDecodedByHeader() throws Exception {
    String html = "<html><body>中文标题</body></html>";
    server.enqueue(new MockResponse()
            .setBody(html.getBytes(Charset.forName("GBK")))
            .addHeader("Content-Type", "text/html; charset=GBK"));
    ToolResult r = new WebFetchTool().execute(json("url", server.url("/").toString()));
    assertTrue(r.output, r.output.contains("中文标题"));
}

@Test
public void gbkPageDecodedByMeta() throws Exception {
    String html = "<html><head><meta charset=\"gb2312\"></head><body>查询结果</body></html>";
    server.enqueue(new MockResponse()
            .setBody(html.getBytes(Charset.forName("GBK")))
            .addHeader("Content-Type", "text/html"));
    ToolResult r = new WebFetchTool().execute(json("url", server.url("/").toString()));
    assertTrue(r.output, r.output.contains("查询结果"));
}

@Test
public void utf8PageWithoutDeclStillWorks() throws Exception {
    server.enqueue(new MockResponse()
            .setBody("<html><body>正常内容</body></html>".getBytes(StandardCharsets.UTF_8))
            .addHeader("Content-Type", "text/html"));
    ToolResult r = new WebFetchTool().execute(json("url", server.url("/").toString()));
    assertTrue(r.output, r.output.contains("正常内容"));
}
```

(参考现有用例的 `server`、`json(...)` 辅助。`MockResponse.setBody(byte[])` 与 `addHeader` 为 okhttp mockwebserver 3.14 API。)

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=WebFetchToolTest`
Expected: 前 2 个新用例失败(乱码)

- [ ] **Step 3: 实现 WebFetchTool 改动**

```java
// import 追加:
import java.nio.charset.Charset;
// 常量追加:
    /** charset 探测段长度(字节):只扫 HTML 头部声明 */
    private static final int CHARSET_PROBE_BYTES = 2048;
// 类级追加:
    /** meta charset / http-equiv 声明正则 */
    private static final Pattern META_CHARSET = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_EQUIV_CHARSET = Pattern.compile(
            "content\\s*=\\s*[\"'][^\"']*charset\\s*=\\s*([\\w-]+)", Pattern.CASE_INSENSITIVE);

// execute() 内 RawBody 改 bytes(原两行):
        RawBody raw = readBody(response);
        Charset cs = detectCharset(contentType, raw.bytes);
        String text = stripHtml(new String(raw.bytes, cs));

// readBody 返回 byte[]:
    private static RawBody readBody(Response response) throws IOException {
        BufferedSource source = response.body().source();
        ByteArrayOutputStream out = new ByteArrayOutputStream(READ_LIMIT);
        byte[] chunk = new byte[READ_CHUNK];
        int n;
        while (out.size() < READ_LIMIT) {
            n = source.read(chunk, 0, chunk.length);
            if (n == -1) break;
            out.write(chunk, 0, n);
        }
        return new RawBody(out.toByteArray(), out.size() >= READ_LIMIT);
    }

// RawBody 字段改 byte[]:
    private static final class RawBody {
        final byte[] bytes;
        final boolean cut;
        RawBody(byte[] bytes, boolean cut) { this.bytes = bytes; this.cut = cut; }
    }

// 新增检测方法:
    /**
     * 解码字符集:Content-Type 头 charset 优先,其次 HTML meta 声明,均无回退 UTF-8。
     * GBK/GB2312/GB18030 统一映射 GBK。探测段按 ISO-8859-1 解码(字节↔字符 1:1,meta 检测不受解码影响)。
     */
    static Charset detectCharset(String contentType, byte[] head) {
        String cs = charsetFromHeader(contentType);
        if (cs == null) {
            String probe = new String(head, 0, Math.min(head.length, CHARSET_PROBE_BYTES),
                    StandardCharsets.ISO_8859_1);
            cs = charsetFromMeta(probe);
        }
        if (cs == null) return StandardCharsets.UTF_8;
        if (cs.equalsIgnoreCase("GBK") || cs.equalsIgnoreCase("GB2312")
                || cs.equalsIgnoreCase("GB18030")) {
            return charsetOrUtf8("GBK");
        }
        return charsetOrUtf8(cs);
    }

    private static String charsetFromHeader(String contentType) {
        if (contentType == null) return null;
        Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    private static String charsetFromMeta(String head) {
        Matcher m1 = META_CHARSET.matcher(head);
        if (m1.find()) return m1.group(1);
        Matcher m2 = HTTP_EQUIV_CHARSET.matcher(head);
        return m2.find() ? m2.group(1) : null;
    }

    private static Charset charsetOrUtf8(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=WebFetchToolTest`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/WebFetchTool.java src/test/java/com/minion/core/tools/WebFetchToolTest.java
git commit -m "feat: WebFetch 支持 GBK 等中文编码页面探测解码"
```

---

### Task 6: `/new` `/delete` 会话管理

**Files:**
- Modify: `src/main/java/com/minion/core/storage/SessionStore.java`
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Modify: `src/main/java/com/minion/cli/CommandDispatcher.java`
- Modify: `src/main/java/com/minion/cli/Repl.java`
- Test: `src/test/java/com/minion/core/storage/SessionStoreTest.java`
- Test: `src/test/java/com/minion/cli/CommandDispatcherTest.java`

**Interfaces:**
- Consumes: `AgentLoop.startNewSession()`(Task 4)
- Produces: `SessionStore.delete(String id)`;`Command.NEW`/`Command.DELETE`;`/new` 落盘当前会话后开新会话;`/delete` 列表选择删除

- [ ] **Step 1: 写失败测试**

`SessionStoreTest` 追加:
```java
@Test
public void deleteRemovesSession() throws Exception {
    Session s = Session.create(new Config());
    store.save(s);
    assertNotNull(store.load(s.id));
    store.delete(s.id);
    assertFalse(Files.exists(Paths.get(storeDir, s.id + ".json")));
    try {
        store.load(s.id);
        fail("应抛 IOException");
    } catch (IOException expected) { }
}

@Test
public void deleteMissingIdSilent() throws Exception {
    store.delete("不存在的会话id");
}
```
(字段名按现有测试的实际字段适配:`storeDir`/`dir`。)

`CommandDispatcherTest` 追加:
```java
@Test
public void dispatchNewAndDelete() {
    assertEquals(CommandDispatcher.Command.NEW, dispatcher.dispatch("/new"));
    assertEquals(CommandDispatcher.Command.DELETE, dispatcher.dispatch("/delete"));
}
```
(dispatcher 为现有测试的实例。)

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=SessionStoreTest,CommandDispatcherTest`
Expected: 编译失败(方法/枚举不存在)

- [ ] **Step 3: 实现**

**SessionStore.java** 追加:
```java
    /** 删除会话文件(不存在也静默成功) */
    public void delete(String id) throws IOException {
        Files.deleteIfExists(dir.resolve(id + ".json"));
    }
```

**CommandDispatcher.java**:
```java
// 枚举追加:
    public enum Command { HELP, EXIT, SKILLS, SKILL, RESUME, COMPACT, TOKENS, CLEAR, MODEL, NEW, DELETE }

// dispatch switch 追加两个 case:
            case "/new":
                return Command.NEW;
            case "/delete":
                return Command.DELETE;
```

**Repl.java** — `handleCommand` switch 追加:
```java
                case NEW:
                    try {
                        store.save(loop.session());
                        System.out.println("已保存当前会话");
                    } catch (Exception e) {
                        System.out.println("保存当前会话失败: " + e.getMessage());
                    }
                    loop.startNewSession();
                    System.out.println("已开始新会话");
                    break;
                case DELETE:
                    deleteFlow();
                    break;
```

`resumeFlow` 之后新增 `deleteFlow`(结构同 resumeFlow):
```java
    private void deleteFlow() {
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
            System.out.print("输入要删除的会话编号（回车取消）: ");
            String line;
            try {
                line = reader.readLine();
            } catch (org.jline.reader.UserInterruptException e) {
                return;
            } catch (org.jline.reader.EndOfFileException e) {
                return;
            }
            if (line == null || line.trim().isEmpty()) return;
            int idx = Integer.parseInt(line.trim()) - 1;
            if (idx < 0 || idx >= metas.size()) {
                System.out.println("无效编号");
                return;
            }
            store.delete(metas.get(idx).id);
            System.out.println("已删除会话 " + metas.get(idx).createdAt);
        } catch (Exception e) {
            System.out.println("删除失败: " + e.getMessage());
        }
    }
```

`helpText()` 追加两行:
```java
                + "  /new          保存当前会话并开始新会话\n"
                + "  /delete       删除历史会话\n"
```

- [ ] **Step 4: 跑全量测试确认通过**

Run: `mvn test`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/storage/SessionStore.java src/main/java/com/minion/core/agent/AgentLoop.java src/main/java/com/minion/cli/CommandDispatcher.java src/main/java/com/minion/cli/Repl.java src/test/java/com/minion/core/storage/SessionStoreTest.java src/test/java/com/minion/cli/CommandDispatcherTest.java
git commit -m "feat: 新增 /new /delete 会话管理命令"
```

---

### Task 7: Config 新增 browser.* 配置项

**Files:**
- Modify: `src/main/java/com/minion/core/config/Config.java`
- Modify: `src/resource/config.properties`
- Test: `src/test/java/com/minion/core/config/ConfigTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `Config.browserPath()`(String,""),`browserPort()`(int,9222),`browserUserDataDir()`(String,"./.minion/browser-profile"),`browserHeadless()`(boolean,false),`browserTimeoutMs()`(int,30000)

- [ ] **Step 1: 写失败测试**

`ConfigTest` 追加(按现有用例的加载方式):
```java
@Test
public void browserDefaults() throws Exception {
    Config c = Config.load(tmpDir); // 空外部配置 → 走默认值
    assertEquals("", c.browserPath());
    assertEquals(9222, c.browserPort());
    assertEquals("./.minion/browser-profile", c.browserUserDataDir());
    assertFalse(c.browserHeadless());
    assertEquals(30000, c.browserTimeoutMs());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=ConfigTest`
Expected: 编译失败(方法不存在)

- [ ] **Step 3: 实现**

**Config.java** 追加(getter 区):
```java
    public String browserPath()       { return get("browser.path", ""); }
    public int browserPort()          { return Integer.parseInt(get("browser.port", "9222")); }
    public String browserUserDataDir(){ return get("browser.userDataDir", "./.minion/browser-profile"); }
    public boolean browserHeadless()  { return Boolean.parseBoolean(get("browser.headless", "false")); }
    public int browserTimeoutMs()     { return Integer.parseInt(get("browser.timeoutMs", "30000")); }
```

**src/resource/config.properties** 追加:
```
# ===== 浏览器工具(CDP 驱动 Chrome,需 Chrome 109+) =====
browser.path=
browser.port=9222
browser.userDataDir=./.minion/browser-profile
browser.headless=false
browser.timeoutMs=30000
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=ConfigTest`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/config/Config.java src/resource/config.properties src/test/java/com/minion/core/config/ConfigTest.java
git commit -m "feat: 新增 browser.* 配置项(浏览器工具)"
```

---

### Task 8: CdpClient(CDP WebSocket 协议封装)

**Files:**
- Create: `src/main/java/com/minion/core/tools/browser/CdpClient.java`
- Test: `src/test/java/com/minion/core/tools/browser/CdpClientTest.java`

**Interfaces:**
- Consumes: 无(新类;okhttp 3.14 WebSocket)
- Produces: `CdpClient(int connectTimeoutMs, int commandTimeoutMs)`;`boolean isConnected()`;`void connect(String wsUrl)`;`JsonObject command(String method, JsonObject params)`;`List<JsonObject> events(String methodPrefix)`

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/** CDP 客户端:命令/响应 id 匹配、事件缓冲、超时与断线 */
public class CdpClientTest {

    private MockWebServer server;
    private WebSocket serverWs;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) { serverWs = ws; }
            @Override
            public void onMessage(WebSocket ws, String text) {
                // 自动应答:收到命令回同 id 的 result(网络事件除外,事件不发 id)
                JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                if (!msg.has("id")) return;
                int id = msg.get("id").getAsInt();
                JsonObject resp = new JsonObject();
                resp.addProperty("id", id);
                if ("Runtime.evaluate".equals(msg.get("method").getAsString())) {
                    JsonObject value = new JsonObject();
                    value.addProperty("value", "42");
                    JsonObject result = new JsonObject();
                    result.add("result", value);
                    resp.add("result", result);
                } else {
                    resp.add("result", new JsonObject());
                }
                ws.send(resp.toString());
            }
        }));
        server.start();
    }

    @After
    public void tearDown() throws Exception { server.shutdown(); }

    private String wsUrl() {
        return "ws://" + server.getHostName() + ":" + server.getPort() + "/devtools/page/1";
    }

    private void waitServerWs() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (serverWs == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertNotNull("服务端 WebSocket 未建立", serverWs);
    }

    @Test
    public void commandRoundTrip() throws Exception {
        CdpClient client = new CdpClient(5000, 5000);
        client.connect(wsUrl());
        waitServerWs();
        JsonObject params = new JsonObject();
        params.addProperty("expression", "1+1");
        JsonObject result = client.command("Runtime.evaluate", params);
        assertEquals("42", result.getAsJsonObject("result").get("value").getAsString());
    }

    @Test
    public void eventsBufferedByPrefix() throws Exception {
        CdpClient client = new CdpClient(5000, 5000);
        client.connect(wsUrl());
        waitServerWs();
        serverWs.send("{\"method\":\"Runtime.consoleAPICalled\",\"params\":{\"type\":\"log\",\"args\":[]}}");
        serverWs.send("{\"method\":\"Network.requestWillBeSent\",\"params\":{}}");
        // 等待事件到达(异步推送)
        long deadline = System.currentTimeMillis() + 5000;
        while (client.events("Runtime").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(1, client.events("Runtime.consoleAPICalled").size());
        assertEquals(1, client.events("Network").size());
        assertEquals(0, client.events("Page").size());
    }

    @Test
    public void commandTimeout() throws Exception {
        // 不回应的服务端
        MockWebServer silent = new MockWebServer();
        silent.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) { }
        }));
        silent.start();
        try {
            CdpClient client = new CdpClient(5000, 500);
            client.connect("ws://" + silent.getHostName() + ":" + silent.getPort() + "/devtools/page/1");
            try {
                client.command("Page.navigate", new JsonObject());
                fail("应抛超时 IOException");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("超时"));
            }
        } finally {
            silent.shutdown();
        }
    }

    @Test
    public void commandAfterDisconnectFails() throws Exception {
        CdpClient client = new CdpClient(5000, 5000);
        client.connect(wsUrl());
        waitServerWs();
        serverWs.close(1000, "bye");
        long deadline = System.currentTimeMillis() + 5000;
        while (client.isConnected() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        try {
            client.command("Page.navigate", new JsonObject());
            fail("应抛 IOException");
        } catch (IOException expected) { }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=CdpClientTest`
Expected: 编译失败(CdpClient 不存在)

- [ ] **Step 3: 实现 CdpClient**

```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CDP 客户端:okhttp WebSocket 直连 Chrome 调试端点。
 * 命令/响应按自增 id 匹配;事件推入环形缓冲(上限 MAX_EVENTS)供 BrowserDebug 查询。
 */
public class CdpClient extends WebSocketListener {

    private static final int MAX_EVENTS = 500;

    private final int connectTimeoutMs;
    private final int commandTimeoutMs;
    private final List<JsonObject> events = new CopyOnWriteArrayList<JsonObject>();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<Integer, Pending>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile WebSocket socket;
    private volatile String error;
    private volatile boolean connected;
    private CountDownLatch openLatch;

    public CdpClient(int connectTimeoutMs, int commandTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public boolean isConnected() { return connected; }

    /** 连接(阻塞至握手完成或超时);失败抛 IOException */
    public void connect(String wsUrl) throws IOException {
        if (connected) return;
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接,命令层自行控制超时
                .build();
        openLatch = new CountDownLatch(1);
        socket = client.newWebSocket(new Request.Builder().url(wsUrl).build(), this);
        try {
            if (!openLatch.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("连接 Chrome 超时: " + wsUrl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("连接被中断");
        }
        if (error != null) throw new IOException("连接失败: " + error);
        connected = true;
    }

    /** 发送命令并等待响应;未连接/超时/协议错误/断线抛 IOException */
    public JsonObject command(String method, JsonObject params) throws IOException {
        if (!connected) throw new IOException("浏览器未连接,请先执行 Browser 工具");
        int id = nextId.getAndIncrement();
        JsonObject msg = new JsonObject();
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        if (params != null) msg.add("params", params);
        Pending p = new Pending();
        pending.put(id, p);
        socket.send(msg.toString());
        synchronized (p) {
            long deadline = System.currentTimeMillis() + commandTimeoutMs;
            while (p.result == null && p.err == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    pending.remove(id);
                    throw new IOException("CDP 命令超时(" + commandTimeoutMs + "ms): " + method);
                }
                try { p.wait(remain); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending.remove(id);
                    throw new IOException("命令等待被中断: " + method);
                }
            }
        }
        pending.remove(id);
        if (p.err != null) throw new IOException(p.err);
        if (p.result.has("error")) {
            String msg2 = p.result.getAsJsonObject("error").has("message")
                    ? p.result.getAsJsonObject("error").get("message").getAsString() : "未知错误";
            throw new IOException("CDP 命令失败 " + method + ": " + msg2);
        }
        return p.result.has("result") ? p.result.getAsJsonObject("result") : new JsonObject();
    }

    /** 按方法名前缀取事件(网络/console 调试用) */
    public List<JsonObject> events(String methodPrefix) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonObject e : events) {
            if (e.has("method") && e.get("method").getAsString().startsWith(methodPrefix)) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public void onOpen(WebSocket ws, Response response) {
        socket = ws;
        if (openLatch != null) openLatch.countDown();
    }

    @Override
    public void onMessage(WebSocket ws, String text) {
        try {
            JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
            if (msg.has("id") && !msg.get("id").isJsonNull()) {
                Pending p = pending.get(msg.get("id").getAsInt());
                if (p != null) {
                    synchronized (p) { p.result = msg; p.notifyAll(); }
                }
            } else if (msg.has("method")) {
                events.add(msg);
                if (events.size() > MAX_EVENTS) events.remove(0);
            }
        } catch (Exception ignored) { } // 非 JSON 消息忽略
    }

    @Override
    public void onFailure(WebSocket ws, Throwable t, Response response) {
        connected = false;
        error = t == null ? "连接断开" : t.getMessage();
        if (openLatch != null) openLatch.countDown();
        for (Pending p : pending.values()) {
            synchronized (p) { p.err = error; p.notifyAll(); }
        }
        pending.clear();
    }

    private static class Pending {
        JsonObject result;
        String err;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=CdpClientTest`
Expected: 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/browser/CdpClient.java src/test/java/com/minion/core/tools/browser/CdpClientTest.java
git commit -m "feat: 新增 CdpClient(CDP WebSocket 协议封装)"
```

---

### Task 9: ChromeLauncher

**Files:**
- Create: `src/main/java/com/minion/core/tools/browser/ChromeLauncher.java`
- Test: `src/test/java/com/minion/core/tools/browser/ChromeLauncherTest.java`

**Interfaces:**
- Consumes: 无(新类;okhttp HTTP 轮询 /json)
- Produces: `ChromeLauncher(String chromePath, int port, Path userDataDir, boolean headless, int readyTimeoutMs)`;`String pageEndpoint()`(启动/复用 Chrome,返回页面 ws 端点);`void stop()`;包内可见 `static String pageEndpoint(String json)`、`List<String> buildCommand(String chrome)`(可测)

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools.browser;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class ChromeLauncherTest {

    private static final String JSON_LIST =
            "[{\"type\":\"page\",\"url\":\"about:blank\",\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/page/ABC\"},"
            + "{\"type\":\"other\",\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/devtools/other/XYZ\"}]";

    @Test
    public void pageEndpointPicksFirstPage() {
        assertEquals("ws://127.0.0.1:9222/devtools/page/ABC",
                ChromeLauncher.pageEndpoint(JSON_LIST));
    }

    @Test
    public void pageEndpointEmptyWithoutPage() {
        assertNull(ChromeLauncher.pageEndpoint("[{\"type\":\"other\"}]"));
        assertNull(ChromeLauncher.pageEndpoint("不是 json"));
    }

    @Test
    public void buildCommandHeadless() {
        ChromeLauncher launcher = new ChromeLauncher("C:\\chrome.exe", 9222,
                Paths.get("C:\\profile"), true, 10000);
        List<String> cmd = launcher.buildCommand("C:\\chrome.exe");
        assertTrue(cmd.contains("--remote-debugging-port=9222"));
        assertTrue(cmd.contains("--user-data-dir=C:\\profile"));
        assertTrue(cmd.contains("--headless=new"));
    }

    @Test
    public void buildCommandHeadedNoHeadlessFlag() {
        ChromeLauncher launcher = new ChromeLauncher("C:\\chrome.exe", 9223,
                Paths.get("C:\\profile"), false, 10000);
        List<String> cmd = launcher.buildCommand("C:\\chrome.exe");
        assertFalse(cmd.contains("--headless"));
        assertTrue(cmd.contains("--remote-debugging-port=9223"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=ChromeLauncherTest`
Expected: 编译失败(ChromeLauncher 不存在)

- [ ] **Step 3: 实现 ChromeLauncher**

```java
package com.minion.core.tools.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Chrome 进程管理:优先复用已在调试端口监听的实例,否则拉起新进程(有头/无头)。
 * 就绪判定:轮询 http://127.0.0.1:port/json 直至返回页面端点。
 */
public class ChromeLauncher {

    private final String chromePath;   // 空 = 自动探测
    private final int port;
    private final Path userDataDir;
    private final boolean headless;
    private final int readyTimeoutMs;
    private Process process;

    public ChromeLauncher(String chromePath, int port, Path userDataDir,
                          boolean headless, int readyTimeoutMs) {
        this.chromePath = chromePath;
        this.port = port;
        this.userDataDir = userDataDir;
        this.headless = headless;
        this.readyTimeoutMs = readyTimeoutMs;
    }

    /** 确保 Chrome 就绪并返回页面调试端点 ws://.../devtools/page/<id>;失败抛 IOException */
    public String pageEndpoint() throws Exception {
        // 1. 端口已有调试服务?直接复用(避免重复拉起进程)
        String json = fetchJsonList();
        if (json != null) {
            String ep = pageEndpoint(json);
            if (ep != null) return ep;
        }
        // 2. 启动新实例
        String chrome = chromePath != null && !chromePath.isEmpty() ? chromePath : findChrome();
        if (chrome == null) {
            throw new IOException("未找到 Chrome,请在 config.properties 配置 browser.path");
        }
        process = new ProcessBuilder(buildCommand(chrome))
                .redirectErrorStream(true).start();
        // 3. 轮询就绪
        long deadline = System.currentTimeMillis() + readyTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            String j = fetchJsonList();
            if (j != null) {
                String ep = pageEndpoint(j);
                if (ep != null) return ep;
            }
        }
        throw new IOException("Chrome 启动超时(端口 " + port + "),请检查 browser.path 配置");
    }

    /** Chrome 命令行(包内可见,测试用) */
    List<String> buildCommand(String chrome) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(chrome);
        cmd.add("--remote-debugging-port=" + port);
        cmd.add("--user-data-dir=" + userDataDir.toAbsolutePath());
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        if (headless) cmd.add("--headless=new"); // 109 的 new headless 模式(旧模式在 109 已弃用)
        return cmd;
    }

    /** 从 /json 列表响应取第一个 type=page 的 webSocketDebuggerUrl;无 page/解析失败返回 null */
    static String pageEndpoint(String json) {
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if ("page".equals(o.get("type").getAsString())
                        && o.has("webSocketDebuggerUrl")) {
                    return o.get("webSocketDebuggerUrl").getAsString();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 查询调试端口 /json 列表;未监听/连接失败返回 null */
    String fetchJsonList() {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build();
            Response r = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + port + "/json").build()).execute();
            return r.body() != null ? r.body().string() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 自动探测 Chrome 常见安装位置;找不到返回 null */
    static String findChrome() {
        String[] candidates = {
                envOrNull("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"};
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        return null;
    }

    private static String envOrNull(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    /** 退出时停止自启进程(minion 退出钩子调用;复用外部实例时 process 为 null,无操作) */
    public void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -Dtest=ChromeLauncherTest`
Expected: 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/minion/core/tools/browser/ChromeLauncher.java src/test/java/com/minion/core/tools/browser/ChromeLauncherTest.java
git commit -m "feat: 新增 ChromeLauncher(Chrome 启动/探测/复用)"
```

---

### Task 10: BrowserSession + 4 个浏览器工具

**Files:**
- Create: `src/main/java/com/minion/core/tools/browser/BrowserSession.java`
- Create: `src/main/java/com/minion/core/tools/browser/BrowserTool.java`
- Create: `src/main/java/com/minion/core/tools/browser/BrowserEvalTool.java`
- Create: `src/main/java/com/minion/core/tools/browser/BrowserScreenshotTool.java`
- Create: `src/main/java/com/minion/core/tools/browser/BrowserDebugTool.java`
- Test: `src/test/java/com/minion/core/tools/browser/BrowserToolsTest.java`

**Interfaces:**
- Consumes: `CdpClient`(Task 8)、`ChromeLauncher`(Task 9)、`Workspace`(Task 1)
- Produces: `BrowserSession(ChromeLauncher, CdpClient)` 与 `open/back/refresh/evaluate/screenshot(String absPath)/debugNetwork(int)/debugConsole(int)/pageInfo`;4 个工具实现 `Tool` 接口

- [ ] **Step 1: 写失败测试**

```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** 浏览器工具:参数校验与启动失败路径(连接成功路径留给真实 Chrome 集成测试) */
public class BrowserToolsTest {

    /** 永远启动失败的 launcher:模拟未装 Chrome */
    private static class FailingLauncher extends ChromeLauncher {
        FailingLauncher() {
            super("", 1, Paths.get("."), false, 100);
        }
        @Override
        public String pageEndpoint() throws Exception {
            throw new IOException("未找到 Chrome(测试)");
        }
    }

    private static JsonObject json(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static BrowserSession session() {
        return new BrowserSession(new FailingLauncher(), new CdpClient(100, 100));
    }

    @Test
    public void browserToolMissingAction() {
        ToolResult r = new BrowserTool(session()).execute(new JsonObject());
        assertTrue(r.output, r.output.contains("action"));
    }

    @Test
    public void browserToolOpenWithoutUrl() {
        ToolResult r = new BrowserTool(session()).execute(json("action", "open"));
        assertTrue(r.output, r.output.contains("url"));
    }

    @Test
    public void browserToolUnknownAction() {
        ToolResult r = new BrowserTool(session()).execute(json("action", "fly"));
        assertTrue(r.output, r.output.contains("未知 action"));
    }

    @Test
    public void browserToolOpenFailsWhenChromeMissing() {
        ToolResult r = new BrowserTool(session()).execute(
                json2("action", "open", "url", "https://example.com"));
        assertTrue(r.output, r.output.contains("启动失败"));
    }

    @Test
    public void browserEvalMissingExpression() {
        ToolResult r = new BrowserEvalTool(session()).execute(new JsonObject());
        assertTrue(r.output, r.output.contains("expression"));
    }

    @Test
    public void browserEvalFailsWhenChromeMissing() {
        ToolResult r = new BrowserEvalTool(session()).execute(json("expression", "1+1"));
        assertTrue(r.output, r.output.contains("启动失败"));
    }

    @Test
    public void browserScreenshotMissingPath() {
        ToolResult r = new BrowserScreenshotTool(session(), new Workspace("."), null)
                .execute(new JsonObject());
        assertTrue(r.output, r.output.contains("path"));
    }

    @Test
    public void browserScreenshotOutsideWorkDirRejected() throws Exception {
        Workspace ws = new Workspace(java.nio.file.Files.createTempDirectory("ws").toString());
        ToolResult r = new BrowserScreenshotTool(session(), ws, null)
                .execute(json("path", "C:\\Windows\\x.png"));
        assertTrue(r.output, r.output.contains("工作路径之外"));
    }

    @Test
    public void browserDebugUnknownAction() {
        ToolResult r = new BrowserDebugTool(session()).execute(json("action", "x"));
        assertTrue(r.output, r.output.contains("未知 action"));
    }

    private static JsonObject json2(String k1, String v1, String k2, String v2) {
        JsonObject o = new JsonObject();
        o.addProperty(k1, v1);
        o.addProperty(k2, v2);
        return o;
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=BrowserToolsTest`
Expected: 编译失败(类不存在)

- [ ] **Step 3: 实现 BrowserSession**

```java
package com.minion.core.tools.browser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * 浏览器会话:懒启动 Chrome、封装 CDP 命令、网络/console 事件查询。
 * 应用内单例(BrowserSession 非线程安全,工具执行已串行化)。
 */
public class BrowserSession {

    private final ChromeLauncher launcher;
    private final CdpClient client;
    private volatile String currentUrl = "";
    private volatile boolean domainsEnabled;
    private volatile boolean helperInjected;

    public BrowserSession(ChromeLauncher launcher, CdpClient client) {
        this.launcher = launcher;
        this.client = client;
    }

    // 注意:AgentLoop 同回合并行执行工具,公开方法用 synchronized 串行化,
    // 避免多个工具并发触发 ensureConnected 的双连接竞态。

    private void ensureConnected() throws IOException {
        if (client.isConnected()) return;
        String ws;
        try {
            ws = launcher.pageEndpoint();
        } catch (Exception e) {
            throw new IOException("浏览器启动失败: " + e.getMessage());
        }
        client.connect(ws);
    }

    /** Network/Runtime 事件域启用(幂等):网络记录与 console 日志的前提 */
    private void enableDomains() throws IOException {
        if (domainsEnabled) return;
        client.command("Network.enable", new JsonObject());
        client.command("Runtime.enable", new JsonObject());
        domainsEnabled = true;
    }

    /**
     * 注入页面级辅助函数(幂等):__minion_set_value(el, v) ——
     * React/Vue 受控组件填值:原生 value setter + 触发 input 事件,
     * 模型填表时直接用,不用手写事件细节。
     */
    private void ensureHelper() throws IOException {
        if (helperInjected) return;
        JsonObject params = new JsonObject();
        params.addProperty("expression",
                "window.__minion_set_value=function(el,v){var d=Object.getOwnPropertyDescriptor("
                + "Object.getPrototypeOf(el),'value');if(d&&d.set){d.set.call(el,v);}else{el.value=v;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));}");
        params.addProperty("returnByValue", true);
        client.command("Runtime.evaluate", params);
        helperInjected = true;
    }

    public synchronized String open(String url) throws IOException {
        ensureConnected();
        enableDomains();
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        client.command("Page.navigate", params);
        currentUrl = url;
        waitForPage(15000);
        return "已打开: " + url;
    }

    public synchronized String back() throws IOException {
        ensureConnected();
        enableDomains();
        client.command("Page.goBack", new JsonObject());
        waitForPage(15000);
        return "已后退";
    }

    public synchronized String refresh() throws IOException {
        ensureConnected();
        enableDomains();
        client.command("Page.reload", new JsonObject());
        waitForPage(15000);
        return "已刷新";
    }

    /** 等待页面加载完成(readyState=complete,上限 timeoutMs;未连接/SPA 无 load 事件时不阻塞) */
    public synchronized void waitForPage(int timeoutMs) throws IOException {
        if (!client.isConnected()) return; // 未连接由后续 ensureConnected 负责
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonObject params = new JsonObject();
                params.addProperty("expression", "document.readyState");
                params.addProperty("returnByValue", true);
                JsonObject r = client.command("Runtime.evaluate", params);
                if (r.has("result") && r.getAsJsonObject("result").has("value")
                        && "complete".equals(r.getAsJsonObject("result").get("value").getAsString())) {
                    return;
                }
            } catch (IOException ignored) { }
            try { Thread.sleep(300); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 执行 JS 并返回值;JS 异常附最近 3 条 console 错误 */
    public synchronized String evaluate(String expression) throws IOException {
        ensureConnected();
        enableDomains();
        ensureHelper();
        JsonObject params = new JsonObject();
        params.addProperty("expression", expression);
        params.addProperty("returnByValue", true);
        JsonObject r = client.command("Runtime.evaluate", params);
        if (r.has("exceptionDetails")) {
            String text = r.getAsJsonObject("exceptionDetails").get("text").getAsString();
            return "JS 异常: " + text + consoleErrors(3);
        }
        JsonObject result = r.has("result") ? r.getAsJsonObject("result") : new JsonObject();
        if (!result.has("value")) return "(无返回值)";
        return String.valueOf(result.get("value"));
    }

    /** 截图(路径已由工具层守卫;fullPage=true 时 captureBeyondViewport 截全页) */
    public synchronized String screenshot(String absPath, boolean fullPage) throws IOException {
        ensureConnected();
        JsonObject params = new JsonObject();
        if (fullPage) params.addProperty("captureBeyondViewport", true);
        JsonObject r = client.command("Page.captureScreenshot", params);
        if (!r.has("data")) return "截图失败: 无数据";
        byte[] png = Base64.getDecoder().decode(r.get("data").getAsString());
        Files.write(Paths.get(absPath), png);
        return "截图已保存: " + absPath;
    }

    /** 网络请求汇总:method url → status,耗时 ms(按 requestId 关联) */
    public String debugNetwork(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JsonObject> sent = client.events("Network.requestWillBeSent");
        List<JsonObject> got = client.events("Network.responseReceived");
        int shown = 0;
        for (JsonObject e : sent) {
            if (shown >= limit) break;
            JsonObject req = e.getAsJsonObject("request");
            String method = req.get("method").getAsString();
            String url = req.get("url").getAsString();
            String tail = "";
            String id = e.get("requestId").getAsString();
            double t1 = e.has("timestamp") ? e.get("timestamp").getAsDouble() : 0;
            for (JsonObject g : got) {
                if (id.equals(g.get("requestId").getAsString())) {
                    String status = g.getAsJsonObject("response").get("status").getAsString();
                    double t2 = g.has("timestamp") ? g.get("timestamp").getAsDouble() : 0;
                    tail = " → " + status
                            + (t1 > 0 && t2 > 0 ? ", " + Math.round((t2 - t1) * 1000) + "ms" : "");
                    break;
                }
            }
            sb.append(method).append(' ').append(truncate(url, 120)).append(tail).append('\n');
            shown++;
            if (sb.length() > 20000) { sb.append("... 输出过长已截断\n"); break; }
        }
        return sb.toString().trim().isEmpty() ? "暂无网络记录(需先打开页面)" : sb.toString();
    }

    /** console 日志(错误标 [ERROR]) */
    public String debugConsole(int limit) {
        StringBuilder sb = new StringBuilder();
        List<JsonObject> logs = client.events("Runtime.consoleAPICalled");
        int from = Math.max(0, logs.size() - limit);
        for (int i = from; i < logs.size(); i++) {
            JsonObject e = logs.get(i);
            String type = e.get("type").getAsString();
            StringBuilder args = new StringBuilder();
            JsonElement argsArr = e.get("args");
            if (argsArr != null && argsArr.isJsonArray()) {
                for (JsonElement a : argsArr.getAsJsonArray()) {
                    JsonObject o = a.getAsJsonObject();
                    if (o.has("value")) args.append(o.get("value")).append(' ');
                }
            }
            sb.append("error".equals(type) ? "[ERROR] " : "[").append(type).append("] ")
              .append(args).append('\n');
        }
        return sb.toString().trim().isEmpty() ? "暂无 console 日志" : sb.toString();
    }

    /** evaluate 异常时附带的 console 错误摘要 */
    private String consoleErrors(int n) {
        List<JsonObject> logs = client.events("Runtime.consoleAPICalled");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logs.size() - n);
        for (int i = from; i < logs.size(); i++) {
            JsonObject e = logs.get(i);
            if (!"error".equals(e.get("type").getAsString())) continue;
            JsonElement argsArr = e.get("args");
            if (argsArr != null && argsArr.isJsonArray()) {
                for (JsonElement a : argsArr.getAsJsonArray()) {
                    JsonObject o = a.getAsJsonObject();
                    if (o.has("value")) sb.append(' ').append(o.get("value"));
                }
            }
        }
        return sb.length() == 0 ? "" : "\n最近 console 错误:" + sb;
    }

    /** 当前页面信息:标题 + URL(已连接时实时查询;未连接提示先 open) */
    public String pageInfo() {
        if (!client.isConnected()) {
            return "当前页面: (未打开,可用 action=open 打开)";
        }
        try {
            JsonObject params = new JsonObject();
            params.addProperty("expression", "document.title + ' | ' + location.href");
            params.addProperty("returnByValue", true);
            JsonObject r = client.command("Runtime.evaluate", params);
            if (r.has("result") && r.getAsJsonObject("result").has("value")) {
                return "当前页面: " + r.getAsJsonObject("result").get("value").getAsString();
            }
        } catch (IOException ignored) { }
        return "当前页面: " + (currentUrl.isEmpty() ? "(未知)" : currentUrl);
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
```

- [ ] **Step 4: 实现 4 个工具类**

**BrowserTool.java**:
```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;

/** 浏览器导航:open/back/refresh/status(首次调用启动 Chrome) */
public class BrowserTool implements Tool {

    private final BrowserSession session;

    public BrowserTool(BrowserSession session) { this.session = session; }

    @Override
    public String name() { return "Browser"; }

    @Override
    public String description() { return "浏览器导航:open(url) 打开页面 / back 后退 / refresh 刷新 / status 当前页面状态(首次调用自动启动 Chrome)"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("浏览器导航",
                new String[]{"action", "url"}, new String[]{"action"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("action")) return ToolResult.error("缺少 action 参数");
        String action = args.get("action").getAsString();
        try {
            if ("open".equals(action)) {
                if (!args.has("url")) return ToolResult.error("open 需要 url 参数");
                return ToolResult.success(session.open(args.get("url").getAsString()));
            }
            if ("back".equals(action)) return ToolResult.success(session.back());
            if ("refresh".equals(action)) return ToolResult.success(session.refresh());
            if ("status".equals(action)) return ToolResult.success(session.pageInfo());
            return ToolResult.error("未知 action: " + action + "(支持 open/back/refresh/status)");
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
```

**BrowserEvalTool.java**:
```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

import java.io.IOException;

/**
 * 页面内执行 JS 并返回结果。输入/点击/取数据都用它,例如:
 *   document.querySelector('#user').value = 'admin'(受控组件用 __minion_set_value 辅助)
 *   [...document.querySelectorAll('table tr')].map(r => r.innerText).join('\n')
 */
public class BrowserEvalTool implements Tool {

    private final BrowserSession session;

    public BrowserEvalTool(BrowserSession session) { this.session = session; }

    @Override
    public String name() { return "BrowserEval"; }

    @Override
    public String description() { return "在浏览器当前页面执行 JS 并返回结果(输入、点击、提取数据都用它)"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("在页面执行 JS",
                new String[]{"expression", "awaitPage"},
                new String[]{"expression"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("expression")) return ToolResult.error("缺少 expression 参数");
        boolean await = !args.has("awaitPage") || args.get("awaitPage").getAsBoolean();
        try {
            if (await) session.waitForPage(10000); // 未连接时直接返回,由 evaluate 兜底
            return ToolResult.success(session.evaluate(args.get("expression").getAsString()));
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
```

**BrowserScreenshotTool.java**:
```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.PathGuard;
import com.minion.core.tools.PathsGuard;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;

import java.io.IOException;
import java.nio.file.Path;

/** 页面截图保存到工作区(模型可随后用 Read 查看) */
public class BrowserScreenshotTool implements Tool {

    private final BrowserSession session;
    private final Workspace workspace;
    private final String skillsDir;

    public BrowserScreenshotTool(BrowserSession session, Workspace workspace, String skillsDir) {
        this.session = session;
        this.workspace = workspace;
        this.skillsDir = skillsDir;
    }

    @Override
    public String name() { return "BrowserScreenshot"; }

    @Override
    public String description() { return "对浏览器当前页面截图保存到工作区(相对路径以当前目录为基准)"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("页面截图",
                new String[]{"path", "fullPage"},
                new String[]{"path"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("path")) return ToolResult.error("缺少 path 参数");
        boolean fullPage = !args.has("fullPage") || args.get("fullPage").getAsBoolean();
        Path p = PathsGuard.resolve(workspace.cwd().toString(), args.get("path").getAsString());
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, p);
        if (guard != null) return guard;
        try {
            return ToolResult.success(session.screenshot(p.toString(), fullPage));
        } catch (IOException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
```

(注意:上面工具类里的 `import com.minion.core.tools.PathGuard;` 为误植,应删除,只保留 `PathsGuard`。)

**BrowserDebugTool.java**:
```java
package com.minion.core.tools.browser;

import com.google.gson.JsonObject;
import com.minion.core.tools.SchemaGenerator;
import com.minion.core.tools.Tool;
import com.minion.core.tools.ToolResult;

/** 网页调试:网络请求 / console 日志 / 页面信息 */
public class BrowserDebugTool implements Tool {

    private final BrowserSession session;

    public BrowserDebugTool(BrowserSession session) { this.session = session; }

    @Override
    public String name() { return "BrowserDebug"; }

    @Override
    public String description() { return "调试信息:network 网络请求列表 / console 控制台日志 / page 当前页面状态"; }

    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("浏览器调试信息",
                new String[]{"action", "limit"}, new String[]{"action"});
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("action")) return ToolResult.error("缺少 action 参数");
        String action = args.get("action").getAsString();
        int limit = 50;
        if (args.has("limit")) {
            try {
                limit = args.get("limit").getAsInt();
            } catch (NumberFormatException e) {
                return ToolResult.error("参数 limit 格式错误: " + e.getMessage());
            }
        }
        if ("network".equals(action)) return ToolResult.success(session.debugNetwork(limit));
        if ("console".equals(action)) return ToolResult.success(session.debugConsole(limit));
        if ("page".equals(action)) return ToolResult.success(session.pageInfo());
        return ToolResult.error("未知 action: " + action + "(支持 network/console/page)");
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn test -Dtest=BrowserToolsTest`
Expected: 9 tests, 0 failures

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/minion/core/tools/browser/ src/test/java/com/minion/core/tools/browser/BrowserToolsTest.java
git commit -m "feat: 新增 BrowserSession 与 4 个浏览器工具"
```

---

### Task 11: Main 注册 + 退出钩子 + 文档同步

**Files:**
- Modify: `src/main/java/com/minion/Main.java`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Test: 全量

**Interfaces:**
- Consumes: Task 7-10 全部

- [ ] **Step 1: Main 注册浏览器工具**

```java
// Main.main 中,技能注册之后、SessionStore 之前插入:
        // 浏览器工具(懒启动 Chrome,首次工具调用才拉起进程;退出钩子关停自启进程)
        ChromeLauncher chrome = new ChromeLauncher(config.browserPath(), config.browserPort(),
                Paths.get(config.browserUserDataDir()), config.browserHeadless(),
                config.browserTimeoutMs());
        BrowserSession browserSession = new BrowserSession(chrome, new CdpClient(10000,
                config.browserTimeoutMs()));
        registry.register(new BrowserTool(browserSession));
        registry.register(new BrowserEvalTool(browserSession));
        registry.register(new BrowserScreenshotTool(browserSession, workspace, skillsDir));
        registry.register(new BrowserDebugTool(browserSession));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> chrome.stop()));
```

import 追加:
```java
import com.minion.core.tools.browser.BrowserDebugTool;
import com.minion.core.tools.browser.BrowserEvalTool;
import com.minion.core.tools.browser.BrowserScreenshotTool;
import com.minion.core.tools.browser.BrowserSession;
import com.minion.core.tools.browser.BrowserTool;
import com.minion.core.tools.browser.CdpClient;
import com.minion.core.tools.browser.ChromeLauncher;
```

- [ ] **Step 2: 跑编译与全量测试**

Run: `mvn test`
Expected: 全部通过(现有 + 新增约 150 个用例)

- [ ] **Step 3: 更新文档**

**docs/ARCHITECTURE.md**:
- §1 包结构:`├── tools/              Tool 接口、ToolRegistry、13 个工具、SchemaGenerator、confirm/、browser/、PathsGuard`(9→13)
- §2 `core/tools/` 段:追加 `browser/` 子包描述:`ChromeLauncher(Chrome 进程管理)、CdpClient(CDP WebSocket 协议)、BrowserSession(浏览器会话与事件缓冲)、Browser/BrowserEval/BrowserScreenshot/BrowserDebug 四个工具`
- 工具清单 `- Tool 接口...→ 9 个实现` 改为 13 个,追加浏览器工具说明
- §5 扩展点不变

**README.md** 追加使用说明段落(放在「模型供应商配置」之前):

```markdown
## 浏览器工具(登录、点击、查询、调试网页)

对接本机 Chrome(CDP 协议,零额外依赖)。首次使用自动启动 Chrome(默认有头窗口,便于观察调试;
自动化场景可配置 `browser.headless=true`)。配置项:

    browser.path=          # Chrome 可执行文件路径,留空自动探测常见安装位置
    browser.port=9222      # 调试端口(Chrome 默认只绑定本机,不暴露局域网)
    browser.userDataDir=./.minion/browser-profile   # 登录状态持久化目录(清空即重置)
    browser.headless=false
    browser.timeoutMs=30000

用法(模型自动调用,也可在对话里描述操作):

- `Browser`  open/back/refresh/status —— 打开页面与导航
- `BrowserEval`  执行 JS:输入、点击、提取表格数据(SPA 受控组件用 __minion_set_value 辅助)
- `BrowserScreenshot`  截图存工作区
- `BrowserDebug`  network/console/page —— 网络请求、控制台日志、页面状态

登录示例:对话里告知账号密码 → 模型用 BrowserEval 填表提交 → 登录态保存在 userDataDir,下次会话保留。
```

**CLAUDE.md**:
- `tools/` 行:`Tool 接口 + 9 个工具 + ToolRegistry` → `Tool 接口 + 13 个工具 + ToolRegistry + browser/(CDP 浏览器)`(tools/ 段追加 `browser/` 子包说明)
- 常用命令段不变

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/minion/Main.java docs/ARCHITECTURE.md README.md CLAUDE.md
git commit -m "feat: 注册浏览器工具并同步文档"
```

- [ ] **Step 5: 全量验证**

Run: `mvn clean package`
Expected: BUILD SUCCESS(产物 target/minion-0.1.0.jar)

- [ ] **Step 6: 真实 Chrome 冒烟测试(手动,可选)**

```bash
java -jar target/minion-0.1.0.jar -c "用 Browser 打开 https://www.baidu.com,再执行 BrowserEval 取 document.title,最后 BrowserScreenshot 存 baidu.png"
```
Expected: 输出页面标题、工作区出现 baidu.png

---

## Self-Review 记录

- **Spec 覆盖**:浏览器设计文档各节(架构/CdpClient/ChromeLauncher/BrowserSession/4 工具/配置/错误处理/测试/不做的)分别对应 Task 7-11;三项优化对应 Task 1-6;Global Constraints 复制自项目规约
- **占位符检查**:所有步骤含完整代码或明确改动点;无 TBD/TODO
- **类型一致性**:`Workspace.cd()` 返回 `Path` 或 null、`BrowserSession.evaluate()` 返回 String、`CdpClient.command()` 返回 JsonObject——各任务间引用一致;Task 10 工具类中误植的 `import ...PathGuard;` 已在代码内标注删除
- **首轮自审修正**(对照 spec 逐节核对后补齐):
  - headless 参数改为 `--headless=new`(spec §Chrome 生命周期;Chrome 109 的 new headless 模式;测试断言同步)
  - `__minion_set_value` 辅助函数从"工具描述提及"落实为 BrowserSession.ensureHelper() 实际注入(spec §BrowserEval JS 执行)
  - BrowserEval 补 `awaitPage` 参数(默认 true,走 waitForPage;未连接时直接返回,不阻塞测试)
  - BrowserScreenshot 补 `fullPage` 参数(默认 true → captureBeyondViewport)
  - `status` 输出标题 + URL;debugNetwork 输出耗时 ms(spec 工具表两处)
  - AgentLoop 6 参构造委托处追加 `new Workspace(config.workDir())`(`Config.workDir()` 已存在,Config.java:104)
- **依赖签名核对**:AgentLoop 双构造(AgentLoop.java:56-63)、`SessionStore.SessionMeta(id/createdAt/preview)`、`SessionStore.load` 不存在时抛 IOException、`Config.load(Path)` 测试注入方式——均已按实际代码核对,计划引用一致
