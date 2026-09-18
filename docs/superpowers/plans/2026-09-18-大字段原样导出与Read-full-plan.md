# 大字段原样导出与 Read full Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SQL 查询结果中超过 20000 字符的大字段逐字节原样导出为文件并给出清单路径；Read 新增 `full=true`（上限 100000 字符、单行不截断）；闸门联动放宽 Read 上限——让模型一次读回大字段原文，消除「Markdown 转义污染 + 单行 2000 截断」导致的拼接错误。

**Architecture:** 新增 `DbExport` 纯函数类（阈值判定 / 文件名前缀 / 写盘 / 清单文案），`DbExecutor.query` 行循环里收集待导出条目、末尾写盘并把清单传入 `MarkdownTable.fit` 新增的 extraHint 参数；`MarkdownTable.cell` 增加 exported 重载在表格里标记「见下方导出清单」；`ReadTool` 增加 full 模式（上限 100000、取消单行截断）；`ToolOutputGate` 对 Read 放宽到 100000（仍不落盘）。

**Tech Stack:** JDK 8、JUnit 4、Maven 单模块、Git Bash（Windows）。

## Global Constraints

- JDK 8 语法兼容（无 var / List.of / Stream.toList）；注释与 commit 用中文；commit 用 conventional 格式。
- 设计文档（已获用户批准）：`docs/superpowers/specs/2026-09-18-大字段原样导出与Read-full-design.md`
- 新增常量：`DbExport.EXPORT_MIN_CHARS=20000`、`DbExport.HINT_MAX_ITEMS=5`、`ReadTool.FULL_MAX_OUTPUT_CHARS=100000`、`ToolOutputGate.READ_FULL_MAX_CHARS=100000`。
- 既有口径**一律不动**：`MarkdownTable.CHAR_BUDGET=30000`、`CELL_MAX=120`、`DbExecutor.FULL_CELL_MAX=20000`、`MAX_ROWS=100`、`ReadTool.MAX_OUTPUT_CHARS=30000`、`MAX_LINE_CHARS=2000`、`ToolOutputGate.MAX_CHARS=30000`。
- 不新增依赖、不改 `config.properties`、不改 GUI、不改 SqlGuard / DbType。
- 所有命令在 `E:\javame\code\code2\minion` 下用 Git Bash 执行；每个任务结束跑相关测试，最后 `mvn test` 全量。

---

### Task 1: DbExport 纯函数类（判定 / 前缀 / 写盘 / 清单）

**Files:**
- Create: `src/main/java/com/minion/core/tools/db/DbExport.java`
- Test: `src/test/java/com/minion/core/tools/db/DbExportTest.java`

**Interfaces:**
- Consumes: `com.minion.core.tools.OutputDump.write(Path tmpDir, String prefix, String content) → Path`（tmpDir 为 null / 失败返回 null）
- Produces（后续任务按此签名调用）:
  - `public static final int EXPORT_MIN_CHARS = 20000`
  - `public static final int HINT_MAX_ITEMS = 5`
  - `public static final class Entry`（字段 `int rowNum` 1-based、`int colIdx` 1-based、`String colName`、`String value`、`String path`；构造器 `Entry(int, int, String, String, String)`）
  - `public static boolean shouldExport(Object rawValue)`
  - `public static String prefix(int rowNum, int colIdx, String colName)`
  - `public static String hint(List<Entry> written, int failed)`
  - `public static String dump(Path tmpDir, List<Entry> pending)` — 写盘全部 pending 条目并返回清单文案；无条目返回 `""`

- [x] **Step 1: 写失败测试** `src/test/java/com/minion/core/tools/db/DbExportTest.java`

```java
package com.minion.core.tools.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 大字段原样导出纯函数：阈值判定 / 文件名前缀 / 写盘 / 清单文案 */
public class DbExportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static Path onlyFile(Path dir) throws Exception {
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        try {
            for (Path p : ds) files.add(p);
        } finally {
            ds.close();
        }
        assertEquals("应只产生一个导出文件", 1, files.size());
        return files.get(0);
    }

    @Test
    public void shouldExport_onlyOverThreshold() {
        assertFalse(DbExport.shouldExport(null));
        assertFalse("恰为阈值不导出", DbExport.shouldExport(repeat('a', 20000)));
        assertTrue(DbExport.shouldExport(repeat('a', 20001)));
    }

    @Test
    public void prefix_cleansIllegalCharsAndFallsBack() {
        assertEquals("db-r2-c3-payload", DbExport.prefix(2, 3, "payload"));
        assertEquals("db-r1-c2-a_b_c", DbExport.prefix(1, 2, "a b|c"));
        assertEquals("db-r1-c1-col1", DbExport.prefix(1, 1, ""));
        assertEquals("db-r1-c1-col1", DbExport.prefix(1, 1, null));
        assertEquals("列名超长截到 40", "db-r1-c1-" + repeat('x', 40), DbExport.prefix(1, 1, repeat('x', 60)));
    }

    @Test
    public void dump_writesRawValueAndListsPath() throws Exception {
        Path dir = tmp.newFolder("t").toPath();
        String raw = "第一行\n第二行|竖线\r\n<br>字面量";  // 换行/竖线/字面 <br> 全部原样保留
        List<DbExport.Entry> pending = new ArrayList<DbExport.Entry>();
        pending.add(new DbExport.Entry(1, 3, "payload", raw, null));
        String hint = DbExport.dump(dir, pending);
        Path f = onlyFile(dir);
        assertEquals("逐字节原样（无转义无截断）", raw,
                new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        assertTrue("清单含引导语", hint.contains("已原样导出"));
        assertTrue("清单含绝对路径", hint.contains(f.toAbsolutePath().toString()));
        assertTrue("清单含行号与列名", hint.contains("第 1 行 · payload（" + raw.length() + " 字符）"));
    }

    @Test
    public void dump_nullTmpDir_degradesToFailureHint() {
        List<DbExport.Entry> pending = new ArrayList<DbExport.Entry>();
        pending.add(new DbExport.Entry(1, 1, "c", repeat('a', 20001), null));
        String hint = DbExport.dump(null, pending);
        assertTrue(hint, hint.contains("1 个大字段原样导出失败"));
        assertTrue(hint, hint.contains("WHERE/LIMIT"));
    }

    @Test
    public void hint_emptyReturnsEmpty_foldsOverFive_partialFailure() {
        assertEquals("", DbExport.hint(new ArrayList<DbExport.Entry>(), 0));
        List<DbExport.Entry> written = new ArrayList<DbExport.Entry>();
        for (int i = 1; i <= 6; i++) {
            written.add(new DbExport.Entry(i, 1, "c", repeat('a', 20001), "/tmp/f" + i + ".txt"));
        }
        String hint = DbExport.hint(written, 2);
        assertTrue(hint.contains("/tmp/f1.txt"));
        assertTrue(hint.contains("/tmp/f5.txt"));
        assertFalse("第 6 条折叠不列出", hint.contains("/tmp/f6.txt"));
        assertTrue(hint.contains("另有 1 个文件未列出"));
        assertTrue(hint.contains("另有 2 个大字段导出失败"));
    }
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=DbExportTest`
Expected: 编译失败 `cannot find symbol: class DbExport`

- [x] **Step 3: 实现** `src/main/java/com/minion/core/tools/db/DbExport.java`

```java
package com.minion.core.tools.db;

import com.minion.core.tools.OutputDump;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 大字段原样导出（纯函数，无 JDBC 依赖）：阈值判定 / 文件名前缀 / 写盘 / 表尾清单文案。
 * 逐字节原样落盘（不做 Markdown 转义、不做截断），供解析/解码场景避免转义污染与分页拼接错位。
 * 生命周期随会话临时目录（SessionManager 删会话递归删除）。
 */
public final class DbExport {

    /** 导出阈值：单元格原始值（转义前）超过此长度即原样导出（与 DbExecutor.FULL_CELL_MAX 同口径） */
    public static final int EXPORT_MIN_CHARS = 20000;
    /** 清单最多列出的条目数，超出折叠（防撑爆返回字符预算） */
    public static final int HINT_MAX_ITEMS = 5;
    /** 文件名中列名片段的长度上限（防超长列名撑爆文件名） */
    private static final int NAME_MAX = 40;

    private DbExport() { }

    /** 待导出条目：rowNum/colIdx 均为 1-based；path 为写盘后的绝对路径（未写盘为 null） */
    public static final class Entry {
        public final int rowNum;
        public final int colIdx;
        public final String colName;
        public final String value;
        public final String path;

        public Entry(int rowNum, int colIdx, String colName, String value, String path) {
            this.rowNum = rowNum;
            this.colIdx = colIdx;
            this.colName = colName;
            this.value = value;
            this.path = path;
        }
    }

    /** 原始值是否需要原样导出（null 与 ≤ 阈值不导出；恰为 20000 不导出） */
    public static boolean shouldExport(Object rawValue) {
        return rawValue != null && String.valueOf(rawValue).length() > EXPORT_MIN_CHARS;
    }

    /** 文件名前缀：db-r<行>-c<列>-<清洗列名>；列名仅保留 [A-Za-z0-9_-]，空名兜底 col<列序号> */
    public static String prefix(int rowNum, int colIdx, String colName) {
        String safe = colName == null ? "" : colName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) safe = "col" + colIdx;
        if (safe.length() > NAME_MAX) safe = safe.substring(0, NAME_MAX);
        return "db-r" + rowNum + "-c" + colIdx + "-" + safe;
    }

    /** 写盘全部待导出条目并返回清单文案：写盘失败自动降级（不抛异常），无条目返回 "" */
    public static String dump(Path tmpDir, List<Entry> pending) {
        if (pending == null || pending.isEmpty()) return "";
        List<Entry> written = new ArrayList<Entry>();
        int failed = 0;
        for (Entry e : pending) {
            Path f = OutputDump.write(tmpDir, prefix(e.rowNum, e.colIdx, e.colName), e.value);
            if (f == null) {
                failed++;
                continue;
            }
            written.add(new Entry(e.rowNum, e.colIdx, e.colName, e.value, f.toAbsolutePath().toString()));
        }
        return hint(written, failed);
    }

    /** 表尾清单：written 为已写盘条目（path 非 null），failed 为写盘失败数；无任何导出时返回 "" */
    public static String hint(List<Entry> written, int failed) {
        boolean hasWritten = written != null && !written.isEmpty();
        if (!hasWritten && failed <= 0) return "";
        StringBuilder sb = new StringBuilder("\n\n…（");
        if (!hasWritten) {
            return sb.append(failed).append(" 个大字段原样导出失败，请拆分查询或加 WHERE/LIMIT）").toString();
        }
        sb.append("以下单元格完整内容已原样导出（未转义、未截断），可用 Read full=true 一次读回，或用 Bash 直接处理）：");
        int shown = Math.min(written.size(), HINT_MAX_ITEMS);
        for (int i = 0; i < shown; i++) {
            Entry e = written.get(i);
            sb.append("\n  · 第 ").append(e.rowNum).append(" 行 · ").append(e.colName)
              .append("（").append(e.value.length()).append(" 字符）→ ").append(e.path);
        }
        if (written.size() > shown) {
            sb.append("\n  · …另有 ").append(written.size() - shown).append(" 个文件未列出");
        }
        if (failed > 0) {
            sb.append("\n  · 另有 ").append(failed).append(" 个大字段导出失败（请拆分查询或加 WHERE/LIMIT）");
        }
        return sb.append("）").toString();
    }
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=DbExportTest`
Expected: 全绿（5 个用例）

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/db/DbExport.java src/test/java/com/minion/core/tools/db/DbExportTest.java
git commit -m "feat(db): 新增大字段原样导出纯函数类 DbExport"
```

---

### Task 2: MarkdownTable 导出标记与清单附加

**Files:**
- Modify: `src/main/java/com/minion/core/tools/db/MarkdownTable.java`（cell 区约 40-60 行、fit 区约 80-120 行）
- Test: `src/test/java/com/minion/core/tools/db/MarkdownTableTest.java`

**Interfaces:**
- Consumes: 无（本任务只扩展 MarkdownTable 自身）
- Produces（Task 3 依赖）:
  - `public static String cell(Object v, int cellMax, boolean exported)` — exported=true 时截断标注追加 `" → 见下方导出清单"`；旧 `cell(Object)` / `cell(Object, int)` 委托 exported=false，行为逐字符不变
  - `public static String fit(String display, String complete, Path tmpDir, String extraHint)` — extraHint（非 null）附加在落盘提示**之前**，返回总长恒 ≤ `CHAR_BUDGET`；旧 3 参/2 参签名委托 extraHint=null，行为不变
  - hint 文案中「Read 分页查看」改为「Read（full=true 一次读回或分页）查看」

- [x] **Step 1: 写失败测试**（追加到 `MarkdownTableTest`，类尾部即可；文件已 import `Files/Path/List/ArrayList`，无需新增 import）

```java
    /** exported=true 的截断标注追加导出清单指引；exported=false 文案与现状逐字符一致 */
    @Test
    public void cellExportedMarker() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 130; i++) sb.append('x');
        assertTrue(MarkdownTable.cell(sb.toString(), MarkdownTable.CELL_MAX, true)
                .endsWith("…[完整 130 字符 → 见下方导出清单]"));
        assertTrue(MarkdownTable.cell(sb.toString(), MarkdownTable.CELL_MAX, false)
                .endsWith("…[完整 130 字符]"));
    }

    /** extraHint 附加在落盘提示之前，返回总长仍 ≤ CHAR_BUDGET */
    @Test
    public void fitAppendsExtraHintWithinBudget() throws Exception {
        Path tmp = Files.createTempDirectory("md-table-extra");
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < 25000; i++) display.append('z');
        String complete = display.toString() + "TAIL";
        String extra = "\n\n…（以下单元格完整内容已原样导出（未转义、未截断），"
                + "可用 Read full=true 一次读回，或用 Bash 直接处理）：\n  · 第 1 行 · c（25004 字符）→ /abs/f.txt";
        String out = MarkdownTable.fit(display.toString(), complete, tmp, extra);
        assertTrue(out, out.contains("已原样导出"));
        assertTrue(out, out.contains("已落盘："));
        assertTrue("清单在落盘提示之前", out.indexOf("已原样导出") < out.indexOf("已落盘："));
        assertTrue("总长受预算约束: " + out.length(), out.length() <= MarkdownTable.CHAR_BUDGET);
    }

    /** extraHint=null（旧签名）：行为与现状一致（回归） */
    @Test
    public void fitNullExtraHintKeepsLegacyBehavior() throws Exception {
        Path tmp = Files.createTempDirectory("md-table-extra-null");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 31000; i++) sb.append('w');
        String out = MarkdownTable.fit(sb.toString(), sb.toString(), tmp, null);
        assertTrue(out, out.contains("已落盘："));
    }
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=MarkdownTableTest`
Expected: 编译失败（`cell(Object,int,boolean)` 与 4 参 `fit` 不存在）

- [x] **Step 3: 实现**

`MarkdownTable` 顶部常量区新增：

```java
    /** 导出标记：附在截断标注后，提示完整内容在表尾导出清单的文件里 */
    static final String EXPORT_MARK = " → 见下方导出清单";
```

`cell` 区替换为（新增 exported 重载，旧签名委托）：

```java
    /** null → 字面量 NULL；竖线转义；换行转 &lt;br&gt;；超 120 截断并标注真实长度 */
    public static String cell(Object v) { return cell(v, CELL_MAX, false); }

    /** null → 字面量 NULL；竖线转义；换行转 &lt;br&gt;；超 cellMax 截断：省略号 + 真实长度标注 */
    public static String cell(Object v, int cellMax) { return cell(v, cellMax, false); }

    /** 超 cellMax 截断：省略号 + 真实长度标注；exported=true 时追加「→ 见下方导出清单」 */
    public static String cell(Object v, int cellMax, boolean exported) {
        if (v == null) return "NULL";
        int rawLen = String.valueOf(v).length();   // 标注口径：转义（<br> 膨胀）前的真实长度
        String s = escape(v);
        if (s.length() > cellMax) {
            int cut = cellMax;
            // 截断点可能切在代理对（如 emoji）中间：丢弃尾部孤立高代理（对齐 TruncatedOutput 口径）
            if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
            s = s.substring(0, cut) + "…[完整 " + rawLen + " 字符" + (exported ? EXPORT_MARK : "") + "]";
        }
        return s;
    }
```

`fit` 区替换为（新增 4 参重载；hint 文案两处「可用 Read 分页查看」→「可用 Read（full=true 一次读回或分页）查看」）：

```java
    /**
     * 字符预算处理（旧口径，等价于 display == complete）：≤30000 原样返回；超出则全量落盘并返回头部 + 路径提示。
     */
    public static String fit(String full, Path tmpDir) { return fit(full, full, tmpDir, null); }

    /** 旧签名：等价于 extraHint=null（行为不变） */
    public static String fit(String display, String complete, Path tmpDir) {
        return fit(display, complete, tmpDir, null);
    }

    /**
     * 字符预算 + 截断落盘统一入口（extraHint = 大字段原样导出清单，附加在落盘提示之前）：
     * - display 是展示文本（单元格可能已按 cellMax 截断、含「…[完整 N 字符]」标注）；
     * - complete 是未截断全量文本，落盘内容永远是它（"完整内容已落盘"名副其实）；
     * - extraHint 非 null 时一并附加，并从 headBudget 中扣除（返回总长恒 ≤ CHAR_BUDGET）。
     * 触发器与既有口径不变：display 超 CHAR_BUDGET 或 display 与 complete 不一致。
     * tmpDir 为 null 或落盘失败 → 降级为纯内存截断。表格式数据头部比尾部有用（首行是表头），故不用 OutputDump.tail。
     */
    public static String fit(String display, String complete, Path tmpDir, String extraHint) {
        if (display == null) return "";
        if (complete == null) complete = display;
        String extra = extraHint == null ? "" : extraHint;
        boolean overBudget = display.length() > CHAR_BUDGET;
        boolean cellCut = !display.equals(complete);
        // 无截断即无导出（extra 非空必伴随单元格截断），防御分支保持最简
        if (!overBudget && !cellCut) return display + extra;

        Path dumped = OutputDump.write(tmpDir, "db", complete);
        String hint;
        if (dumped == null) {
            hint = cellCut && !overBudget
                    ? "\n\n…（单元格超长已截断：完整内容共 " + complete.length()
                      + " 字符未能落盘。请用 WHERE/LIMIT 缩小范围，或分列查询）"
                    : "\n\n…（结果过大：共 " + complete.length()
                      + " 字符，完整内容未能落盘。请拆分查询：加 WHERE/LIMIT 缩小范围、分页或分列查询）";
        } else if (cellCut && !overBudget) {
            hint = "\n\n…（单元格超长已截断：完整内容共 " + complete.length() + " 字符已落盘："
                    + dumped.toAbsolutePath()
                    + "。需要全文可用 Read（full=true 一次读回或分页）查看；也可加 WHERE/LIMIT 限定到需要的行）";
        } else {
            hint = "\n\n…（结果过大：共 " + complete.length() + " 字符，完整内容已落盘："
                    + dumped.toAbsolutePath()
                    + "。请拆分查询：加 WHERE/LIMIT 缩小范围、分页或分列查询；需要全文可用 Read（full=true 一次读回或分页）查看）";
        }
        int headBudget = CHAR_BUDGET - hint.length() - extra.length();
        if (headBudget < 0) headBudget = 0;
        String head = display.length() <= headBudget ? display : truncateAt(display, headBudget);
        return head + extra + hint;
    }
```

- [x] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=MarkdownTableTest`
Expected: 全绿（含既有回归用例：`fitTruncatesWhenNoTmpDir` / `fitDumpsUncutCompleteWhenCellTruncated` / `fitDumpsToTmpDirWhenOverBudget` 等断言文案未被改动）

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/db/MarkdownTable.java src/test/java/com/minion/core/tools/db/MarkdownTableTest.java
git commit -m "feat(db): 表格标记大字段导出并支持附加导出清单"
```

---

### Task 3: DbExecutor 接线 + DbTool description

**Files:**
- Modify: `src/main/java/com/minion/core/tools/db/DbExecutor.java`（`query` 方法，约 90-125 行）
- Modify: `src/main/java/com/minion/core/tools/db/DbTool.java`（`description` 方法，约 55-85 行）
- Test: `src/test/java/com/minion/core/tools/db/DbToolTest.java`

**Interfaces:**
- Consumes: Task 1 的 `DbExport.shouldExport/Entry/dump`；Task 2 的 `MarkdownTable.cell(v, cellMax, exported)` 与 `MarkdownTable.fit(display, complete, tmpDir, extraHint)`
- Produces: 无新公共接口（DbExecutor 内部接线）

**说明：** DbExecutor 无 JDBC 环境无法直接单测，其正确性由 Task 1/2 的纯函数测试 + 代码审查保证；本任务测试聚焦 DbTool description 文案。

- [x] **Step 1: 写失败测试**（追加到 `DbToolTest`）

```java
    /** description 必须引导：大字段原样导出（未转义未截断）+ Read full=true 读回 */
    @Test
    public void descriptionMentionsRawExportAndReadFull() {
        DbConfig c = new DbConfig();
        c.dataSources.add(new DataSourceConfig("ds1", "jdbc:mysql://127.0.0.1:3306/db", "u", "p"));
        c.current = "ds1";
        DbTool t = new DbTool(DbType.MYSQL, c, (java.nio.file.Path) null);
        String d = t.description();
        assertTrue(d, d.contains("原样文件"));
        assertTrue(d, d.contains("Read full=true"));
    }
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=DbToolTest`
Expected: `descriptionMentionsRawExportAndReadFull` 失败（文案尚未包含）

- [x] **Step 3: 改 DbExecutor.query 接线**

`query` 方法内（原 `List<List<String>> rows` 与 `List<List<String>> fullRows` 声明之后）新增：

```java
            List<DbExport.Entry> pending = new ArrayList<DbExport.Entry>();  // 待原样导出的大字段
```

原行循环替换为：

```java
            while (rs.next()) {
                if (rows.size() == MAX_ROWS) { truncated = true; break; }
                List<String> row = new ArrayList<String>();
                List<String> fullRow = new ArrayList<String>();
                int rowNum = rows.size() + 1;   // 1-based，与清单/文件名一致
                for (int i = 1; i <= cols; i++) {
                    Object v = rs.getObject(i);
                    boolean exported = DbExport.shouldExport(v);
                    row.add(MarkdownTable.cell(v, cellMax, exported));
                    fullRow.add(MarkdownTable.escape(v));
                    if (exported) {
                        pending.add(new DbExport.Entry(rowNum, i, names.get(i - 1), String.valueOf(v), null));
                    }
                }
                rows.add(row);
                fullRows.add(fullRow);
            }
```

原返回段（`if (rows.isEmpty()) ...` 之后的 `MarkdownTable.fit(...)`）替换为：

```java
            if (rows.isEmpty()) return ToolResult.success(head + "\n\n查询成功，0 行结果");
            String exportHint = DbExport.dump(tmpDir, pending);   // 大字段原样导出（失败自动降级）
            return ToolResult.success(MarkdownTable.fit(
                    head + "\n\n" + MarkdownTable.render(names, rows),
                    head + "\n\n" + MarkdownTable.render(names, fullRows), tmpDir, exportHint));
```

（`listTables` / `describe` 里既有的 `MarkdownTable.fit(sb.toString(), tmpDir)` 两参调用不动，走旧签名。）

- [x] **Step 4: 改 DbTool.description**

在 `.append(" 字符，单值查询可 inline 全文，超出自动落盘并附文件路径；")` 之后插入一句（保持链式 append）：

```java
          .append("超过 ").append(DbExport.EXPORT_MIN_CHARS)
          .append(" 字符的单元格会另存为原样文件（未转义、未截断）并在表尾给出导出清单：")
          .append("需要解析/解码时优先用 Bash 在文件上做，需要整段读进上下文时用 Read full=true 一次读回；")
```

- [x] **Step 5: 运行测试与编译**

Run: `mvn -q compile && mvn test -Dtest='DbToolTest,DbExportTest,MarkdownTableTest'`
Expected: 全绿（DbToolTest 含既有用例；编译零错误）

- [x] **Step 6: 提交**

```bash
git add src/main/java/com/minion/core/tools/db/DbExecutor.java src/main/java/com/minion/core/tools/db/DbTool.java src/test/java/com/minion/core/tools/db/DbToolTest.java
git commit -m "feat(db): 查询时大字段原样导出并在表尾给出清单"
```

---

### Task 4: ReadTool 新增 full 参数

**Files:**
- Modify: `src/main/java/com/minion/core/tools/ReadTool.java`
- Test: `src/test/java/com/minion/core/tools/FileToolsTest.java`

**Interfaces:**
- Consumes: 无
- Produces（Task 5 依赖口径）:
  - `public static final int FULL_MAX_OUTPUT_CHARS = 100000`
  - `full=true` 时单次上限 100000 字符且单行不截断；`full=false` 行为逐字符不变

- [x] **Step 1: 写失败测试**（追加到 `FileToolsTest`，Read 相关用例区）

```java
    /** full=true：单行 50000 字符一次读回、无截断（大字段解析场景） */
    @Test
    public void read_full_readsLongSingleLineInOneCall() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 50000; i++) line.append('x');
        Files.write(p("bigfield.txt"), line.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"bigfield.txt\",\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertEquals("输出 = 原文 + 行尾换行", line.length() + 1, r.output.length());
        assertTrue(r.output.startsWith(line.toString()));
        assertFalse("full 模式无截断提示", r.output.contains("截断"));
    }

    /** full=true 超 100000：截断 + offset 分页提示（读取类仍不落盘） */
    @Test
    public void read_fullOverLimit_truncatesAndHintsOffset() throws Exception {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            src.append("行").append(i);
            for (int j = 0; j < 12; j++) src.append('y');
            src.append('\n');
        }
        Files.write(p("hugefield.txt"), src.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"hugefield.txt\",\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("受 full 上限约束", r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS + 200);
        assertTrue("截断提示", r.output.contains("单次输出上限") && r.output.contains("请用 offset="));
    }
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=FileToolsTest`
Expected: `read_full_readsLongSingleLineInOneCall` 失败（无 full 参数，单行被 2000 截断）

- [x] **Step 3: 实现 ReadTool 改动**

(a) 常量区新增：

```java
    /** full=true 时的单次输出上限：几万字大字段一次读回（默认路径仍 MAX_OUTPUT_CHARS=30000） */
    public static final int FULL_MAX_OUTPUT_CHARS = 100000;
```

(b) `description()` 替换为：

```java
        return "读取文件内容，支持行号、偏移与行数限制；单次输出上限 " + MAX_OUTPUT_CHARS
                + " 字符（full=true 时 " + FULL_MAX_OUTPUT_CHARS + " 字符且单行不截断，用于读取大字段原文，"
                + "代价是单次上下文占用变大；超限请用 offset 分页续读）";
```

(c) `schema()` 替换为（手写 full 布尔属性；其余属性沿用 SchemaGenerator）：

```java
    @Override
    public JsonObject schema() {
        JsonObject schema = SchemaGenerator.objectSchema("读取文件内容",
                new String[][]{{"path", "文件路径"}, {"offset", "起始行（0-based，默认 0）"},
                        {"limit", "最多读取行数（默认 2000）"}, {"lineNumbers", "输出行号（默认 false）"}},
                new String[]{"path"});
        JsonObject full = new JsonObject();
        full.addProperty("type", "boolean");
        full.addProperty("description", "需要整段大字段原文时设 true：单次上限从 "
                + MAX_OUTPUT_CHARS + " 放宽到 " + FULL_MAX_OUTPUT_CHARS
                + " 字符且单行不再截断；代价是单次上下文占用变大（10 万字符约 2.5 万 token）");
        schema.getAsJsonObject("properties").add("full", full);
        return schema;
    }
```

(d) 新增容错解析（与 DbTool.fullOf 同口径）：

```java
    /** full 参数容错解析：布尔原值 / 字符串 "true"（大小写、首尾空白容忍），缺省或畸形一律 false */
    public static boolean fullOf(JsonObject args) {
        if (args == null || !args.has("full")) return false;
        try {
            JsonElement e = args.get("full");
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            return Boolean.parseBoolean(e.getAsString().trim());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
```

（需新增 `import com.google.gson.JsonElement;`）

(e) `execute` 的行循环替换为：

```java
        boolean full = fullOf(args);
        int maxChars = full ? FULL_MAX_OUTPUT_CHARS : MAX_OUTPUT_CHARS;
        ...
        for (int i = offset; i < to; i++) {
            String raw = lines.get(i);
            String body = full ? clipLineFull(raw) : clipLine(raw);
            String chunk = (lineNumbers ? (i + 1) + ": " : "") + body + '\n';
            if (sb.length() + chunk.length() > maxChars) {
                charLimited = true; // 本行及之后未显示：提示 offset 续读（不丢行、可无限分页推进）
                break;
            }
            sb.append(chunk);
            shown++;
        }
```

（`boolean full` 声明放在既有 `boolean lineNumbers = ...` 之后；循环内两处改动：`body` 计算与 `maxChars`。）

(f) 截断提示与新增 `clipLineFull`：

```java
        if (charLimited) {
            sb.append("... 单次输出上限 ").append(maxChars).append(" 字符，已显示第 ")
              .append(offset + 1).append('-').append(lastLine).append(" 行（共 ")
              .append(lines.size()).append(" 行）；请用 offset=").append(lastLine)
              .append(" 继续读取\n");
        } else if (lastLine < lines.size()) {
            sb.append("... 共 ").append(lines.size()).append(" 行，已显示 ")
              .append(shown).append(" 行（可用 offset/limit 翻页）\n");
        }
        return ToolResult.success(sb.toString());
    }

    /** full 模式单行上限 = 单次输出上限：超过则截断并说明该行无法用 offset 续读（交 Bash 处理） */
    private static String clipLineFull(String line) {
        if (line.length() <= FULL_MAX_OUTPUT_CHARS) return line;
        int cut = FULL_MAX_OUTPUT_CHARS - 100;   // 预留提示文本空间
        if (Character.isHighSurrogate(line.charAt(cut - 1))) cut--;
        return line.substring(0, cut) + "…[本行超长，共 " + line.length()
                + " 字符，超过单次上限；该行无法用 offset 续读，请用 Bash 处理（如 split/head -c）]";
    }
```

- [x] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=FileToolsTest`
Expected: 全绿（含既有回归：`read_charLimit_truncatesAndHintsNextOffset`、`read_singleLineOverLimit_clipped`）

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/ReadTool.java src/test/java/com/minion/core/tools/FileToolsTest.java
git commit -m "feat(read): 新增 full 模式一次读回大字段原文"
```

---

### Task 5: ToolOutputGate 放宽 Read 上限

**Files:**
- Modify: `src/main/java/com/minion/core/tools/ToolOutputGate.java`
- Test: `src/test/java/com/minion/core/tools/ToolOutputGateTest.java`

**Interfaces:**
- Consumes: Task 4 的 `ReadTool.FULL_MAX_OUTPUT_CHARS` 口径（数值一致 100000）
- Produces: `public static final int READ_FULL_MAX_CHARS = 100000`；`apply` 对 `"Read"` 用放宽上限（仍不落盘），其余工具/读取类口径不变

- [x] **Step 1: 写失败测试**（追加到 `ToolOutputGateTest`）

```java
    /** Read 放宽到 100000：100000 原样、100001 截断且不落盘（Read full 依赖） */
    @Test
    public void apply_readRelaxedTo100k_noDump() throws Exception {
        Path dir = tmp.newFolder("readfull").toPath();
        String out = repeat('a', ToolOutputGate.READ_FULL_MAX_CHARS);
        assertSame(out, ToolOutputGate.apply("Read", out, dir));
        String over = repeat('a', ToolOutputGate.READ_FULL_MAX_CHARS + 1);
        String gated = ToolOutputGate.apply("Read", over, dir);
        assertTrue(gated.startsWith(repeat('a', ToolOutputGate.READ_FULL_MAX_CHARS)));
        assertTrue(gated.contains("单次上限（" + ToolOutputGate.READ_FULL_MAX_CHARS + " 字符）"));
        assertEquals("读取类不落盘", 0, filesIn(dir).size());
    }
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ToolOutputGateTest`
Expected: 编译失败（`READ_FULL_MAX_CHARS` 不存在）

- [x] **Step 3: 实现**

常量区新增：

```java
    /** Read 的放宽上限：full=true 时可一次读回大字段原文（10 万字符 ≈ 2.5 万 token，ASCII 口径） */
    public static final int READ_FULL_MAX_CHARS = 100000;
```

`apply` 开头改为：

```java
    public static String apply(String toolName, String output, Path tmpDir) {
        int max = "Read".equals(toolName) ? READ_FULL_MAX_CHARS : MAX_CHARS;
        if (output == null || output.length() <= max) return output;
        String head = truncateAt(output, max);
        if (READ_TOOLS.contains(toolName)) {
            return head + "\n\n…（内容超单次上限（" + max + " 字符）已截断，原内容共 "
                    + output.length() + " 字符；请缩小范围或用 offset/limit 分页继续读取）";
        }
        // ……其余落盘分支与文案保持原样（既有测试断言依赖「Read 分页查看」字样，不改）
```

（类注释同步补一句：Read 放宽到 `READ_FULL_MAX_CHARS`，Grep/Glob 与生产类维持 `MAX_CHARS`。）

- [x] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=ToolOutputGateTest`
Expected: 全绿（含既有回归：Bash 30000 落盘、Grep 30000 不落盘）

- [x] **Step 5: 提交**

```bash
git add src/main/java/com/minion/core/tools/ToolOutputGate.java src/test/java/com/minion/core/tools/ToolOutputGateTest.java
git commit -m "feat(tools): 入历史闸门放宽 Read 上限至 10 万字符"
```

---

### Task 6: 文档同步与全量回归

**Files:**
- Modify: `README.md`（第 83-90 行「内置工具输出上限与落盘」、第 170-171 行数据库工具说明）
- Modify: `docs/ARCHITECTURE.md`（第 91-92 行 ToolOutputGate/ReadTool、第 203-206 行常量表）
- Modify: `docs/superpowers/specs/2026-09-18-大字段原样导出与Read-full-design.md`（状态行）
- Modify: 本计划文件（追加「执行快照」）

**Interfaces:** 无代码接口；纯文档。

- [x] **Step 1: 更新 README**

- 「内置工具输出上限与落盘」条目：Read 自限改为
  「**Read 自限**：单次输出上限 30000 字符、单行 2000 字符（超长行截断并标注总长）；`full=true` 时上限放宽到 100000 字符且单行不截断（读大字段原文用，代价是单次上下文占用变大）；达到上限时提示「请用 offset=B 继续读取」分页续读」
- 数据库条目：在「大字段全文」一行后补
  「超过 20000 字符的单元格会**另存为原样文件**（未转义、未截断，`db-r<行>-c<列>-<列名>-*.txt`）并在表尾给出导出清单：解析/解码优先用 Bash 在文件上做，需整段读进上下文时用 `Read full=true` 一次读回」

- [x] **Step 2: 更新 ARCHITECTURE**

- `ToolOutputGate` 行：补「Read 放宽至 READ_FULL_MAX_CHARS=100000（仍不落盘）；Grep/Glob 与生产类维持 30000」
- `ReadTool` 行：补「`full=true`：单次上限 FULL_MAX_OUTPUT_CHARS=100000、单行不截断（大字段原文读回）」
- 常量表新增三行：

```markdown
| 大字段原样导出阈值 EXPORT_MIN_CHARS / 清单折叠条数 | 20000 / 5 | DbExport |
| Read full 单次输出上限 | 100000 | ReadTool |
| 入历史闸门 Read 放宽上限 | 100000 | ToolOutputGate |
```

- [x] **Step 3: 回写设计文档状态**

`docs/superpowers/specs/2026-09-18-大字段原样导出与Read-full-design.md` 状态行改为：

```markdown
状态：已实施（2026-09-18，全量测试通过）
```

- [x] **Step 4: 全量回归**

Run: `mvn test`
Expected: 全绿（无失败、无错误；含 Task 1-5 全部新增用例）

- [x] **Step 5: 本计划追加执行快照并提交**

在本文件末尾追加：

```markdown
## 执行快照（2026-09-18）

| Task | 完成提交 | 说明 |
|---|---|---|
| 1 DbExport | `<commit>` | 阈值/前缀/写盘/清单纯函数 + 5 用例 |
| 2 MarkdownTable | `<commit>` | cell exported 重载 + fit extraHint + 文案引导 full |
| 3 DbExecutor/DbTool | `<commit>` | 接线 + description 引导 |
| 4 ReadTool full | `<commit>` | 上限 100000、单行不截断 |
| 5 ToolOutputGate | `<commit>` | Read 放宽 100000 |
| 6 文档与回归 | `<commit>` | README/ARCHITECTURE/spec 状态 + `mvn test` 全绿 |
```

（`<commit>` 在提交前替换为实际短 hash。）

```bash
git add README.md docs/ARCHITECTURE.md docs/superpowers/specs/2026-09-18-大字段原样导出与Read-full-design.md docs/superpowers/plans/2026-09-18-大字段原样导出与Read-full-plan.md
git commit -m "docs: 大字段原样导出与 Read full 实施记录与文档同步"
```

---

## Self-Review 记录

- **Spec 覆盖**：设计 §4.1（导出/清单/降级）→ Task 1+3；§4.2（Read full）→ Task 4；§4.3（闸门）→ Task 5；§4.4（描述）→ Task 3+4；§5 测试计划 → Task 1-5 各步；§7 文档同步 → Task 6。无遗漏。
- **占位符**：无 TBD/TODO；所有代码步骤含实际代码；Task 6 的 `<commit>` 为提交前需替换的明确占位（有替换指引）。
- **类型一致性**：`DbExport.Entry/prefix/dump/hint/shouldExport`、`MarkdownTable.cell(v, cellMax, exported)`、`MarkdownTable.fit(display, complete, tmpDir, extraHint)`、`ReadTool.FULL_MAX_OUTPUT_CHARS`、`ToolOutputGate.READ_FULL_MAX_CHARS` 在 Task 1-5 中命名一致。

---

## 执行快照（2026-09-18）

| Task | 完成提交 | 说明 |
|---|---|---|
| 1 DbExport | `a7b32b9` | 阈值/前缀/写盘/清单纯函数 + 5 用例 |
| 2 MarkdownTable | `977c6b9` | cell exported 重载 + fit extraHint + 文案引导 full |
| 3 DbExecutor/DbTool | `6e7aff4` | 接线 + description 引导 |
| 4 ReadTool full | `c111a69` | 上限 100000、单行不截断 |
| 5 ToolOutputGate | `c6d91a1` | Read 放宽 100000 |
| 6 文档与回归 | `d296274` | README/ARCHITECTURE/spec 状态 + `mvn test` 全绿 |

执行期事实（如实记录简报与实际的差异，供后续参考）：

- Task 2 的执行说明「等断言文案未被改动」与强制文案变更存在措辞矛盾，实际同步了 MarkdownTableTest 一条期望字符串（等强度，未弱化）。
- Task 4 简报第 2 条用例数据不自洽（默认 limit 窗口仅 34890 字符，full 不会触发截断），实际改用 limit=6000；另追加了单行 >100000 的 Bash 提示用例（超简报范围，设计 4.2 要求）。
- Task 4 fix 轮修复了「恰好 100000 字符单行 → 空输出 + offset=0 自指循环」（commit `c0e12aa`，补 2 用例）。
- Task 5 的必要连带修改：ToolOutputGateTest 2 处 + AgentLoopTest 1 处既有断言按新口径更新（断言强度未降）。
- 遗留（已修，修复波）：Task 5 review Minor 1——ReadTool 尾提示可超 100000 被闸门替换 → 修复波引入
  `ReadTool.FULL_TAIL_RESERVE=128`（正文按 100000-128 累积，保证内容 + 全部尾提示 ≤ 100000 逐字过闸门），
  并补常量守卫断言 + 逐字过闸门组合用例；同波修复 Oracle CLOB 取值（`DbExport.stringOf`）与 Read `full` 的 schema 断言。

> Task 6 为本计划的收尾提交，提交时 hash 无法自指，故其行原记「本次提交」，后回填为 `d296274`；
> Task 1-5 为实际短 hash。
> 最终审查修复波（Read full 尾提示预留 / CLOB 原样导出 / full schema 断言 / 文档打磨）为**后续独立提交**，
> hash 同样无法在本文件内自指（提交时写死会与自身 hash 不一致）；改动明细与验证证据见
> `.superpowers/sdd/2026-09-18-大字段原样导出与Read-full-plan/final-fix-report.md`。
