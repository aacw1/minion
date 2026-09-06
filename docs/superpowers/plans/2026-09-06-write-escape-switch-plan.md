# 空间外写开关 + 读逃逸改名为空间外读 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增「空间外写」配置开关（开=Write/Edit 越界写走高危确认链，关=直接拒绝），并把读逃逸/新开关的用户可见命名改为「空间外读/空间外写」。

**Architecture:** 配置键 `paths.write.allowOutside`（默认 false）由 Config 提供；ConfirmGate 新增 `checkEscapeWrite`（开关关即返回 false，开则走会话放行/confirm.skip/工具白名单/弹框链）；WriteTool/EditTool 构造注入 ConfirmGate，越界守卫命中时咨询之；设置窗基础页「空间外读」行下新增「空间外写」行。

**Tech Stack:** JDK 8、JavaFX 8、JUnit 4、无新依赖。

## Global Constraints

- JDK 8 兼容；不加新依赖
- 内部符号不更名：方法 `readAllowOutside()`、配置键 `paths.read.allowOutside` 保持原样（存量配置兼容）
- 用户可见文案（UI 标签、config.properties 注释、README、弹框文案）术语为「空间外读 / 空间外写」，不再出现「读逃逸」
- BrowserScreenshot 越界保存**不动**（沿用 `checkWriteOutside`，受空间外读开关影响）
- 注释与 commit 用中文（commit 用 conventional 格式）
- 测试命令在项目根 `mvn -q -Dtest=XXX test`；全部跑 `mvn -q test`

---

### Task 1: Config 新增 writeAllowOutside + 默认资源同步

**Files:**
- Modify: `src/main/java/com/minion/core/config/Config.java`（`readAllowOutside()` 方法之后，约 97 行）
- Modify: `src/resource/config.properties`
- Modify: `src/test/resources/config-test.properties`
- Test: `src/test/java/com/minion/core/config/ConfigTest.java`

**Interfaces:**
- Consumes: 无（`Config.get(key, def)` 与 `Config.load(Path, String)` 均已存在；`Config.EXTERNAL_FILE_NAME`、`externalFile()` 已存在）
- Produces: `Config.writeAllowOutside()` → `boolean`（键 `paths.write.allowOutside`，默认 `"false"`）——Task 2/3/4 依赖

- [ ] **Step 1: 写失败测试**

在 `ConfigTest.java` 末尾（类内）追加：

```java
    /** 空间外写：默认 false，外部配置可覆盖为 true */
    @Test
    public void writeAllowOutside_defaultFalse_andExternalOverride() throws IOException {
        Config c = Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS);
        assertFalse(c.writeAllowOutside());
        Path ext = c.externalFile();
        Files.write(ext, "\npaths.write.allowOutside=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        assertTrue(Config.load(tmp.getRoot().toPath(), TEST_DEFAULTS).writeAllowOutside());
    }
```

（`StandardOpenOption` 需在 import 区追加 `import java.nio.file.StandardOpenOption;`——若文件已 import 则跳过。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ConfigTest test`
Expected: FAIL（编译错误 `cannot find symbol: writeAllowOutside`）

- [ ] **Step 3: 实现**

`Config.java` 在 `readAllowOutside()` 行后插入：

```java
    /** 空间外写：true 时 Write/Edit 越界写放行至高危确认链（会话放行/确认跳过/白名单/弹框）；false（默认）时越界写直接拒绝 */
    public boolean writeAllowOutside() { return Boolean.parseBoolean(get("paths.write.allowOutside", "false")); }
```

`src/resource/config.properties`（===== 路径 ===== 段，第 3 行注释改术语并在其后加新键）：

```
# 空间外读（paths.read.allowOutside）：true 时 Read/Grep/Glob 可读取工作区外文件；false（默认）时越界读弹确认
paths.read.allowOutside=false
# 空间外写（paths.write.allowOutside）：true 时 Write/Edit 越界写放行至高危确认链（确认跳过/白名单/弹框）；false（默认）时越界写直接拒绝
paths.write.allowOutside=false
```

`src/test/resources/config-test.properties` 末尾追加：

```
paths.write.allowOutside=false
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ConfigTest test`
Expected: PASS（2 个测试全绿）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/config/Config.java src/resource/config.properties src/test/resources/config-test.properties src/test/java/com/minion/core/config/ConfigTest.java
git commit -m "feat: Config 新增空间外写开关键 paths.write.allowOutside（读逃逸注释改名为空间外读）"
```

---

### Task 2: ConfirmGate.checkEscapeWrite + 方法级单测

**Files:**
- Modify: `src/main/java/com/minion/core/tools/confirm/ConfirmGate.java`（`checkWriteOutside` 方法之后，约 52 行）
- Test: `src/test/java/com/minion/core/tools/confirm/ConfirmGateTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Config.writeAllowOutside()`；既有 `ConfirmUi.Decision`、`isWhitelisted`、`sessionBypass`、`ui.ask`
- Produces: `ConfirmGate.checkEscapeWrite(Tool tool, JsonObject args, String path)` → `boolean`（true=放行）——Task 3/4 依赖。与既有 `checkWriteOutside`（截图用、受空间外读开关）语义不同

- [ ] **Step 1: 写失败测试**

在 `ConfirmGateTest.java` 追加 helper 与 6 个用例（放 `skipFlag_bypassesAsk` 测试之后；helper 放类内任意位置）。一次性全部追加以下内容（`FakeConfirmUi`/`Decision` 同包可裸用；`Files/Paths/StandardCharsets` 测试类已 import，缺 `StandardOpenOption` 时补 `import java.nio.file.StandardOpenOption;`；`writeTool()` 与 `args()` helper 已存在）：

```java
    /** 追加键值对到外部配置并重载（键=值成对传入；先 Config.load 保证文件存在） */
    private ConfirmGate gateWith(ConfirmUi ui, String... kv) throws Exception {
        java.nio.file.Path root = tmp.getRoot().toPath();
        com.minion.core.config.Config.load(root);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append('\n').append(kv[i]).append('=').append(kv[i + 1]);
        }
        Files.write(root.resolve("config.properties"), sb.toString().getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(root);
        return new ConfirmGate(config, ui);
    }

    // ---- 空间外写（checkEscapeWrite）：关=直接拒绝；开=跳过/白名单/弹框链 ----

    @Test
    public void escapeWrite_switchOff_rejects_evenIfSkipOn() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(Decision.REJECT),
                "paths.write.allowOutside", "false", "confirm.skip", "true");
        assertFalse("开关关应直接拒绝（跳过开关不生效）",
                g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_skip_allows() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(Decision.REJECT),
                "paths.write.allowOutside", "true", "confirm.skip", "true");
        assertTrue("确认跳过应放行且不弹框", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_whitelisted_allows() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(Decision.REJECT),
                "paths.write.allowOutside", "true", "confirm.whitelist.tools", "Write");
        assertTrue("工具白名单应放行且不弹框", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_confirmApprove_allows() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(Decision.APPROVE);
        ConfirmGate g = gateWith(ui, "paths.write.allowOutside", "true");
        assertTrue(g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
        assertEquals(1, ui.asked.size());
        assertTrue("弹框文案应含越界写入: " + ui.asked.get(0), ui.asked.get(0).contains("越界写入"));
    }

    @Test
    public void escapeWrite_switchOn_confirmReject_rejects() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(Decision.REJECT), "paths.write.allowOutside", "true");
        assertFalse("N 拒绝应返回 false", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_sessionApprove_allows() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(Decision.APPROVE_SESSION),
                "paths.write.allowOutside", "true");
        assertTrue("W 会话放行应返回 true", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ConfirmGateTest test`
Expected: FAIL（编译错误 `cannot find symbol: checkEscapeWrite`）

- [ ] **Step 3: 实现**

`ConfirmGate.java` 在 `checkWriteOutside` 方法后插入：

```java
    /** 空间外写审批（Write/Edit 越界写专用）：
     *  开关关 → 直接拒绝（不弹框，无视会话放行/确认跳过/白名单）；
     *  开关开 → 高危放行链：会话放行/确认跳过/工具白名单命中即放行，否则弹框
     *    （Y 放行本次 / N 拒绝 / W 会话放行）。
     *  与 checkWriteOutside（BrowserScreenshot 输出、受空间外读开关）语义不同，勿混用。 */
    public synchronized boolean checkEscapeWrite(Tool tool, JsonObject args, String path) {
        if (!config.writeAllowOutside()) return false;
        if (sessionBypass || config.confirmSkip()) return true;
        if (isWhitelisted(tool, args)) return true;
        ConfirmUi.Decision d = ui.ask("! 越界写入 " + tool.name() + " → " + path);
        if (d == ConfirmUi.Decision.APPROVE) return true;
        if (d == ConfirmUi.Decision.REJECT) return false;
        sessionBypass = true; // APPROVE_WHITELIST / APPROVE_SESSION 均会话放行
        return true;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ConfirmGateTest test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/confirm/ConfirmGate.java src/test/java/com/minion/core/tools/confirm/ConfirmGateTest.java
git commit -m "feat: ConfirmGate 新增 checkEscapeWrite 空间外写审批（关=拒绝，开=跳过/白名单/弹框链）"
```

---

### Task 3: WriteTool/EditTool 注入 ConfirmGate + 集成测试

**Files:**
- Modify: `src/main/java/com/minion/core/tools/WriteTool.java`
- Modify: `src/main/java/com/minion/core/tools/EditTool.java`
- Test: `src/test/java/com/minion/core/tools/EditToolsTest.java`

**Interfaces:**
- Consumes: Task 2 的 `ConfirmGate.checkEscapeWrite(Tool, JsonObject, String)`；既有 3 参构造器 `WriteTool(Workspace, String skillsDir, String tmpDir)` / `EditTool(Workspace, String skillsDir, String tmpDir)`
- Produces: 新 4 参构造 `WriteTool(Workspace, String, String, ConfirmGate)` / `EditTool(Workspace, String, String, ConfirmGate)`（旧构造委托 confirm=null）——Task 4 装配依赖

- [ ] **Step 1: 写失败测试**

在 `EditToolsTest.java` 追加 helper 与 6 个用例（放 `write_outsideRejected` 测试之后；类内已有 `args`/`p`/`tmp`/`ws` fixture）。所需新 import：`com.minion.core.tools.confirm.ConfirmGate`、`com.minion.core.tools.confirm.FakeConfirmUi`、`com.minion.core.tools.confirm.ConfirmUi`、`java.nio.file.StandardOpenOption`（EditToolsTest 目前只 import 了部分，按编译报错补齐，一律全限定名亦可避免 import 遗漏——helper 用全限定名更稳，参见 FileToolsTest 先例）。

```java
    // ---- 空间外写：Write/Edit 越界写（开关关=拒绝；开=跳过/白名单/弹框链） ----

    /** 追加键值对到外部配置并重载（键=值成对传入；先 Config.load 保证外部文件存在） */
    private com.minion.core.config.Config cfg(String... kv) throws Exception {
        java.nio.file.Path root = tmp.getRoot().toPath();
        com.minion.core.config.Config.load(root);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append('\n').append(kv[i]).append('=').append(kv[i + 1]);
        }
        Files.write(root.resolve("config.properties"), sb.toString().getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        return com.minion.core.config.Config.load(root);
    }

    private WriteTool writeWith(com.minion.core.tools.confirm.ConfirmGate gate) {
        return new WriteTool(ws, null, null, gate);
    }

    @Test
    public void write_outside_switchOff_rejected_evenIfSkipOn() throws Exception {
        java.io.File out = new java.io.File(System.getProperty("java.io.tmpdir"),
                "minion-out-sw-off-" + System.nanoTime() + ".txt");
        ConfirmGate gate = new ConfirmGate(cfg("paths.write.allowOutside", "false",
                "confirm.skip", "true"), new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        try {
            ToolResult r = writeWith(gate).execute(args("{\"path\":\""
                    + out.getAbsolutePath().replace("\\", "\\\\") + "\",\"content\":\"x\"}"));
            assertFalse("开关关仍应拒绝: " + r.output, r.ok);
            assertTrue(r.output.contains("工作路径之外"));
            assertFalse(Files.exists(out.toPath()));
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    @Test
    public void write_outside_switchOn_skip_allows() throws Exception {
        java.io.File out = new java.io.File(System.getProperty("java.io.tmpdir"),
                "minion-out-sw-skip-" + System.nanoTime() + ".txt");
        ConfirmGate gate = new ConfirmGate(cfg("paths.write.allowOutside", "true",
                "confirm.skip", "true"), new FakeConfirmUi(ConfirmUi.Decision.REJECT));
        try {
            ToolResult r = writeWith(gate).execute(args("{\"path\":\""
                    + out.getAbsolutePath().replace("\\", "\\\\") + "\",\"content\":\"hi\"}"));
            assertTrue("确认跳过应放行: " + r.output, r.ok);
            assertEquals("hi", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    @Test
    public void write_outside_switchOn_whitelisted_allows() throws Exception {
        java.io.File out = new java.io.File(System.getProperty("java.io.tmpdir"),
                "minion-out-sw-wl-" + System.nanoTime() + ".txt");
        ConfirmGate gate = new ConfirmGate(cfg("paths.write.allowOutside", "true",
                "confirm.whitelist.tools", "Write"), new FakeConfirmUi(ConfirmUi.Decision.REJECT));
        try {
            ToolResult r = writeWith(gate).execute(args("{\"path\":\""
                    + out.getAbsolutePath().replace("\\", "\\\\") + "\",\"content\":\"hi\"}"));
            assertTrue("工具白名单应放行: " + r.output, r.ok);
            assertTrue(Files.exists(out.toPath()));
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    @Test
    public void write_outside_switchOn_confirmApprove_allows() throws Exception {
        java.io.File out = new java.io.File(System.getProperty("java.io.tmpdir"),
                "minion-out-sw-ok-" + System.nanoTime() + ".txt");
        ConfirmGate gate = new ConfirmGate(cfg("paths.write.allowOutside", "true"),
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        try {
            ToolResult r = writeWith(gate).execute(args("{\"path\":\""
                    + out.getAbsolutePath().replace("\\", "\\\\") + "\",\"content\":\"hi\"}"));
            assertTrue("Y 放行应写入: " + r.output, r.ok);
            assertTrue(r.output.contains("已写入"));
            assertTrue(Files.exists(out.toPath()));
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    @Test
    public void write_outside_switchOn_confirmReject_rejected() throws Exception {
        java.io.File out = new java.io.File(System.getProperty("java.io.tmpdir"),
                "minion-out-sw-no-" + System.nanoTime() + ".txt");
        ConfirmGate gate = new ConfirmGate(cfg("paths.write.allowOutside", "true"),
                new FakeConfirmUi(ConfirmUi.Decision.REJECT));
        try {
            ToolResult r = writeWith(gate).execute(args("{\"path\":\""
                    + out.getAbsolutePath().replace("\\", "\\\\") + "\",\"content\":\"x\"}"));
            assertFalse("N 拒绝应不写入: " + r.output, r.ok);
            assertFalse(Files.exists(out.toPath()));
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }

    @Test
    public void edit_outside_switchOn_confirmApprove_allows() throws Exception {
        java.io.File out = new java.io.File(System.getProperty("java.io.tmpdir"),
                "minion-out-sw-edit-" + System.nanoTime() + ".txt");
        Files.write(out.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        ConfirmGate gate = new ConfirmGate(cfg("paths.write.allowOutside", "true"),
                new FakeConfirmUi(ConfirmUi.Decision.APPROVE));
        try {
            EditTool e = new EditTool(ws, null, null, gate);
            ToolResult r = e.execute(args("{\"path\":\""
                    + out.getAbsolutePath().replace("\\", "\\\\")
                    + "\",\"oldString\":\"abc\",\"newString\":\"xyz\"}"));
            assertTrue("Y 放行应替换: " + r.output, r.ok);
            assertEquals("xyz", new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out.toPath());
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=EditToolsTest test`
Expected: FAIL（编译错误：WriteTool/EditTool 无 4 参 ConfirmGate 构造）

- [ ] **Step 3: 实现**

`WriteTool.java`：
- 字段区加 `private final ConfirmGate confirm;`（顶部需 `import com.minion.core.tools.confirm.ConfirmGate;`）
- 3 参构造改为委托 4 参：`public WriteTool(Workspace workspace, String skillsDir, String tmpDir) { this(workspace, skillsDir, tmpDir, null); }`
- 新增 4 参构造并赋值全部字段
- `execute` 中越界守卫处改为：

```java
        ToolResult guard = outsideGuard(p);
        if (guard != null && (confirm == null || !confirm.checkEscapeWrite(this, args, p.toString()))) {
            return guard;
        }
```

`EditTool.java` 同法（字段 `confirm`、import、3 参委托 4 参、新增 4 参构造），`execute` 中：

```java
        ToolResult guard = PathsGuard.errorIfOutside(workspace, skillsDir, tmpDir, p);
        if (guard != null && (confirm == null || !confirm.checkEscapeWrite(this, args, p.toString()))) {
            return guard;
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=EditToolsTest,FileToolsTest test`
Expected: PASS（既有 write_outsideRejected 等用例验证 confirm=null 旧构造路径不回归）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/WriteTool.java src/main/java/com/minion/core/tools/EditTool.java src/test/java/com/minion/core/tools/EditToolsTest.java
git commit -m "feat: Write/Edit 注入 ConfirmGate，越界写接空间外写审批链"
```

---

### Task 4: 设置页 UI + 会话装配 + README

**Files:**
- Modify: `src/main/java/com/minion/gui/dialog/SettingsDialog.java`
- Modify: `src/main/java/com/minion/gui/session/SessionManager.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1 的 `Config.writeAllowOutside()`；Task 3 的 4 参构造 `WriteTool(Workspace, String, String, ConfirmGate)` / `EditTool(Workspace, String, String, ConfirmGate)`
- Produces: 无（UI/装配收尾）

- [ ] **Step 1: 改设置页 BasicPane**

`SettingsDialog.java`（类 `BasicPane` 内）：
1. 字段区（`private final CheckBox allowOutside;` 旁，约 619 行）加：`private final CheckBox writeOutside;`
2. 构造器内（`allowOutside.setSelected(...)` 之后，约 648 行）加：

```java
            writeOutside = new CheckBox("允许写入工作区外文件（Write/Edit）");
            writeOutside.setSelected(config.writeAllowOutside());
```

3. rows 列表（约 661 行）改名为两行（「空间外写」在「空间外读」之下）：

```java
                    row("空间外读:", allowOutside),
                    row("空间外写:", writeOutside),
```

4. `apply()`（`config.set("paths.read.allowOutside", ...)` 之后，约 681 行）加：

```java
            config.set("paths.write.allowOutside", String.valueOf(writeOutside.isSelected()));
```

- [ ] **Step 2: 改会话装配**

`SessionManager.java`（约 314-315 行）Write/Edit 注册注入 gate（`ConfirmGate gate` 局部变量已存在）：

```java
        registry.register(new WriteTool(workspace, skillsDir, tmpDir, gate));
        registry.register(new EditTool(workspace, skillsDir, tmpDir, gate));
```

- [ ] **Step 3: 改 README**

`README.md` 第 28 行 config.properties 说明中 `paths（读逃逸）` 改为 `paths（空间外读/空间外写）`；若其他用户可见处仍有「读逃逸」字样一并改。

- [ ] **Step 4: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS（无 GUI 单测覆盖 SettingsDialog；编译 + 前述测试已足够）
再跑一次相关测试确认无回归：`mvn -q -Dtest=ConfigTest,ConfirmGateTest,EditToolsTest,FileToolsTest test` → PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/gui/dialog/SettingsDialog.java src/main/java/com/minion/gui/session/SessionManager.java README.md
git commit -m "feat: 设置页新增空间外写开关（读逃逸改名为空间外读），Write/Edit 装配注入确认门"
```

---

### Task 5: 全量验证与自查

**Files:**
- 无代码改动（仅验证；若发现文档术语遗漏则顺手补并提交）

- [ ] **Step 1: 全量测试**

Run: `mvn -q test`
Expected: 全部 PASS

- [ ] **Step 2: 对照 spec 逐项核对**

对照 `docs/superpowers/specs/2026-09-06-write-escape-switch-design.md` 核对：
- [ ] `paths.write.allowOutside` 键在 Config/config.properties/config-test.properties 三处齐备、默认 false
- [ ] `checkEscapeWrite`：关=拒绝（无视跳过/白名单/会话放行），开=跳过/白名单/弹框链——单测覆盖 6 分支
- [ ] Write/Edit 越界接线；confirm=null（旧路径/老测试）不回归
- [ ] 设置页「空间外读」在上、「空间外写」在下、apply 落盘新键
- [ ] SessionManager 装配注入
- [ ] BrowserScreenshot 代码零改动
- [ ] 用户可见文案无残留「读逃逸」：`grep -rn "读逃逸" src/main src/resource README.md`（预期无输出；历史 docs/ 与计划文件不回溯，排除在外）

- [ ] **Step 3: 提交收尾（若有文档遗漏修改）**

```bash
git add -A
git commit -m "docs: 同步空间外读/空间外写术语"   # 仅当 Step 2 有改动时执行
```

- [ ] **Step 4: 完成汇报**

汇报：改动文件清单、测试结果（mvn -q test 全绿）、spec 逐项核对结果。
