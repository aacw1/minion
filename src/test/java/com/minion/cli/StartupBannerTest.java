package com.minion.cli;

import com.minion.core.config.Config;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class StartupBannerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 写一个最小 config.properties 并加载（未覆盖的键走默认资源，不依赖本机配置） */
    private Config configWith(String content) throws Exception {
        Path work = tmp.getRoot().toPath();
        File cf = new File(work.toFile(), "config.properties");
        Files.write(cf.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return Config.load(work);
    }

    @Test
    public void format_listsSixLinesInOrder() throws Exception {
        Config c = configWith("model.name=deepseek-v4-flash\n"
                + "model.maxContextTokens=900000\n"
                + "work.dir=.\nproject.md.path=./project.md\n"
                + "skills.dir=./skills\nsession.dir=./.minion/sessions\n");
        String[] lines = StartupBanner.format(c).split("\\n");
        assertEquals(6, lines.length);
        assertTrue(lines[0].startsWith("模型: deepseek-v4-flash"));
        assertTrue(lines[1].startsWith("上下文上限: 900000 tokens"));
        assertTrue(lines[2].startsWith("工作空间: "));
        assertTrue(lines[3].startsWith("项目说明: "));
        assertTrue(lines[4].startsWith("技能目录: "));
        assertTrue(lines[5].startsWith("会话存储: "));
    }

    @Test
    public void format_resolvesAbsolutePaths() throws Exception {
        Path work = tmp.getRoot().toPath();
        File dir = new File(work.toFile(), "myskills");
        dir.mkdirs();
        Config c = configWith("model.name=x\nskills.dir=" + dir.getAbsolutePath() + "\n");
        String s = StartupBanner.format(c);
        assertTrue(s.contains("技能目录: " + dir.getAbsolutePath()));
    }

    @Test
    public void format_marksMissingPath() throws Exception {
        Path work = tmp.getRoot().toPath();
        String missing = work.resolve("nope.md").toAbsolutePath().toString();
        Config c = configWith("model.name=x\nproject.md.path=" + missing + "\n");
        assertTrue(StartupBanner.format(c).contains(missing + " (未创建)"));
    }

    @Test
    public void format_existingPathHasNoMarker() throws Exception {
        Path work = tmp.getRoot().toPath();
        File dir = new File(work.toFile(), "sess");
        dir.mkdirs();
        Config c = configWith("model.name=x\nsession.dir=" + dir.getAbsolutePath() + "\n");
        String[] lines = StartupBanner.format(c).split("\\n");
        assertTrue(lines[5].startsWith("会话存储: " + dir.getAbsolutePath()));
        assertFalse(lines[5].contains("(未创建)"));
    }
}
