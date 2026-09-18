# 大字段原样导出与 Read full 设计

日期：2026-09-18
状态：已实施（2026-09-18，全量测试通过；含最终审查修复波：CLOB 取值修复 + full 尾提示预留）

## 1. 背景与问题

只读数据库查询（DbTool → DbExecutor → MarkdownTable）遇到"多字段查询、其中一个大字段几万字
（JSON / base64 / 长文本）需要解析解码"的典型场景时，模型无法可靠取得该字段完整原文。现有链路
三段损伤叠加：

| # | 环节 | 行为 | 后果 |
|---|---|---|---|
| 1 | `MarkdownTable.escape` | `\n`→`<br>`、`\|`→`\|`；**落盘文件同样转义** | 不可逆污染：原文无法还原，且 `<br>` 与内容中的字面 `<br>` 不可区分 |
| 2 | `DbExecutor` | 单元格超 `FULL_CELL_MAX=20000`（full 模式）或 `CELL_MAX=120`（默认）截断；总长超 `CHAR_BUDGET=30000` 时整表（转义版）落盘 | 模型手上只有碎片 + 一个不可无损还原的文件 |
| 3 | `ReadTool` | 单次输出上限 30000 字符；**单行超 `MAX_LINE_CHARS=2000` 直接截断，且 offset 只能按行推进、无行内续读** | 无换行的长行（base64 / minified JSON）后半段**永远读不出来**，分页拼接不可能拼对 |
| 4 | `ToolOutputGate` | 所有工具结果入历史统一 30000 字符硬上限（Read 类不落盘但同样截断） | 即使读侧放开也会被闸门砍回 |

实际后果：模型多次 `Read` + 拼接，拼错、费 token，且因第 3 条在结构上就不可能成功。

## 2. 目标与非目标

### 目标

- 大字段完整内容以**逐字节原样**（零转义、零截断）形态可取得，供解析/解码；
- 少轮次读回：`Read full=true` 单次上限提升到 100000 字符并取消单行截断，几万字字段一次到手；
- 默认路径的上下文卫生产不变：30000 / 2000 / 120 / 20000 / MAX_ROWS=100 等既有口径全部不动；
- 任何放开都有界，且与闸门口径联动（不出现"工具放开、闸门砍回"的白改）。

### 非目标

- 不做行内续读（单行 >100000 字符的极端情况明确交给 Bash，YAGNI）；
- 不做 JSONL / TSV 结构化导出（已定：原样文件，逐字节等于库里的值）；
- 不改 GUI、不新增 config.properties 配置项、不改 SqlGuard / 行数上限 / 单元格截断线。

## 3. 方案总览（四处改动）

| # | 改动 | 位置 |
|---|---|---|
| ① | 大字段原样导出（>20000 字符的单元格值逐字节写文件）+ 表尾导出清单 | DbExecutor + 新增 DbExport 纯函数类 + OutputDump |
| ② | Read 新增 `full` 参数（上限 100000、取消单行截断） | ReadTool |
| ③ | 闸门联动放宽（Read 上限 100000，其余工具不变） | ToolOutputGate |
| ④ | 工具描述引导（导出文件用 Read full / Bash 处理） | DbTool / ReadTool description |

数据流（核心场景）：

```
模型查询：   SELECT id, name, payload FROM t WHERE id = 7
            → 表格照旧（payload 格显示 …[完整 51234 字符 → 见下方导出清单]）
            → 表尾清单：第 1 行 · payload（51234 字符）→ /abs/.../db-r1-c3-payload-<ts>-<seq>.txt

模型取原文： Read full=true 该文件 → 一次读回 51234 字符原文（无转义无截断）
            或 Bash 直接处理（base64 -d / jq …），原文不进上下文
```

## 4. 详细设计

### 4.1 大字段原样导出

**触发（判定规则，刻意简单）**：query 结果中单元格**原始值**（`String.valueOf(rs.getObject(i))`，
转义前）长度 **> `EXPORT_MIN_CHARS = 20000`** 即导出。与 `FULL_CELL_MAX` 同口径：该区间的值在
两种 cellMax 下都必然被截断，无需类型识别。

**写盘**：复用 `OutputDump.write(tmpDir, prefix, content)`，prefix 为
`db-r<行号>-c<列序号>-<清洗列名>`（行号 1-based，列序号 1-based；列名仅保留 `[A-Za-z0-9_-]`，
其余替换 `_`，空名用 `col<列序号>`）。落盘内容 = 原始值，**不做任何转义与截断**。
文件生命周期随会话临时目录（SessionManager 删会话递归删除，现有机制）。

**展示（表格内）**：被导出的单元格显示 `…[完整 N 字符 → 见下方导出清单]`；
`MarkdownTable.cell(v, cellMax)` 增加 `exported` 重载（未导出时标注文案不变，防回归）。

**表尾导出清单（附加 hint）**：由 DbExecutor 生成、经 `MarkdownTable.fit` 附加：

```
…（以下单元格完整内容已原样导出（未转义、未截断），可用 Read full=true 一次读回，或用 Bash 直接处理）：
  · 第 1 行 · payload（51234 字符）→ /abs/.../db-r1-c3-payload-1699999999-1.txt
  · …另有 N 个文件未列出
```

- 清单最多列 **5** 条，超出折叠为"另有 N 个文件未列出"（防撑爆 30000 字符预算）；
- 清单文本占用 `CHAR_BUDGET` 内的 headBudget，返回总长仍 ≤ 30000（沿用 fit 现有约束）。

**与既有整表落盘的关系**：整表 Markdown 落盘（`overBudget` 或 `cellCut` 触发）**保留不变**，
作为转义版全景兜底；大字段原样导出是**新增的独立通道**，可同时出现。提示顺序：先原样导出清单，
后原有落盘提示。

**降级**：`tmpDir` 为 null 或写盘失败（`OutputDump.write` 返回 null）→ 该单元格恢复现状标注
（无"→ 见下方导出清单"），清单段替换为"…（N 个大字段原样导出失败，请拆分查询或加 WHERE/LIMIT）"。
导出失败不影响查询结果本身。

**抽出的纯函数（便于单测，无 JDBC）**：新增 `core/tools/db/DbExport.java`：

- `int EXPORT_MIN_CHARS = 20000`
- `boolean shouldExport(Object rawValue)` — 原始值长度判定
- `String stringOf(Object v)` — JDBC 列值转字符串（**修复波补充**：`java.sql.Clob` 显式 `getSubString` 读内容，
  读取失败降级 `String.valueOf(v)`；Oracle 的 CLOB 经 `getObject` 只拿到句柄，不读内容则判定与导出双失效）
- `String prefix(int rowNum, int colIdx, String colName)` — 文件名前缀（清洗 + 兜底）
- `String hint(List<Entry> exports, int failed)` — 清单拼装（含 5 条折叠、失败降级文案）
- `Entry`：行号 / 列名 / 字符数 / 绝对路径

### 4.2 Read 新增 `full`

- 参数：`full`（boolean，默认 false，schema 中声明）。
- `full=true` 时：
  - 单次输出上限 `FULL_MAX_OUTPUT_CHARS = 100000`（新常量；默认路径仍 `MAX_OUTPUT_CHARS = 30000`）；
  - **取消单行 `MAX_LINE_CHARS=2000` 截断**（整段原文原样输出，整体仍受 100000 上限约束）；
  - 单行上限改为约 `FULL_MAX_OUTPUT_CHARS`：**超过约 10 万字符的单行仍截断**并附
    "该行无法用 offset 续读，请用 Bash 处理（如 head -c / split）"（不支持行内续读，与非目标一致）；
  - 正文按 `FULL_MAX_OUTPUT_CHARS - FULL_TAIL_RESERVE(128)` 累积（**修复波补充**：为尾提示预留），
    保证「内容 + 全部尾提示」总长 ≤ 100000——从而逐字通过入历史闸门，offset 精确续读提示不被
    闸门换成通用分页提示（否则模型可能按原样重发，白吃 10 万字符）；首行即装不下时兜底裁剪到同一预算内。
- `full=false`（默认）行为**逐字符不变**：30000 上限 + 单行 2000 截断 + 续读提示。
- 成本提示写入 description：100000 字符 ≈ 2.5 万 token（ASCII；中文更高），仅在需要整段原文时使用。

### 4.3 ToolOutputGate 联动

- 常量：`MAX_CHARS = 30000`（不变，其余工具）；新增 `READ_FULL_MAX_CHARS = 100000`。
- `apply` 内按工具名分支：`"Read"` 用 `READ_FULL_MAX_CHARS`（仍**不落盘**，超限截断 + 分页提示）；
  `Grep`/`Glob` 与其他工具维持 30000 与既有落盘行为。
- 非 full 的 Read 输出实际为「约 ≤30000 正文 + 尾提示」（尾提示会小幅超出 30000）——闸门对 Read 已按
  工具名放宽到 100000，故不会被截断；`full=true` 才是放宽后真正获益的路径，其余工具口径不变。

### 4.4 描述与引导

- `DbTool.description` 增加：大字段超 20000 字符会**原样导出文件**（未转义、未截断）；需要解析/解码时
  优先用 Bash 在文件上做（base64/jq 等），需要整段读进上下文时用 `Read full=true`。
- `ReadTool.description`：说明 `full` 的用途、100000 上限与 token 成本。
- 系统提示（SystemPromptBuilder）不动。

## 5. 测试计划

| # | 用例 | 位置 |
|---|---|---|
| 1 | `shouldExport`：20000 不导出、20001 导出、NULL/普通值不导出 | 新增 DbExportTest |
| 2 | `prefix`：列名清洗（中文/空格/特殊字符→`_`）、空名兜底 `col<idx>`、行号列序号格式 | DbExportTest |
| 3 | `hint`：单条/多条、>5 条折叠、失败降级文案、空清单返回空串 | DbExportTest |
| 4 | `cell(v, cellMax, exported)`：exported=true 追加"→ 见下方导出清单"；未导出文案逐字符不变 | MarkdownTableTest |
| 5 | `fit(display, complete, tmpDir, extraHint)`：extraHint 附加且总长仍 ≤30000；旧签名行为不变 | MarkdownTableTest |
| 6 | Read `full=true`：50000 字符文件（含单行 50000）一次读回、无截断提示；`full=false` 同文件仍截断 + 续读（回归） | FileToolsTest |
| 7 | Read `full=true` 超 100000：截断 + 分页提示 | FileToolsTest |
| 8 | 闸门：Read 输出 100000 不砍、100001 截断且不落盘；Grep 30000 口径不变（回归） | ToolOutputGateTest |
| 9 | DbTool description 含"原样导出"与"Read full=true"字样 | DbToolTest |
| 10 | 全量 `mvn test` 回归 | — |

## 6. 边界与错误处理

| 场景 | 行为 |
|---|---|
| tmpDir 为 null / 写盘失败 | 降级：无清单条目，提示"N 个大字段原样导出失败，请拆分查询或加 WHERE/LIMIT"；表格标注回退现状 |
| 列名重复 | 文件名含列序号 `c<idx>`，天然不撞；展示名用原列名 |
| 一列多行大字段 | 每格一个文件（行号区分） |
| 导出文件过多（如 100 行 × 1 列） | 磁盘成本可忽略；清单折叠为最多 5 条 + "另有 N 个文件未列出" |
| NULL 值 | 长度 0，不导出处 |
| 恰为 20000 字符 | 不导出（`>` 判定） |
| full 读取 0 字节/空文件 | 与现状一致（返回空内容） |
| 单行 >100000 字符 | 提示用 Bash 处理，不支持行内续读（非目标） |

## 7. 影响与同步

- **修改**：`DbExecutor.java`、`MarkdownTable.java`、`ReadTool.java`、`ToolOutputGate.java`、`DbTool.java`（description）
- **新增**：`core/tools/db/DbExport.java`、`src/test/java/com/minion/core/tools/db/DbExportTest.java`
- **测试同步**：`MarkdownTableTest`、`FileToolsTest`、`ToolOutputGateTest`、`DbToolTest`
- **文档同步**：README（工具行为说明）、本设计文档；ARCHITECTURE.md 如描述到落盘/读机制一并同步
- **不动**：config.properties（常量硬编码，风格与 `SubAgentLoop.REPORT_MAX_CHARS` 一致）、GUI、SqlGuard、DbType

## 8. 风险与权衡

- **上下文成本**：一次读 100000 字符 ≈ 2.5 万 token（ASCII 口径；中文更高）。由模型显式选择 `full=true`，
  描述中明示成本；默认路径零变化。
- **闸门按工具名放宽**：非调用级；非 full 输出为「约 30000 正文 + 尾提示」，闸门对 Read 已放宽到
  100000（不会被截断），实际额外放开面仅来自 `full=true` 的显式选择。
- **导出文件冗余**（与整表落盘并存）：磁盘成本可忽略（会话临时目录，随会话删除）；换来两套兜底。
- **不做行内续读**：>100000 字符单行场景交 Bash；避免为极端情况增加工具复杂度。
