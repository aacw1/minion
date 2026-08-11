package com.minion.core.tools;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/** cd 后文件工具相对路径以 cwd 为基准 */
public class WorkspacePathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String workDir;

    private static JsonObject json(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    @Test
    public void readRelativeToCwd() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        Files.createDirectories(Paths.get(workDir, "sub"));
        Files.write(Paths.get(workDir, "sub", "a.txt"), "hello-cwd".getBytes(StandardCharsets.UTF_8));
        Workspace ws = new Workspace(workDir);
        ws.cd("sub");
        ReadTool tool = new ReadTool(ws);
        ToolResult r = tool.execute(json("path", "a.txt"));
        assertTrue(r.output, r.output.contains("hello-cwd"));
    }

    @Test
    public void writeRelativeToCwd() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        Files.createDirectories(Paths.get(workDir, "sub"));
        Workspace ws = new Workspace(workDir);
        ws.cd("sub");
        WriteTool tool = new WriteTool(ws);
        ToolResult r = tool.execute(json2("path", "b.txt", "content", "x"));
        assertTrue(r.output, r.output.contains("b.txt"));
        assertTrue(Files.exists(Paths.get(workDir, "sub", "b.txt")));
    }

    @Test
    public void globRelativeToCwd() throws Exception {
        workDir = tmp.getRoot().getAbsolutePath();
        Files.createDirectories(Paths.get(workDir, "sub"));
        Files.write(Paths.get(workDir, "sub", "g.java"), new byte[0]);
        Files.write(Paths.get(workDir, "g.java"), new byte[0]);
        Workspace ws = new Workspace(workDir);
        ws.cd("sub");
        GlobTool tool = new GlobTool(ws);
        ToolResult r = tool.execute(json("pattern", "*.java"));
        assertTrue(r.output, r.output.contains("g.java"));
        assertTrue(r.output, !r.output.contains(".."));
    }

    private static JsonObject json2(String k1, String v1, String k2, String v2) {
        JsonObject o = new JsonObject();
        o.addProperty(k1, v1);
        o.addProperty(k2, v2);
        return o;
    }
}
