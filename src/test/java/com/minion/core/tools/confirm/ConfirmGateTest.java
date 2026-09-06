package com.minion.core.tools.confirm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ConfirmGateTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private com.minion.core.config.Config config;

    private ConfirmGate gate(ConfirmUi ui) throws Exception {
        Path work = tmp.getRoot().toPath();
        return new ConfirmGate(config, ui);
    }

    private com.minion.core.tools.Tool writeTool() {
        return new com.minion.core.tools.WriteTool(
                new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath()));
    }

    private com.minion.core.tools.Tool readTool() {
        return new com.minion.core.tools.ReadTool(
                new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath()));
    }

    private JsonObject args(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    @org.junit.Before
    public void setup() throws Exception {
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        // T9 语义：WriteTool.isHighRisk 仅当目标文件已存在（覆盖）为真；
        // 预建 a.txt / b.txt 使测试中的写操作成为真实高危覆盖写，门才会询问
        Files.write(tmp.getRoot().toPath().resolve("a.txt"),
                "x".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.getRoot().toPath().resolve("b.txt"),
                "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void skipFlag_bypassesAsk() throws Exception {
        Files.write(java.nio.file.Paths.get(config.externalFile().toString()),
                "confirm.skip=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void approve_reject_whitelist() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertEquals(1, ui.asked.size());
        assertTrue(ui.asked.get(0).contains("Write"));
        // ⚠ 在 mintty 默认字体链中渲染为 ?，确认提示用 ASCII 的 !
        assertTrue(ui.asked.get(0).startsWith("! 高危操作 "));

        FakeConfirmUi ui2 = new FakeConfirmUi(ConfirmUi.Decision.REJECT);
        assertFalse(gate(ui2).check(writeTool(), args("{\"path\":\"a.txt\"}")));
    }

    @Test
    public void whitelistWrite_persistsToExternalConfig() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_WHITELIST);
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(config.whitelistTools().contains("write"));
        // 重新加载后仍生效
        com.minion.core.config.Config reloaded =
                com.minion.core.config.Config.load(tmp.getRoot().toPath());
        assertTrue(reloaded.whitelistTools().contains("write"));
    }

    @Test
    public void whitelistedTool_noAsk() throws Exception {
        config.appendWhitelist("confirm.whitelist.tools", "write");
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void whitelistedCommand_noAsk() throws Exception {
        config.appendWhitelist("confirm.whitelist.commands", "rm");
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.check(new com.minion.core.tools.BashTool(
                        new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath())),
                args("{\"command\":\"rm -rf x\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void whitelistCommand_approveWhitelist_persistsAndBypasses() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_WHITELIST);
        ConfirmGate g = gate(ui);
        com.minion.core.tools.BashTool bash =
                new com.minion.core.tools.BashTool(
                        new com.minion.core.tools.Workspace(tmp.getRoot().getAbsolutePath()));
        JsonObject danger = args("{\"command\":\"rm -rf x\"}");
        // W 键：确认危险命令并写入 confirm.whitelist.commands（外部文件落盘）
        assertTrue(g.check(bash, danger));
        String file = new String(Files.readAllBytes(config.externalFile()), StandardCharsets.UTF_8);
        assertTrue(file.contains("confirm.whitelist.commands=rm"));
        com.minion.core.config.Config reloaded =
                com.minion.core.config.Config.load(tmp.getRoot().toPath());
        assertTrue(reloaded.whitelistCommands().contains("rm"));
        // 同类危险命令下次直接放行，不再询问
        assertTrue(g.check(bash, danger));
        assertEquals(1, ui.asked.size());
    }

    @Test
    public void approveSession_bypassesRest() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(
                ConfirmUi.Decision.APPROVE_SESSION, ConfirmUi.Decision.REJECT);
        ConfirmGate g = gate(ui);
        assertTrue(g.check(writeTool(), args("{\"path\":\"a.txt\"}")));
        // 第二个本来会 REJECT，但会话已放行
        assertTrue(g.check(writeTool(), args("{\"path\":\"b.txt\"}")));
        assertEquals(1, ui.asked.size());
    }

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

    // ---- 越界写审批（截图等输出类工具）----

    @Test
    public void writeOutside_approveReject() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gate(ui);
        assertTrue(g.checkWriteOutside(writeTool(), args("{\"path\":\"D:/x.png\"}"), "D:/x.png"));
        assertEquals(1, ui.asked.size());
        assertTrue(ui.asked.get(0).startsWith("! 越界写入 "));
        assertTrue(ui.asked.get(0).contains("D:/x.png"));

        assertFalse(gate(new FakeConfirmUi(ConfirmUi.Decision.REJECT))
                .checkWriteOutside(writeTool(), args("{\"path\":\"D:/x.png\"}"), "D:/x.png"));
    }

    @Test
    public void writeOutside_approveSession_bypassesRest() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_SESSION);
        ConfirmGate g = gate(ui);
        assertTrue(g.checkWriteOutside(writeTool(), args("{\"path\":\"D:/x.png\"}"), "D:/x.png"));
        assertTrue(g.checkWriteOutside(writeTool(), args("{\"path\":\"D:/y.png\"}"), "D:/y.png"));
        assertEquals(1, ui.asked.size()); // 会话放行后不再弹窗
    }

    @Test
    public void writeOutside_switchOn_skipsAsk() throws Exception {
        Files.write(java.nio.file.Paths.get(config.externalFile().toString()),
                "\npaths.read.allowOutside=true\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        config = com.minion.core.config.Config.load(tmp.getRoot().toPath());
        FakeConfirmUi ui = new FakeConfirmUi();
        ConfirmGate g = gate(ui);
        assertTrue(g.checkWriteOutside(writeTool(), args("{\"path\":\"D:/x.png\"}"), "D:/x.png"));
        assertTrue(ui.asked.isEmpty());
    }

    // ---- 空间外写（checkEscapeWrite）：关=直接拒绝；开=跳过/白名单/弹框链 ----

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

    @Test
    public void escapeWrite_switchOff_rejects_evenIfSkipOn() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(ConfirmUi.Decision.REJECT),
                "paths.write.allowOutside", "false", "confirm.skip", "true");
        assertFalse("开关关应直接拒绝（跳过开关不生效）",
                g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_skip_allows() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(ConfirmUi.Decision.REJECT),
                "paths.write.allowOutside", "true", "confirm.skip", "true");
        assertTrue("确认跳过应放行且不弹框", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_whitelisted_allows() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(ConfirmUi.Decision.REJECT),
                "paths.write.allowOutside", "true", "confirm.whitelist.tools", "Write");
        assertTrue("工具白名单应放行且不弹框", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_confirmApprove_allows() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE);
        ConfirmGate g = gateWith(ui, "paths.write.allowOutside", "true");
        assertTrue(g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
        assertEquals(1, ui.asked.size());
        assertTrue("弹框文案应含越界写入: " + ui.asked.get(0), ui.asked.get(0).contains("越界写入"));
    }

    @Test
    public void escapeWrite_switchOn_confirmReject_rejects() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(ConfirmUi.Decision.REJECT), "paths.write.allowOutside", "true");
        assertFalse("N 拒绝应返回 false", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }

    @Test
    public void escapeWrite_switchOn_sessionApprove_allows() throws Exception {
        ConfirmGate g = gateWith(new FakeConfirmUi(ConfirmUi.Decision.APPROVE_SESSION),
                "paths.write.allowOutside", "true");
        assertTrue("W 会话放行应返回 true", g.checkEscapeWrite(writeTool(), args("{}"), "/tmp/x.txt"));
    }
}
