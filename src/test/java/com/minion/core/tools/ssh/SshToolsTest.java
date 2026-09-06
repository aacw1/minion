package com.minion.core.tools.ssh;

import com.google.gson.JsonObject;
import com.minion.core.tools.ToolResult;
import com.minion.core.tools.Workspace;
import com.minion.core.tools.plugin.ToolContext;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** 工具层不触网错误路径：未选连接 / 缺参 / 本地路径越界（守卫先于连网）。
 *  越界用例的本地守卫在 executor 之前返回，故不真连网（兜底连接 127.0.0.1:1 会秒失败）。 */
public class SshToolsTest {

    private static JsonObject json(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    private static ToolContext ctx(Path work, Path tmp) throws IOException {
        Workspace ws = new Workspace(work.toString());
        return new ToolContext(ws, work.resolve("skills").toString(),
                tmp == null ? null : tmp.toString(), null);
    }

    private static SshConfig configWithUnreachable() {
        SshConfig cfg = new SshConfig();
        SshConnection c = new SshConnection("bad", "127.0.0.1", 1, "u", "p", "", "");
        cfg.connections.add(c);
        cfg.current = "bad";
        return cfg;
    }

    @Test
    public void noConnectionReturnsGuidance() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        SshConfig cfg = new SshConfig();
        SshTool[] tools = {new SshExecTool(cfg, ctx(work, null), new SshExecutor()),
                new SftpLsTool(cfg, ctx(work, null), new SshExecutor())};
        for (SshTool t : tools) {
            ToolResult r = t.execute(json("path", "/", "command", "ls"));
            assertFalse(r.ok);
            assertTrue(r.output.contains("未选择连接"));
        }
    }

    @Test
    public void missingRequiredParamsRejectedBeforeNetwork() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        ToolContext c = ctx(work, null);
        SshConfig cfg = configWithUnreachable();
        SshTool t = new SshExecTool(cfg, c, new SshExecutor());
        ToolResult r = t.execute(new JsonObject());
        assertFalse(r.ok);
        assertTrue(r.output.contains("command"));
    }

    @Test
    public void getOutsideWorkspaceRejectedByGuard() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        Path outside = Files.createTempDirectory("ssh-outside");
        SshConfig cfg = configWithUnreachable();
        ToolContext c = ctx(work, null);
        SshTool t = new SftpGetTool(cfg, c, new SshExecutor());
        ToolResult r = t.execute(json("remotePath", "/etc/hosts",
                "localPath", outside.resolve("x").toString()));
        assertFalse("越界必须先于连网被拒", r.ok);
        assertTrue(r.output.contains("工作区") || r.output.contains("越界") || r.output.contains("之外"));
    }

    @Test
    public void putOutsideWorkspaceRejectedByGuard() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        Path outside = Files.createTempFile("ssh-outside", ".txt");
        SshConfig cfg = configWithUnreachable();
        ToolContext c = ctx(work, null);
        SshTool t = new SftpPutTool(cfg, c, new SshExecutor());
        ToolResult r = t.execute(json("localPath", outside.toString(), "remotePath", "/tmp/x"));
        assertFalse(r.ok);
    }

    @Test
    public void rmAndPutAreHighRisk() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        ToolContext c = ctx(work, null);
        SshConfig cfg = configWithUnreachable();
        assertTrue(new SftpRmTool(cfg, c, new SshExecutor()).isHighRisk(json("path", "/tmp/a")));
        assertTrue(new SftpPutTool(cfg, c, new SshExecutor())
                .isHighRisk(json("localPath", "a", "remotePath", "/tmp/a")));
        assertFalse(new SftpGetTool(cfg, c, new SshExecutor())
                .isHighRisk(json("remotePath", "/a", "localPath", "a")));
    }

    @Test
    public void execHighRiskMatchesRemoteDangerList() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        ToolContext c = ctx(work, null);
        SshConfig cfg = configWithUnreachable();
        SshExecTool t = new SshExecTool(cfg, c, new SshExecutor());
        assertTrue(t.isHighRisk(json("command", "systemctl stop nginx")));
        assertFalse(t.isHighRisk(json("command", "tail -n 50 /var/log/messages")));
    }

    @Test
    public void unreachableConnectionProducesReadableError() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        ToolContext c = ctx(work, Files.createTempDirectory("ssh-tmp"));
        SshConfig cfg = configWithUnreachable();
        ToolResult r = new SshExecTool(cfg, c, new SshExecutor()).execute(json("command", "ls"));
        assertFalse(r.ok);
        assertFalse(r.output.contains("com.jcraft"));
        assertTrue(r.output.length() < 500);
    }

    @Test
    public void namesFollowSpec() throws Exception {
        Path work = Files.createTempDirectory("ssh-tool-test");
        ToolContext c = ctx(work, null);
        SshConfig cfg = new SshConfig();
        assertEquals("SshExec", new SshExecTool(cfg, c, new SshExecutor()).name());
        assertEquals("SftpGet", new SftpGetTool(cfg, c, new SshExecutor()).name());
        assertEquals("SftpPut", new SftpPutTool(cfg, c, new SshExecutor()).name());
        assertEquals("SftpRm", new SftpRmTool(cfg, c, new SshExecutor()).name());
        assertEquals("SftpMkdir", new SftpMkdirTool(cfg, c, new SshExecutor()).name());
        assertEquals("SftpRename", new SftpRenameTool(cfg, c, new SshExecutor()).name());
        assertEquals("SftpLs", new SftpLsTool(cfg, c, new SshExecutor()).name());
    }
}
