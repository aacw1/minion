package com.minion.core.config;

/** 工作空间配置项（workspace.json 条目，字段名 = JSON 键） */
public class WorkspaceConfig {

    public String workSpaceName;
    public String workDir;
    public String projectMd;

    public WorkspaceConfig() { }

    public WorkspaceConfig(String workSpaceName, String workDir, String projectMd) {
        this.workSpaceName = workSpaceName;
        this.workDir = workDir;
        this.projectMd = projectMd;
    }
}
