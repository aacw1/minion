# 读逃逸开关 + 工具轮数上限 + 卡住止损 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增读逃逸开关让 Read/Grep/Glob 可越界读（写入不变）、轮数上限改 1000、AI 卡住时止损请求用户补充信息。

**Architecture:** 读逃逸走「配置开关 + ConfirmGate 交互」：开关开=自动放行，关=弹 Y-N-A 确认复用全局 sessionBypass；轮数上限改 AgentLoop 常量；卡住止损=提示词规则 7 + AgentLoop 连续失败计数 ≥30 注入 `[系统提醒]` user 消息。

**Tech Stack:** JDK 8、Maven 单模块、junit4、gson、无新依赖。

## Global Constraints

- JDK 8 兼容，禁止新依赖
- 中文注释、中文 commit（conventional 格式）
- 高危操作 `ConfirmGate.check()` 逻辑**不变**（只新增 `checkReadOutside`）
- 工具旧构造签名保留（单参/双参委托三参），现有测试不破坏
- 新配置键默认值 `false`，同步生产 `src/resource/config.properties` 与测试纯净默认 `src/test/resources/config-test.properties`
- 每次提交包含 Co-Authored-By: Claude <noreply@anthropic.com>

---

### Task 1: 配置键 paths.read.allowOutside

**Files:**
- Modify: `src/resource/config.properties`（路径段加键）
- Modify: `src/test/resources/config-test.properties`（加键）
- Modify: `src/main/java/com/minion/core/config/Config.java`（加 getter）
- Test: `src/test/java/com/minion/core/config/ConfigTest.java`

**Interfaces:**
- Produces: `Config.readAllowOutside()` → `boolean`（默认 false，外部文件 `paths.read.allowOutside=true` 覆盖为 true）。后续 Task 2 的 ConfirmGate 与 Task 7 的 Main 使用。

- [ ] **Step 1: 写失败测试**

在 `ConfigTest.java` 末尾（`browserDefaults` 测试后）新增：

```java
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
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=ConfigTest`
Expected: 编译失败 `cannot find symbol: method readAllowOutside()`

- [ ] **Step 3: 实现**

`src/main/java/com/minion/core/config/Config.java`，在 `workDir()` 附近（路径配置段）新增：

```java
    /** 读逃逸：true 时 Read/Grep/Glob 可读取工作区外文件（写入类工具不受影响，仍受限） */
    public boolean readAllowOutside() { return Boolean.parseBoolean(get("paths.read.allowOutside", "false")); }
```

`src/resource/config.properties`，`# ===== 路径 =====` 段末尾新增：

```
# 读逃逸：true 时 Read/Grep/Glob 可读取工作区外文件（写入工具仍受限）；false（默认）时越界读弹确认
paths.read.allowOutside=false
```

`src/test/resources/config-test.properties` 末尾追加：

```
paths.read.allowOutside=false
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=ConfigTest`
Expected: 全绿（load_createsExternalFileWithDefaults、load_externalOverridesDefault、appendWhitelist_deduplicatesAndPersists、browserDefaults、readAllowOutside_defaultsFalseAndOverridable）

- [ ] **Step 5: 提交**

```bash
git add src/resource/config.properties src/test/resources/config-test.properties \
  src/main/java/com/minion/core/config/Config.java src/test/java/com/minion/core/config/ConfigTest.java
git commit -m "feat: 新增 paths.read.allowOutside 读逃逸开关配置

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: ConfirmGate.checkReadOutside 越界读确认

**Files:**
- Modify: `src/main/java/com/minion/core/tools/confirm/ConfirmGate.java`
- Test: `src/test/java/com/minion/core/tools/confirm/ConfirmGateTest.java`

**Interfaces:**
- Consumes: `Config.readAllowOutside()`（Task 1）
- Produces: `ConfirmGate.checkReadOutside(Tool tool, JsonObject args, String path)` → `boolean`。语义：开关开或 sessionBypass → true 不弹；否则 `ui.ask("! 越界读取 <工具> → <path>")`，Y→true、N→false、A/W→置位 sessionBypass 后 true。后续 Task 3/4 的 Read/Grep/Glob 与 Task 7 的 Main 使用。

- [ ] **Step 1: 写失败测试**

在 `ConfirmGateTest.java` 中新增 helper 与测试：

```java
    private com.minion.core.tools.Tool readTool() {
        return new com.minion.core.tools.ReadTool(
                new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath()));
    }
```

```java
    @Test
    public void readOutside_switchOn_skipsAsk() throws Exception {
        Files.write(java.nio.file.Paths.get(config.externalFile().toString()),
                "\npaths.read.allowOutside=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void readOutside_approveReject() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
        assertEquals(1, ui.asked.size());
        assertTrue(ui.asked.get(0).startsWith("! 越界读取 "));
        assertTrue(ui.asked.get(0).contains("D:/x.txt"));

        assertFalse(gate(new FakeConfirmUi(ConfirmUi.Decision.REJECT))
                .checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
    }

    @Test
    public void readOutside_approveSession_bypassesHighRiskAndReads() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_SESSION);
        ConfirmGate g = gate(ui);
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
        // 会话放行后：高危操作与越界读均不再弹窗（与既有 sessionBypass 全局语义一致）
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/y.txt\"}"), "D:/y.txt"));
        assertEquals(1, ui.asked.size());
    }

    @Test
    public void readOutside_approveWhitelist_isSessionBypassNotPersisted() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_WHITELIST);
        ConfirmGate g = gate(ui);
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/x.txt\"}"), "D:/x.txt"));
        assertTrue(g.checkReadOutside(readTool(), args("{\"path\":\"D:/y.txt\"}"), "D:/y.txt"));
        assertEquals(1, ui.asked.size()); // W 按会话放行，不落持久化白名单
        assertFalse(config.whitelistTools().contains("read"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=ConfirmGateTest`
Expected: 编译失败 `cannot find symbol: method checkReadOutside`

- [ ] **Step 3: 实现**

`ConfirmGate.java`，在 `check()` 方法后新增：

```java
    /** 越界读审批：开关开或会话放行 → 直接放行；否则弹确认（Y 放行本次 / N 拒绝 / A/W 置位会话放行）。
     *  W 对越界读按会话放行处理、不落持久化白名单（与高危操作的白名单语义区分，YAGNI）。 */
    public synchronized boolean checkReadOutside(Tool tool, JsonObject args, String path) {
        if (config.readAllowOutside() || sessionBypass) return true;
        String detail = tool.name() + " → " + path;
        ConfirmUi.Decision d = ui.ask("! 越界读取 " + detail);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        sessionBypass = true; // APPROVE_SESSION / APPROVE_WHITELIST 均会话放行
        return true;
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=ConfirmGateTest`
Expected: 全绿（含既有 7 个测试：skipFlag_bypassesAsk、approve_reject_whitelist、whitelistWrite_persistsToExternalConfig、whitelistedTool_noAsk、whitelistedCommand_noAsk、whitelistCommand_approveWhitelist_persistsAndBypasses、approveSession_bypassesRest + 新增 4 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/confirm/ConfirmGate.java \
  src/test/java/com/minion/core/tools/confirm/ConfirmGateTest.java
git commit -m "feat: ConfirmGate 新增越界读确认 checkReadOutside

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: ReadTool / GrepTool 越界读接入确认

**Files:**
- Modify: `src/main/java/com/minion/core/tools/ReadTool.java`
- Modify: `src/main/java/com/minion/core/tools/GrepTool.java`
- Test: `src/test/java/com/minion/core/tools/FileToolsTest.java`

**Interfaces:**
- Consumes: `ConfirmGate.checkReadOutside(Tool, JsonObject, String)`（Task 2）
- Produces: `ReadTool(Workspace, String skillsDir, ConfirmGate)` 与 `GrepTool(Workspace, String skillsDir, ConfirmGate)` 三参构造（旧单参/双参构造保留、confirm 传 null）。越界且 confirm 为 null 或确认被拒 → 返回原拒绝文案。Task 7 的 Main 使用。

- [ ] **Step 1: 写失败测试**

在 `FileToolsTest.java` 新增 helper 与测试（放在 `skillsDir_notAllowed_whenNotConfigured` 测试之后）：

```java
    /** 读逃逸确认：构造可注入 ConfirmGate 的 Config（开关开/关由 allowOutside 控制） */
    private com.minion.core.config.Config readConfig(boolean allowOutside) throws Exception {
        com.minion.core.config.Config c = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        if (allowOutside) {
            Files.write(c.externalFile(),
                    "\npaths.read.allowOutside=true\n".getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            c = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        }
        return c;
    }

    // ---- 读逃逸：越界读确认放行 / 拒绝 / 开关自动放行 ----

    @Test
    public void read_outside_confirmApprove_allows() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-read-approve.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE)));
        ToolResult res = r.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("secret"));
    }

    @Test
    public void read_outside_confirmReject_rejects() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-read-reject.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.REJECT)));
        ToolResult res = r.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertFalse(res.ok);
        assertTrue(res.output.contains("工作路径之外"));
    }

    @Test
    public void read_outside_switchOn_autoAllows() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-read-switchon.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(true), new com.minion.core.tools.confirm.FakeConfirmUi()));
        ToolResult res = r.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("secret"));
    }

    @Test
    public void grep_outside_confirmApprove_allows() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-grep-approve.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret count value".getBytes(StandardCharsets.UTF_8));
        GrepTool g = new GrepTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE)));
        ToolResult res = g.execute(args("{\"pattern\":\"count\",\"path\":\""
                + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("secret count value"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=FileToolsTest`
Expected: 编译失败 `constructor ReadTool(Workspace,String,ConfirmGate) is not defined`

- [ ] **Step 3: 实现**

`ReadTool.java`：加字段、保留旧构造、新增三参构造、改越界分支。

```java
    private final Workspace workspace;
    private final String skillsDir;
    private final ConfirmGate confirm;

    public ReadTool(Workspace workspace) { this(workspace, null, null); }

    public ReadTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public ReadTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.confirm = confirm;
    }
```

`ReadTool.java` 越界分支（第 46-47 行附近）：

```java
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, p);
        if (guard != null) {
            if (confirm == null || !confirm.checkReadOutside(this, args, p.toString())) return guard;
        }
```

`GrepTool.java`：同样加字段与构造（原样，三参构造 + 双参/单参委托），越界分支（第 58-59 行附近）：

```java
        ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, root);
        if (guard != null) {
            if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
        }
```

import 补：`com.minion.core.tools.confirm.ConfirmGate`（两个文件）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=FileToolsTest`
Expected: 全绿（含既有 read_outsideWorkDir_rejected、read_traversal_rejected、grep_outsideRejected、skillsDir_* 等回归 + 新增 4 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/ReadTool.java src/main/java/com/minion/core/tools/GrepTool.java \
  src/test/java/com/minion/core/tools/FileToolsTest.java
git commit -m "feat: Read/Grep 越界读接入确认放行（开关开自动放行）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: GlobTool 新增 path 参数 + 越界读确认

**Files:**
- Modify: `src/main/java/com/minion/core/tools/GlobTool.java`
- Test: `src/test/java/com/minion/core/tools/FileToolsTest.java`

**Interfaces:**
- Consumes: `ConfirmGate.checkReadOutside(Tool, JsonObject, String)`（Task 2）
- Produces: `GlobTool(Workspace, String skillsDir, ConfirmGate)` 三参构造（旧构造保留）；schema 新增可选 `path`（相对 cwd 解析，默认无 → 维持现 cwd+技能目录行为）。Task 7 的 Main 使用。

- [ ] **Step 1: 写失败测试**

在 `FileToolsTest.java` 新增：

```java
    // ---- Glob path 参数：指定搜索根（工作区内直搜，工作区外走确认） ----

    @Test
    public void glob_pathParam_insideWork_finds() throws Exception {
        Files.createDirectories(p("src"));
        Files.write(p("src/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        ToolResult r = glob.execute(args("{\"pattern\":\"*.java\",\"path\":\"src\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output.contains("A.java"));
    }

    @Test
    public void glob_pathParam_outside_confirmApprove_allows() throws Exception {
        Path outside = java.nio.file.Files.createTempDirectory("minion-glob-outside");
        try {
            Files.write(outside.resolve("Z.java"), "x".getBytes(StandardCharsets.UTF_8));
            GlobTool g = new GlobTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                    readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                            com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE)));
            ToolResult res = g.execute(args("{\"pattern\":\"*.java\",\"path\":\""
                    + outside.toString().replace("\\", "\\\\") + "\"}"));
            assertTrue(res.output, res.ok);
            assertTrue(res.output.contains("Z.java"));
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    public void glob_pathParam_outside_confirmReject_rejects() throws Exception {
        Path outside = java.nio.file.Files.createTempDirectory("minion-glob-outside2");
        try {
            Files.write(outside.resolve("Z.java"), "x".getBytes(StandardCharsets.UTF_8));
            GlobTool g = new GlobTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                    readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                            com.minion.core.tools.confirm.ConfirmUi.Decision.REJECT)));
            ToolResult res = g.execute(args("{\"pattern\":\"*.java\",\"path\":\""
                    + outside.toString().replace("\\", "\\\\") + "\"}"));
            assertFalse(res.ok);
            assertTrue(res.output.contains("工作路径之外"));
        } finally {
            deleteRecursively(outside);
        }
    }

    @Test
    public void glob_pathParam_missingPath_error() throws Exception {
        ToolResult r = glob.execute(args("{\"pattern\":\"*.java\",\"path\":\"./nope-dir\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("路径不存在"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=FileToolsTest`
Expected: `glob_pathParam_insideWork_finds` 失败（schema 无 path 参数，参数被忽略 → 搜 cwd 根，`*.java` 对 `src/A.java` 相对 cwd 不匹配）

- [ ] **Step 3: 实现**

`GlobTool.java` 全量关键改动（保留 import 增补）：

schema（第 39-43 行）：

```java
    @Override
    public JsonObject schema() {
        return SchemaGenerator.objectSchema("按 glob 模式查找文件",
                new String[]{"pattern", "path"}, new String[]{"pattern"});
    }
```

字段与构造（第 23-31 行）：

```java
    private final Workspace workspace;
    private final String skillsDir;
    private final ConfirmGate confirm;

    public GlobTool(Workspace workspace) { this(workspace, null, null); }

    public GlobTool(Workspace workspace, String skillsDir) { this(workspace, skillsDir, null); }

    public GlobTool(Workspace workspace, String skillsDir, ConfirmGate confirm) {
        this.workspace = workspace;
        this.skillsDir = skillsDir;
        this.confirm = confirm;
    }
```

execute 中 roots 计算（第 55-64 行区域，替换）：

```java
        final Path workRoot = workspace.cwd();
        final List<String> found = new ArrayList<String>();
        // 遍历根：无 path 时 = cwd + 技能目录（若在 cwd 之外且存在）；
        // 指定 path 时 = 该路径（工作区内直搜；工作区外经确认放行后直搜）。
        // 结果路径格式：工作区内输出相对路径；工作区外输出绝对路径（模型可直接 Read）
        final List<Path> roots = new ArrayList<Path>();
        String start = args.has("path") ? args.get("path").getAsString() : null;
        if (start != null && !start.isEmpty()) {
            final Path root = PathsGuard.resolve(workspace.cwd().toString(), start);
            if (!Files.exists(root)) return ToolResult.error("路径不存在: " + root);
            ToolResult guard = PathsGuard.errorIfOutside(workspace.workDir(), skillsDir, root);
            if (guard != null) {
                if (confirm == null || !confirm.checkReadOutside(this, args, root.toString())) return guard;
            }
            roots.add(root);
        } else {
            roots.add(workRoot);
            if (skillsDir != null && !skillsDir.isEmpty() && Files.isDirectory(Paths.get(skillsDir))) {
                Path skillsAbs = Paths.get(skillsDir).toAbsolutePath().normalize();
                if (!skillsAbs.startsWith(workRoot.toAbsolutePath().normalize())) roots.add(skillsAbs);
            }
        }
```

import 补：`com.minion.core.tools.confirm.ConfirmGate`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=FileToolsTest`
Expected: 全绿（含 glob_matches、glob_badPattern_error、skillsDir_allowsReadGlobGrep 等回归 + 新增 4 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/GlobTool.java src/test/java/com/minion/core/tools/FileToolsTest.java
git commit -m "feat: Glob 支持 path 参数并接入越界读确认

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 系统提示追加卡住止损规则 7

**Files:**
- Modify: `src/main/java/com/minion/core/agent/SystemPromptBuilder.java`
- Test: `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java`

**Interfaces:**
- Produces: BUILTIN 规则 7（执行中卡住止损），与现有规则 1（指令不明确先提问）互补。

- [ ] **Step 1: 写失败测试**

在 `SystemPromptBuilderTest.java` 新增：

```java
    @Test
    public void build_includesStuckStopRule() throws Exception {
        Path work = tmp.getRoot().toPath();
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), "model.name=x\n".getBytes(StandardCharsets.UTF_8));
        com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
        String prompt = new SystemPromptBuilder(config).build(
                java.util.Collections.<com.minion.core.skills.Skill>emptyList(),
                java.util.Collections.<com.minion.core.skills.Skill>emptyList());
        assertTrue(prompt.contains("停止调用工具"));
        assertTrue(prompt.contains("不要反复重试同一方法"));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SystemPromptBuilderTest`
Expected: `build_includesStuckStopRule` FAIL（prompt 不含"停止调用工具"）

- [ ] **Step 3: 实现**

`SystemPromptBuilder.java` BUILTIN（第 16-24 行），规则 6 行后追加：

```java
          + "7. 当工具连续失败、或发现缺少完成任务所必需的信息/权限时，停止调用工具；向用户说明已尝试的方案、失败原因，并列出需要用户补充的信息或需要用户选择的方案，等待用户回复。不要反复重试同一方法。";
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=SystemPromptBuilderTest`
Expected: 全绿（含既有 3 个：build_includesProjectMdAndSkillsInOrder、build_missingProjectMd_skipsSection、build_clarificationRuleIsFirst + 新增）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/SystemPromptBuilder.java \
  src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java
git commit -m "feat: 系统提示追加卡住止损规则 7（停止调用工具并请求补充信息）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: AgentLoop 轮数上限 1000 + 连续失败止损注入

**Files:**
- Modify: `src/main/java/com/minion/core/agent/AgentLoop.java`
- Test: `src/test/java/com/minion/core/agent/AgentLoopTest.java`

**Interfaces:**
- Consumes: `ToolResult.ok`（成功/失败判定）、`Message.user(String)`、`Message.Role.USER`
- Produces: `AgentLoop.DEFAULT_ROUND_LIMIT = 1000`；连续失败 ≥30 时在工具结果之后、下一轮请求之前注入 `[系统提醒]` user 消息（计数清零）。无其他对外接口变化。

- [ ] **Step 1: 写失败测试**

在 `AgentLoopTest.java` 新增失败工具与测试（放在文件末尾、BlockingTool 类之前或之后均可）：

```java
    /** 总是失败的测试工具：驱动连续失败计数 */
    public static class FailingTool implements Tool {
        @Override public String name() { return "failing"; }
        @Override public String description() { return "总是失败的测试工具"; }
        @Override public JsonObject schema() {
            return com.minion.core.tools.SchemaGenerator.objectSchema("失败", new String[0], new String[0]);
        }
        @Override public ToolResult execute(JsonObject args) { return ToolResult.error("模拟失败"); }
    }

    private static ToolCall failingCall(int i) {
        ToolCall tc = new ToolCall();
        tc.id = "f" + i;
        tc.name = "failing";
        tc.arguments = "{}";
        return tc;
    }

    private static long stuckHints(List<Message> msgs) {
        return msgs.stream().filter(m ->
                m.role == Message.Role.USER && m.content != null && m.content.contains("[系统提醒]")).count();
    }

    @Test
    public void defaultRoundLimit_is1000() {
        assertEquals(1000, AgentLoop.DEFAULT_ROUND_LIMIT);
    }

    @Test
    public void stuck_30ConsecutiveFailures_injectsReminder() {
        registry.register(new FailingTool());
        for (int i = 0; i < 30; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        llm.addTurn("好的，我需要用户补充信息");
        AgentLoop loop = newLoop();
        loop.roundLimit = 40;
        loop.runUserTurn("任务");
        assertEquals(1, stuckHints(loop.messages()));
        Message hint = loop.messages().stream().filter(m ->
                m.role == Message.Role.USER && m.content != null && m.content.contains("[系统提醒]"))
                .findFirst().get();
        assertTrue(hint.content.contains("30 次"));
        assertTrue(loop.messages().get(loop.messages().size() - 1).role == Message.Role.ASSISTANT);
        assertTrue(ui.warnings.stream().anyMatch(w -> w.contains("工具连续失败")));
    }

    @Test
    public void stuck_29Failures_noReminder() {
        registry.register(new FailingTool());
        for (int i = 0; i < 29; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        llm.addTurn("结束");
        AgentLoop loop = newLoop();
        loop.roundLimit = 40;
        loop.runUserTurn("任务");
        assertEquals(0, stuckHints(loop.messages()));
    }

    @Test
    public void stuck_successResetsCounter() {
        registry.register(new FailingTool());
        for (int i = 0; i < 10; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        ToolCall ok = new ToolCall();
        ok.id = "ok";
        ok.name = "example";
        ok.arguments = "{\"text\":\"x\"}";
        llm.addTurnWithTools(Collections.singletonList(ok), null);
        for (int i = 0; i < 29; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(100 + i)), null);
        }
        llm.addTurn("结束");
        AgentLoop loop = newLoop();
        loop.roundLimit = 60;
        loop.runUserTurn("任务");
        // 成功清零后仅 29 次连续失败，不注入
        assertEquals(0, stuckHints(loop.messages()));
    }

    @Test
    public void stuck_injectionResetsCounter_second30InjectsAgain() {
        registry.register(new FailingTool());
        for (int i = 0; i < 60; i++) {
            llm.addTurnWithTools(Collections.singletonList(failingCall(i)), null);
        }
        llm.addTurn("结束");
        AgentLoop loop = newLoop();
        loop.roundLimit = 70;
        loop.runUserTurn("任务");
        // 注入后计数重置：60 次失败 → 两次提醒
        assertEquals(2, stuckHints(loop.messages()));
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=AgentLoopTest`
Expected: `defaultRoundLimit_is1000` FAIL（10000 ≠ 1000）；`stuck_*` FAIL（消息数组无 `[系统提醒]`）

- [ ] **Step 3: 实现**

`AgentLoop.java`：

常量（第 32 行）：

```java
    public static final int DEFAULT_ROUND_LIMIT = 1000;
```

新增常量与字段（roundLimit 字段附近）：

```java
    /** 连续工具失败止损阈值：达到后注入提醒让模型停止尝试并请求用户补充信息 */
    private static final int STUCK_THRESHOLD = 30;
    /** 连续失败工具计数（成功即清零；注入提醒后重置） */
    private int consecutiveToolErrors = 0;
```

`runUserTurn` 工具结果处理循环（第 327-339 行区域），在 `ui.onToolResult` 后加计数：

```java
                        if (result == null) result = ToolResult.error("工具执行失败");
                        if (result.ok) {
                            consecutiveToolErrors = 0;
                        } else {
                            consecutiveToolErrors++;
                        }
                        session.messages.add(Message.toolResult(
                                calls.get(i).id, calls.get(i).name, result.output));
                        ui.onToolResult(calls.get(i).name, result);
```

`futures` 循环结束后（`finally` 块之后、`persistSession()` 之前）注入：

```java
                // 卡住止损：连续失败达阈值时注入系统提醒（user 消息而非 system——
                // OpenAI 兼容 API 只接受首条 system，插在对话中间会 400），
                // 模型下轮应输出提问文本而非再调工具；注入后计数重置，roundLimit 为最外层兜底
                if (consecutiveToolErrors >= STUCK_THRESHOLD) {
                    String hint = "[系统提醒] 你已连续 " + consecutiveToolErrors
                            + " 次工具调用失败。请停止调用工具，向用户说明已尝试的方案、失败原因，"
                            + "并列出完成任务还需要用户补充的信息或需要用户选择的方案。";
                    session.messages.add(Message.user(hint));
                    ui.onWarning("工具连续失败 " + consecutiveToolErrors
                            + " 次，已提醒模型停止尝试并请求用户补充信息");
                    consecutiveToolErrors = 0;
                }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=AgentLoopTest`
Expected: 全绿（含既有 13 个测试回归 + 新增 5 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/agent/AgentLoop.java \
  src/test/java/com/minion/core/agent/AgentLoopTest.java
git commit -m "feat: 工具轮数上限改 1000，连续失败 30 次注入止损提醒

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: Main 接线读逃逸确认 + 全量验证

**Files:**
- Modify: `src/main/java/com/minion/Main.java`

**Interfaces:**
- Consumes: `ReadTool/GrepTool/GlobTool` 三参构造（Task 3/4）、`ConfirmGate`（Task 2）
- Produces: 生产装配——ConfirmGate 构造提前到读工具注册前，读工具注入 confirm；-c 模式全放行语义保留。

- [ ] **Step 1: 修改 Main.java**

`Main.java`（第 57-61 行区域），`Renderer` 创建后、`ToolRegistry` 创建前插入 ConfirmGate（原第 96-98 行删除，注释随代码移动）：

```java
        ConfirmReader confirmReader = new ConfirmReader();
        ConfirmUi confirmUi = confirmReader;
        Renderer renderer = new Renderer(config.uiColor());
        // 交互模式下确认用 ConfirmReader；-c 模式全部放行（脚本化）。
        // 读逃逸确认需要注入读工具，ConfirmGate 须在读工具注册前创建
        ConfirmGate confirm = new ConfirmGate(config,
                args.length >= 2 && "-c".equals(args[0])
                        ? ui -> ConfirmUi.Decision.APPROVE : confirmUi);
```

读工具注册（第 70、73、74 行）改三参：

```java
        registry.register(new ReadTool(workspace, skillsDir, confirm));
        registry.register(new WriteTool(workspace, skillsDir));
        registry.register(new EditTool(workspace, skillsDir));
        registry.register(new GlobTool(workspace, skillsDir, confirm));
        registry.register(new GrepTool(workspace, skillsDir, confirm));
```

删除原第 95-98 行的重复创建：

```java
        // 交互模式下确认用 ConfirmReader；-c 模式全部放行（脚本化）
        ConfirmGate confirm = new ConfirmGate(config,
                args.length >= 2 && "-c".equals(args[0])
                        ? ui -> ConfirmUi.Decision.APPROVE : confirmUi);
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 全量测试**

Run: `mvn test`
Expected: 全绿（无 FAIL；记录 Tests run 总数）

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/minion/Main.java
git commit -m "feat: Main 接线读逃逸确认（ConfirmGate 前置注入读工具）

Co-Authored-By: Claude <noreply@anthropic.com>"
```

- [ ] **Step 5: 验证规格覆盖（自检）**

对照 `docs/superpowers/specs/2026-08-11-read-escape-and-stuck-stop-design.md` 逐项核对：
- 改动 1（开关/确认/写入不变/不提示联动）：Task 1-4、7 ✓
- 改动 2（轮数 1000）：Task 6 ✓
- 改动 3a（提示词规则 7）：Task 5 ✓
- 改动 3b（连续失败 ≥30 注入）：Task 6 ✓
- README 无路径配置段，本次为内部安全开关，不需增补（README 仅记录模型/浏览器配置）

若无遗漏即完成。
