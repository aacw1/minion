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
        return new com.minion.core.tools.WriteTool(tmp.getRoot().getAbsolutePath());
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
        assertTrue(g.check(new com.minion.core.tools.BashTool(tmp.getRoot().getAbsolutePath()),
                args("{\"command\":\"rm -rf x\"}")));
        assertTrue(ui.asked.isEmpty());
    }

    @Test
    public void whitelistCommand_approveWhitelist_persistsAndBypasses() throws Exception {
        FakeConfirmUi ui = new FakeConfirmUi(ConfirmUi.Decision.APPROVE_WHITELIST);
        ConfirmGate g = gate(ui);
        com.minion.core.tools.BashTool bash =
                new com.minion.core.tools.BashTool(tmp.getRoot().getAbsolutePath());
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
}
