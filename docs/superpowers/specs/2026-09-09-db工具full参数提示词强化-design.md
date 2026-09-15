# db 工具 full 参数提示词与 schema 强化设计

日期：2026-09-09
状态：已实施（2026-09-09，schema 手写 + description 硬规则化落地，全量 1047 测试通过）

## 1. 背景与问题

`full=true` 全文模式（见 2026-09-08-数据库大字段解析优化-design.md）已落地，但线上使用中
**模型几乎不主动调用**：拿到 `…[完整 N 字符]` 截断标注后，仍然继续用截断片段做结论，不重发查询取全文。

根因（两处，均为「模型看不见/不当回事」）：

1. **schema 里 full 是隐形的**：`DbTool.schema()` 走 `SchemaGenerator.objectSchema`，
   该生成器对每个属性只产出 `{"type":"string"}`，**无 description**，且 full 语义为布尔却声明成 string。
   模型在工具定义里看到的只有四个光秃秃的 string 属性，无法判断 full 是什么、何时该用。
2. **description 引导弱且位置靠后**：full 说明夹在「大字段默认截断…」长句中间，用「请加 full=true」的
   建议语气，且未写出触发条件（看到 `…[完整 N 字符]` 就是触发信号），模型读过去即忽略。

## 2. 目标与非目标

### 目标

- 模型在工具定义层面就能看清 full 的**类型、语义、触发条件**；
- 三个数据库工具（DbMysql / DbPostgres / DbOracle，均由 `DbTool` 参数化）一致生效；
- 把「取全文」写成**硬规则**（必须），而不是建议；
- 不改变任何执行行为（截断线、行数上限、预算、落盘逻辑全部不动）。

### 非目标

- 不改 `MarkdownTable` / `DbExecutor` 的任何逻辑与常量；
- 不改默认截断线 120、不改 `MAX_ROWS=100`、不改 `CHAR_BUDGET=30000`；
- 不做工具结果内的追加提示（本轮只改工具定义；方案 B 留待观察效果后再定）；
- 不新增 config.properties / tools.json 配置项。

## 3. 改动一：`DbTool.schema()` 手写精确 schema

参照 `TodoWriteTool` 的既有做法（`SchemaGenerator` 全 string + 无描述，已在 TodoWrite 上踩过坑），
`DbTool.schema()` 改为手写 `JsonObject`：

| 属性 | 类型 | required | description |
|---|---|---|---|
| action | string（enum = `type.actions()`） | 是 | 操作类型：query 执行只读 SQL；schema 列出表与视图；describe 查看表字段（PG 仅有 query） |
| sql | string | 否 | action=query 时必填：单条只读 SQL（SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN） |
| table | string | 否 | action=describe 时必填：表名（支持 % / _ 模式） |
| full | **boolean** | 否 | **仅在需要大字段全文时设 true**：单元格截断线从 120 放宽到 20000 字符；结果里出现「…[完整 N 字符]」且需要该内容时，必须设 full=true 重发同一查询（SQL 用 WHERE/LIMIT 限定到单行/少行） |

- required 仍只有 `action`（sql/table 的条件必需由 `execute` 的既有错误文案兜底，JSON Schema 不支持条件 required）。
- 顶层 `description` 与 `type.displayName() + " 只读数据库操作"` 保持现状。
- `full` 改 boolean 后，`DbTool.fullOf` 的容错解析**保留不动**（模型若仍传字符串 `"true"` 也能解析）。
- action 的 enum 按 `type.actions()` 动态生成：PG 只有 query，schema/describe 不出现在枚举里。

## 4. 改动二：`DbTool.description()` 文案重排（硬规则前置）

保持信息量不增不减，只调整顺序与语气，把 full 规则提到「默认截断」同一句内并写成必须：

```
mysql 只读查询（当前数据源: prod，jdbc:mysql://…）。
action=query 执行只读 SQL 返回 Markdown 表格（最多 100 行）。
大字段(CLOB/TEXT/LONGTEXT/JSON 等)默认截断 120 字符并标注「…[完整 N 字符]」；
当结果里出现该标注、或你已知该字段很长而需要其完整内容时，
必须重发同一查询并设 full=true，并用 WHERE/LIMIT 把 SQL 限定到需要的行：
full=true 时单元格上限 20000 字符，单值查询可 inline 全文，超出自动落盘并附文件路径。
action=schema 列出表与视图；action=describe 查看表字段。
仅允许 SELECT/WITH/SHOW/DESC/DESCRIBE/EXPLAIN，禁止多语句与写操作。
```

- 三个 DbType 共用该模板（PG 分支仍把 schema/describe 换成既有「不支持」提示文案）。
- 文案中的 `120` / `20000` / `100` 分别引用 `MarkdownTable.CELL_MAX`、`DbExecutor.FULL_CELL_MAX`、
  `DbExecutor.MAX_ROWS` 常量拼接，不写死字面量。

## 5. 测试计划

`DbToolTest`（不触达 JDBC）：

1. 既有 `schemaDeclaresFullParameter` 保留（properties 含 full）；
2. 新增：`full` 属性 `type == "boolean"` 且 description 非空、含「完整」与「full=true」关键词；
3. 新增：`action` 属性有 enum，MYSQL 含 query/schema/describe，POSTGRES 仅含 query；
4. 新增：三个 DbType 的 `description()` 都含「必须重发同一查询并设 full=true」；
5. 既有 `fullOfParsesTolerantly` 保留（字符串容错不回归）。

回归：`mvn test` 全量通过。

## 6. 影响与同步

- 涉及文件：`DbTool.java`、`DbToolTest.java`、`README.md`（补「大字段全文 / full=true」使用说明）、本文档。
- 无 config.properties / tools.json 变更（工具名、参数集不变，仅 full 类型与描述强化）。
- 若线上仍出现「模型不调用 full」，下一步按方案 B 在截断结果尾部追加一行显式提示（本轮不做）。

## 7. 实施快照（2026-09-09）

- `DbTool.schema()` 手写：action（enum 按 DbType 动态）、sql、table、full（boolean + 触发条件描述）；required 仍为 `action`。
- `DbTool.description()`：full 规则提为「必须重发同一查询并设 full=true」并写出触发信号 `…[完整 N 字符]`；
  120 / 20000 / 100 分别引用 `MarkdownTable.CELL_MAX` / `DbExecutor.FULL_CELL_MAX` / `DbExecutor.MAX_ROWS`。
- 新增测试：`schemaDeclaresFullParameter`（boolean + 描述关键词）、`schemaDeclaresActionEnumByDbType`、
  `descriptionStatesFullRuleForAllDbTypes`；`fullOfParsesTolerantly` 保留（字符串容错不回归）。
- 回归：`mvn test` 1047 全绿。

## 8. 与远端合并（2026-09-15）

与远端「大输出入历史闸门 ToolOutputGate + ReadTool 自限 + SQL 超限拆分引导」(`e41f7c4`) 合并，冲突两处均按「互为补充、全部保留」解决：

- `DbTool.description()`：全文硬规则文案（本文档 §4）保留，尾部追加远端落盘拆分引导
  （`结果超过 CHAR_BUDGET 字符会落盘并提示拆分——请避免一次拉取大量大字段（如整表 JSON 列），按需 WHERE/LIMIT 拆分查询`）；
  合并后文案同时满足双方新增测试（「必须重发同一查询并设 full=true」与「结果超过 30000 字符会落盘并提示拆分」）。
- `README.md`：保留远端更完整的「结果上限 / 落盘拆分引导」一行，另起一行保留本文档引入的
  「大字段全文 / full=true」说明，并补注 schema 已将 `full` 显式声明为布尔参数。
- 回归：`mvn test` 1167 全绿（含双方新增用例）；`mvn package` 通过。
