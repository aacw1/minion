package com.minion.cli;

import com.minion.core.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 启动信息横幅：模型/上下文/路径概览，每行一条。路径显示为绝对路径，不存在的标注 (未创建) */
public class StartupBanner {

    public static String format(Config config) {
        StringBuilder sb = new StringBuilder();
        sb.append("模型: ").append(config.modelName()).append('\n');
        sb.append("上下文上限: ").append(config.maxContextTokens()).append(" tokens\n");
        sb.append("工作空间: ").append(describe(config.workDir())).append('\n');
        sb.append("项目说明: ").append(describe(config.projectMdPath())).append('\n');
        sb.append("技能目录: ").append(describe(config.skillsDir())).append('\n');
        sb.append("会话存储: ").append(describe(config.sessionDir()));
        return sb.toString();
    }

    /** 绝对路径 + 存在性标注 */
    private static String describe(String p) {
        Path path = Paths.get(p).toAbsolutePath();
        return path.toString() + (Files.exists(path) ? "" : " (未创建)");
    }
}
