# Glob 匹配基准修复：pattern 写绝对路径/相对工作空间时搜不到文件

日期：2026-09-17
状态：已实施（2026-09-17，全量 1172 用例通过 + 端到端探针验证通过）

## 背景与问题

用户报：模型调用 Glob 时把**绝对路径直接写进 `pattern`**（如 `E:/…/minion/src/**/*.java`），
工具以「搜索根相对路径」为基准匹配，最终返回「未找到匹配文件」，而目标文件确实存在。

## 方案选择（用户确认）

| 候选 | 结论 |
|------|------|
| 只改提示词 | 不充分：模型写绝对路径是自然推理（Bash 输出绝对路径、Read/Write 接受绝对路径），提示词是软约束，模型不遵守时 bug 原样复现 |
| 加显式基准参数（`patternBase=…`） | 否决：把正确性押在「模型每次正确传参」上，不传即退化 |
| **提示词 + 工具侧多基准容错** | **采纳**：描述写清（降发生率）+ 工具自愈（不遵守也能命中）+ 无命中提示（让模型自纠） |

## 复现（JDK8 实测，见 run/.session/tmp 探针 GlobProbe/GlobProbe2）

现有实现 `GlobTool.java:88`：`Path rel = root.relativize(file); if (matcher.matches(rel))`。

| # | path 参数 | pattern | 实测 |
|---|-----------|---------|------|
| A | 绝对子目录 `…\core\tools` | `src/main/java/**/*.java`（相对工作空间写法） | 0 命中 ❌ |
| B | 绝对子目录 | `**/*.java` | 命中 ✅ |
| D | 绝对子目录 `…\minion\src` | `src/**/*.java` | 0 命中 ❌ |
| E | 绝对 cwd | `src/main/java/**/*.java` | 命中 ✅ |
| F | 未传 | `E:/javame/…/minion/src/**/*.java` | 0 命中 ❌（用户形态） |
| G | 绝对子目录 | 同上绝对形式 | 0 命中 ❌ |
| — | base 换成**绝对路径**后再匹配上例 F/G 的 pattern | — | 命中 ✅ |

Java glob 语义补充：

| # | 场景 | 结果 |
|---|------|------|
| 2 | pattern 用反斜杠 `E:\a\**\*.java` | 恒不匹配 ❌（`\` 是 glob 转义符） |
| 4 | 盘符小写 `e:/…` vs 路径 `E:\…` | 匹配 ✅（Windows 盘符大小写不敏感） |
| 5 | `a/**/b.java` vs `a/b.java` | 不匹配 ❌（`**/` 不覆盖零层目录） |
| 8 | `**/*.java` vs 裸文件名 `A.java` | 不匹配 ❌（同零层问题，根下直接文件被漏） |
| 7 | `src/**/tools/*.java` vs `src/com/minion/tools/A.java` | 匹配 ✅ |

## 根因

1. **匹配基准错误（主因）**：`root.relativize(file)` 以「搜索根」为基准，而模型写 pattern 的自然形态是
   「相对工作空间」或「绝对路径」→ 恒 0 命中（形态 F/G 为用户报障，A/D 为同源缺陷）。
2. **与 Grep 语义不一致**：`GrepTool` 早已改为「相对 cwd 输出 + 相对 cwd 匹配」（`GrepTool.java:129/167`），Glob 未同步。
3. **pattern 分隔符未归一化**：Windows 下反斜杠 pattern 恒不匹配（glob 语义决定）。
4. **`**/` 零层语义缺失**：`**/*.java` 漏掉搜索根下的直接文件。
5. **输出路径基准与 Read 不一致**：Glob 输出「相对搜索根」的路径（如 `A.java`，实为 `src/sub/A.java`），
   模型拿去 `Read` 解析到错误位置；Grep 输出「相对 cwd」路径。
6. **工具描述未说明 pattern 基准**：`description()` 仅一句「按 glob 模式在工作路径内查找文件，如 `**/*.java`」；
   `schema()` 经 `SchemaGenerator` 只产出 `type`，无属性描述（`ToolRegistry.java:66-67` 原样进请求）。

## 修复方案

### P0-1 提示词层：把 pattern 基准写进模型可见处

| 落点 | 拟改文案 |
|------|----------|
| `GlobTool.description()`（进请求 `function.description`） | 「按 glob 模式查找文件；pattern 支持工作空间相对路径（如 src/**/*.java）或绝对路径；path 为搜索起点（默认工作空间）」 |
| `GlobTool.schema()` 属性描述 | pattern：「glob 模式；可写工作空间相对路径或绝对路径」；path：「搜索起点目录；默认工作空间；工作路径外需确认」 |
| `SystemPromptBuilder`「当前工作目录」段补一行 | 「搜索类工具的 pattern 可写相对工作目录的形式（如 src/**/*.java），也支持绝对路径。」 |

`schema()` 需属性描述：`SchemaGenerator` 新增**重载**（形如 `objectSchema(desc, String[][] props{name,desc}, required)`），
现有 17 处调用不改，Glob 单独使用新重载。

### P0-2 工具侧：多基准匹配（核心，兼容新旧语义）

对每个文件构造 base 候选，pattern 命中任一即计入结果：

```
abs   = file.toAbsolutePath().normalize()
bases = [ cwd 相对(abs 在 cwd 内) , 搜索根相对(搜索根≠cwd 时) , 绝对路径(分隔符按平台归一) ]
```

覆盖形态 A/D/F/G；保留既有 `path=相对子目录 + pattern=*.java` 语义（靠「搜索根相对」base），不破坏原有用例。

### P0-3 无命中结果可诊断

```
未找到匹配文件: <pattern>
搜索根: <root…>；pattern 支持绝对路径或相对工作空间（如 src/**/*.java），也支持相对搜索根
```

### P1-1 pattern 归一化（**平台条件化**）

- **仅 Windows**（`File.separatorChar == '\\'`）把 pattern 中 `\` 归一为 `/`；Linux/麒麟不转换——Linux 文件名可含反斜杠，
  且模型在 Linux 上以 `/` 书写路径。
- 去掉开头 `./`、`.\`（模型常写 `./src/**/*.java`）。
- 代价（文档明示）：glob 转义写法 `\*` 不再可用，属罕见用法。

### P1-2 `**/` 零层展开

对 pattern 中每处 `**/` 生成「可省略」候选组合（2^n，n≤4 展开，超过不展开），任一命中即算。
修掉 `**/*.java` 漏根下文件、`a/**/b` 不匹配 `a/b`。不自实现 glob→regex 转译。

### P1-3 输出路径对齐 Grep

工作区内 → 相对 cwd（与 Read 一致）；工作区外（技能目录等）→ 绝对路径。与 `GrepTool.java:167` 同口径。

## 平台差异（Windows / 麒麟 Linux）

| 项 | Windows | 麒麟（Linux） |
|----|---------|---------------|
| 绝对路径 base | `E:\…`，盘符大小写不敏感（已实测） | `/home/…`，无盘符问题（更简单） |
| pattern 反斜杠归一化 | 生效（P1-1） | **不生效**（文件名可含 `\`；glob 转义仍有效） |
| `**/` 零层展开 | 同 | 同（同一套 `sun.nio.fs.Globs` 引擎，语义一致） |
| 输出路径 | 区外绝对用 `/` 分隔显示 | 同 |
| 越界守卫/确认 | 同（纯 Path 逻辑） | 同 |

探针程序（GlobProbe/GlobProbe2）改掉写死的 `E:/` 路径即可在麒麟上复核同一批语义。
minion 整体在麒麟上运行属另一任务（`Boot.java:29` 硬编码 `java.exe`、注册表定位 Git、`cmd /c` 包装 `.cmd/.bat` 等 Windows 绑定）。

## 影响面与兼容性核查

| 既有用例 / 行为 | 变化 |
|---|---|
| `glob_matches`（`**/*.java` 命中 `src/A.java`） | 仍命中（cwd 相对 base） |
| `glob_pathParam_insideWork_finds`（`*.java` + `path=src`） | 仍命中（搜索根相对 base） |
| `skillsDir_allowsReadGlobGrep`（`**/SKILL.md`，输出含 `debug/SKILL.md`） | 仍命中；区外输出绝对路径，断言子串不变 |
| `glob_pathParam_missingPath_error` / `glob_badPattern_error` | 不变 |
| 越界守卫（`errorIfOutsideRead` + 确认）、命中上限 200 | 不变 |
| 17 处 `SchemaGenerator.objectSchema` 现有调用 | 不变（走旧签名） |

## 测试计划（TDD，先写失败用例）

1. `glob_patternAbsolutePath_insideWork_finds`：pattern 为 cwd 绝对路径 + `/src/**/*.java` → 命中，输出**相对 cwd** 路径。
2. `glob_patternRelativeCwd_withAbsoluteSubdirPath_finds`：`path=绝对子目录` + `pattern=src/**/*.java` → 命中。
3. `glob_doubleStar_matchesTopLevelFile`：`**/*.java` 命中搜索根下裸文件。
4. `glob_patternBackslash_normalizedOnWindows`：反斜杠 pattern（Windows 断言命中；Linux 跳过断言）。
5. `glob_notFound_hintContainsRoots`：无命中输出含搜索根与语义提示。
6. `glob_schema_hasParamDescriptions`：schema 属性含描述文案（`SchemaGenerator` 新重载单测）。
7. `glob_absolutePattern_outsideWork_noLeak`：pattern 指向工作区外 → 不越界遍历，无区外内容泄露。
8. 全量 `mvn test` 通过。

## 不做的事（YAGNI）

- 不按 pattern 静态前缀自动确定搜索根（会引入越界判定与确认链路的额外分支），改为未命中提示告知搜索根。
- 不加显式基准参数（用户否决：把正确性押在模型传参上）。
- 不处理路径段大小写（仅 Windows 盘符已验）。
- 不自己实现 glob→regex 转译。

## 实施记录（2026-09-17）

| 文件 | 改动 |
|------|------|
| `core/tools/SchemaGenerator.java` | 新增 `objectSchema(desc, String[][]{名称, 描述}, required)` 重载；旧签名与 17 处调用不变 |
| `core/tools/GlobTool.java` | description/schema 补 pattern 基准语义；`normalizePattern`（仅 Windows 反斜杠→`/`，去 `./` 前缀）；`zeroLevelVariants` 双星零层变体；`baseCandidates` 多基准（cwd 相对/搜索根相对/绝对）；输出统一「区内相对 cwd、区外绝对」；未命中回显搜索根与语义提示 |
| `core/agent/SystemPromptBuilder.java` | 「当前工作目录」段补一行：搜索类 pattern 可写相对工作目录形式，也支持绝对路径 |
| `src/test/.../FileToolsTest.java` | +6 用例：绝对 pattern 命中并输出相对 cwd、绝对 path + 相对 cwd pattern、双星零层（根下/一级直接子文件）、反斜杠 pattern（Windows）、未命中提示、schema 属性描述 |
| `src/test/.../SchemaGeneratorTest.java` | +1 用例：带属性描述重载（空描述不输出字段） |

验证：

- `mvn test` 全量 **1172 用例通过**（FileToolsTest 50 / SchemaGeneratorTest 2 / SystemPromptBuilderTest 7，含全部新增用例）。
- 端到端探针（`run/.session/tmp/…/GlobFixVerify.java`，真实仓库目录，直接调用编译产物）6 场景全通过：
  1. **用户场景**：pattern 绝对路径（单文件）→ 命中，输出 `src/main/java/com/minion/core/tools/GlobTool.java`（相对 cwd）
  2. 绝对 pattern + 通配 → 命中，输出相对 cwd
  3. 反斜杠绝对 pattern → 命中（Windows 归一化生效）
  4. `path=绝对子目录` + pattern 相对工作空间 → 命中
  5. 零层：`src/main/java/com/minion/core/tools/**/*.java` → 命中 tools 下直接文件
  6. 未命中 → 回显「搜索根 + 语义提示」

未改：越界守卫与确认链路、命中上限 200、`GrepTool`（其 pattern 是内容正则，不受影响）。
