package com.minion.core.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/** 工作空间内相对路径解析：基准一律是「该空间项目路径」，不是进程当前目录 */
public class WorkspacePathsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String projA, projB;

    private void initDirs() throws Exception {
        projA = tmp.newFolder("projA").getCanonicalPath();
        projB = tmp.newFolder("projB").getCanonicalPath();
    }

    /** 同一份相对写法在两个空间解析出不同绝对路径（回归：过去按 jar 目录解析会串台） */
    @Test
    public void sameRelativeConfig_resolvesPerWorkspace() throws Exception {
        initDirs();
        String a = WorkspacePaths.resolve(projA, "./skills");
        String b = WorkspacePaths.resolve(projB, "./skills");
        assertEquals(Paths.get(projA, "skills").toAbsolutePath().normalize().toString(), a);
        assertEquals(Paths.get(projB, "skills").toAbsolutePath().normalize().toString(), b);
        assertFalse(a.equals(b));
    }

    /** 绝对路径原样规范化，不拼基准 */
    @Test
    public void absolutePath_isNormalizedAsIs() throws Exception {
        initDirs();
        Path abs = tmp.newFolder("elsewhere").toPath().resolve("skills");
        assertEquals(abs.toAbsolutePath().normalize().toString(),
                WorkspacePaths.resolve(projA, abs.toString()));
    }

    /** 空白/ null → null（表示未配置） */
    @Test
    public void blank_isNull() throws Exception {
        initDirs();
        assertNull(WorkspacePaths.resolve(projA, null));
        assertNull(WorkspacePaths.resolve(projA, "   "));
    }

    /** projectMd 配置为相对写法时按项目路径解析；未配置（null/空）回落 <项目路径>/project.md */
    @Test
    public void projectMd_resolvesAndFallsBack() throws Exception {
        initDirs();
        WorkspaceConfig w = new WorkspaceConfig("a", projA, "./doc.md", "./skills");
        assertEquals(Paths.get(projA, "doc.md").toAbsolutePath().normalize().toString(),
                WorkspacePaths.projectMd(w));
        assertEquals(Paths.get(projA, "skills").toAbsolutePath().normalize().toString(),
                WorkspacePaths.projectSkillsDir(w));
        WorkspaceConfig def = new WorkspaceConfig("b", projB, null, null);
        assertEquals(Paths.get(projB, "project.md").toAbsolutePath().normalize().toString(),
                WorkspacePaths.projectMd(def));
        assertNull(WorkspacePaths.projectSkillsDir(def));
    }

    /** 基准目录缺失时不抛异常：回落绝对化后的原值 */
    @Test
    public void missingBaseDir_doesNotThrow() {
        String v = WorkspacePaths.resolve(
                Paths.get("Z:/definitely-missing-dir").toString(), "./skills");
        assertTrue(v.endsWith("skills"));
    }
}
