# Grep / Glob 遍历有界化（目录环防护 + 预算 + 中断 + 跳过不可读项）

日期：2026-10-26
状态：已实施（2026-10-26；`GrepGlobBudgetTest` 12 用例 + 全量 1291 用例通过）
范围：`core/tools/GrepTool`、`core/tools/GlobTool`，新增包内工具类 `core/tools/WalkBudget`
不含：`ReadTool`（用户明确暂不处理）、`AgentLoop` 工具硬超时看门狗（本次不做，见「残留风险」）

## 背景与现象

用户在另一台机器（Windows 7）上偶发：并发工具调用时其中一个失败、另一个迟迟不返回，
正文左下角一直轮换「正在加载中...／可随时补充信息...」，只能手动终止后用「继续」恢复；
另有「显示加载中但后台实际已无进展」的偶发现象。

已确认的代码级机制（与本次修改相关部分）：

1. **结果按提交顺序串行收集**：`AgentLoop:679-694` 把所有 tool_call 一次性 submit 到工具池
   （`AgentLoop:77` `threads = 4`），再按顺序 `awaitToolResult`；`ui.onToolResult` 在取到该
   future 时才发。→ 先提交的工具不返回，后面已完成/已失败的工具结果行都不会显示。
2. **等待无总超时**：`AgentLoop:778` `awaitToolResult` 用 `future.get(200ms)` 无限轮询，
   唯一出口是用户点停止。工具不返回 → 本轮永不结束。
3. **运行中补充注入不了**：补充只在「工具阶段全部收完后」的检查点入历史，
   卡在工具阶段时输入「继续」无效 → 必须手动终止（与用户观察一致）。
4. 工具卡住时 `cancel(true)` 只置中断位。`Files.walkFileTree` / `Files.readAllLines` 不解中断，
   线程不退出 → 池线程被永久占用；4 个线程耗尽后所有工具只进队列不执行（重启才恢复）。

## 本次定位到的确定缺陷（带证据）

### D1 遍历遇到不可读目录/文件 → 整个工具失败

`GrepTool:108`、`GlobTool:104` 的 `SimpleFileVisitor` **没有覆写 `visitFileFailed`**。
`walkFileTree` 默认实现对「拒绝访问」等错误直接抛出，导致整次 Grep/Glob 抛 IOException 失败
（`SkillManager:112` 是覆写并 CONTINUE 的，文件工具此前漏了）。
公司机器上存在权限受限目录时，Grep/Glob 会随机整体失败——**大概率就是「其中一个失败」那一次**。

### D2 目录环可能无限递归

`walkFileTree` 默认不跟随符号链接，但 Java 8 在 Windows 上只把 reparse tag 为
`SYMLINK` 的项识别为符号链接；**junction（`MOUNT_POINT`）被当成普通目录**，会被递归进入。
项目/用户目录里存在 junction 环时，遍历永不结束。

### D3 Grep 对每个文件全量读入（无大小上限、无二进制判断）

`GrepTool:130` → `TextFiles.readAllLines`（`TextFiles:27`）整文件读进内存；
UTF-8 严格解码失败后还要按 GBK **再整读一遍**。遇到日志、jar/exe、dump、数据文件时：
耗时从秒级升到分钟级，内存可达 GB 级，且期间不响应中断。这是「偶发长时间无响应」的主要来源。

### D4 无时间/条目预算，且中断不可感知

遍历本身没有时间与条目上限（Glob 仅受 `MAX_RESULTS = 200` 命中数限制，空匹配时无界），
也不检查线程中断 → 停止按钮无法真正结束遍历线程。

## 设计

### 1. 新增 `WalkBudget`（`com.minion.core.tools`，包内可见）

Grep/Glob 共用的遍历预算器：

| 字段/常量 | 默认值 | 说明 |
|---|---|---|
| `MAX_MILLIS` | 30_000 | 时间预算（自遍历开始计；用户评审时由 10s 调整为 30s） |
| `MAX_ENTRIES` | 200_000 | 访问条目（目录 + 文件）上限 |
| `MAX_DEPTH` | 64 | 单条路径最大深度（相对搜索根） |

方法（返回 `FileVisitResult` 语义，调用方直接返回给 walkFileTree）：

- `FileVisitResult enterDir(Path dir)`：深度优先入口，顺序执行
  ① 中断检查（`Thread.currentThread().isInterrupted()` → `TERMINATE`，置 `interrupted`）
  ② 条目计数 / 时间预算（超限 → `TERMINATE`，置 `exhausted` + `exhaustedReason`）
  ③ 深度超限 → `SKIP_SUBTREE`
  ④ 目录环去重：`dir.toRealPath()` 放入 `Set<Path> seenDirs`，已存在 → `SKIP_SUBTREE`；
     `toRealPath` 抛 IOException（无权限/失效）→ `SKIP_SUBTREE` 并 `skippedInaccessible++`
- `boolean checkFile()`：中断 + 预算检查（false = 终止遍历）
- `FileVisitResult fileFailed()`：`skippedInaccessible++`，返回 `CONTINUE`
  （预算已耗尽时返回 `TERMINATE`）
- `String summary()`：生成追加文案（见第 4 节），无跳过且未截断时返回 `null`
- 包内构造 `WalkBudget(int maxEntries, long maxMillis, int maxDepth)` 供单测注入小预算；
  生产用 `WalkBudget.defaults()`

`toRealPath` 每目录一次系统调用；预算封顶下开销可接受。它同时解决 junction 环与
「软链接指向搜索根之外」两个问题。

### 2. GrepTool 改动（`GrepTool:108` 起的 visitor）

新增常量：

- `MAX_FILE_BYTES = 5 * 1024 * 1024`：单文件上限，`attrs.size()` 超限 → 跳过并计数（属性已随
  `visitFile` 提供，无额外 IO）
- `BINARY_PROBE_BYTES = 8192`：二进制探测读前 8KB，出现 `0x00` → 跳过并计数；读失败也按跳过处理

visitor 内顺序：

```
preVisitDirectory: dump 目录判断（保留原逻辑）→ budget.enterDir(dir)
visitFile:         budget.checkFile() → dump 文件跳过（原逻辑）→ 大小上限 →
                   二进制探测 → TextFiles.readAllLines（原逻辑不变）
visitFileFailed:   budget.fileFailed()
```

结果文案：原有「未匹配 / 成功 / 落盘路径」契约不变，末尾追加 `budget.summary()`
（例如「（遍历已截断：达到 30 秒时间预算，已扫描 12345 项，结果可能不完整）」+
「（已跳过 3 个大于 5MB 的文件、2 个二进制文件、1 个无权限项）」）。

截断判定扩展：`budget.exhausted || budget.interrupted` 也视为结果不完整——
此时若已有匹配（`totalCount > 0`）保留落盘文件并在文案里给出路径；无匹配则照旧删除落盘文件。

中断语义：返回 `ToolResult.success`（部分结果 + 文案），不用 error —— 用户主动停止不是工具错误；
主循环此时已 break，结果行不会显示。

### 3. GlobTool 改动（`GlobTool:104` 起的 visitor）

同样接入 `budget.enterDir` / `checkFile` / `fileFailed`；`MAX_RESULTS = 200` 命中即 `TERMINATE`
的现有逻辑保留。结果末尾追加 `budget.summary()`。

### 4. 文案规范（两种工具统一）

- 预算耗尽：`（遍历已截断：达到 <时间|条目> 预算，已扫描 N 项，结果可能不完整）`
- 中断：`（遍历已中断，结果可能不完整）`
- 跳过项：`（已跳过 <A> 个大于 5MB 的文件、<B> 个二进制文件、<C> 个无权限项）`
- 各项为 0 时不出现；`summary()` 全空返回 null，调用方不追加任何内容（零文案变化）

### 5. 不新增配置项

预算/上限沿用现有硬编码常量风格（`MAX_RESULTS`、`LINE_MAX`、`DISPLAY_CHARS` 均硬编码），
不进 `config.properties`，避免配置面扩散；确有需要时再统一外置。

## 测试计划（新建 `src/test/java/com/minion/core/tools/GrepGlobBudgetTest.java`）

1. `grep_skips_large_file`：临时目录写入 >5MB 文本（含唯一标记）→ 断言无匹配、文案含「大于 5MB」
2. `grep_skips_binary_file`：写入含 `0x00` 的文件（带可匹配文本）→ 断言跳过、文案含「二进制」
3. `grep_entry_budget_exhausted`：注入 `WalkBudget(5, 60_000, 64)`，目录内 20 个文件 → 断言文案含「遍历已截断」
4. `grep_time_budget_exhausted`：注入 `WalkBudget(200_000, 1, 64)` → 断言快速返回且文案含「截断」
5. `grep_interrupted_terminates`：先 `Thread.currentThread().interrupt()` → execute 快速返回；
   `finally` 清中断位（`Thread.interrupted()`）→ 断言文案含「已中断」
6. `glob_budget_and_depth`：注入 `WalkBudget(200_000, 60_000, 2)` + 3 层深目录 → 断言深层文件不出现
7. `glob_interrupted_terminates`：同 5
8. `walkbudget_file_failed_counts_and_continues`：直接单测 `WalkBudget.fileFailed()` 返回 CONTINUE 且计数 +1
9. 回归：`FileToolsTest` 现有 Grep/Glob 用例全绿（输出前缀与落盘路径口径不变）

## 残留风险（本次不解决，需向上层说明）

1. **单次文件系统调用不可取消**：网络盘/UNC 不可达、杀毒软件锁文件、坏盘时，单次
   `readAttributes` / `readAllLines` / `toRealPath` 仍可能阻塞数十秒甚至更久，期间中断检查
   没有机会执行。预算只能消除「多次小阻塞累积」，消除不了「单次长阻塞」。
2. **池线程仍可能被占死**：上述单次阻塞的线程 `cancel(true)` 后不会退出；4 个线程耗尽后
   后续工具只入队不执行（需重启）。彻底兜底要 `AgentLoop` 侧的工具硬超时看门狗（本次不做）。
3. **ReadTool 未改**：`ReadTool:145` 仍全量 `readAllLines`，单个超大文件同样能占住池线程。
4. **确认等待持锁**：`ConfirmGate:22` 等用户点卡片期间持对象锁，同工作空间所有工具串行阻塞
   （本次不改，属独立问题）。

## 实施记录（2026-10-26）

- 新增 `WalkBudget`：`MAX_MILLIS=30_000` / `MAX_ENTRIES=200_000` / `MAX_DEPTH=64`；
  `enterDir(dir, root)` 返回 `FileVisitResult`（`TERMINATE`/`SKIP_SUBTREE`/`CONTINUE`），
  `checkFile()`、`fileFailed()`、`skipLargeFile()`、`skipBinaryFile()`、`incomplete()`、`summary()`；
  `newRoot()` 供 Glob 多根共享时间/条目预算（去重集合按根独立）；`appendNote(body, note)`
  统一追加口径（note 为 null 时逐字符不变）。
- `GrepTool`：新增 `MAX_FILE_BYTES=5MB`、`BINARY_PROBE_BYTES=8192` 与静态 `isBinary()`；
  预算耗尽/中断且已有匹配时保留落盘文件，文案改为「已收集结果已保存到 …」（未截断仍是
  「完整结果已保存到 …」，既有断言不受影响）。
- `GlobTool`：接入预算与 `visitFileFailed`；`MAX_RESULTS=200` 提前终止逻辑保留。
- 单测 `GrepGlobBudgetTest`（12 例）：大文件/二进制跳过、条目/时间预算、中断终止、
  深度上限、多根共享预算、`fileFailed` 计数、同真实目录二次进入（环）跳过、默认常量。
- 文档同步：`docs/ARCHITECTURE.md`（core/tools 说明 + 关键写死常量表）、`README.md`
  （「Grep / Glob 遍历有界」小节）。
