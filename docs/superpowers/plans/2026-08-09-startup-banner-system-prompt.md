# 启动信息横幅 + 系统提示词优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交互模式启动时打印 6 行启动信息（模型名/上下文上限/工作空间/项目说明/技能目录/会话存储，绝对路径+存在性标注），并在系统提示词中新增"输入不明确先提问"规则。

**Architecture:** 新增纯函数格式化器 `StartupBanner.format(Config)`（可单测），在 `Repl.start()` 欢迎横幅前打印；`SystemPromptBuilder.BUILTIN` 插入澄清规则作为第 1 条，原规则顺延。`-c` 脚本模式不打印横幅（`Main` 提前 return，天然不受影响）。

**Tech Stack:** Java 8, Maven, JUnit 4, jline 3。

## Global Constraints

- Java 8（pom 中 `maven.compiler.source/target=1.8`），不使用 Java 9+ API
- 测试框架 JUnit 4（`org.junit.Test` + `Assert`），运行命令 `mvn test`
- 配置读取统一走 `com.minion.core.config.Config.load(Path)` + 临时目录中的 `config.properties`（参照现有 `SystemPromptBuilderTest` 的写法，避免读到本机真实配置）
- 路径解析用 `Paths.get(x).toAbsolutePath()`，存在性用 `Files.exists`
- 新规则文本必须与现有 BUILTIN 措辞风格一致（中文、编号、句号结尾）
- 提交信息沿用仓库风格：`feat:` / `test:` 前缀，结尾带 `Co-Authored-By: Claude <noreply@anthropic.com>`
- 设计文档：`docs/superpowers/specs/2026-08-09-startup-banner-system-prompt-design.md`

---

### Task 1: StartupBanner 格式化器

**Files:**
- Create: `src/main/java/com/minion/cli/StartupBanner.java`
- Test: `src/test/java/com/minion/cli/StartupBannerTest.java`

**Interfaces:**
- Consumes: `com.minion.core.config.Config` 的 getter：`modelName()` → String、`maxContextTokens()` → int、`workDir()` → String、`projectMdPath()` → String、`skillsDir()` → String、`sessionDir()` → String
- Produces: `StartupBanner.format(Config config)` → String（6 行、每行 `标签: 值`、路径为绝对路径、不存在的路径追加 ` (未创建)`），供 Task 2 使用

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/minion/cli/StartupBannerTest.java`：

```java
package com.minion.cli;

import com.minion.core.config.Config;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class StartupBannerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 写一个最小 config.properties 并加载（未覆盖的键走默认资源，不依赖本机配置） */
    private Config configWith(String content) throws Exception {
        Path work = tmp.getRoot().toPath();
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return Config.load(work);
    }

    @Test
    public void format_listsSixLinesInOrder() throws Exception {
        Config c = configWith("model.name=deepseek-v4-flash\n"
                + "model.maxContextTokens=900000\n"
                + "work.dir=.\nproject.md.path=./project.md\n"
                + "skills.dir=./skills\nsession.dir=./.minion/sessions\n");
        String[] lines = StartupBanner.format(c).split("\\n");
        assertEquals(6, lines.length);
        assertTrue(lines[0].startsWith("模型: deepseek-v4-flash"));
        assertTrue(lines[1].startsWith("上下文上限: 900000 tokens"));
        assertTrue(lines[2].startsWith("工作空间: "));
        assertTrue(lines[3].startsWith("项目说明: "));
        assertTrue(lines[4].startsWith("技能目录: "));
        assertTrue(lines[5].startsWith("会话存储: "));
    }

    @Test
    public void format_resolvesAbsolutePaths() throws Exception {
        Path work = tmp.getRoot().toPath();
        File dir = new File(work.toFile(), "myskills");
        dir.mkdirs();
        Config c = configWith("model.name=x\nskills.dir=" + dir.getAbsolutePath() + "\n");
        String s = StartupBanner.format(c);
        assertTrue(s.contains("技能目录: " + dir.getAbsolutePath()));
    }

    @Test
    public void format_marksMissingPath() throws Exception {
        Path work = tmp.getRoot().toPath();
        String missing = work.resolve("nope.md").toAbsolutePath().toString();
        Config c = configWith("model.name=x\nproject.md.path=" + missing + "\n");
        assertTrue(StartupBanner.format(c).contains(missing + " (未创建)"));
    }

    @Test
    public void format_existingPathHasNoMarker() throws Exception {
        Path work = tmp.getRoot().toPath();
        File dir = new File(work.toFile(), "sess");
        dir.mkdirs();
        Config c = configWith("model.name=x\nsession.dir=" + dir.getAbsolutePath() + "\n");
        assertFalse(StartupBanner.format(c).contains("(未创建)"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=StartupBannerTest -q`
Expected: FAIL — `cannot find symbol` StartupBanner

- [ ] **Step 3: 实现 StartupBanner**

创建 `src/main/java/com/minion/cli/StartupBanner.java`：

```java
package com.minion.cli;

import com.minion.core.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 启动信息横幅：模型/上下文/路径概览，每行一条。路径显示为绝对路径，不存在的标注 (未创建) */
public class StartupBanner {

    public static String format(Config config) {
        StringBuilder sb = new StringBuilder();
        sb.append("模型: ").append(config.modelName()).append('\n');
        sb.append("上下文上限: ").append(config.maxContextTokens()).append(" tokens\n");
        sb.append("工作空间: ").append(describe(config.workDir())).append('\n');
        sb.append("项目说明: ").append(describe(config.projectMdPath())).append('\n');
        sb.append("技能目录: ").append(describe(config.skillsDir())).append('\n');
        sb.append("会话存储: ").append(describe(config.sessionDir()));
        return sb.toString();
    }

    /** 绝对路径 + 存在性标注 */
    private static String describe(String p) {
        Path path = Paths.get(p).toAbsolutePath();
        return path.toString() + (Files.exists(path) ? "" : " (未创建)");
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=StartupBannerTest -q`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/minion/cli/StartupBanner.java src/test/java/com/minion/cli/StartupBannerTest.java
git commit -m "feat: startup banner with model, context and path overview

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Repl 启动时打印横幅

**Files:**
- Modify: `src/main/java/com/minion/cli/Repl.java:73-75`

**Interfaces:**
- Consumes: Task 1 的 `StartupBanner.format(Config)`；`Renderer.wrapBanner(String)`（青色加粗）
- Produces: 交互模式启动输出 —— 先 6 行启动信息，再欢迎横幅

- [ ] **Step 1: 接入 Repl.start()**

修改 `src/main/java/com/minion/cli/Repl.java`，在 `renderer().setEchoUser(false);` 之后（原第 74 行欢迎横幅之前）插入：

```java
renderer().setEchoUser(false); // JLine 已回显用户输入，避免二次打印
System.out.println(renderer().wrapBanner(StartupBanner.format(config)));
System.out.println(renderer().wrapBanner("minion — 代码开发助手  (输入 /help 查看命令)"));
printResumeHint();
```

`StartupBanner` 与 `Repl` 同包（`com.minion.cli`），无需 import。`config` 字段已存在。

- [ ] **Step 2: 全量测试确认编译与回归**

Run: `mvn test -q`
Expected: PASS — 全部测试通过（含新 StartupBannerTest）

- [ ] **Step 3: 手动验证（交互模式）**

Run: `mvn package -q -DskipTests && java -jar target/minion-0.1.0.jar`
Expected: 先打印 6 行启动信息（工作空间/项目说明/技能目录/会话存储为绝对路径，不存在的带 `(未创建)`），随后是欢迎横幅，然后出现 `minion>` 提示符。输入 `/exit` 退出。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/minion/cli/Repl.java
git commit -m "feat: print startup banner in interactive repl

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 系统提示词新增澄清规则

**Files:**
- Modify: `src/main/java/com/minion/core/agent/SystemPromptBuilder.java:16-23`（BUILTIN）
- Modify: `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java`（追加一个测试方法）

**Interfaces:**
- Consumes: 现有 `SystemPromptBuilder(config).build(allSkills, loadedSkills)` 签名
- Produces: BUILTIN 规则列表变为 6 条，新规则"输入不明确先提问"为第 1 条

- [ ] **Step 1: 写失败测试**

在 `src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java` 中追加测试方法：

```java
@Test
public void build_clarificationRuleIsFirst() throws Exception {
    Path work = tmp.getRoot().toPath();
    File cf = new File(work.toFile(), "config.properties");
    Files.write(cf.toPath(), "model.name=x\n".getBytes(StandardCharsets.UTF_8));
    com.minion.core.config.Config config = com.minion.core.config.Config.load(work);
    String prompt = new SystemPromptBuilder(config).build(
            java.util.Collections.<com.minion.core.skills.Skill>emptyList(),
            java.util.Collections.<com.minion.core.skills.Skill>emptyList());
    int iClarify = prompt.indexOf("不要猜测用户意图");
    int iOldRule1 = prompt.indexOf("使用工具前先想清楚目标");
    assertTrue(iClarify > 0);
    assertTrue(iOldRule1 > iClarify);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=SystemPromptBuilderTest -q`
Expected: FAIL — `build_clarificationRuleIsFirst` 断言失败（`iClarify == -1`）

- [ ] **Step 3: 修改 BUILTIN**

修改 `src/main/java/com/minion/core/agent/SystemPromptBuilder.java` 的 BUILTIN，插入新规则为第 1 条，原 1-5 顺延为 2-6：

```java
private static final String BUILTIN =
        "你是 minion，一个运行在命令行里的代码开发助手。你可以调用工具读写文件、执行命令、搜索代码。\n"
      + "规则：\n"
      + "1. 用户指令不明确、信息不足或存在多种可能理解时，先列出需要补充的问题，等待用户回答后再行动；不要猜测用户意图。\n"
      + "2. 使用工具前先想清楚目标，避免无谓调用；Bash 命令在项目工作目录下执行。\n"
      + "3. 修改文件前先 Read 确认当前内容；Edit 必须精确匹配原文。\n"
      + "4. 复杂任务可用 task 工具派发子 agent 并行处理，子 agent 会返回结果摘要。\n"
      + "5. 回答使用简洁中文，代码块使用 ``` 标记。\n"
      + "6. 涉及删除/覆盖等破坏性操作时，等待用户确认（系统会拦截）。";
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SystemPromptBuilderTest -q`
Expected: PASS — 3 tests（含新 `build_clarificationRuleIsFirst`），0 failures

- [ ] **Step 5: 全量回归 + 提交**

Run: `mvn test -q`
Expected: PASS — 全部测试通过

```bash
git add src/main/java/com/minion/core/agent/SystemPromptBuilder.java src/test/java/com/minion/core/agent/SystemPromptBuilderTest.java
git commit -m "feat: system prompt asks user to clarify ambiguous input

Co-Authored-By: Claude <noreply@anthropic.com>"
```
