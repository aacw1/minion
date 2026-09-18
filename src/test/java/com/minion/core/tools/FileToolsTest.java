package com.minion.core.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class FileToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String work;
    private Workspace ws;
    private ReadTool read;
    private GlobTool glob;
    private GrepTool grep;
    private Path tmpDir;

    @org.junit.Before
    public void setup() throws Exception {
        work = tmp.getRoot().getAbsolutePath();
        ws = new Workspace(work);
        tmpDir = tmp.newFolder("jar", ".session", "tmp", "s1").toPath();
        read = new ReadTool(ws);
        glob = new GlobTool(ws);
        grep = new GrepTool(ws, null, tmpDir.toString(), null);
    }

    private JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void read_withLineNumbers() throws Exception {
        Files.write(p("a.txt"), "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"lineNumbers\":true}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("1: line1"));
        assertTrue(r.output.contains("3: line3"));
    }

    @Test
    public void read_outsideWorkDir_rejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-outside-test.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"" + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
    }

    @Test
    public void read_missingFile_error() throws Exception {
        ToolResult r = read.execute(args("{\"path\":\"nope.txt\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("文件不存在"));
    }

    /** 文件不存在且路径在工作区外：错误需提示"工作目录"，引导模型自纠（曾实测模型编造旧项目路径） */
    @Test
    public void read_missingOutsideWorkDir_hintsWorkDir() throws Exception {
        String missing = new File(System.getProperty("java.io.tmpdir"), "minion-no-such-file-xyz.txt").getAbsolutePath();
        ToolResult r = read.execute(args("{\"path\":\"" + missing.replace("\\", "\\\\") + "\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("文件不存在"));
        assertTrue(r.output.contains("工作目录"));
    }

    /** GBK 编码文件（如记事本 ANSI 保存）：UTF-8 解码失败后自动降级 GBK，内容正确并标注转码 */
    // ---- Read 单次输出上限（大输出闸门配套：分页续读，不落盘） ----

    /** 超字符上限：截断 + offset 续读提示；按提示续读可覆盖全文（分页永不卡死） */
    @Test
    public void read_charLimit_truncatesAndHintsNextOffset() throws Exception {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            src.append("行").append(i);
            for (int j = 0; j < 36; j++) src.append('a');
            src.append('\n');
        }
        Files.write(p("big.txt"), src.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"big.txt\"}"));
        assertTrue(r.output, r.ok);
        assertTrue("单次输出受字符上限约束", r.output.length() <= ReadTool.MAX_OUTPUT_CHARS + 200);
        assertTrue("截断提示续读位置", r.output.contains("单次输出上限") && r.output.contains("请用 offset="));
        int next = Integer.parseInt(r.output.replaceAll("(?s).*请用 offset=(\\d+).*", "$1"));
        assertTrue("续读位置有效: " + next, next > 0 && next < 1000);
        ToolResult r2 = read.execute(args("{\"path\":\"big.txt\",\"offset\":" + next + "}"));
        assertTrue(r2.output, r2.ok);
        assertFalse("末页不再截断", r2.output.contains("单次输出上限"));
        assertEquals("分页拼接覆盖全文", src.toString(), stripHints(r.output) + stripHints(r2.output));
    }

    /** 单行超长：截断并标注原始长度（防 minified 单行撑爆上下文） */
    @Test
    public void read_singleLineOverLimit_clipped() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 5000; i++) line.append('x');
        Files.write(p("oneline.json"), line.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"oneline.json\"}"));
        assertTrue(r.output, r.ok);
        assertTrue("单行截断标注", r.output.contains("本行超长，共 5000 字符已截断"));
        assertTrue("本行截断后长度受控", r.output.length() <= ReadTool.MAX_LINE_CHARS + 200);
    }

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
        // 全文 106890 字符：须放开默认 2000 行窗口（2000 行仅 34890 字符）才能触及 full 的 100000 字符上限
        ToolResult r = read.execute(args("{\"path\":\"hugefield.txt\",\"limit\":6000,\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("受 full 上限约束（含全部提示，实际 " + r.output.length() + "）",
                r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS);
        assertTrue("确为 full 生效（超出默认上限）", r.output.length() > ReadTool.MAX_OUTPUT_CHARS + 200);
        assertTrue("截断提示", r.output.contains("单次输出上限") && r.output.contains("请用 offset="));
    }

    /** full=true 且单行超 100000：截断并提示交 Bash（不支持行内续读，设计 4.2 非目标） */
    @Test
    public void read_full_singleLineOverFullLimit_hintsBash() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 150000; i++) line.append('z');
        Files.write(p("overfull.txt"), line.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"overfull.txt\",\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("受 full 上限约束（含全部提示，实际 " + r.output.length() + "）",
                r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS);
        assertTrue("该行无法续读、提示交 Bash",
                r.output.contains("无法用 offset 续读") && r.output.contains("请用 Bash 处理"));
        assertFalse("未走字符上限截断分支（提示行特征）", r.output.contains("单次输出上限"));
    }

    /** 边界回归：单行恰好 100000 字符（75000 字节 base64 编码后正为此长度）——曾返回零内容 +
     *  「请用 offset=0 继续读取」自指提示，模型按提示重发得到相同结果，形成无进展循环 */
    @Test
    public void read_full_singleLineExactlyAtLimit_exportsContentAndBashHint() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < ReadTool.FULL_MAX_OUTPUT_CHARS; i++) line.append('q');
        Files.write(p("exact-limit.txt"), line.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"exact-limit.txt\",\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("输出受上限约束（含全部提示，实际 " + r.output.length() + "）",
                r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS);
        assertTrue("必须含真实内容（非空输出），实际输出: " + r.output, r.output.startsWith("qqqq"));
        assertTrue("须指引交 Bash", r.output.contains("请用 Bash 处理"));
        assertFalse("不得 offset 自指（offset 不推进）", r.output.contains("请用 offset="));
    }

    /** 边界回归：full + 行号时 99999 字符行（行号前缀 + 换行把首行挤出上限的组合）同样须输出内容与 Bash 指引 */
    @Test
    public void read_full_lineNumbers_justBelowLimit_exportsContentAndBashHint() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < ReadTool.FULL_MAX_OUTPUT_CHARS - 1; i++) line.append('w');
        Files.write(p("exact-limit-num.txt"), line.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"exact-limit-num.txt\",\"lineNumbers\":true,\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("输出受上限约束（含全部提示，实际 " + r.output.length() + "）",
                r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS);
        assertTrue("必须含真实内容（含行号前缀）", r.output.startsWith("1: wwww"));
        assertTrue("须指引交 Bash", r.output.contains("请用 Bash 处理"));
        assertFalse("不得 offset 自指", r.output.contains("请用 offset="));
    }

    // ---- 修复波：full 输出与入历史闸门（100000）之间的缓冲 ----

    /** 组合断言（修复波 Important 1）：full 输出「内容接近上限 + charLimited 尾提示」总长 ≤
     *  FULL_MAX_OUTPUT_CHARS，过 ToolOutputGate.apply("Read", out) 后逐字不变（不被换成通用分页提示，
     *  否则模型可能原样重发吃 10 万字符）且不落盘 */
    @Test
    public void read_fullOutputWithTailHint_passesGateVerbatimWithoutDump() throws Exception {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            src.append("行").append(i);
            for (int j = 0; j < 12; j++) src.append('y');
            src.append('\n');
        }
        Files.write(p("gate-full.txt"), src.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"gate-full.txt\",\"limit\":6000,\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("须触发 charLimited 的 offset 精确续读尾提示",
                r.output.contains("单次输出上限") && r.output.contains("请用 offset="));
        assertTrue("内容 + 全部提示总长 ≤ " + ReadTool.FULL_MAX_OUTPUT_CHARS + "，实际 " + r.output.length(),
                r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS);
        assertSame("闸门须逐字放行（尾提示不被替换）",
                r.output, ToolOutputGate.apply("Read", r.output, tmpDir));
        assertTrue("读取类不得落盘", filesIn(tmpDir).isEmpty());
    }

    /** 单行超 full 上限且后面还有行：兜底路径（首行即装不下）同样总长 ≤ FULL_MAX_OUTPUT_CHARS、
     *  含 Bash 指引，且过闸门逐字不变（修复波 Important 1 的边界） */
    @Test
    public void read_fullOverLongLineThenMoreLines_passesGateVerbatim() throws Exception {
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < ReadTool.FULL_MAX_OUTPUT_CHARS + 1000; i++) src.append('m');
        src.append("\n后续行\n");
        Files.write(p("gate-full-over.txt"), src.toString().getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"gate-full-over.txt\",\"full\":true}"));
        assertTrue(r.output, r.ok);
        assertTrue("必须含真实内容（非零输出）", r.output.startsWith("mmmm"));
        assertTrue("该行无法续读、提示交 Bash", r.output.contains("请用 Bash 处理"));
        assertTrue("总长 ≤ " + ReadTool.FULL_MAX_OUTPUT_CHARS + "，实际 " + r.output.length(),
                r.output.length() <= ReadTool.FULL_MAX_OUTPUT_CHARS);
        assertSame(ToolOutputGate.apply("Read", r.output, tmpDir), r.output);
        assertTrue(filesIn(tmpDir).isEmpty());
    }

    /** schema 必须声明 full 布尔开关（修复波 Important 3：现 6 条 full 用例全绕过 schema，
     *  schema 被重构时 full 会静默失效——仿 DbToolTest.schemaDeclaresFullParameter 写法） */
    @Test
    public void read_schemaDeclaresFullBoolean() {
        JsonObject props = read.schema().getAsJsonObject("properties");
        assertTrue("full 必须声明", props.has("full"));
        JsonObject full = props.getAsJsonObject("full");
        assertEquals("full 必须是布尔类型，模型才会当开关用", "boolean", full.get("type").getAsString());
        String desc = full.get("description").getAsString();
        assertTrue(desc, desc.contains("整段") || desc.contains("大字段"));
        assertTrue(desc, desc.contains(String.valueOf(ReadTool.FULL_MAX_OUTPUT_CHARS)));
        assertTrue("工具 description 也须说明 full=true", read.description().contains("full=true"));
    }

    /** 目录内文件列举（测试辅助） */
    private static java.util.List<Path> filesIn(Path dir) throws Exception {
        java.util.List<Path> out = new java.util.ArrayList<Path>();
        if (!Files.exists(dir)) return out;
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
        try {
            for (Path f : ds) out.add(f);
        } finally {
            ds.close();
        }
        return out;
    }

    /** 工具提示行剥离（测试辅助）：提示统一以 "... " 开头 */
    private static String stripHints(String out) {
        StringBuilder sb = new StringBuilder();
        for (String line : out.split("\n", -1)) {
            if (line.startsWith("... ") || line.isEmpty()) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void read_gbkFile_autoDecoded() throws Exception {
        // 「阿诗丹顿」的 GBK 字节序列（8 字节 4 汉字）
        byte[] gbk = new byte[]{(byte) 0xB0, (byte) 0xA2, (byte) 0xCA, (byte) 0xAB,
                (byte) 0xB5, (byte) 0xA4, (byte) 0xB6, (byte) 0xD9};
        Files.write(p("gbk.txt"), gbk);
        ToolResult r = read.execute(args("{\"path\":\"gbk.txt\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("阿诗丹顿"));
        assertTrue(r.output.contains("[GBK 编码文件"));
    }

    /** UTF-8 文件：正常路径零打扰，不出现转码标注 */
    @Test
    public void read_utf8File_noDecodeBanner() throws Exception {
        Files.write(p("utf8.txt"), "你好".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"utf8.txt\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("你好"));
        assertFalse(r.output.contains("GBK"));
    }

    /** GBK 编码文件：搜索中文 pattern 应命中（现状静默跳过导致"未匹配"） */
    @Test
    public void grep_gbkFile_matches() throws Exception {
        Files.write(p("gbk.txt"), "阿诗丹顿".getBytes(Charset.forName("GBK")));
        ToolResult r = grep.execute(args("{\"pattern\":\"诗丹\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("gbk.txt"));
    }

    @Test
    public void glob_matches() throws Exception {
        Files.createDirectories(p("src/sub"));
        Files.write(p("src/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(p("src/sub/B.java"), "y".getBytes(StandardCharsets.UTF_8));
        Files.write(p("src/C.txt"), "z".getBytes(StandardCharsets.UTF_8));
        ToolResult r = glob.execute(args("{\"pattern\":\"**/*.java\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("A.java"));
        assertTrue(r.output.contains("B.java"));
        assertFalse(r.output.contains("C.txt"));
    }

    @Test
    public void grep_matchesWithContext() throws Exception {
        Files.write(p("a.java"), "public class A {}\nint count = 1;\n// count here".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("a.java:2:"));
        assertTrue(r.output.contains("a.java:3:"));
    }

    // ---- Round 1 review regression tests ----

    @Test
    public void read_traversal_rejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-outside-traversal.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"../minion-outside-traversal.txt\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
    }

    @Test
    public void read_invalidOffset_error() throws Exception {
        Files.write(p("a.txt"), "line1".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"offset\":\"abc\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("格式错误"));
    }

    @Test
    public void read_negativeOffset_error() throws Exception {
        Files.write(p("a.txt"), "line1".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"offset\":-1}"));
        assertFalse(r.ok);
    }

    @Test
    public void read_offsetOverflow_error() throws Exception {
        Files.write(p("a.txt"), "line1".getBytes(StandardCharsets.UTF_8));
        ToolResult r = read.execute(args("{\"path\":\"a.txt\",\"offset\":2147483647,\"limit\":2000}"));
        assertFalse(r.ok);
    }

    @Test
    public void grep_outsideRejected() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"), "minion-grep-outside.txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret count value".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\",\"path\":\"../minion-grep-outside.txt\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("工作路径之外"));
        ToolResult abs = grep.execute(args("{\"pattern\":\"count\",\"path\":\""
                + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertFalse(abs.ok);
        assertTrue(abs.output.contains("工作路径之外"));
    }

    @Test
    public void grep_invalidMaxResults_error() throws Exception {
        Files.write(p("a.java"), "count".getBytes(StandardCharsets.UTF_8));
        ToolResult r = grep.execute(args("{\"pattern\":\"count\",\"maxResults\":\"abc\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("格式错误"));
        ToolResult neg = grep.execute(args("{\"pattern\":\"count\",\"maxResults\":-1}"));
        assertFalse(neg.ok);
    }

    @Test
    public void glob_badPattern_error() throws Exception {
        ToolResult r = glob.execute(args("{\"pattern\":\"[\"}"));
        assertFalse(r.ok);
        assertTrue(r.output.contains("语法错误"));
    }

    // ---- 技能目录（工作路径外）放行 ----

    @Test
    public void skillsDir_allowsReadGlobGrep() throws Exception {
        Path skillsDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-skills-test-" + System.nanoTime());
        Path skillFile = skillsDir.resolve("debug").resolve("SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.write(skillFile, "调试技能正文".getBytes(StandardCharsets.UTF_8));
        try {
            ReadTool r = new ReadTool(ws, skillsDir.toString());
            GlobTool g = new GlobTool(ws, skillsDir.toString());
            GrepTool gr = new GrepTool(ws, skillsDir.toString());
            String esc = skillFile.toString().replace("\\", "\\\\");

            ToolResult read = r.execute(args("{\"path\":\"" + esc + "\"}"));
            assertTrue(read.output, read.ok);
            assertTrue(read.output.contains("调试技能正文"));

            ToolResult glob = g.execute(args("{\"pattern\":\"**/SKILL.md\"}"));
            assertTrue(glob.output, glob.ok);
            assertTrue(glob.output, glob.output.contains("debug/SKILL.md")); // 技能目录内输出绝对路径

            ToolResult grep = gr.execute(args("{\"pattern\":\"技能\",\"path\":\""
                    + skillsDir.toString().replace("\\", "\\\\") + "\"}"));
            assertTrue(grep.output, grep.ok);
            assertTrue(grep.output, grep.output.contains("SKILL.md"));
        } finally {
            deleteRecursively(skillsDir);
        }
    }

    @Test
    public void skillsDir_notAllowed_whenNotConfigured() throws Exception {
        Path skillsDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "minion-skills-test-" + System.nanoTime());
        Path skillFile = skillsDir.resolve("SKILL.md");
        Files.createDirectories(skillsDir);
        Files.write(skillFile, "secret".getBytes(StandardCharsets.UTF_8));
        try {
            // 未配置技能目录（单参构造）时，工作路径外的文件仍被拒绝
            ToolResult r = read.execute(args("{\"path\":\""
                    + skillFile.toString().replace("\\", "\\\\") + "\"}"));
            assertFalse(r.ok);
            assertTrue(r.output.contains("工作路径之外"));
        } finally {
            deleteRecursively(skillsDir);
        }
    }

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

    // ---- Glob 匹配基准：pattern 绝对路径 / 相对工作空间 / 双星零层 ----

    @Test
    public void glob_patternAbsolutePath_insideWork_finds() throws Exception {
        Files.createDirectories(p("src/sub"));
        Files.write(p("src/sub/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        String abs = p("src").toAbsolutePath().toString().replace('\\', '/');
        ToolResult r = glob.execute(args("{\"pattern\":\"" + abs + "/**/*.java\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("src/sub/A.java")); // 输出相对 cwd，Read 可直接解析
    }

    @Test
    public void glob_patternRelativeCwd_withAbsoluteSubdirPath_finds() throws Exception {
        Files.createDirectories(p("src/sub"));
        Files.write(p("src/sub/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        String abs = p("src").toAbsolutePath().toString().replace('\\', '/');
        ToolResult r = glob.execute(args("{\"pattern\":\"src/**/*.java\",\"path\":\"" + abs + "\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("src/sub/A.java"));
    }

    @Test
    public void glob_doubleStar_matchesTopLevelAndDirectChild() throws Exception {
        Files.write(p("Top.java"), "x".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(p("src"));
        Files.write(p("src/Direct.java"), "y".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(p("src/sub"));
        Files.write(p("src/sub/Deep.java"), "z".getBytes(StandardCharsets.UTF_8));

        ToolResult top = glob.execute(args("{\"pattern\":\"**/*.java\"}"));
        assertTrue(top.output, top.output.contains("Top.java"));      // 零层：根下直接文件

        ToolResult zero = glob.execute(args("{\"pattern\":\"src/**/*.java\"}"));
        assertTrue(zero.output, zero.output.contains("src/Direct.java")); // 零层：一级直接子文件
        assertTrue(zero.output, zero.output.contains("src/sub/Deep.java"));
    }

    @Test
    public void glob_patternBackslash_normalizedOnWindows() throws Exception {
        org.junit.Assume.assumeTrue(File.separatorChar == '\\');
        Files.createDirectories(p("src"));
        Files.write(p("src/A.java"), "x".getBytes(StandardCharsets.UTF_8));
        String pat = work + "\\src\\*.java";
        ToolResult r = glob.execute(args("{\"pattern\":\"" + pat.replace("\\", "\\\\") + "\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("src/A.java"));
    }

    @Test
    public void glob_notFound_hintContainsSearchRootAndHint() throws Exception {
        ToolResult r = glob.execute(args("{\"pattern\":\"**/*.nope\"}"));
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("未找到匹配文件"));
        assertTrue(r.output, r.output.contains("搜索根"));
        assertTrue(r.output, r.output.contains("相对工作空间"));
    }

    @Test
    public void glob_schema_hasPatternAndPathDescriptions() {
        JsonObject props = glob.schema().getAsJsonObject("properties");
        assertTrue(props.getAsJsonObject("pattern").has("description"));
        assertTrue(props.getAsJsonObject("path").has("description"));
        assertTrue(glob.description().contains("绝对路径")); // 描述写清 pattern 基准（提示词层）
    }

    // ---- 「有权限路径」文件不存在：不加越界拒绝提示（放行目录未创建 / 开关开 / 会话放行） ----

    /** 会话临时目录尚未创建时，读其下不存在文件应报纯「文件不存在」，不得误报越界拒绝 */
    @Test
    public void read_missingFileUnderMissingTmpDir_plainMissing() throws Exception {
        Path work2 = tmp.newFolder("work-mt").toPath();
        Path missingTmp = tmp.getRoot().toPath().resolve("jarM").resolve(".session")
                .resolve("tmp").resolve("s9"); // 不创建：惰性创建目录的读场景
        ReadTool r = new ReadTool(new Workspace(work2.toString()), null, missingTmp.toString(), null);
        ToolResult res = r.execute(args("{\"path\":\""
                + missingTmp.resolve("report.md").toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(res.ok);
        assertTrue(res.output.contains("文件不存在"));
        assertFalse("不应误报越界: " + res.output, res.output.contains("访问将被拒绝"));
    }

    /** 技能目录尚未创建时，读其下不存在文件同样报纯「文件不存在」 */
    @Test
    public void read_missingFileUnderMissingSkillsDir_plainMissing() throws Exception {
        Path work2 = tmp.newFolder("work-ms").toPath();
        Path missingSkills = tmp.getRoot().toPath().resolve("skillsM"); // 不创建
        ReadTool r = new ReadTool(new Workspace(work2.toString()), missingSkills.toString(), null, null);
        ToolResult res = r.execute(args("{\"path\":\""
                + missingSkills.resolve("SKILL.md").toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(res.ok);
        assertTrue(res.output.contains("文件不存在"));
        assertFalse("不应误报越界: " + res.output, res.output.contains("访问将被拒绝"));
    }

    /** 越界读开关开启：不存在的越界文件直接报「文件不存在」，不再提示越界拒绝（开关=全盘可读） */
    @Test
    public void read_missingOutside_switchOn_plainMissing() throws Exception {
        String missing = new File(System.getProperty("java.io.tmpdir"),
                "minion-missing-switchon-" + System.nanoTime() + ".txt").getAbsolutePath();
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(true), new com.minion.core.tools.confirm.FakeConfirmUi()));
        ToolResult res = r.execute(args("{\"path\":\"" + missing.replace("\\", "\\\\") + "\"}"));
        assertFalse(res.ok);
        assertTrue(res.output.contains("文件不存在"));
        assertFalse("开关开不应再提示越界: " + res.output, res.output.contains("访问将被拒绝"));
    }

    /** 会话内已按 A/W 放行越界读后，不存在的越界文件同样报纯「文件不存在」 */
    @Test
    public void read_missingOutside_sessionApproved_plainMissing() throws Exception {
        File outside = new File(System.getProperty("java.io.tmpdir"),
                "minion-approve-sess-" + System.nanoTime() + ".txt");
        outside.deleteOnExit();
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi(
                        com.minion.core.tools.confirm.ConfirmUi.Decision.APPROVE_SESSION)));
        ToolResult first = r.execute(args("{\"path\":\""
                + outside.getAbsolutePath().replace("\\", "\\\\") + "\"}"));
        assertTrue(first.output, first.ok); // 首次：弹确认 → 会话放行
        String missing = new File(System.getProperty("java.io.tmpdir"),
                "minion-missing-sess-" + System.nanoTime() + ".txt").getAbsolutePath();
        ToolResult res = r.execute(args("{\"path\":\"" + missing.replace("\\", "\\\\") + "\"}"));
        assertFalse(res.output.contains("访问将被拒绝"));
    }

    /** 开关关闭：不存在的越界文件仍保留越界提示（防模型编造路径的既有特性，回归钉） */
    @Test
    public void read_missingOutside_switchOff_stillHints() throws Exception {
        String missing = new File(System.getProperty("java.io.tmpdir"),
                "minion-missing-switchoff-" + System.nanoTime() + ".txt").getAbsolutePath();
        ReadTool r = new ReadTool(ws, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi()));
        ToolResult res = r.execute(args("{\"path\":\"" + missing.replace("\\", "\\\\") + "\"}"));
        assertFalse(res.ok);
        assertTrue(res.output.contains("文件不存在"));
        assertTrue("开关关应保留越界提示: " + res.output, res.output.contains("访问将被拒绝"));
    }

    // ---- 会话存储目录（只读放行） ----

    /** 会话存储目录：已存在文件读放行（无需确认）；其下不存在文件报纯「文件不存在」；目录未创建同样放行 */
    @Test
    public void read_sessionStoreDir_readAllowed() throws Exception {
        Path work2 = tmp.newFolder("work-ss").toPath();
        Path sessionDir = tmp.newFolder("session-ss", "wsA").toPath();
        Workspace w2 = new Workspace(work2.toString());
        w2.setExtraReadDirs(java.util.Collections.singletonList(sessionDir.toString()));
        ReadTool r = new ReadTool(w2, null, null, null);
        Path f = Files.write(sessionDir.resolve("s1.json"), "{\"k\":1}".getBytes(StandardCharsets.UTF_8));
        ToolResult ok = r.execute(args("{\"path\":\"" + f.toString().replace("\\", "\\\\") + "\"}"));
        assertTrue(ok.output, ok.ok);
        assertTrue(ok.output.contains("\"k\""));

        ToolResult missing = r.execute(args("{\"path\":\""
                + sessionDir.resolve("nope.json").toString().replace("\\", "\\\\") + "\"}"));
        assertFalse(missing.ok);
        assertTrue(missing.output.contains("文件不存在"));
        assertFalse("不应误报越界: " + missing.output, missing.output.contains("访问将被拒绝"));

        // 会话存储目录尚未创建（惰性创建）：配置为只读放行目录后，其下不存在文件同样报纯「文件不存在」
        Path missingSessionDir = tmp.getRoot().toPath().resolve("session-ss-missing");
        Workspace w3 = new Workspace(work2.toString());
        w3.setExtraReadDirs(java.util.Collections.singletonList(missingSessionDir.toString()));
        ReadTool r3 = new ReadTool(w3, null, null, null);
        ToolResult missing2 = r3.execute(args("{\"path\":\""
                + missingSessionDir.resolve("x.json").toString().replace("\\", "\\\\") + "\"}"));
        assertTrue(missing2.output.contains("文件不存在"));
        assertFalse("目录未创建也不应误报越界: " + missing2.output, missing2.output.contains("访问将被拒绝"));
    }

    /** 会话存储目录只放行读：Write 写入仍拒绝（开关关闭时） */
    @Test
    public void write_sessionStoreDir_stillRejected() throws Exception {
        Path work2 = tmp.newFolder("work-sw").toPath();
        Path sessionDir = tmp.newFolder("session-sw").toPath();
        Workspace w2 = new Workspace(work2.toString());
        w2.setExtraReadDirs(java.util.Collections.singletonList(sessionDir.toString()));
        WriteTool w = new WriteTool(w2, null, null, new com.minion.core.tools.confirm.ConfirmGate(
                readConfig(false), new com.minion.core.tools.confirm.FakeConfirmUi()));
        ToolResult res = w.execute(args("{\"path\":\""
                + sessionDir.resolve("hack.json").toString().replace("\\", "\\\\")
                + "\",\"content\":\"x\"}"));
        assertFalse("写会话存储目录应被拒: " + res.output, res.ok);
    }

    /** 会话存储目录：Grep 指定该目录为搜索根放行（与 Read 同口径） */
    @Test
    public void grep_sessionStoreDir_allowed() throws Exception {
        Path work2 = tmp.newFolder("work-sg").toPath();
        Path sessionDir = tmp.newFolder("session-sg").toPath();
        Files.write(sessionDir.resolve("s1.json"),
                "{\"note\":\"findme-session\"}".getBytes(StandardCharsets.UTF_8));
        Workspace w2 = new Workspace(work2.toString());
        w2.setExtraReadDirs(java.util.Collections.singletonList(sessionDir.toString()));
        GrepTool g = new GrepTool(w2, null, null, null);
        ToolResult res = g.execute(args("{\"pattern\":\"findme-session\",\"path\":\""
                + sessionDir.toString().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.output, res.ok);
        assertTrue(res.output.contains("s1.json"));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private Path p(String rel) {
        return java.nio.file.Paths.get(work, rel);
    }

    // ---- Grep 单行截断 + 全量落盘（工具输出截断落盘 Task 3） ----

    /** 超长单行：每条结果内容 ≤ 1000 字符 + 省略号，总字符可控（爆炸回归用例） */
    @Test
    public void grep_longLine_truncated() throws Exception {
        StringBuilder longLine = new StringBuilder("needle");
        for (int i = 0; i < 5000; i++) longLine.append('x');
        Files.write(Paths.get(work, "big.txt"), longLine.toString().getBytes("UTF-8"));
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.ok);
        String[] lines = r.output.split("\\r?\\n");
        assertEquals(1, lines.length); // 单文件单行
        // "big.txt:1: " 前缀 + 1000 内容 + "..." ≈ 1013
        assertTrue("结果行过长: " + lines[0].length(), lines[0].length() <= 1020);
        assertTrue(lines[0].endsWith("..."));
        // 不落盘（未超限）
        assertFalse("不超限不落盘", Files.exists(tmpDir));
    }

    /** 超 250 条：显示 250 条 + 提示路径，落盘全量 */
    @Test
    public void grep_manyMatches_dumpWritten() throws Exception {
        for (int i = 0; i < 300; i++) {
            Files.write(Paths.get(work, "f" + i + ".txt"), ("needle line " + i).getBytes("UTF-8"));
        }
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.ok);
        int shown = r.output.split("needle", -1).length - 1;
        assertTrue("显示条数应 ≤250，实际 " + shown, shown <= 250);
        assertTrue(r.output.contains("完整结果已保存到 "));
        assertTrue(r.output.contains(tmpDir.toAbsolutePath().toString()));
        assertTrue(r.output.contains("可用 Read 查看"));
        Path dumpDir = tmpDir;
        java.util.List<Path> files = Files.list(dumpDir).collect(java.util.stream.Collectors.toList());
        assertEquals(1, files.size());
        assertEquals(300, Files.readAllLines(files.get(0)).size());
    }

    /** maxResults 传超大值被钳制，不放大显示量（防爆炸） */
    @Test
    public void grep_maxResults_clamped() throws Exception {
        for (int i = 0; i < 300; i++) {
            Files.write(Paths.get(work, "g" + i + ".txt"), ("needle line " + i).getBytes("UTF-8"));
        }
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\",\"maxResults\":99999}"));
        int shown = r.output.split("needle", -1).length - 1;
        assertTrue(shown <= 250);
        assertTrue(r.output.contains("完整结果已保存到"));
    }

    /** 一行级：maxResults=0 时显示 0 条（旧实现先 append 后检查会多显示 1 条），
     *  全量仍落盘并提示路径 */
    @Test
    public void grep_maxZero_showsNothing_dumpWritten() throws Exception {
        Files.write(Paths.get(work, "m0.txt"), "needle zero-a".getBytes("UTF-8"));
        Files.write(Paths.get(work, "m1.txt"), "needle zero-b".getBytes("UTF-8"));
        ToolResult r = grep.execute(args("{\"pattern\":\"needle\",\"maxResults\":0}"));
        assertTrue(r.ok);
        assertFalse("max=0 不应显示匹配内容: " + r.output, r.output.contains("needle zero"));
        assertTrue("应提示全量落盘: " + r.output, r.output.contains("共 2 条"));
        assertTrue(r.output.contains(tmpDir.toAbsolutePath().toString()));
        Path dumpDir = tmpDir;
        java.util.List<Path> files = Files.list(dumpDir).collect(java.util.stream.Collectors.toList());
        assertEquals(1, files.size());
        assertEquals(2, Files.readAllLines(files.get(0)).size());
    }

    /** 一行级：truncateLine 截断点（1000）切在代理对中间时丢弃孤立高代理。
     *  行 = 999a + emoji + 500b，substring(0,1000) 尾部恰为 emoji 高代理 */
    @Test
    public void grep_longLine_surrogateSafe() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 999; i++) sb.append('a');
        sb.append("\uD83D\uDE00"); // emoji，UTF-16 双 char
        for (int i = 0; i < 500; i++) sb.append('b');
        Files.write(Paths.get(work, "emoji.txt"), sb.toString().getBytes("UTF-8"));
        ToolResult r = grep.execute(args("{\"pattern\":\"aaaa\"}"));
        assertTrue(r.ok);
        assertTrue(r.output.contains("..."));
        assertNoLoneSurrogate("结果含孤立代理: " + r.output, r.output);
    }

    /** P2 降级文案：落盘失败（会话临时目录被占位成文件导致目录创建失败）时，
     *  不得提示"完整结果已保存到 <空路径>，可用 Read 查看"误导模型去读空路径 */
    @Test
    public void grep_dumpFailure_honestNote() throws Exception {
        Path tmpDirAsFile = tmp.getRoot().toPath().resolve("jar2").resolve(".session")
                .resolve("tmp").resolve("s1");
        Files.createDirectories(tmpDirAsFile.getParent());
        Files.write(tmpDirAsFile, "x".getBytes()); // 占位成普通文件 → 目录创建失败 → 落盘失败
        GrepTool g = new GrepTool(ws, null, tmpDirAsFile.toString(), null);
        for (int i = 0; i < 300; i++) {
            Files.write(Paths.get(work, "h" + i + ".txt"), ("needle line " + i).getBytes("UTF-8"));
        }
        ToolResult r = g.execute(args("{\"pattern\":\"needle\"}"));
        assertTrue(r.ok);
        assertTrue("缺降级说明: " + r.output, r.output.contains("落盘失败未保存完整结果"));
        assertFalse("不应提示保存到空路径: " + r.output, r.output.contains("完整结果已保存到 "));
        assertFalse("不应提示可 Read 查看: " + r.output, r.output.contains("可用 Read 查看"));
    }

    /** tmpDir 白名单：模型 Read 会话临时目录内文件免确认（jar 目录在工作路径之外，无 confirm 时直接放行） */
    @Test
    public void read_sessionTmpDir_allowedWithoutConfirm() throws Exception {
        Path workDir = tmp.newFolder("work3").toPath();
        Path jar = tmp.newFolder("jar3").toPath();
        Path tmpDir = jar.resolve(".session").resolve("tmp").resolve("s1");
        Files.createDirectories(tmpDir);
        Path dump = Files.write(tmpDir.resolve("bash-1.txt"), "hi".getBytes(StandardCharsets.UTF_8));
        ReadTool r = new ReadTool(new Workspace(workDir.toString()), null, tmpDir.toString(), null);
        ToolResult res = r.execute(args("{\"path\":\"" + dump.toAbsolutePath().toString().replace("\\", "\\\\") + "\"}"));
        assertTrue(res.ok);
        assertTrue(res.output.contains("hi"));
    }

    /** Grep 路径指向会话临时目录：守卫放行且可搜索（SKIP_SUBTREE 豁免搜索根自身；
     *  落盘文件本身单独跳过防自噬），不被拒绝 */
    @Test
    public void grep_sessionTmpDirPath_allowedWithoutConfirm() throws Exception {
        Path workDir = tmp.newFolder("work-grep").toPath();
        Path jar = tmp.newFolder("jar-grep").toPath();
        Path sessionTmp = jar.resolve(".session").resolve("tmp").resolve("s1");
        Files.createDirectories(sessionTmp);
        Files.write(sessionTmp.resolve("note.txt"), "hello world".getBytes(StandardCharsets.UTF_8));
        GrepTool grep = new GrepTool(new Workspace(workDir.toString()), null, sessionTmp.toString(), null); // confirm=null：被拒则硬拒绝
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "hello");
        args.addProperty("path", sessionTmp.toAbsolutePath().toString());
        ToolResult r = grep.execute(args);
        assertFalse("tmp 目录内路径不应被守卫拒绝: " + r.output, r.output.contains("路径在工作路径之外"));
        assertTrue("root==tmpDir 应可搜索到内容: " + r.output, r.output.contains("note.txt"));
    }

    /** 遍历检查字符串无孤立代理（每个高/低代理必须成对出现） */
    private static void assertNoLoneSurrogate(String msg, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(msg + "（孤立高代理 @" + i + "）",
                        i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
            } else if (Character.isLowSurrogate(c)) {
                assertTrue(msg + "（孤立低代理 @" + i + "）",
                        i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            }
        }
    }
}
