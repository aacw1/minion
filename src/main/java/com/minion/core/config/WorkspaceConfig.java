package com.minion.core.config;

/** 工作空间配置项（workspace.json 条目，字段名 = JSON 键） */
public class WorkspaceConfig {

    public String workSpaceName;
    public String workDir;
    public String projectMd;
    /** 项目级技能目录（可空）；相对路径按 workDir 解析，见 WorkspacePaths */
    public String projectSkillsDir;

    public WorkspaceConfig() { }

    public WorkspaceConfig(String workSpaceName, String workDir, String projectMd, String projectSkillsDir) {
        this.workSpaceName = workSpaceName;
        this.workDir = workDir;
        this.projectMd = projectMd;
        this.projectSkillsDir = projectSkillsDir;
    }
}
